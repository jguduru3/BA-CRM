import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LayoutDashboard, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { SkeletonCards } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";

interface Board {
  id: number;
  name: string;
  description: string | null;
  createdBy: number | null;
  itemCount?: number;
  updatedAt: string;
}

const GRADIENTS = [
  "linear-gradient(135deg,#2a78d6,#1baf7a)",
  "linear-gradient(135deg,#eb6834,#eda100)",
  "linear-gradient(135deg,#4a3aa7,#e87ba4)",
  "linear-gradient(135deg,#1baf7a,#2a78d6)",
];

export default function WhiteboardsPage() {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { confirm, node: confirmNode } = useConfirm();
  const me = useAuthStore((s) => s.user);
  const readOnly = isReadOnly(me?.role);
  const canDelete = (b: Board) => !readOnly && (isManagerUp(me?.role) || b.createdBy === me?.id);

  const list = useQuery({
    queryKey: ["whiteboards"],
    queryFn: async () => (await api.get<Board[]>("/whiteboards")).data,
  });

  const create = useMutation({
    mutationFn: async () => (await api.post<Board>("/whiteboards", { name })).data,
    onSuccess: (board) => {
      toast.success("Board created");
      qc.invalidateQueries({ queryKey: ["whiteboards"] });
      setCreating(false);
      setName("");
      navigate(`/whiteboards/${board.id}`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/whiteboards/${id}`),
    onSuccess: () => {
      toast.success("Board deleted");
      qc.invalidateQueries({ queryKey: ["whiteboards"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Whiteboards"
        description="A freeform canvas for planning and brainstorming — sticky notes, text, shapes and images."
        icon={<LayoutDashboard size={20} />}
        actions={
          !readOnly && (
            <button className="btn-primary" onClick={() => setCreating(true)}>
              <Plus size={15} /> New board
            </button>
          )
        }
      />

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load whiteboards" />
        </div>
      ) : list.isLoading ? (
        <SkeletonCards count={4} />
      ) : (list.data ?? []).length === 0 ? (
        <div className="glass-card">
          <EmptyState icon={LayoutDashboard} title="No boards yet" description="Start a blank canvas for your next planning session." />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {list.data!.map((b, i) => (
            <div key={b.id} className="glass-card-hover overflow-hidden relative group cursor-pointer" onClick={() => navigate(`/whiteboards/${b.id}`)}>
              <div className="h-24" style={{ backgroundImage: GRADIENTS[i % GRADIENTS.length] }} />
              <div className="p-4">
                <p className="text-sm font-semibold truncate" style={{ color: "var(--ink-primary)" }}>
                  {b.name}
                </p>
                <p className="text-xs mt-1" style={{ color: "var(--ink-muted)" }}>
                  {b.itemCount != null ? `${b.itemCount} element${b.itemCount === 1 ? "" : "s"} · ` : ""}Updated {formatRelative(b.updatedAt)}
                </p>
              </div>
              {canDelete(b) && (
              <button
                aria-label={`Delete ${b.name}`}
                className="btn-icon absolute top-2 right-2 sm:opacity-0 sm:group-hover:opacity-100 bg-black/20 text-white hover:bg-black/40"
                onClick={async (e) => {
                  e.stopPropagation();
                  const ok = await confirm("Delete this board?", `"${b.name}" and everything on it will be removed.`);
                  if (ok) remove.mutate(b.id);
                }}
              >
                <Trash2 size={14} />
              </button>
              )}
            </div>
          ))}
        </div>
      )}

      <Modal open={creating} onClose={() => setCreating(false)} title="New whiteboard" width="22rem">
        <div className="space-y-4">
          <Field label="Name" required>
            <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="Q3 strategy brainstorm" />
          </Field>
          <div className="flex justify-end gap-2">
            <button className="btn-ghost" onClick={() => setCreating(false)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!name.trim()} onClick={() => create.mutate()}>
              Create board
            </button>
          </div>
        </div>
      </Modal>
      {confirmNode}
    </div>
  );
}
