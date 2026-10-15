import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FileText, Plus, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Document } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { AttachmentList } from "@/components/ui/AttachmentList";
import { EmptyState } from "@/components/ui/EmptyState";
import { useConfirm } from "@/components/ui/ConfirmDialog";


export default function DocumentsPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [dirty, setDirty] = useState(false);
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const list = useQuery({
    queryKey: ["documents"],
    queryFn: async () => (await api.get<Document[]>("/documents")).data,
  });

  const selected = list.data?.find((d) => d.id === selectedId) ?? null;

  useEffect(() => {
    if (selected) {
      setTitle(selected.title);
      setBody(selected.contentJson ?? "");
      setDirty(false);
    }
  }, [selectedId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!selectedId && (list.data?.length ?? 0) > 0) setSelectedId(list.data![0].id);
  }, [list.data, selectedId]);

  const create = useMutation({
    mutationFn: async () => (await api.post<Document>("/documents", { title: "Untitled", contentJson: "" })).data,
    onSuccess: (doc) => {
      qc.invalidateQueries({ queryKey: ["documents"] });
      setSelectedId(doc.id);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const save = useMutation({
    mutationFn: async () => api.put(`/documents/${selectedId}`, { title, contentJson: body }),
    onSuccess: () => {
      toast.success("Saved");
      setDirty(false);
      qc.invalidateQueries({ queryKey: ["documents"] });
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
  });

  useEffect(() => {
    if (!dirty) return;
    const t = setTimeout(() => save.mutate(), 900);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title, body]);

  return (
    <div className="flex flex-col gap-5 flex-1 min-h-0">
      <PageHeader
        title="Documents"
        description="Notion-style pages — write freely, attach files and images, link to any record."
        icon={<FileText size={20} />}
        actions={
          <button className="btn-primary" onClick={() => create.mutate()}>
            <Plus size={15} /> New document
          </button>
        }
      />

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 flex-1 min-h-0">
        <div className="lg:col-span-1 glass-card p-2 overflow-y-auto thin-scroll max-h-[70vh]">
          {list.isLoading ? (
            <p className="text-xs p-3" style={{ color: "var(--ink-muted)" }}>
              Loading…
            </p>
          ) : (list.data ?? []).length === 0 ? (
            <p className="text-xs p-3" style={{ color: "var(--ink-muted)" }}>
              No documents yet.
            </p>
          ) : (
            list.data!.map((d) => (
              <button
                key={d.id}
                onClick={() => setSelectedId(d.id)}
                className="w-full text-left px-3 py-2.5 rounded-lg mb-0.5"
                style={{ backgroundColor: d.id === selectedId ? "rgb(var(--ring-brand) / 0.1)" : undefined }}
              >
                <p className="text-sm font-medium truncate" style={{ color: d.id === selectedId ? "var(--brand-600)" : "var(--ink-primary)" }}>
                  {d.title || "Untitled"}
                </p>
                <p className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
                  {formatRelative(d.updatedAt)}
                </p>
              </button>
            ))
          )}
        </div>

        <div className="lg:col-span-3 glass-card p-5 flex flex-col min-h-[60vh]">
          {!selected ? (
            <EmptyState icon={FileText} title="No document selected" description="Create a new document or pick one from the list." />
          ) : (
            <>
              <div className="flex items-start justify-between gap-3 mb-4">
                <input
                  className="text-xl font-bold bg-transparent outline-none flex-1 min-w-0"
                  style={{ color: "var(--ink-primary)" }}
                  value={title}
                  onChange={(e) => {
                    setTitle(e.target.value);
                    setDirty(true);
                  }}
                  placeholder="Untitled"
                />
                <button
                  className="btn-icon shrink-0"
                  style={{ color: "var(--critical)" }}
                  onClick={async () => {
                    const ok = await confirm("Delete this document?", `"${selected.title}" will be removed.`);
                    if (ok) remove.mutate(selected.id);
                  }}
                >
                  <Trash2 size={16} />
                </button>
              </div>
              <textarea
                className="flex-1 bg-transparent outline-none resize-none text-sm leading-relaxed min-h-[260px]"
                style={{ color: "var(--ink-secondary)" }}
                placeholder="Start writing…"
                value={body}
                onChange={(e) => {
                  setBody(e.target.value);
                  setDirty(true);
                }}
              />
              <p className="text-[11px] mb-4" style={{ color: "var(--ink-muted)" }}>
                {save.isPending ? "Saving…" : dirty ? "Unsaved changes…" : "All changes saved"}
              </p>
              <div className="pt-4 hairline border-t">
                <AttachmentList relatedType="Document" relatedId={selected.id} />
              </div>
            </>
          )}
        </div>
      </div>
      {confirmNode}
    </div>
  );
}
