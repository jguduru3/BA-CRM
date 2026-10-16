import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pin, PinOff, Plus, StickyNote, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { api, getList } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Note } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { SearchInput } from "@/components/ui/SearchInput";
import { SkeletonCards } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { LinkifiedText } from "@/features/recordpages/LinkifiedText";

const RELATED_TYPES = ["Lead", "Opportunity", "Contact", "Company", "Meeting"];

type Draft = Partial<Note>;

/**
 * Notes as a card board: pinned notes first, full-text search in the database,
 * bodies rendered as plain text with links only through SafeLink.
 */
export default function NotesPage() {
  const qc = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const readOnly = isReadOnly(user?.role);
  const [search, setSearch] = useState("");
  const [debounced, setDebounced] = useState("");
  const [editing, setEditing] = useState<Note | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<Draft>({});
  const { confirm, node: confirmNode } = useConfirm();

  useEffect(() => {
    const t = setTimeout(() => setDebounced(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);

  const list = useQuery({
    queryKey: ["notes", { q: debounced }],
    queryFn: () => getList<Note>("/notes", { q: debounced || undefined, size: 300 }),
  });

  const canEdit = (n: Note) => !readOnly && (isManagerUp(user?.role) || n.createdBy === user?.id);
  const invalidate = () => qc.invalidateQueries({ queryKey: ["notes"] });

  const save = useMutation({
    mutationFn: async () => {
      const payload = {
        title: draft.title ?? null,
        body: draft.body,
        pinned: !!draft.pinned,
        relatedType: draft.relatedType || null,
        relatedId: draft.relatedType ? draft.relatedId ?? null : null,
      };
      if (editing) return api.put(`/notes/${editing.id}`, { title: payload.title, body: payload.body, pinned: payload.pinned });
      return api.post("/notes", payload);
    },
    onSuccess: () => {
      toast.success(editing ? "Note updated" : "Note created");
      close();
      invalidate();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const togglePin = useMutation({
    mutationFn: async (n: Note) => api.put(`/notes/${n.id}`, { pinned: !n.pinned }),
    onSuccess: invalidate,
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (n: Note) => api.delete(`/notes/${n.id}`),
    onSuccess: () => {
      toast.success("Note moved to trash");
      close();
      invalidate();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const open = (n: Note | null) => {
    setEditing(n);
    setCreating(!n);
    setDraft(n ? { ...n } : { pinned: false });
  };
  const close = () => {
    setEditing(null);
    setCreating(false);
  };

  const notes = list.data?.data ?? [];
  const pinned = notes.filter((n) => n.pinned);
  const others = notes.filter((n) => !n.pinned);
  const editable = creating || (editing ? canEdit(editing) : false);

  const card = (n: Note) => (
    <article
      key={n.id}
      className="glass-card-hover p-4 flex flex-col gap-2 cursor-pointer min-h-[8rem]"
      onClick={() => open(n)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => e.key === "Enter" && open(n)}
    >
      <header className="flex items-start justify-between gap-2">
        <h3 className="text-sm font-semibold leading-snug line-clamp-2" style={{ color: "var(--ink-primary)" }}>
          {n.title || "Untitled note"}
        </h3>
        {canEdit(n) ? (
          <button
            className="btn-icon h-7 w-7 -mr-1 -mt-1"
            aria-label={n.pinned ? "Unpin" : "Pin"}
            title={n.pinned ? "Unpin" : "Pin to top"}
            style={{ color: n.pinned ? "var(--brand-500)" : "var(--ink-muted)" }}
            onClick={(e) => {
              e.stopPropagation();
              togglePin.mutate(n);
            }}
          >
            {n.pinned ? <PinOff size={14} /> : <Pin size={14} />}
          </button>
        ) : (
          n.pinned && <Pin size={14} style={{ color: "var(--brand-500)" }} />
        )}
      </header>
      <p className="text-sm leading-relaxed line-clamp-5 flex-1" style={{ color: "var(--ink-secondary)" }}>
        <LinkifiedText text={n.body} />
      </p>
      <footer className="flex items-center justify-between gap-2 pt-2 hairline border-t text-[11px]" style={{ color: "var(--ink-muted)" }}>
        {n.relatedType ? (
          <span className="badge-brand">
            {n.relatedType} #{n.relatedId}
          </span>
        ) : (
          <span className="badge-neutral">General</span>
        )}
        <span>{formatRelative(n.updatedAt || n.createdAt)}</span>
      </footer>
    </article>
  );

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Notes"
        description={`${list.data?.count ?? 0} notes`}
        icon={<StickyNote size={20} />}
        actions={
          !readOnly && (
            <button className="btn-primary" onClick={() => open(null)}>
              <Plus size={15} /> New note
            </button>
          )
        }
      />
      <SearchInput value={search} onChange={setSearch} placeholder="Search notes…" className="max-w-sm" />

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load notes" />
        </div>
      ) : list.isLoading ? (
        <SkeletonCards count={6} />
      ) : notes.length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={StickyNote}
            title={debounced ? "No notes match your search" : "No notes yet"}
            description={debounced ? "Try a different word." : "Capture call notes, ideas and context — link them to any record."}
            action={
              !readOnly &&
              !debounced && (
                <button className="btn-primary" onClick={() => open(null)}>
                  <Plus size={15} /> New note
                </button>
              )
            }
          />
        </div>
      ) : (
        <div className="space-y-6">
          {pinned.length > 0 && (
            <section>
              <h2 className="field-label">Pinned</h2>
              <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">{pinned.map(card)}</div>
            </section>
          )}
          {others.length > 0 && (
            <section>
              {pinned.length > 0 && <h2 className="field-label">Others</h2>}
              <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">{others.map(card)}</div>
            </section>
          )}
        </div>
      )}

      <Modal
        open={creating || !!editing}
        onClose={close}
        title={creating ? "New note" : editable ? "Edit note" : editing?.title || "Note"}
        width="36rem"
        footer={
          <>
            {editing && editable && (
              <button
                className="btn-danger mr-auto"
                onClick={async () => {
                  const ok = await confirm("Delete this note?", "It will be moved to trash.");
                  if (ok) remove.mutate(editing);
                }}
              >
                <Trash2 size={14} /> Delete
              </button>
            )}
            <button className="btn-ghost" onClick={close}>
              {editable ? "Cancel" : "Close"}
            </button>
            {editable && (
              <button className="btn-primary" disabled={!draft.body?.trim() || save.isPending} onClick={() => save.mutate()}>
                {creating ? "Create note" : "Save"}
              </button>
            )}
          </>
        }
      >
        {editable ? (
          <div className="space-y-4">
            <Field label="Title">
              <input className="input" value={draft.title ?? ""} onChange={(e) => setDraft((d) => ({ ...d, title: e.target.value }))} />
            </Field>
            <Field label="Note" required>
              <textarea
                className="textarea min-h-[10rem]"
                value={draft.body ?? ""}
                onChange={(e) => setDraft((d) => ({ ...d, body: e.target.value }))}
              />
            </Field>
            <label className="flex items-center gap-2 text-sm" style={{ color: "var(--ink-secondary)" }}>
              <input type="checkbox" className="checkbox" checked={!!draft.pinned} onChange={(e) => setDraft((d) => ({ ...d, pinned: e.target.checked }))} />
              Pin to top
            </label>
            {creating && (
              <FieldGrid>
                <Field label="Linked record type">
                  <select
                    className="select"
                    value={draft.relatedType ?? ""}
                    onChange={(e) => setDraft((d) => ({ ...d, relatedType: e.target.value || null }))}
                  >
                    <option value="">— None —</option>
                    {RELATED_TYPES.map((t) => (
                      <option key={t}>{t}</option>
                    ))}
                  </select>
                </Field>
                <Field label="Record ID">
                  <input
                    className="input"
                    type="number"
                    disabled={!draft.relatedType}
                    value={draft.relatedId ?? ""}
                    onChange={(e) => setDraft((d) => ({ ...d, relatedId: e.target.value ? Number(e.target.value) : null }))}
                  />
                </Field>
              </FieldGrid>
            )}
          </div>
        ) : (
          editing && (
            <div className="text-sm leading-relaxed" style={{ color: "var(--ink-secondary)" }}>
              <LinkifiedText text={editing.body} />
            </div>
          )
        )}
      </Modal>
      {confirmNode}
    </div>
  );
}
