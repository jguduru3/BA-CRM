import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Copy, GitMerge, Settings2 } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { formatDate } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { EmptyState } from "@/components/ui/EmptyState";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { SkeletonCards } from "@/components/ui/Skeleton";
import { Tabs } from "@/components/ui/Tabs";
import { DuplicateRulesPanel } from "./DuplicateRulesPanel";
import {
  apiPath,
  COMPARE_FIELDS,
  differingFields,
  matchLabel,
  recordHref,
  scorePercent,
} from "./duplicates";
import type { DuplicateGroup, DuplicateGroupsPage, DuplicateType } from "./duplicates";

type Row = Record<string, unknown> & { id: number; name: string };

const PAGE_SIZE = 10;
const TYPE_LABEL: Record<DuplicateType, string> = { lead: "leads", contact: "contacts", company: "companies" };
const MAX_SECONDARIES = 2;

function display(key: string, v: unknown): string {
  if (v === null || v === undefined || v === "") return "—";
  if (key === "createdAt") return formatDate(String(v));
  return String(v);
}

export default function DuplicatesPage() {
  const [type, setType] = useState<DuplicateType>("lead");
  const [page, setPage] = useState(0);
  const [rulesOpen, setRulesOpen] = useState(false);
  const role = useAuthStore((s) => s.user?.role);
  const canMerge = isManagerUp(role) && !isReadOnly(role);
  const canEditRules = role === "SUPER_ADMIN" || role === "VP_SALES";

  const groups = useQuery({
    queryKey: ["duplicates", type, page],
    queryFn: async () =>
      (await api.get<DuplicateGroupsPage<Row>>("/duplicates", { params: { type, page, size: PAGE_SIZE } })).data,
  });
  const total = groups.data?.count ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Duplicates"
        description={
          groups.data
            ? `${total.toLocaleString()} possible duplicate ${total === 1 ? "group" : "groups"} among the ${TYPE_LABEL[type]} you can see`
            : "Find and merge records that describe the same person or company"
        }
        icon={<Copy size={20} />}
        actions={
          <button className="btn-secondary" onClick={() => setRulesOpen(true)}>
            <Settings2 size={15} /> <span className="hidden sm:inline">Duplicate rules</span>
          </button>
        }
      />

      <Tabs
        value={type}
        onChange={(t) => {
          setType(t);
          setPage(0);
        }}
        tabs={[
          { value: "lead", label: "Leads" },
          { value: "contact", label: "Contacts" },
          { value: "company", label: "Companies" },
        ]}
      />

      {groups.isError ? (
        <div className="glass-card">
          <QueryError error={groups.error} onRetry={() => groups.refetch()} title="Couldn't load duplicates" />
        </div>
      ) : groups.isLoading || !groups.data ? (
        <SkeletonCards count={3} />
      ) : groups.data.data.length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={Copy}
            title={`No duplicate ${TYPE_LABEL[type]} found`}
            description="Records sharing an email, phone or web domain, or with very similar names, show up here."
          />
        </div>
      ) : (
        <>
          {groups.data.truncated && (
            <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
              Very large match blocks were capped; merge these groups and reload to see more.
            </p>
          )}
          <div className="flex flex-col gap-4">
            {groups.data.data.map((g) => (
              <GroupCard key={g.key} type={type} group={g} canMerge={canMerge} />
            ))}
          </div>
          {pages > 1 && (
            <div className="flex items-center justify-end gap-2">
              <span className="text-sm" style={{ color: "var(--ink-muted)" }}>
                Page {page + 1} of {pages}
              </span>
              <button className="btn-icon" aria-label="Previous page" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                <ChevronLeft size={16} />
              </button>
              <button className="btn-icon" aria-label="Next page" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>
                <ChevronRight size={16} />
              </button>
            </div>
          )}
        </>
      )}

      <Modal open={rulesOpen} onClose={() => setRulesOpen(false)} title="Duplicate rules" width="44rem">
        <DuplicateRulesPanel canEdit={canEditRules} />
      </Modal>
    </div>
  );
}

