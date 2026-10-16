import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { toast } from "@/lib/toast";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import type { DuplicateActionValue, DuplicateRule } from "./duplicates";

const LABELS: Record<DuplicateRule["objectType"], string> = { LEAD: "Leads", CONTACT: "Contacts", COMPANY: "Companies" };
const ACTIONS: { value: DuplicateActionValue; label: string }[] = [
  { value: "WARN", label: "Warn (allow saving anyway)" },
  { value: "BLOCK", label: "Block" },
  { value: "ALLOW", label: "Allow silently" },
];

/** Duplicate rules per object type — editable by Super Admin / VP Sales, read-only for others. */
export function DuplicateRulesPanel({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const rules = useQuery({
    queryKey: ["duplicate-rules"],
    queryFn: async () => (await api.get<DuplicateRule[]>("/duplicate-rules")).data,
  });
  const update = useMutation({
    mutationFn: async ({ type, patch }: { type: DuplicateRule["objectType"]; patch: Partial<DuplicateRule> }) =>
      (await api.put<DuplicateRule>(`/duplicate-rules/${type}`, patch)).data,
    onSuccess: (r) => {
      toast.success(`${LABELS[r.objectType]} duplicate rule saved`);
      qc.invalidateQueries({ queryKey: ["duplicate-rules"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  if (rules.isError) return <QueryError compact error={rules.error} onRetry={() => rules.refetch()} title="Couldn't load rules" />;
  if (rules.isLoading || !rules.data) {
    return (
      <div className="space-y-2">
        <Skeleton className="h-12" />
        <Skeleton className="h-12" />
        <Skeleton className="h-12" />
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <p className="text-sm" style={{ color: "var(--ink-secondary)" }}>
        Matching uses exact email, phone and web domain plus fuzzy names (typos, sound-alikes, initials). The rule
        decides what happens when a save matches a record the person can see.
      </p>
      <div className="divide-y hairline rounded-xl border">
        {rules.data.map((r) => (
          <div key={r.objectType} className="grid gap-3 p-3 sm:grid-cols-[7rem_1fr_1fr_auto] sm:items-center">
            <span className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
              {LABELS[r.objectType]}
            </span>
            <label className="text-xs" style={{ color: "var(--ink-muted)" }}>
              On create
              <select
                className="select mt-1"
                aria-label={`${LABELS[r.objectType]} on create`}
                disabled={!canEdit || update.isPending}
                value={r.actionOnCreate}
                onChange={(e) => update.mutate({ type: r.objectType, patch: { actionOnCreate: e.target.value as DuplicateActionValue } })}
              >
                {ACTIONS.map((a) => (
                  <option key={a.value} value={a.value}>
                    {a.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-xs" style={{ color: "var(--ink-muted)" }}>
              On edit
              <select
                className="select mt-1"
                aria-label={`${LABELS[r.objectType]} on edit`}
                disabled={!canEdit || update.isPending}
                value={r.actionOnEdit}
                onChange={(e) => update.mutate({ type: r.objectType, patch: { actionOnEdit: e.target.value as DuplicateActionValue } })}
              >
                {ACTIONS.map((a) => (
                  <option key={a.value} value={a.value}>
                    {a.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="inline-flex items-center gap-2 text-sm" style={{ color: "var(--ink-secondary)" }}>
              <input
                type="checkbox"
                checked={r.enabled}
                disabled={!canEdit || update.isPending}
                onChange={(e) => update.mutate({ type: r.objectType, patch: { enabled: e.target.checked } })}
              />
              Enabled
            </label>
          </div>
        ))}
      </div>
      {!canEdit && (
        <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
          Only a Super Admin or VP Sales can change these rules.
        </p>
      )}
    </div>
  );
}
