import { useQueryClient } from "@tanstack/react-query";
import { ListChecks, Plus, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { useEntityCrud, useUsersLookup } from "@/lib/hooks";
import { formatDate, isOverdue } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Task } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { PriorityBadge, StageBadge } from "@/components/ui/Badge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { Kanban, KanbanColumnDef } from "@/components/ui/Kanban";
import { Tabs } from "@/components/ui/Tabs";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { TASK_STATUSES, TaskDraft, TaskForm } from "./TaskForm";
import { TaskKanbanCard } from "./TaskKanbanCard";

const STATUS_ACCENTS: Record<string, string> = { Todo: "#2a78d6", "In Progress": "#eda100", Done: "#0ca30c" };

export default function TasksPage() {
  const [view, setView] = useState<"board" | "table">("board");
  const [selected, setSelected] = useState<Task | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<TaskDraft>({});
  const users = useUsersLookup();
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const { list, create, update, remove } = useEntityCrud<Task>("tasks", { params: { limit: 500 }, label: "Task" });

  useEffect(() => {
    setDraft(selected ?? { status: "Todo", priority: "Medium" });
  }, [selected, creating]);

  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name;

  const statusColumns: KanbanColumnDef[] = TASK_STATUSES.map((s) => ({ key: s, label: s, accent: STATUS_ACCENTS[s] }));
  const statusItems: Record<string, Task[]> = useMemo(() => {
    const map: Record<string, Task[]> = {};
    for (const s of TASK_STATUSES) map[s] = [];
    for (const t of list.data?.data ?? []) (map[t.status] ??= []).push(t);
    return map;
  }, [list.data]);

  async function moveStatus(id: number, _from: string, to: string) {
    try {
      await api.put(`/tasks/${id}`, { status: to, completedAt: to === "Done" ? new Date().toISOString() : null });
      qc.invalidateQueries({ queryKey: ["tasks"] });
      toast.success(`Moved to ${to}`);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not move task");
    }
  }

  const columns: Column<Task>[] = [
    {
      key: "title",
      header: "Task",
      sortValue: (r) => r.title,
      render: (r) => (
        <div className="min-w-0">
          <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
            {r.title}
          </p>
          {r.relatedType && (
            <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
              {r.relatedType} #{r.relatedId}
            </p>
          )}
        </div>
      ),
    },
    { key: "status", header: "Status", sortValue: (r) => r.status, render: (r) => <StageBadge value={r.status} /> },
    { key: "priority", header: "Priority", sortValue: (r) => r.priority, render: (r) => <PriorityBadge value={r.priority} /> },
    {
      key: "dueDate",
      header: "Due",
      sortValue: (r) => r.dueDate ?? "",
      render: (r) => (
        <span className="text-sm" style={{ color: r.status !== "Done" && isOverdue(r.dueDate) ? "var(--critical)" : "var(--ink-secondary)" }}>
          {formatDate(r.dueDate)}
        </span>
      ),
    },
    {
      key: "assignee",
      header: "Assignee",
      render: (r) =>
        r.assigneeId ? (
          <div className="flex items-center gap-2">
            <Avatar name={userName(r.assigneeId)} size={22} />
            <span className="text-sm truncate">{userName(r.assigneeId)}</span>
          </div>
        ) : (
          <span className="badge-neutral">Unassigned</span>
        ),
    },
  ];

  const closeDrawers = () => {
    setSelected(null);
    setCreating(false);
  };

  const save = () => {
    if (selected) update.mutate({ id: selected.id, payload: draft }, { onSuccess: closeDrawers });
    else create.mutate(draft, { onSuccess: closeDrawers });
  };

  return (
    <div className="flex flex-col gap-5 flex-1 min-h-0">
      <PageHeader
        title="Tasks"
        description={`${list.data?.count ?? 0} tasks`}
        icon={<ListChecks size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New task
          </button>
        }
      />

      <Tabs
        value={view}
        onChange={setView}
        tabs={[
          { value: "board", label: "Board" },
          { value: "table", label: "Table" },
        ]}
      />

      {view === "board" ? (
        <div className="flex-1 min-h-[480px]">
          <Kanban
            columns={statusColumns}
            itemsByColumn={statusItems}
            onMove={moveStatus}
            loading={list.isLoading}
            renderCard={(t) => (
              <div onClick={() => setSelected(t)}>
                <TaskKanbanCard task={t} assigneeName={userName(t.assigneeId)} />
              </div>
            )}
          />
        </div>
      ) : (
        <DataTable columns={columns} rows={list.data?.data ?? []} loading={list.isLoading} onRowClick={setSelected} emptyTitle="No tasks yet" />
      )}

      <Drawer
        open={!!selected || creating}
        onClose={closeDrawers}
        title={selected ? selected.title : "New task"}
        headerExtra={
          selected && (
            <button
              className="btn-icon"
              style={{ color: "var(--critical)" }}
              title="Move to trash"
              onClick={async () => {
                const ok = await confirm("Delete this task?", `"${selected.title}" will be moved to trash.`);
                if (ok) remove.mutate({ id: selected.id, reason: "Deleted from task panel" }, { onSuccess: closeDrawers });
              }}
            >
              <Trash2 size={16} />
            </button>
          )
        }
      >
        <TaskForm value={draft} onChange={(p) => setDraft((d) => ({ ...d, ...p }))} />
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            Cancel
          </button>
          <button className="btn-primary" disabled={!draft.title} onClick={save}>
            {selected ? "Save changes" : "Create task"}
          </button>
        </div>
      </Drawer>
      {confirmNode}
    </div>
  );
}