/** One candidate group: side-by-side comparison, primary picker and merge. */
export function GroupCard({ type, group, canMerge }: { type: DuplicateType; group: DuplicateGroup<Row>; canMerge: boolean }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { confirm, node: confirmNode } = useConfirm();
  const records = group.records;
  const [primaryId, setPrimaryId] = useState<number>(records[0].id);
  const [secondaryIds, setSecondaryIds] = useState<number[]>(
    records.slice(1, 1 + MAX_SECONDARIES).map((r) => r.id)
  );
  const fields = COMPARE_FIELDS[type];
  const differs = differingFields(records, fields.map((f) => f.key));

  const merge = useMutation({
    mutationFn: async () =>
      (await api.post(`${apiPath(type)}/${primaryId}/merge`, { secondaryIds: secondaryIds.filter((id) => id !== primaryId) })).data,
    onSuccess: () => {
      toast.success("Records merged");
      qc.invalidateQueries({ queryKey: ["duplicates"] });
      qc.invalidateQueries({ queryKey: [TYPE_LABEL[type]] }); // "leads" | "contacts" | "companies"
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const choosePrimary = (id: number) => {
    setPrimaryId(id);
    setSecondaryIds((s) => {
      const rest = s.filter((x) => x !== id);
      if (rest.length === 0) {
        const fallback = records.find((r) => r.id !== id);
        return fallback ? [fallback.id] : [];
      }
      return rest;
    });
  };
  const toggleSecondary = (id: number) =>
    setSecondaryIds((s) => (s.includes(id) ? s.filter((x) => x !== id) : s.length >= MAX_SECONDARIES ? s : [...s, id]));

  const chosen = secondaryIds.filter((id) => id !== primaryId);
  const primaryName = records.find((r) => r.id === primaryId)?.name ?? "";

  async function onMerge() {
    const ok = await confirm(
      `Merge into "${primaryName}"?`,
      `${chosen.length} record${chosen.length === 1 ? "" : "s"} will be merged into "${primaryName}". The primary's values win, its empty fields are filled in, and every activity, task, note and file moves over. Merged records go to the trash. This cannot be undone.`
    );
    if (ok) merge.mutate();
  }

  return (
    <section className="glass-card p-4 sm:p-5" aria-label={`Duplicate group ${group.key}`}>
      <header className="flex flex-wrap items-center gap-2 mb-4">
        <span className="badge-brand">{scorePercent(group.score)} match</span>
        {group.matchedOn.map((m) => (
          <span key={m} className="badge-neutral">
            {matchLabel(m)}
          </span>
        ))}
        <span className="text-xs ml-auto" style={{ color: "var(--ink-muted)" }}>
          {records.length} records
        </span>
      </header>

      <div className="overflow-x-auto thin-scroll -mx-1 px-1">
        <table className="w-full text-sm border-separate" style={{ borderSpacing: 0, minWidth: `${8 + records.length * 12}rem` }}>
          <thead>
            <tr>
              <th className="w-28" />
              {records.map((r) => (
                <th key={r.id} className="text-left align-top p-2" scope="col">
                  <button
                    type="button"
                    className="font-semibold hover:underline text-left"
                    style={{ color: "var(--ink-primary)" }}
                    onClick={() => navigate(recordHref(type, r.id))}
                  >
                    {r.name}
                  </button>
                  <div className="text-xs font-normal" style={{ color: "var(--ink-muted)" }}>
                    #{r.id}
                  </div>
                  {canMerge && (
                    <div className="mt-2 flex flex-col gap-1 font-normal text-xs" style={{ color: "var(--ink-secondary)" }}>
                      <label className="inline-flex items-center gap-1.5 cursor-pointer">
                        <input
                          type="radio"
                          name={`primary-${group.key}`}
                          checked={primaryId === r.id}
                          onChange={() => choosePrimary(r.id)}
                        />
                        Keep as primary
                      </label>
                      {primaryId !== r.id && (
                        <label className="inline-flex items-center gap-1.5 cursor-pointer">
                          <input
                            type="checkbox"
                            checked={secondaryIds.includes(r.id)}
                            disabled={!secondaryIds.includes(r.id) && chosen.length >= MAX_SECONDARIES}
                            onChange={() => toggleSecondary(r.id)}
                          />
                          Merge in
                        </label>
                      )}
                    </div>
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {fields.map((f) => (
              <tr key={f.key}>
                <th scope="row" className="text-left font-medium text-xs p-2 hairline border-t" style={{ color: "var(--ink-muted)" }}>
                  {f.label}
                </th>
                {records.map((r) => (
                  <td
                    key={r.id}
                    className="p-2 hairline border-t break-words"
                    style={{
                      color: "var(--ink-primary)",
                      background: differs.has(f.key) ? "color-mix(in srgb, var(--warning) 8%, transparent)" : undefined,
                    }}
                  >
                    {display(f.key, r[f.key])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {canMerge && (
        <footer className="mt-4 flex flex-col-reverse sm:flex-row sm:items-center justify-end gap-2">
          <span className="text-xs sm:mr-auto" style={{ color: "var(--ink-muted)" }}>
            Up to {MAX_SECONDARIES} records merge into the primary at a time. Merging is irreversible.
          </span>
          <button className="btn-primary" disabled={chosen.length === 0 || merge.isPending} onClick={onMerge}>
            <GitMerge size={15} /> {merge.isPending ? "Merging…" : `Merge ${chosen.length + 1} records`}
          </button>
        </footer>
      )}
      {confirmNode}
    </section>
  );
}
