import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckSquare, Eye, EyeOff, Lock, Pin, Plus, Trash2, Users } from "lucide-react";
import { useMemo, useState } from "react";
import { api } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { formatDate, isOverdue } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { TodoItem, TodoList } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { PriorityBadge } from "@/components/ui/Badge";
import { EmptyState } from "@/components/ui/EmptyState";
import { Modal } from "@/components/ui/Modal";
import { Field } from "@/components/ui/Field";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";

const COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#4a3aa7"];
const PRIORITIES = ["low", "normal", "high", "urgent"];

export default function TodosPage() {
  const [activeListId, setActiveListId] = useState<number | null>(null);
  const [newListOpen, setNewListOpen] = useState(false);
  const [newListName, setNewListName] = useState("");
  const [newListShared, setNewListShared] = useState(false);
  const [newItemTitle, setNewItemTitle] = useState("");
  const [newItemDue, setNewItemDue] = useState("");
  const [newItemPriority, setNewItemPriority] = useState("normal");
  const [showDone, setShowDone] = useState(true);
  const qc = useQueryClient();
  const me = useAuthStore((s) => s.user);
  const readOnly = isReadOnly(me?.role);
  const { confirm, node: confirmNode } = useConfirm();

  const lists = useQuery({
    queryKey: ["todo-lists"],
    queryFn: async () => (await api.get<TodoList[]>("/todos/lists")).data,
  });
  const currentListId = activeListId ?? lists.data?.[0]?.id ?? null;
  const activeList = lists.data?.find((l) => l.id === currentListId);
  const isOwner = activeList?.ownerId === me?.id;
  const canManageList = !readOnly && (isOwner || (activeList?.shared && isManagerUp(me?.role)));
  const canWorkItems = !readOnly && (isOwner || !!activeList?.shared);

  const items = useQuery({
    queryKey: ["todo-items", currentListId],
    queryFn: async () => (await api.get<TodoItem[]>(`/todos/lists/${currentListId}/items`)).data,
    enabled: !!currentListId,
  });
  const refreshItems = () => qc.invalidateQueries({ queryKey: ["todo-items", currentListId] });
  const fail = (e: Error) => toast.error(e.message);

  const createList = useMutation({
    mutationFn: async () =>
      (
        await api.post<TodoList>("/todos/lists", {
          name: newListName,
          shared: newListShared,
          color: COLORS[(lists.data?.length ?? 0) % COLORS.length],
        })
      ).data,
    onSuccess: (list) => {
      toast.success("List created");
      qc.invalidateQueries({ queryKey: ["todo-lists"] });
      setActiveListId(list.id);
      setNewListOpen(false);
      setNewListName("");
      setNewListShared(false);
    },
    onError: fail,
  });

  const shareList = useMutation({
    mutationFn: async (l: TodoList) => api.put(`/todos/lists/${l.id}`, { shared: !l.shared }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["todo-lists"] }),
    onError: fail,
  });

  const deleteList = useMutation({
    mutationFn: async (id: number) => api.delete(`/todos/lists/${id}`),
    onSuccess: () => {
      toast.success("List deleted");
      setActiveListId(null);
      qc.invalidateQueries({ queryKey: ["todo-lists"] });
    },
    onError: fail,
  });

  const createItem = useMutation({
    mutationFn: async () =>
      api.post(`/todos/lists/${currentListId}/items`, {
        title: newItemTitle,
        priority: newItemPriority,
        dueAt: newItemDue ? new Date(`${newItemDue}T17:00:00`).toISOString() : null,
        position: items.data?.length ?? 0,
      }),
    onSuccess: () => {
      refreshItems();
      setNewItemTitle("");
      setNewItemDue("");
      setNewItemPriority("normal");
    },
    onError: fail,
  });

  const toggleItem = useMutation({
    mutationFn: async (id: number) => api.post(`/todos/items/${id}/toggle`),
    onSuccess: refreshItems,
    onError: fail,
  });

  const pinItem = useMutation({
    mutationFn: async (item: TodoItem) => api.put(`/todos/items/${item.id}`, { pinned: !item.pinned }),
    onSuccess: refreshItems,
    onError: fail,
  });

  const deleteItem = useMutation({
    mutationFn: async (id: number) => api.delete(`/todos/items/${id}`),
    onSuccess: refreshItems,
    onError: fail,
  });

  const sortedItems = useMemo(
    () =>
      [...(items.data ?? [])]
        .filter((i) => showDone || !i.done)
        .sort((a, b) => {
          if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
          if (a.done !== b.done) return a.done ? 1 : -1;
          return a.position - b.position;
        }),
    [items.data, showDone]
  );
  const doneCount = (items.data ?? []).filter((i) => i.done).length;
  const total = items.data?.length ?? 0;
  const pct = total ? Math.round((doneCount / total) * 100) : 0;

  return (
    <div className="flex flex-col gap-4 flex-1 min-h-0">
      <PageHeader
        title="Todo Lists"
        description="Personal and shared checklists."
        icon={<CheckSquare size={20} />}
        actions={
          !readOnly && (
            <button className="btn-primary" onClick={() => setNewListOpen(true)}>
              <Plus size={15} /> New list
            </button>
          )
        }
      />

      {lists.isError ? (
        <div className="glass-card">
          <QueryError error={lists.error} onRetry={() => lists.refetch()} title="Couldn't load your lists" />
        </div>
      ) : lists.isLoading ? (
        <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
          <Skeleton className="h-48" />
          <Skeleton className="h-64 lg:col-span-3" />
        </div>
      ) : !currentListId ? (
        <div className="glass-card">
          <EmptyState
            icon={CheckSquare}
            title="No lists yet"
            description="Create a list to start tracking checklist items — keep it private or share it with the team."
            action={
              !readOnly && (
                <button className="btn-primary" onClick={() => setNewListOpen(true)}>
                  <Plus size={15} /> New list
                </button>
              )
            }
          />
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 flex-1 min-h-0">
          <nav className="glass-card p-2 flex lg:flex-col gap-1 overflow-x-auto lg:overflow-y-auto thin-scroll lg:max-h-[75vh]" aria-label="Lists">
            {lists.data?.map((l) => {
              const active = l.id === currentListId;
              return (
                <button
                  key={l.id}
                  onClick={() => setActiveListId(l.id)}
                  className="shrink-0 lg:w-full text-left px-3 py-2 rounded-xl flex items-center gap-2.5 transition-colors"
                  style={{ backgroundColor: active ? "rgb(var(--ring-brand) / 0.1)" : undefined }}
                >
                  <span className="h-2.5 w-2.5 rounded-full shrink-0" style={{ backgroundColor: l.color || "#999" }} />
                  <span className="text-sm font-medium truncate flex-1" style={{ color: active ? "var(--brand-600)" : "var(--ink-primary)" }}>
                    {l.name}
                  </span>
                  {l.shared ? (
                    <Users size={12} style={{ color: "var(--ink-muted)" }} aria-label="Shared" />
                  ) : (
                    <Lock size={12} style={{ color: "var(--ink-muted)" }} aria-label="Private" />
                  )}
                </button>
              );
            })}
          </nav>

          <section className="lg:col-span-3 glass-card p-4 flex flex-col min-h-0">
            <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
              <div className="min-w-0">
                <h2 className="text-base font-semibold truncate" style={{ color: "var(--ink-primary)" }}>
                  {activeList?.name}
                </h2>
                <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                  {activeList?.shared ? "Shared with your organization" : "Private to you"} · {doneCount}/{total} done
                </p>
              </div>
              <div className="flex items-center gap-1">
                <button className="btn-ghost px-2 py-1 text-xs" onClick={() => setShowDone((v) => !v)}>
                  {showDone ? <EyeOff size={13} /> : <Eye size={13} />} {showDone ? "Hide done" : "Show done"}
                </button>
                {isOwner && !readOnly && activeList && (
                  <button className="btn-ghost px-2 py-1 text-xs" onClick={() => shareList.mutate(activeList)}>
                    {activeList.shared ? <Lock size={13} /> : <Users size={13} />} {activeList.shared ? "Make private" : "Share"}
                  </button>
                )}
                {canManageList && (
                  <button
                    className="btn-icon h-8 w-8"
                    style={{ color: "var(--critical)" }}
                    aria-label="Delete list"
                    onClick={async () => {
                      const ok = await confirm("Delete this list?", `"${activeList?.name}" and all its items will be removed.`);
                      if (ok) deleteList.mutate(currentListId);
                    }}
                  >
                    <Trash2 size={14} />
                  </button>
                )}
              </div>
            </div>

            <div className="h-1.5 rounded-full overflow-hidden mb-5" style={{ backgroundColor: "rgb(var(--panel-border) / 0.08)" }}>
              <div className="h-full rounded-full transition-all duration-500" style={{ width: `${pct}%`, backgroundImage: "var(--brand-gradient)" }} />
            </div>

            {canWorkItems && (
              <form
                className="flex flex-col sm:flex-row gap-2 mb-4"
                onSubmit={(e) => {
                  e.preventDefault();
                  if (newItemTitle.trim()) createItem.mutate();
                }}
              >
                <input className="input flex-1" placeholder="Add an item…" value={newItemTitle} onChange={(e) => setNewItemTitle(e.target.value)} />
                <input className="input sm:w-40" type="date" value={newItemDue} onChange={(e) => setNewItemDue(e.target.value)} aria-label="Due date" />
                <select className="select sm:w-28" value={newItemPriority} onChange={(e) => setNewItemPriority(e.target.value)} aria-label="Priority">
                  {PRIORITIES.map((p) => (
                    <option key={p}>{p}</option>
                  ))}
                </select>
                <button type="submit" className="btn-primary" disabled={!newItemTitle.trim() || createItem.isPending}>
                  <Plus size={15} /> Add
                </button>
              </form>
            )}

            <div className="flex-1 overflow-y-auto thin-scroll space-y-0.5">
              {items.isError ? (
                <QueryError error={items.error} onRetry={() => items.refetch()} title="Couldn't load items" compact />
              ) : items.isLoading ? (
                <div className="space-y-2">
                  <Skeleton className="h-9" />
                  <Skeleton className="h-9" />
                  <Skeleton className="h-9" />
                </div>
              ) : sortedItems.length === 0 ? (
                <p className="text-sm text-center py-10" style={{ color: "var(--ink-muted)" }}>
                  {total > 0 ? "Everything here is done." : "Nothing here yet."}
                </p>
              ) : (
                sortedItems.map((item) => (
                  <div key={item.id} className="flex items-center gap-2.5 py-2 px-2 rounded-lg hover:bg-[rgb(var(--panel-border)/0.04)] group">
                    <input
                      type="checkbox"
                      className="checkbox"
                      checked={item.done}
                      disabled={!canWorkItems}
                      aria-label={`Mark ${item.title} done`}
                      onChange={() => toggleItem.mutate(item.id)}
                    />
                    <span
                      className="flex-1 text-sm truncate"
                      style={{
                        color: item.done ? "var(--ink-muted)" : "var(--ink-primary)",
                        textDecoration: item.done ? "line-through" : undefined,
                      }}
                    >
                      {item.title}
                    </span>
                    {item.pinned && <Pin size={12} style={{ color: "var(--brand-500)" }} />}
                    {item.priority !== "normal" && <PriorityBadge value={item.priority} />}
                    {item.dueAt && (
                      <span className="text-[11px] whitespace-nowrap" style={{ color: isOverdue(item.dueAt) && !item.done ? "var(--critical)" : "var(--ink-muted)" }}>
                        {formatDate(item.dueAt)}
                      </span>
                    )}
                    {canWorkItems && (
                      <>
                        <button
                          className="btn-icon h-7 w-7 sm:opacity-0 sm:group-hover:opacity-100"
                          aria-label={item.pinned ? "Unpin" : "Pin"}
                          onClick={() => pinItem.mutate(item)}
                          style={{ color: item.pinned ? "var(--brand-500)" : undefined }}
                        >
                          <Pin size={12} />
                        </button>
                        <button
                          className="btn-icon h-7 w-7 sm:opacity-0 sm:group-hover:opacity-100"
                          style={{ color: "var(--critical)" }}
                          aria-label={`Delete ${item.title}`}
                          onClick={() => deleteItem.mutate(item.id)}
                        >
                          <Trash2 size={12} />
                        </button>
                      </>
                    )}
                  </div>
                ))
              )}
            </div>
          </section>
        </div>
      )}

      <Modal open={newListOpen} onClose={() => setNewListOpen(false)} title="New todo list" width="24rem">
        <div className="space-y-4">
          <Field label="Name" required>
            <input className="input" value={newListName} onChange={(e) => setNewListName(e.target.value)} autoFocus />
          </Field>
          <label className="flex items-center gap-2 text-sm" style={{ color: "var(--ink-secondary)" }}>
            <input type="checkbox" className="checkbox" checked={newListShared} onChange={(e) => setNewListShared(e.target.checked)} />
            Share with my organization
          </label>
          <div className="flex justify-end gap-2">
            <button className="btn-ghost" onClick={() => setNewListOpen(false)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!newListName.trim() || createList.isPending} onClick={() => createList.mutate()}>
              Create
            </button>
          </div>
        </div>
      </Modal>
      {confirmNode}
    </div>
  );
}
