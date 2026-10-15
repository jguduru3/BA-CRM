import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { File, FileImage, Paperclip, Trash2, Upload } from "lucide-react";
import { useRef } from "react";
import { api } from "@/lib/api";
import { formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";

export interface AttachmentRow {
  id: number;
  fileName: string;
  contentType: string | null;
  sizeBytes: number;
  createdAt: string;
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Drop-in "Files" panel — upload/list/download/delete attachments for any
 * CRM or workspace record via the generic POST/GET/DELETE /api/attachments
 * endpoints. Used by Documents, Database records, and can be dropped into
 * any detail drawer (Lead, Opportunity, Contact, Company...).
 */
export function AttachmentList({ relatedType, relatedId }: { relatedType: string; relatedId: number }) {
  const qc = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const queryKey = ["attachments", relatedType, relatedId];

  const list = useQuery({
    queryKey,
    queryFn: async () => (await api.get<AttachmentRow[]>("/attachments", { params: { relatedType, relatedId } })).data,
  });

  const upload = useMutation({
    mutationFn: async (file: File) => {
      const form = new FormData();
      form.append("file", file);
      form.append("relatedType", relatedType);
      form.append("relatedId", String(relatedId));
      return api.post("/attachments", form, { headers: { "Content-Type": "multipart/form-data" } });
    },
    onSuccess: () => {
      toast.success("File uploaded");
      qc.invalidateQueries({ queryKey });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/attachments/${id}`),
    onSuccess: () => {
      toast.success("File removed");
      qc.invalidateQueries({ queryKey });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const onFiles = (files: FileList | null) => {
    if (!files) return;
    Array.from(files).forEach((f) => upload.mutate(f));
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-2">
        <p className="field-label mb-0">Files &amp; images</p>
        <button className="btn-secondary px-2.5 py-1 text-xs" onClick={() => inputRef.current?.click()} disabled={upload.isPending}>
          <Upload size={12} /> {upload.isPending ? "Uploading…" : "Upload"}
        </button>
        <input ref={inputRef} type="file" multiple hidden onChange={(e) => onFiles(e.target.files)} />
      </div>
      <div
        className="rounded-xl border border-dashed p-3 space-y-1.5"
        style={{ borderColor: "rgb(var(--panel-border) / 0.16)" }}
        onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => {
          e.preventDefault();
          onFiles(e.dataTransfer.files);
        }}
      >
        {list.isLoading ? (
          <p className="text-xs text-center py-3" style={{ color: "var(--ink-muted)" }}>
            Loading…
          </p>
        ) : (list.data ?? []).length === 0 ? (
          <div className="flex flex-col items-center py-4 text-center">
            <Paperclip size={18} style={{ color: "var(--ink-muted)" }} />
            <p className="text-xs mt-1.5" style={{ color: "var(--ink-muted)" }}>
              Drag files here or click Upload
            </p>
          </div>
        ) : (
          list.data!.map((a) => {
            const isImage = a.contentType?.startsWith("image/");
            return (
              <div key={a.id} className="flex items-center gap-2.5 py-1.5 px-2 rounded-lg group hover:bg-[rgb(var(--panel-border)/0.05)]">
                {isImage ? <FileImage size={15} style={{ color: "var(--brand-500)" }} /> : <File size={15} style={{ color: "var(--ink-muted)" }} />}
                <a
                  href={`/api/attachments/${a.id}/download`}
                  target="_blank"
                  rel="noreferrer"
                  className="flex-1 min-w-0 text-sm truncate hover:underline"
                  style={{ color: "var(--ink-primary)" }}
                >
                  {a.fileName}
                </a>
                <span className="text-[11px] shrink-0" style={{ color: "var(--ink-muted)" }}>
                  {formatSize(a.sizeBytes)} · {formatRelative(a.createdAt)}
                </span>
                <button
                  className="btn-icon h-6 w-6 opacity-0 group-hover:opacity-100 shrink-0"
                  style={{ color: "var(--critical)" }}
                  onClick={() => remove.mutate(a.id)}
                >
                  <Trash2 size={12} />
                </button>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
