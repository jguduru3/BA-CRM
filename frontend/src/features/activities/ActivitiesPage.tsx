import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity as ActivityIcon,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  History,
  Linkedin,
  Mail,
  MessageCircle,
  Pencil,
  Phone,
  Plus,
  StickyNote,
  Video,
} from "lucide-react";
import { useEffect, useState } from "react";
import { api, getList } from "@/lib/api";
import { isReadOnly, useAuthStore } from "@/lib/auth";
import { useUsersLookup } from "@/lib/hooks";
import { formatDateTime, formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Activity } from "@/lib/types";
import type { LucideIcon } from "lucide-react";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { SearchInput } from "@/components/ui/SearchInput";
import { SkeletonRows } from "@/components/ui/Skeleton";
import { ExportButton, ListToolbar } from "@/features/crm/ListParts";
import { useDebounced } from "@/features/crm/useServerList";

const TYPES = ["call", "email", "meeting", "note", "whatsapp", "linkedin", "task", "stage_change"];
const RELATED_TYPES = ["Lead", "Opportunity", "Contact", "Company"];
const PAGE_SIZE = 25;
const TYPE_ICON: Record<string, LucideIcon> = {
  call: Phone,
  email: Mail,
  meeting: Video,
  note: StickyNote,
  whatsapp: MessageCircle,
  linkedin: Linkedin,
  task: ActivityIcon,
  stage_change: ActivityIcon,
};

export interface TimelinePage {
  count: number;
  data: Activity[];
  /** originalId → its corrections (newest first) */
  corrections?: Record<string, Activity[]>;
  /** id → original, for corrections on this page */
  originals?: Record<string, Activity>;
}

/** One timeline entry: what to show prominently, and the superseded original (if any). */
interface Entry {
  key: number;
  shown: Activity;
  original: Activity | null;
  extraCorrections: number;
}

/**
 * The timeline is append-only. A correction never edits the original: the newest
 * correction is shown as the entry, with the original kept underneath (struck,
 * collapsed) so the history is always visible.
 */
export function buildEntries(page: TimelinePage): Entry[] {
  const out: Entry[] = [];
  const seenOriginals = new Set<number>();
  for (const a of page.data) {
    if (a.correctionForId) {
      if (seenOriginals.has(a.correctionForId)) continue; // a newer correction already represents it
      seenOriginals.add(a.correctionForId);
      const all = page.corrections?.[String(a.correctionForId)] ?? [a];
      out.push({
        key: a.correctionForId,
        shown: all[0] ?? a,
        original: page.originals?.[String(a.correctionForId)] ?? null,
        extraCorrections: Math.max(0, all.length - 1),
      });
    } else {
      if (seenOriginals.has(a.id)) continue;
      seenOriginals.add(a.id);
      const corr = page.corrections?.[String(a.id)] ?? [];
      out.push(
        corr.length
          ? { key: a.id, shown: corr[0], original: a, extraCorrections: corr.length - 1 }
          : { key: a.id, shown: a, original: null, extraCorrections: 0 }
      );
    }
  }
  return out;
}

