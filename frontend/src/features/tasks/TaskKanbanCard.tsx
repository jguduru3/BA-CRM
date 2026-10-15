import { Clock, Link2 } from "lucide-react";
import { formatDate, isOverdue } from "@/lib/format";
import type { Task } from "@/lib/types";
import { Avatar } from "@/components/ui/Avatar";
import { PriorityBadge } from "@/components/ui/Badge";

export function TaskKanbanCard({ task, assigneeName }: { task: Task; assigneeName?: string }) {
  const overdue = task.status !== "Done" && isOverdue(task.dueDate ? `${task.dueDate}T23:59:59` : null);
  return (
    <div>
      <p className="text-[13px] font-semibold leading-snug line-clamp-2" style={{ color: "var(--ink-primary)" }}>
        {task.title}
      </p>
      {task.relatedType && (
        <p className="flex items-center gap-1 text-xs mt-1 truncate" style={{ color: "var(--ink-muted)" }}>
          <Link2 size={11} /> {task.relatedType} #{task.relatedId}
        </p>
      )}
      <div className="flex items-center justify-between mt-2.5">
        <PriorityBadge value={task.priority} />
        {task.dueDate && (
          <span
            className="inline-flex items-center gap-1 text-[11px] font-medium"
            style={{ color: overdue ? "var(--critical)" : "var(--ink-muted)" }}
          >
            <Clock size={11} /> {formatDate(task.dueDate)}
          </span>
        )}
      </div>
      <div className="flex justify-end mt-2.5">
        <Avatar name={assigneeName ?? null} size={22} />
      </div>
    </div>
  );
}
