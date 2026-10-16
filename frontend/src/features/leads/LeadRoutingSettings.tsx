import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Shuffle } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { roleLabel } from "@/lib/format";
import { toast } from "@/lib/toast";
import { Avatar } from "@/components/ui/Avatar";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";

export interface RoutingSettings {
  enabled: boolean;
  teamId: number | null;
  respectTerritory: boolean;
  lastAssignedUserId: number | null;
  updatedAt: string | null;
  eligibleReps?: { id: number; name: string; role: string; territory: string | null }[];
}

interface TeamOption {
  id: number;
  name: string;
}

/** Round-robin lead routing settings (Manager+), opened from the Leads page header. */
export function LeadRoutingSettings({ open, onClose }: { open: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const settings = useQuery({
    queryKey: ["routing-settings"],
    queryFn: async () => (await api.get<RoutingSettings>("/routing-settings")).data,
    enabled: open,
  });
  const teams = useQuery({
    queryKey: ["teams", "routing-options"],
    queryFn: async () => (await api.get<TeamOption[]>("/teams")).data,
    enabled: open,
  });
  const [draft, setDraft] = useState<Pick<RoutingSettings, "enabled" | "teamId" | "respectTerritory">>({
    enabled: false,
    teamId: null,
    respectTerritory: false,
  });
  useEffect(() => {
    if (settings.data) {
      setDraft({
        enabled: settings.data.enabled,
        teamId: settings.data.teamId,
        respectTerritory: settings.data.respectTerritory,
      });
    }
  }, [settings.data]);

  const save = useMutation({
    mutationFn: async () => (await api.put<RoutingSettings>("/routing-settings", draft)).data,
    onSuccess: (s) => {
      qc.setQueryData(["routing-settings"], s);
      toast.success(s.enabled ? "Round-robin routing is on" : "Routing settings saved");
      onClose();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const reps = settings.data?.eligibleReps ?? [];

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Lead routing"
      width="34rem"
      footer={
        <>
          <button className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn-primary" disabled={!settings.data || save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? "Saving…" : "Save"}
          </button>
        </>
      }
    >
      {settings.isError ? (
        <QueryError compact error={settings.error} onRetry={() => settings.refetch()} title="Couldn't load routing settings" />
      ) : settings.isLoading ? (
        <div className="space-y-3">
          <Skeleton className="h-10" />
          <Skeleton className="h-10" />
          <Skeleton className="h-24" />
        </div>
      ) : (
        <div className="space-y-5">
          <div className="flex gap-3">
            <div
              className="flex items-center justify-center h-10 w-10 rounded-xl shrink-0"
              style={{ backgroundColor: "rgb(var(--ring-brand) / 0.12)", color: "var(--brand-600)" }}
            >
              <Shuffle size={18} />
            </div>
            <p className="text-sm" style={{ color: "var(--ink-secondary)" }}>
              New leads that arrive without an owner — from integrations and the API, or created by a manager who leaves
              the owner empty — are handed to the next rep in turn. The rep gets a notification.
            </p>
          </div>

          <label className="flex items-center justify-between gap-3 text-sm" style={{ color: "var(--ink-primary)" }}>
            <span className="font-medium">Round-robin assignment</span>
            <input
              type="checkbox"
              aria-label="Round-robin assignment"
              checked={draft.enabled}
              onChange={(e) => setDraft((d) => ({ ...d, enabled: e.target.checked }))}
            />
          </label>

          <label className="block text-sm" style={{ color: "var(--ink-primary)" }}>
            <span className="font-medium">Rotate among</span>
            <select
              className="select mt-1.5"
              aria-label="Rotate among"
              value={draft.teamId ?? ""}
              onChange={(e) => setDraft((d) => ({ ...d, teamId: e.target.value ? Number(e.target.value) : null }))}
            >
              <option value="">All active sales reps</option>
              {teams.data?.map((t) => (
                <option key={t.id} value={t.id}>
                  Team: {t.name}
                </option>
              ))}
            </select>
          </label>

          <label className="flex items-start gap-2 text-sm cursor-pointer" style={{ color: "var(--ink-secondary)" }}>
            <input
              type="checkbox"
              className="mt-0.5"
              checked={draft.respectTerritory}
              onChange={(e) => setDraft((d) => ({ ...d, respectTerritory: e.target.checked }))}
            />
            <span>
              Respect territories — Field Sales reps only receive leads whose geography matches their territory.
            </span>
          </label>

          <div>
            <p className="text-xs font-semibold uppercase tracking-wide mb-2" style={{ color: "var(--ink-muted)" }}>
              In the rotation now{draft.teamId !== settings.data?.teamId ? " (before saving)" : ""}
            </p>
            {reps.length === 0 ? (
              <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
                No active reps — leads stay with their creator until someone is added.
              </p>
            ) : (
              <ul className="flex flex-wrap gap-2">
                {reps.map((r) => (
                  <li key={r.id} className="inline-flex items-center gap-1.5 rounded-full px-2 py-1 glass-solid text-xs">
                    <Avatar name={r.name} size={18} />
                    <span style={{ color: "var(--ink-primary)" }}>{r.name}</span>
                    <span style={{ color: "var(--ink-muted)" }}>
                      {roleLabel(r.role)}
                      {r.territory ? ` · ${r.territory}` : ""}
                    </span>
                    {settings.data?.lastAssignedUserId === r.id && <span className="badge-brand text-[10px]">last</span>}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}
    </Modal>
  );
}
