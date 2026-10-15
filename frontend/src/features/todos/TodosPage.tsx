import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckSquare, Pin, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { api } from "@/lib/api";
import { formatDate, isOverdue } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { TodoItem, TodoList } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { PriorityBadge } from "@/components/ui/Badge";
import { EmptyState } from "@/components/ui/EmptyState";
import { Modal } from "@/components/ui/Modal";
import { Field } from "@/components/ui/Field";
import { useConfirm } from "@/components/ui/ConfirmDialog";

const COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#4a3aa7"];

export default function TodosPage() {
  const [activeListId, setActiveListId] = useState<number | null>(null);
  const [newListOpen, setNewListOpen] = useState(false);
  const [newListName, setNewListName] = useState("");
  const [newItemTitle, setNewItemTitle] = useState("");
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const lists = useQuery({
    queryKey: ["todo-lists"],
    queryFn: async () => (await api.get<TodoList[]>("/todos/lists")).data,
  });
  const currentListId = activeListId ?? lists.data?.[0]?.id ?? null;

  const items = useQuery({
    queryKey: ["todo-items", currentListId],
    queryFn: async () => (await api.get<TodoItem[]>(`/todos/lists/${currentListId}/items`)).data,
    enabled: !!currentListId,
  });

  const createList = useMutation({
    mutationFn: async () =>
      (await api.post<TodoList>("/todos/lists", { name: newListName, color: COLORS[(lists.data?.length ?? 0) % COLORS.length] })).data,
    onSuccess: (list) => {
      toast.success("List created");
      qc.invalidateQueries({ queryKey: ["todo-lists"] });
      setActiveListId(list.id);
      setNewListOpen(false);
      setNewListName("");
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const deleteList = useMutation({
    mutationFn: async (id: number) => api.delete(`/todos/lists/${id}`),
    onSuccess: () => {
      toast.success("List deleted");
      setActiveListId(null);
      qc.invalidateQueries({ queryKey: ["todo-lists"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const createItem = useMutation({
    mutationFn: async () => api.post(`/todos/lists/${currentListId}/items`, { title: newItemTitle, priority: "normal" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["todo-items", currentListId] });
      setNewItemTitle("");
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const toggleItem = useMutation({
    mutationFn: async (id: number) => api.post(`/todos/items/${id}/toggle`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["todo-items", currentListId] }),
  });

  const pinItem = useMutation({
    mutationFn: async (item: TodoItem) => api.put(`/todos/items/${item.id}`, { pinned: !item.pinned }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["todo-items", currentListId] }),
  });

  const deleteItem = useMutation({
    mutationFn: async (id: number) => api.delete(`/todos/items/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["todo-items", currentListId] }),
  });

  const sortedItems = [...(items.data ?? [])].sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
    if (a.done !== b.done) return a.done ? 1 : -1;
    return a.position - b.position;
  });
  const activeList = lists.data?.find((l) => l.id === currentListId);

  return (
    <div className="flex flex-col gap-5 flex-1 min-h-0">
      <PageHeader
        title="Todo Lists"
        description="Personal and shared checklists, Notion-style."
        icon={<CheckSquare size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setNewListOpen(true)}>
            <Plus size={15} /> New list
          </button>
        }
      />

      <div className="flex gap-2 overflow-x-auto thin-scroll pb-1">
        {lists.data?.map((l) => (
          <button
            key={l.id}
            onClick={() => setActiveListId(l.id)}
            className="shrink-0 px-3.5 py-1.5 rounded-full text-sm font-medium transition-all flex items-center gap-2"
            style={{
              backgroundImage: l.id === currentListId ? "var(--brand-gradient)" : undefined,
              backgroundColor: l.id === currentListId ? undefined : "rgb(var(--panel-border) / 0.06)",
              color: l.id === currentListId ? "white" : "var(--ink-secondary)",
            }}
          >
            <span className="h-2 w-2 rounded-full" style={{ backgroundColor: l.color || "#999" }} />
            {l.name}
          </button>
        ))}
      </div>

      {!currentListId ? (
        <div className="glass-card">
          <EmptyState icon={CheckSquare} title="No lists yet" description="Create a list to start tracking checklist items." />
        </div>
      ) : (
        <div className="glass-card p-5 flex-1 flex flex-col min-h-0 max-w-2xl">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
              {activeList?.name}
            </h3>
            <button
              className="btn-icon h-7 w-7"
              style={{ color: "var(--critical)" }}
              onClick={async () => {
                const ok = await confirm("Delete this list?", `"${activeList?.name}" and all its items will be removed.`);
                if (ok) deleteList.mutate(currentListId);
              }}
            >
              <Trash2 size={14} />
            </button>
          </div>

          <form
            className="flex gap-2 mb-4"
            onSubmit={(e) => {
              e.preventDefault();
              if (newItemTitle.trim()) createItem.mutate();
            }}
          >
            <input className="input flex-1" placeholder="Add an item…" value={newItemTitle} onChange={(e) => setNewItemTitle(e.target.value)} />
            <button type="submit" className="btn-primary px-3" disabled={!newItemTitle.trim()}>
              <Plus size={15} />
            </button>
          </form>

          <div className="flex-1 overflow-y-auto thin-scroll space-y-1">
            {sortedItems.length === 0 ? (
              <p className="text-sm text-center py-10" style={{ color: "var(--ink-muted)" }}>
                Nothing here yet.
              </p>
            ) : (
              sortedItems.map((item) => (
                <div key={item.id} className="flex items-center gap-2.5 py-2 px-2 rounded-lg hover:bg-[rgb(var(--panel-border)/0.04)] group">
                  <input type="checkbox" className="checkbox" checked={item.done} onChange={() => toggleItem.mutate(item.id)} />
                  <span
                    className="flex-1 text-sm truncate"
                    style={{
                      color: item.done ? "var(--ink-muted)" : "var(--ink-primary)",
                      textDecoration: item.done ? "line-through" : undefined,
                    }}
                  >
                    {item.title}
                  </span>
                  {item.priority !== "normal" && <PriorityBadge value={item.priority} />}
                  {item.dueAt && (
                    <span className="text-[11px]" style={{ color: isOverdue(item.dueAt) && !item.done ? "var(--critical)" : "var(--ink-muted)" }}>
                      {formatDate(item.dueAt)}
                    </span>
                  )}
                  <button
                    className="btn-icon h-6 w-6 opacity-0 group-hover:opacity-100"
                    onClick={() => pinItem.mutate(item)}
                    style={{ color: item.pinned ? "var(--brand-500)" : undefined }}
                  >
                    <Pin size={12} />
                  </button>
                  <button className="btn-icon h-6 w-6 opacity-0 group-hover:opacity-100" style={{ color: "var(--critical)" }} onClick={() => deleteItem.mutate(item.id)}>
                    <Trash2 size={12} />
                  </button>
                </div>
              ))
            )}
          </div>
        </div>
      )}

      <Modal open={newListOpen} onClose={() => setNewListOpen(false)} title="New todo list" width="22rem">
        <div className="space-y-4">
          <Field label="Name" required>
            <input className="input" value={newListName} onChange={(e) => setNewListName(e.target.value)} />
          </Field>
          <div className="flex justify-end gap-2">
            <button className="btn-ghost" onClick={() => setNewListOpen(false)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!newListName.trim()} onClick={() => createList.mutate()}>
              Create
            </button>
          </div>
        </div>
      </Modal>
      {confirmNode}
    </div>
  );
}
