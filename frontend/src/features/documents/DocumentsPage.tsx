import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FileText, Plus, Search, Trash2, Wand2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { isReadOnly, useAuthStore } from "@/lib/auth";
import { formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Document } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { AttachmentList } from "@/components/ui/AttachmentList";
import { EmptyState } from "@/components/ui/EmptyState";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { LinkifiedText } from "@/features/recordpages/LinkifiedText";
import { RecordPagePanel } from "@/features/recordpages/RecordPagePanel";

/**
 * Documents are block pages: the body is a RecordPagePanel bound to
 * relatedType "Document". Older documents kept their text in `contentJson`;
 * that legacy text is shown read-only (plain text, links via SafeLink) with a
 * one-click conversion into a paragraph block.
 */
export default function DocumentsPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [title, setTitle] = useState("");
  const [titleDirty, setTitleDirty] = useState(false);
  const [search, setSearch] = useState("");
  const qc = useQueryClient();
  const role = useAuthStore((s) => s.user?.role);
  const readOnly = isReadOnly(role);
  const canDelete = role === "SUPER_ADMIN" || role === "VP_SALES";
  const { confirm, node: confirmNode } = useConfirm();

  const list = useQuery({
    queryKey: ["documents"],
    queryFn: async () => (await api.get<Document[]>("/documents")).data,
  });

  const docs = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (list.data ?? []).filter((d) => !q || (d.title || "").toLowerCase().includes(q));
  }, [list.data, search]);
  const selected = list.data?.find((d) => d.id === selectedId) ?? null;

  useEffect(() => {
    if (selected) {
      setTitle(selected.title);
      setTitleDirty(false);
    }
  }, [selectedId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!selectedId && (list.data?.length ?? 0) > 0) setSelectedId(list.data![0].id);
  }, [list.data, selectedId]);

  const create = useMutation({
    mutationFn: async () => (await api.post<Document>("/documents", { title: "Untitled" })).data,
    onSuccess: (doc) => {
      qc.invalidateQueries({ queryKey: ["documents"] });
      setSelectedId(doc.id);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const saveTitle = useMutation({
    mutationFn: async () => api.put(`/documents/${selectedId}`, { title }),
    onSuccess: () => {
      setTitleDirty(false);
      qc.invalidateQueries({ queryKey: ["documents"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const convertLegacy = useMutation({
    mutationFn: async (doc: Document) => {
      const text = doc.contentJson ?? "";
      const paragraphs = text.split(/\n{2,}/).map((p) => p.trim()).filter(Boolean);
      for (const p of paragraphs) {
        await api.post(`/pages/Document/${doc.id}/blocks`, { blockType: "paragraph", content: { text: p } });
      }
      await api.put(`/documents/${doc.id}`, { contentJson: null });
    },
    onSuccess: () => {
      toast.success("Moved into blocks");
      qc.invalidateQueries({ queryKey: ["documents"] });
      qc.invalidateQueries({ queryKey: ["record-page", "Document"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/documents/${id}`),
    onSuccess: () => {
      toast.success("Document deleted");
      setSelectedId(null);
      qc.invalidateQueries({ queryKey: ["documents"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  useEffect(() => {
    if (!titleDirty || readOnly) return;
    const t = setTimeout(() => saveTitle.mutate(), 800);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title, titleDirty]);

  return (
    <div className="flex flex-col gap-5 flex-1 min-h-0">
      <PageHeader
        title="Documents"
        description="Block pages — headings, notes, to-dos and files, linked to any record."
        icon={<FileText size={20} />}
        actions={
          !readOnly && (
            <button className="btn-primary" onClick={() => create.mutate()} disabled={create.isPending}>
              <Plus size={15} /> New document
            </button>
          )
        }
      />

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load documents" />
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 flex-1 min-h-0">
          <aside className="lg:col-span-1 glass-card p-2 flex flex-col min-h-0 max-h-[40vh] lg:max-h-[75vh]">
            <label className="relative block m-1 mb-2">
              <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2" style={{ color: "var(--ink-muted)" }} />
              <input
                className="input pl-8 py-1.5 text-sm"
                placeholder="Search documents…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                aria-label="Search documents"
              />
            </label>
            <div className="overflow-y-auto thin-scroll flex-1">
              {list.isLoading ? (
                <div className="space-y-2 p-2">
                  <Skeleton className="h-9" />
                  <Skeleton className="h-9" />
                  <Skeleton className="h-9" />
                </div>
              ) : docs.length === 0 ? (
                <p className="text-xs p-3 text-center" style={{ color: "var(--ink-muted)" }}>
                  {search ? "No matching documents." : "No documents yet."}
                </p>
              ) : (
                docs.map((d) => {
                  const active = d.id === selectedId;
                  return (
                    <button
                      key={d.id}
                      onClick={() => setSelectedId(d.id)}
                      className="w-full text-left px-3 py-2.5 rounded-xl mb-0.5 flex items-start gap-2.5 transition-colors"
                      style={{ backgroundColor: active ? "rgb(var(--ring-brand) / 0.1)" : undefined }}
                    >
                      <FileText size={15} className="mt-0.5 shrink-0" style={{ color: active ? "var(--brand-600)" : "var(--ink-muted)" }} />
                      <span className="min-w-0">
                        <span className="block text-sm font-medium truncate" style={{ color: active ? "var(--brand-600)" : "var(--ink-primary)" }}>
                          {d.title || "Untitled"}
                        </span>
                        <span className="block text-[11px]" style={{ color: "var(--ink-muted)" }}>
                          Edited {formatRelative(d.updatedAt)}
                        </span>
                      </span>
                    </button>
                  );
                })
              )}
            </div>
          </aside>

          <section className="lg:col-span-3 glass-card p-5 sm:p-8 flex flex-col min-h-[60vh]">
            {!selected ? (
              list.isLoading ? (
                <div className="space-y-3">
                  <Skeleton className="h-8 w-1/2" />
                  <Skeleton className="h-4 w-full" />
                </div>
              ) : (
                <EmptyState
                  icon={FileText}
                  title="No document selected"
                  description={readOnly ? "Pick a document from the list." : "Create a new document or pick one from the list."}
                />
              )
            ) : (
              <div className="max-w-3xl w-full mx-auto">
                <div className="flex items-start justify-between gap-3 mb-1">
                  <input
                    className="text-2xl sm:text-3xl font-bold tracking-tight bg-transparent outline-none flex-1 min-w-0"
                    style={{ color: "var(--ink-primary)" }}
                    value={title}
                    readOnly={readOnly}
                    aria-label="Document title"
                    onChange={(e) => {
                      setTitle(e.target.value);
                      setTitleDirty(true);
                    }}
                    placeholder="Untitled"
                  />
                  {canDelete && (
                    <button
                      className="btn-icon shrink-0"
                      style={{ color: "var(--critical)" }}
                      aria-label="Delete document"
                      onClick={async () => {
                        const ok = await confirm("Delete this document?", `"${selected.title}" will be moved to trash.`);
                        if (ok) remove.mutate(selected.id);
                      }}
                    >
                      <Trash2 size={16} />
                    </button>
                  )}
                </div>
                <p className="text-[11px] mb-6" style={{ color: "var(--ink-muted)" }}>
                  {saveTitle.isPending ? "Saving…" : titleDirty ? "Unsaved changes…" : `Last edited ${formatRelative(selected.updatedAt)}`}
                </p>

                {selected.contentJson && selected.contentJson.trim() && (
                  <div className="mb-6 rounded-xl p-4" style={{ backgroundColor: "rgb(var(--panel-border) / 0.035)" }}>
                    <div className="flex items-center justify-between gap-2 mb-2">
                      <p className="field-label mb-0">Earlier notes</p>
                      {!readOnly && (
                        <button className="btn-ghost px-2 py-1 text-xs" onClick={() => convertLegacy.mutate(selected)} disabled={convertLegacy.isPending}>
                          <Wand2 size={12} /> Convert to blocks
                        </button>
                      )}
                    </div>
                    <div className="text-sm leading-relaxed" style={{ color: "var(--ink-secondary)" }}>
                      <LinkifiedText text={selected.contentJson} />
                    </div>
                  </div>
                )}

                <RecordPagePanel key={selected.id} relatedType="Document" relatedId={selected.id} readOnly={readOnly} />

                <div className="mt-8 pt-5 hairline border-t">
                  <AttachmentList relatedType="Document" relatedId={selected.id} readOnly={readOnly} />
                </div>
              </div>
            )}
          </section>
        </div>
      )}
      {confirmNode}
    </div>
  );
}
