import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Calendar, Clock, MapPin, Plus, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { api } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { CalendarEvent } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { SkeletonRows } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";

const EVENT_TYPES = ["event", "meeting", "reminder", "task", "deadline"];
const TYPE_COLOR: Record<string, string> = {
  event: "#2a78d6",
  meeting: "#4a3aa7",
  reminder: "#eda100",
  task: "#1baf7a",
  deadline: "#e34948",
};

export default function CalendarPage() {
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<Partial<CalendarEvent>>({ eventType: "event", allDay: false });
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const list = useQuery({
    queryKey: ["calendar"],
    queryFn: async () => (await api.get<CalendarEvent[]>("/calendar")).data,
  });

  const create = useMutation({
    mutationFn: async () => api.post("/calendar", draft),
    onSuccess: () => {
      toast.success("Event created");
      qc.invalidateQueries({ queryKey: ["calendar"] });
      setCreating(false);
      setDraft({ eventType: "event", allDay: false });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/calendar/${id}`),
    onSuccess: () => {
      toast.success("Event deleted");
      qc.invalidateQueries({ queryKey: ["calendar"] });
    },
  });

  const grouped = useMemo(() => {
    const byDay = new Map<string, CalendarEvent[]>();
    const sorted = [...(list.data ?? [])].sort((a, b) => a.startAt.localeCompare(b.startAt));
    for (const ev of sorted) {
      const day = formatDate(ev.startAt, "EEEE, d MMMM yyyy");
      if (!byDay.has(day)) byDay.set(day, []);
      byDay.get(day)!.push(ev);
    }
    return Array.from(byDay.entries());
  }, [list.data]);

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Calendar"
        description={`${list.data?.length ?? 0} upcoming and past events`}
        icon={<Calendar size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New event
          </button>
        }
      />

      {list.isLoading ? (
        <div className="glass-card">
          <SkeletonRows rows={6} cols={1} />
        </div>
      ) : grouped.length === 0 ? (
        <div className="glass-card">
          <EmptyState icon={Calendar} title="No events yet" description="Schedule meetings, reminders and deadlines." />
        </div>
      ) : (
        <div className="space-y-6">
          {grouped.map(([day, events]) => (
            <div key={day}>
              <h3 className="text-xs font-bold uppercase tracking-wide mb-2" style={{ color: "var(--ink-muted)" }}>
                {day}
              </h3>
              <div className="glass-card divide-y" style={{ borderColor: "inherit" }}>
                {events.map((ev) => (
                  <div key={ev.id} className="flex items-center gap-3 p-4 hairline border-b last:border-b-0 group">
                    <span className="h-2.5 w-2.5 rounded-full shrink-0" style={{ backgroundColor: ev.colorHex || TYPE_COLOR[ev.eventType] }} />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                        {ev.title}
                      </p>
                      <div className="flex items-center gap-3 mt-0.5 text-xs" style={{ color: "var(--ink-muted)" }}>
                        <span className="inline-flex items-center gap-1">
                          <Clock size={11} /> {ev.allDay ? "All day" : formatDateTime(ev.startAt).split(", ")[1]}
                        </span>
                        {ev.location && (
                          <span className="inline-flex items-center gap-1">
                            <MapPin size={11} /> {ev.location}
                          </span>
                        )}
                      </div>
                    </div>
                    <span className="badge-neutral capitalize">{ev.eventType}</span>
                    <button
                      className="btn-icon h-7 w-7 opacity-0 group-hover:opacity-100"
                      style={{ color: "var(--critical)" }}
                      onClick={async () => {
                        const ok = await confirm("Delete this event?", `"${ev.title}" will be removed.`);
                        if (ok) remove.mutate(ev.id);
                      }}
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      <Modal open={creating} onClose={() => setCreating(false)} title="New calendar event" width="26rem">
        <div className="space-y-4">
          <Field label="Title" required>
            <input className="input" value={draft.title ?? ""} onChange={(e) => setDraft((d) => ({ ...d, title: e.target.value }))} />
          </Field>
          <FieldGrid>
            <Field label="Starts" required>
              <input
                className="input"
                type="datetime-local"
                onChange={(e) => setDraft((d) => ({ ...d, startAt: e.target.value ? new Date(e.target.value).toISOString() : undefined }))}
              />
            </Field>
            <Field label="Ends">
              <input
                className="input"
                type="datetime-local"
                onChange={(e) => setDraft((d) => ({ ...d, endAt: e.target.value ? new Date(e.target.value).toISOString() : undefined }))}
              />
            </Field>
            <Field label="Type">
              <select className="select" value={draft.eventType ?? "event"} onChange={(e) => setDraft((d) => ({ ...d, eventType: e.target.value }))}>
                {EVENT_TYPES.map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            </Field>
            <Field label="Location">
              <input className="input" value={draft.location ?? ""} onChange={(e) => setDraft((d) => ({ ...d, location: e.target.value }))} />
            </Field>
          </FieldGrid>
          <Field label="Description">
            <textarea className="textarea" value={draft.description ?? ""} onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))} />
          </Field>
          <div className="flex justify-end gap-2">
            <button className="btn-ghost" onClick={() => setCreating(false)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!draft.title || !draft.startAt} onClick={() => create.mutate()}>
              Create event
            </button>
          </div>
        </div>
      </Modal>
      {confirmNode}
    </div>
  );
}
