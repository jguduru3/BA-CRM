import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ListChecks, Plus, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api, getList } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { useUsersLookup } from "@/lib/hooks";
import { formatDate, formatDateTime, isOverdue } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Task } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { PriorityBadge, StageBadge } from "@/components/ui/Badge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { Kanban, KanbanColumnDef } from "@/components/ui/Kanban";
import { QueryError } from "@/components/ui/QueryError";
import { useReason } from "@/components/ui/ReasonDialog";
import { SearchInput } from "@/components/ui/SearchInput";
import { Tabs } from "@/components/ui/Tabs";
import { changedFields, ExportButton, FilterSelect, ListToolbar, saveErrorMessage } from "@/features/crm/ListParts";
import { useDebounced, useServerList } from "@/features/crm/useServerList";
import { TASK_PRIORITIES, TASK_STATUSES, TaskDraft, TaskForm } from "./TaskForm";
import { TaskKanbanCard } from "./TaskKanbanCard";

const STATUS_ACCENTS: Record<string, string> = { Todo: "#2a78d6", "In Progress": "#eda100", Done: "#0ca30c" };
const BOARD_SIZE = 200;

export default function TasksPage() {
  const [params, setParams] = useSearchParams();
  const [view, setView] = useState<"board" | "table">("board");
  const [status, setStatus] = useState("");
  const [priority, setPriority] = useState("");
  const [overdue, setOverdue] = useState(false);
  const [boardSearch, setBoardSearch] = useState("");
  const [selected, setSelected] = useState<Task | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<TaskDraft>({});
  const users = useUsersLookup();
  const qc = useQueryClient();
  const { askReason, node: reasonNode } = useReason();
  const role = useAuthStore((s) => s.user?.role);
  const readOnly = isReadOnly(role);
  const manager = isManagerUp(role);

  const table = useServerList<Task>("tasks", {
    filters: { status, priority, overdue },
    defaultSort: { key: "dueDate", dir: "asc" },
    enabled: view === "table",
  });
  const boardTerm = useDebounced(boardSearch.trim());
  const board = useQuery({
    queryKey: ["tasks", "board", boardTerm, priority, overdue],
    queryFn: () =>
      getList<Task>("/tasks", {
        search: boardTerm || undefined,
        priority: priority || undefined,
        overdue: overdue || undefined,
        size: BOARD_SIZE,
        sort: "updatedAt,desc",
      }),
    enabled: view === "board",
  });

  useEffect(() => {
    setDraft(selected ?? { status: "Todo", priority: "Medium" });
  }, [selected, creating]);

  useEffect(() => {
    const id = params.get("open");
    if (!id) return;
    api
      .get<Task>(`/tasks/${id}`)
      .then((r) => setSelected(r.data))
      .catch((e) => toast.error(e instanceof Error ? e.message : "Task not found"));
    params.delete("open");
    setParams(params, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name;
  const invalidate = () => qc.invalidateQueries({ queryKey: ["tasks"] });
  const closeDrawers = () => {
    setSelected(null);
    setCreating(false);
  };

  const statusColumns: KanbanColumnDef[] = TASK_STATUSES.map((s) => ({ key: s, label: s, accent: STATUS_ACCENTS[s] }));
  const statusItems: Record<string, Task[]> = useMemo(() => {
    const map: Record<string, Task[]> = {};
    for (const s of TASK_STATUSES) map[s] = [];
    for (const t of board.data?.data ?? []) (map[t.status] ??= []).push(t);
    return map;
  }, [board.data]);

  async function moveStatus(id: number, _from: string, to: string) {
    try {
      // completedAt follows the status on the server
      await api.patch(`/tasks/${id}`, { status: to });
      invalidate();
      toast.success(`Moved to ${to}`);
    } catch (e) {
      toast.error(saveErrorMessage(e, "task"));
    }
  }

  const save = useMutation({
    mutationFn: async () =>
      selected
        ? (await api.patch<Task>(`/tasks/${selected.id}`, changedFields(selected, draft))).data
        : (await api.post<Task>("/tasks", draft)).data,
    onSuccess: () => {
      toast.success(selected ? "Task updated" : "Task created");
      invalidate();
      closeDrawers();
    },
    onError: (e: Error) => toast.error(saveErrorMessage(e, "task")),
  });

  const remove = useMutation({
    mutationFn: async ({ id, reason }: { id: number; reason: string }) => api.delete(`/tasks/${id}`, { params: { reason } }),
    onSuccess: () => {
      toast.success("Task moved to trash");
      invalidate();
      closeDrawers();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const columns: Column<Task>[] = [
    {
      key: "title",
      header: "Task",
      sortKey: "title",
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
    { key: "status", header: "Status", sortKey: "status", render: (r) => <StageBadge value={r.status} /> },
    { key: "priority", header: "Priority", sortKey: "priority", hideOnMobile: true, render: (r) => <PriorityBadge value={r.priority} /> },
    {
      key: "dueDate",
      header: "Due",
      sortKey: "dueDate",
      render: (r) => (
        <span
          className="text-sm"
          style={{ color: r.status !== "Done" && isOverdue(r.dueDate ? `${r.dueDate}T23:59:59` : null) ? "var(--critical)" : "var(--ink-secondary)" }}
        >
          {formatDate(r.dueDate)}
        </span>
      ),
    },
    {
      key: "assignee",
      header: "Assignee",
      hideOnMobile: true,
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

  const activeQuery = view === "board" ? board : table.query;
  const filtered = !!(table.search || status || priority || overdue);
  const openCount = (board.data?.data ?? []).filter((t) => t.status !== "Done").length;

  return (
    <div className="flex flex-col gap-5 flex-1 min-h-0">
      <PageHeader
        title="Tasks"
        description={
          view === "table"
            ? table.query.data
              ? `${table.total.toLocaleString()} tasks${filtered ? " match" : ""}`
              : "Everything that needs doing"
            : board.data
            ? `${board.data.count.toLocaleString()} tasks · ${openCount} open on the board`
            : "Everything that needs doing"
        }
        icon={<ListChecks size={20} />}
        actions={
          <>
            <ExportButton type="tasks" />
            {!readOnly && (
              <button className="btn-primary" onClick={() => setCreating(true)}>
                <Plus size={15} /> <span className="hidden sm:inline">New task</span>
              </button>
            )}
          </>
        }
      />

      <ListToolbar
        left={
          <>
            <Tabs
              value={view}
              onChange={setView}
              tabs={[
                { value: "board", label: "Board" },
                { value: "table", label: "Table" },
              ]}
            />
            {view === "table" && <FilterSelect ariaLabel="Status" allLabel="Any status" value={status} onChange={setStatus} options={TASK_STATUSES} />}
            <FilterSelect ariaLabel="Priority" allLabel="Any priority" value={priority} onChange={setPriority} options={TASK_PRIORITIES} />
            <label className="inline-flex items-center gap-1.5 text-sm cursor-pointer" style={{ color: "var(--ink-secondary)" }}>
              <input type="checkbox" checked={overdue} onChange={(e) => setOverdue(e.target.checked)} />
              Overdue
            </label>
          </>
        }
        right={
          view === "table" ? (
            <SearchInput value={table.search} onChange={table.setSearch} placeholder="Search tasks…" className="w-full sm:w-64" />
          ) : (
            <SearchInput value={boardSearch} onChange={setBoardSearch} placeholder="Search tasks…" className="w-full sm:w-64" />
          )
        }
      />

      {activeQuery.isError ? (
        <div className="glass-card">
          <QueryError error={activeQuery.error} onRetry={() => activeQuery.refetch()} title="Couldn't load tasks" />
        </div>
      ) : view === "board" ? (
        <div className="flex-1 min-h-[480px]">
          <Kanban
            columns={statusColumns}
            itemsByColumn={statusItems}
            onMove={moveStatus}
            draggable={!readOnly}
            loading={board.isLoading}
            renderCard={(t) => (
              <div onClick={() => setSelected(t)}>
                <TaskKanbanCard task={t} assigneeName={userName(t.assigneeId)} />
              </div>
            )}
          />
        </div>
      ) : (
        <DataTable
          columns={columns}
          rows={table.rows}
          loading={table.query.isLoading}
          fetching={table.query.isFetching && !table.query.isLoading}
          onRowClick={setSelected}
          pagination={table.pagination}
          sort={table.sort}
          onSortChange={table.setSort}
          emptyTitle={filtered ? "No tasks match" : "No tasks yet"}
          emptyDescription={filtered ? "Try another search or clear the filters." : "Plan the next step on a lead or deal."}
          emptyAction={
            !filtered && !readOnly ? (
              <button className="btn-primary" onClick={() => setCreating(true)}>
                <Plus size={15} /> New task
              </button>
            ) : undefined
          }
        />
      )}

      <Drawer
        open={!!selected || creating}
        onClose={closeDrawers}
        title={selected ? selected.title : "New task"}
        subtitle={selected ? `Updated ${formatDateTime(selected.updatedAt)}` : "Plan the next step"}
        headerExtra={
          selected &&
          manager && (
            <button
              className="btn-icon"
              style={{ color: "var(--critical)" }}
              title="Move to trash"
              onClick={async () => {
                const reason = await askReason({
                  title: "Move this task to trash?",
                  description: `"${selected.title}" can be restored from Trash later.`,
                  confirmLabel: "Move to trash",
                  danger: true,
                });
                if (reason) remove.mutate({ id: selected.id, reason });
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
          {!readOnly && (
            <button className="btn-primary" disabled={!draft.title || save.isPending} onClick={() => save.mutate()}>
              {save.isPending ? "Saving…" : selected ? "Save changes" : "Create task"}
            </button>
          )}
        </div>
      </Drawer>
      {reasonNode}
    </div>
  );
}
