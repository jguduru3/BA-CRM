import { useUsersLookup } from "@/lib/hooks";
import type { Task } from "@/lib/types";
import { Field, FieldGrid } from "@/components/ui/Field";

export const TASK_STATUSES = ["Todo", "In Progress", "Done"];
export const TASK_PRIORITIES = ["Low", "Medium", "High"];
export const RELATED_TYPES = ["Lead", "Opportunity", "Contact", "Company"];

export type TaskDraft = Partial<Task>;

export function TaskForm({ value, onChange }: { value: TaskDraft; onChange: (patch: TaskDraft) => void }) {
  const users = useUsersLookup();
  const set = <K extends keyof Task>(k: K, v: Task[K]) => onChange({ [k]: v } as TaskDraft);

  return (
    <div className="space-y-4">
      <Field label="Title" required>
        <input className="input" value={value.title ?? ""} onChange={(e) => set("title", e.target.value)} placeholder="Follow up with…" />
      </Field>
      <FieldGrid>
        <Field label="Status">
          <select className="select" value={value.status ?? "Todo"} onChange={(e) => set("status", e.target.value)}>
            {TASK_STATUSES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Priority">
          <select className="select" value={value.priority ?? "Medium"} onChange={(e) => set("priority", e.target.value)}>
            {TASK_PRIORITIES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Assignee">
          <select
            className="select"
            value={value.assigneeId ?? ""}
            onChange={(e) => set("assigneeId", e.target.value ? Number(e.target.value) : (null as any))}
          >
            <option value="">— Unassigned —</option>
            {users.data?.map((u) => (
              <option key={u.id} value={u.id}>
                {u.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Due date">
          <input className="input" type="date" value={value.dueDate ?? ""} onChange={(e) => set("dueDate", e.target.value)} />
        </Field>
        <Field label="Related record type">
          <select className="select" value={value.relatedType ?? ""} onChange={(e) => set("relatedType", e.target.value)}>
            <option value="">—</option>
            {RELATED_TYPES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Related record ID">
          <input
            className="input"
            type="number"
            value={value.relatedId ?? ""}
            onChange={(e) => set("relatedId", e.target.value ? Number(e.target.value) : (null as any))}
          />
        </Field>
      </FieldGrid>
    </div>
  );
}