export default function ActivitiesPage() {
  const [typeFilter, setTypeFilter] = useState<string>("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [composerOpen, setComposerOpen] = useState(false);
  const [correcting, setCorrecting] = useState<Activity | null>(null);
  const [form, setForm] = useState<Partial<Activity>>({ type: "note" });
  const users = useUsersLookup();
  const qc = useQueryClient();
  const role = useAuthStore((s) => s.user?.role);
  const readOnly = isReadOnly(role);
  const term = useDebounced(search.trim());

  useEffect(() => setPage(0), [typeFilter, term]);

  const list = useQuery({
    queryKey: ["activities", "timeline", typeFilter, term, page],
    queryFn: () =>
      getList<Activity>("/activities", {
        type: typeFilter || undefined,
        search: term || undefined,
        page,
        size: PAGE_SIZE,
        withCorrections: true,
      }) as Promise<TimelinePage>,
    placeholderData: keepPreviousData,
  });

  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name ?? "Someone";
  const entries = list.data ? buildEntries(list.data) : [];
  const total = list.data?.count ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  function closeComposer() {
    setComposerOpen(false);
    setCorrecting(null);
    setForm({ type: "note" });
  }

  async function submit() {
    try {
      if (correcting) {
        await api.post(`/activities/${correcting.correctionForId ?? correcting.id}/correct`, form);
        toast.success("Correction logged — the original stays in the history");
      } else {
        await api.post("/activities", form);
        toast.success("Activity logged");
      }
      qc.invalidateQueries({ queryKey: ["activities"] });
      closeComposer();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not save activity");
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Activity Timeline"
        description={
          list.data ? `${total.toLocaleString()} entries · append-only, corrections keep the original` : "An append-only record of every call, email, meeting and note."
        }
        icon={<ActivityIcon size={20} />}
        actions={
          <>
            <ExportButton type="activities" />
            {!readOnly && (
              <button className="btn-primary" onClick={() => setComposerOpen(true)}>
                <Plus size={15} /> <span className="hidden sm:inline">Log activity</span>
              </button>
            )}
          </>
        }
      />

      <ListToolbar
        left={
          <div className="flex items-center gap-2 overflow-x-auto thin-scroll pb-1 max-w-full">
            <button
              className={typeFilter === "" ? "btn-primary px-3 py-1.5 text-xs" : "btn-secondary px-3 py-1.5 text-xs"}
              onClick={() => setTypeFilter("")}
            >
              All
            </button>
            {TYPES.map((t) => (
              <button
                key={t}
                className={typeFilter === t ? "btn-primary px-3 py-1.5 text-xs capitalize" : "btn-secondary px-3 py-1.5 text-xs capitalize"}
                onClick={() => setTypeFilter(t)}
              >
                {t.replace("_", " ")}
              </button>
            ))}
          </div>
        }
        right={<SearchInput value={search} onChange={setSearch} placeholder="Search subject or outcome…" className="w-full sm:w-64" />}
      />

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load the timeline" />
        </div>
      ) : list.isLoading ? (
        <div className="glass-card">
          <SkeletonRows rows={8} cols={1} />
        </div>
      ) : entries.length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={ActivityIcon}
            title={typeFilter || term ? "Nothing matches" : "No activity yet"}
            description={typeFilter || term ? "Try another type or search." : "Log calls, emails, meetings and notes as you work records."}
          />
        </div>
      ) : (
        <>
          <div className={`relative pl-6 transition-opacity ${list.isFetching ? "opacity-60" : ""}`}>
            <div className="absolute left-[9px] top-2 bottom-2 w-px" style={{ backgroundColor: "rgb(var(--panel-border) / 0.12)" }} />
            <div className="space-y-4">
              {entries.map((e) => (
                <TimelineEntry
                  key={e.key}
                  entry={e}
                  userName={userName}
                  canCorrect={!readOnly}
                  onCorrect={(a) => {
                    setCorrecting(a);
                    setForm({ type: a.type, subject: a.subject ?? "", outcome: a.outcome ?? "", body: a.body ?? "" });
                    setComposerOpen(true);
                  }}
                />
              ))}
            </div>
          </div>
          {pages > 1 && (
            <div className="flex items-center justify-center gap-2 text-xs" style={{ color: "var(--ink-muted)" }}>
              <button className="btn-icon h-8 w-8" aria-label="Newer" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                <ChevronLeft size={15} />
              </button>
              <span className="tabular-nums">
                Page {page + 1} of {pages}
              </span>
              <button className="btn-icon h-8 w-8" aria-label="Older" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>
                <ChevronRight size={15} />
              </button>
            </div>
          )}
        </>
      )}

      <Modal
        open={composerOpen}
        onClose={closeComposer}
        title={correcting ? "Correct this entry" : "Log activity"}
        width="30rem"
        footer={
          <>
            <button className="btn-ghost" onClick={closeComposer}>
              Cancel
            </button>
            <button className="btn-primary" onClick={submit}>
              {correcting ? "Save correction" : "Log activity"}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          {correcting && (
            <p className="text-xs rounded-lg px-3 py-2" style={{ backgroundColor: "rgb(var(--ring-brand) / 0.08)", color: "var(--ink-secondary)" }}>
              The timeline is append-only: your correction is added as the current version and the original stays visible underneath it.
            </p>
          )}
          <FieldGrid>
            <Field label="Type">
              <select className="select" value={form.type ?? "note"} onChange={(e) => setForm((f) => ({ ...f, type: e.target.value }))}>
                {TYPES.map((t) => (
                  <option key={t} value={t} className="capitalize">
                    {t.replace("_", " ")}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Outcome">
              <input className="input" value={form.outcome ?? ""} onChange={(e) => setForm((f) => ({ ...f, outcome: e.target.value }))} />
            </Field>
            {!correcting && (
              <>
                <Field label="Related type">
                  <select className="select" value={form.relatedType ?? ""} onChange={(e) => setForm((f) => ({ ...f, relatedType: e.target.value }))}>
                    <option value="">—</option>
                    {RELATED_TYPES.map((t) => (
                      <option key={t}>{t}</option>
                    ))}
                  </select>
                </Field>
                <Field label="Related ID">
                  <input
                    className="input"
                    type="number"
                    value={form.relatedId ?? ""}
                    onChange={(e) => setForm((f) => ({ ...f, relatedId: e.target.value ? Number(e.target.value) : undefined }))}
                  />
                </Field>
              </>
            )}
          </FieldGrid>
          <Field label="Subject">
            <input className="input" value={form.subject ?? ""} onChange={(e) => setForm((f) => ({ ...f, subject: e.target.value }))} />
          </Field>
          <Field label="Details">
            <textarea className="textarea" value={form.body ?? ""} onChange={(e) => setForm((f) => ({ ...f, body: e.target.value }))} />
          </Field>
        </div>
      </Modal>
    </div>
  );
}

function TimelineEntry({
  entry,
  userName,
  canCorrect,
  onCorrect,
}: {
  entry: Entry;
  userName: (id: number | null) => string;
  canCorrect: boolean;
  onCorrect: (a: Activity) => void;
}) {
  const [showOriginal, setShowOriginal] = useState(false);
  const a = entry.shown;
  const corrected = !!a.correctionForId;
  const Icon = TYPE_ICON[a.type] ?? ActivityIcon;
  const restricted = a.subject === "[restricted]";

  return (
    <div className="relative">
      <div
        className="absolute -left-6 top-1 flex items-center justify-center h-5 w-5 rounded-full ring-4"
        style={{ backgroundColor: corrected ? "var(--warning, #eda100)" : "var(--brand-500)", ["--tw-ring-color" as string]: "var(--plane)" } as React.CSSProperties}
      >
        {corrected ? <Pencil size={10} className="text-white" /> : <Icon size={11} className="text-white" />}
      </div>
      <div className="glass-card p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-2.5 min-w-0">
            <Avatar name={userName(a.userId)} size={26} />
            <div className="min-w-0">
              <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                {restricted ? `${a.type.replace("_", " ")} logged` : a.subject || `${a.type.replace("_", " ")} logged`}
              </p>
              <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                {userName(a.userId)} · {formatRelative(a.createdAt)}
                {(a.relatedType ?? entry.original?.relatedType) &&
                  ` · ${a.relatedType ?? entry.original?.relatedType} #${a.relatedId ?? entry.original?.relatedId}`}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-1.5 shrink-0">
            {corrected && <span className="badge-warning">Corrected</span>}
            <span className="badge-brand capitalize">{a.type.replace("_", " ")}</span>
          </div>
        </div>
        {a.outcome && !restricted && (
          <p className="text-xs mt-2 font-medium" style={{ color: "var(--ink-secondary)" }}>
            Outcome: {a.outcome}
          </p>
        )}
        {a.body && (
          <p className="text-sm mt-2.5 whitespace-pre-wrap" style={{ color: "var(--ink-secondary)" }}>
            {a.body}
          </p>
        )}
        {restricted && (
          <p className="text-xs italic mt-2" style={{ color: "var(--ink-muted)" }}>
            Details hidden — only the author and managers can read this entry.
          </p>
        )}

        {corrected && (
          <div className="mt-3 rounded-lg" style={{ backgroundColor: "rgb(var(--panel-border) / 0.04)" }}>
            <button
              className="w-full flex items-center justify-between gap-2 px-3 py-2 text-xs font-semibold"
              style={{ color: "var(--ink-muted)" }}
              onClick={() => setShowOriginal((v) => !v)}
              aria-expanded={showOriginal}
            >
              <span className="inline-flex items-center gap-1.5">
                <History size={12} /> Original entry
                {entry.extraCorrections > 0 && ` · corrected ${entry.extraCorrections + 1}×`}
              </span>
              <ChevronDown size={13} className={`transition-transform ${showOriginal ? "rotate-180" : ""}`} />
            </button>
            {showOriginal && (
              <div className="px-3 pb-3 text-xs space-y-1" style={{ color: "var(--ink-muted)" }}>
                {entry.original ? (
                  <>
                    <p className="line-through">{entry.original.subject || `${entry.original.type.replace("_", " ")} logged`}</p>
                    {entry.original.outcome && <p className="line-through">Outcome: {entry.original.outcome}</p>}
                    {entry.original.body && <p className="line-through whitespace-pre-wrap">{entry.original.body}</p>}
                    <p>
                      Logged by {userName(entry.original.userId)} · {formatDateTime(entry.original.createdAt)}
                    </p>
                  </>
                ) : (
                  <p>The original entry is outside your view.</p>
                )}
              </div>
            )}
          </div>
        )}

        <div className="flex items-center justify-between mt-3">
          <span className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
            {formatDateTime(a.createdAt)}
          </span>
          {canCorrect && !restricted && (
            <button className="text-xs font-semibold" style={{ color: "var(--brand-600)" }} onClick={() => onCorrect(a)}>
              {corrected ? "Correct again" : "Add correction"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
