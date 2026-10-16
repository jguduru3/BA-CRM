import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  addDays,
  addMonths,
  endOfMonth,
  endOfWeek,
  format,
  isSameDay,
  isSameMonth,
  isToday,
  startOfDay,
  startOfMonth,
  startOfWeek,
} from "date-fns";
import { Calendar, ChevronLeft, ChevronRight, Clock, MapPin, Plus, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { api } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { toast } from "@/lib/toast";
import type { CalendarEvent } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { Tabs } from "@/components/ui/Tabs";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { fromLocalInput, toLocalInput } from "@/features/generic/datetime";

const EVENT_TYPES = ["event", "meeting", "reminder", "task", "deadline"];
const TYPE_COLOR: Record<string, string> = {
  event: "#2a78d6",
  meeting: "#4a3aa7",
  reminder: "#c98500",
  task: "#1baf7a",
  deadline: "#d03b3b",
};
const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

type View = "month" | "agenda";

const colorOf = (e: CalendarEvent) => e.colorHex || TYPE_COLOR[e.eventType] || "#2a78d6";

/** True when the event touches the given day (multi-day events show on every day they span). */
function onDay(e: CalendarEvent, day: Date): boolean {
  const start = startOfDay(new Date(e.startAt));
  const end = startOfDay(new Date(e.endAt ?? e.startAt));
  const d = startOfDay(day);
  return d >= start && d <= end;
}

export default function CalendarPage() {
  const [cursor, setCursor] = useState(() => startOfMonth(new Date()));
  const [view, setView] = useState<View>("month");
  const [editing, setEditing] = useState<CalendarEvent | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<Partial<CalendarEvent>>({ eventType: "event", allDay: false });
  const qc = useQueryClient();
  const me = useAuthStore((s) => s.user);
  const readOnly = isReadOnly(me?.role);
  const { confirm, node: confirmNode } = useConfirm();

  const gridStart = startOfWeek(startOfMonth(cursor), { weekStartsOn: 1 });
  const gridEnd = endOfWeek(endOfMonth(cursor), { weekStartsOn: 1 });

  const list = useQuery({
    queryKey: ["calendar", gridStart.toISOString(), gridEnd.toISOString()],
    queryFn: async () =>
      (
        await api.get<CalendarEvent[]>("/calendar", {
          params: { from: gridStart.toISOString(), to: addDays(gridEnd, 1).toISOString() },
        })
      ).data,
  });

  const canEdit = (e: CalendarEvent | null) => !readOnly && (!e || isManagerUp(me?.role) || e.ownerId === me?.id);
  const invalidate = () => qc.invalidateQueries({ queryKey: ["calendar"] });

  const openNew = (day?: Date) => {
    if (readOnly) return;
    const start = day ? new Date(day) : new Date();
    start.setHours(10, 0, 0, 0);
    setEditing(null);
    setDraft({ eventType: "event", allDay: false, startAt: start.toISOString() });
    setCreating(true);
  };
  const openEvent = (e: CalendarEvent) => {
    setCreating(false);
    setEditing(e);
    setDraft({ ...e });
  };
  const close = () => {
    setCreating(false);
    setEditing(null);
  };

  const save = useMutation({
    mutationFn: async () => {
      const payload = {
        title: draft.title,
        description: draft.description ?? null,
        startAt: draft.startAt,
        endAt: draft.endAt ?? null,
        allDay: !!draft.allDay,
        eventType: draft.eventType ?? "event",
        location: draft.location ?? null,
      };
      if (editing) return api.put(`/calendar/${editing.id}`, payload);
      return api.post("/calendar", payload);
    },
    onSuccess: () => {
      toast.success(editing ? "Event updated" : "Event created");
      close();
      invalidate();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/calendar/${id}`),
    onSuccess: () => {
      toast.success("Event deleted");
      close();
      invalidate();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const days = useMemo(() => {
    const out: Date[] = [];
    for (let d = gridStart; d <= gridEnd; d = addDays(d, 1)) out.push(d);
    return out;
  }, [gridStart.getTime(), gridEnd.getTime()]); // eslint-disable-line react-hooks/exhaustive-deps

  const events = useMemo(() => [...(list.data ?? [])].sort((a, b) => a.startAt.localeCompare(b.startAt)), [list.data]);
  const agenda = useMemo(() => {
    const byDay = new Map<string, { day: Date; items: CalendarEvent[] }>();
    for (const ev of events) {
      const d = new Date(ev.startAt);
      if (!isSameMonth(d, cursor)) continue;
      const k = format(d, "yyyy-MM-dd");
      if (!byDay.has(k)) byDay.set(k, { day: d, items: [] });
      byDay.get(k)!.items.push(ev);
    }
    return Array.from(byDay.values());
  }, [events, cursor]);

  const editable = creating || canEdit(editing);

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Calendar"
        description="Your events, deadlines and meetings."
        icon={<Calendar size={20} />}
        actions={
          !readOnly && (
            <button className="btn-primary" onClick={() => openNew()}>
              <Plus size={15} /> New event
            </button>
          )
        }
      />

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <button className="btn-icon" aria-label="Previous month" onClick={() => setCursor((c) => addMonths(c, -1))}>
            <ChevronLeft size={16} />
          </button>
          <h2 className="text-lg font-semibold min-w-[10rem] text-center" style={{ color: "var(--ink-primary)" }}>
            {format(cursor, "MMMM yyyy")}
          </h2>
          <button className="btn-icon" aria-label="Next month" onClick={() => setCursor((c) => addMonths(c, 1))}>
            <ChevronRight size={16} />
          </button>
          <button className="btn-ghost px-2.5 py-1 text-xs" onClick={() => setCursor(startOfMonth(new Date()))}>
            Today
          </button>
        </div>
        <Tabs<View>
          value={view}
          onChange={setView}
          tabs={[
            { value: "month", label: "Month" },
            { value: "agenda", label: "Agenda" },
          ]}
        />
      </div>

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load the calendar" />
        </div>
      ) : list.isLoading ? (
        <Skeleton className="h-[28rem] rounded-2xl" />
      ) : view === "month" ? (
        <div className="glass-card p-2 sm:p-3 overflow-x-auto thin-scroll">
          <div className="grid grid-cols-7 min-w-[42rem]">
            {WEEKDAYS.map((w) => (
              <div key={w} className="text-[11px] font-semibold uppercase tracking-wide text-center py-2" style={{ color: "var(--ink-muted)" }}>
                {w}
              </div>
            ))}
            {days.map((d) => {
              const dayEvents = events.filter((e) => onDay(e, d));
              const inMonth = isSameMonth(d, cursor);
              return (
                <div
                  key={d.toISOString()}
                  className="min-h-[6.5rem] p-1.5 border-t group"
                  style={{
                    borderColor: "rgb(var(--panel-border) / 0.07)",
                    opacity: inMonth ? 1 : 0.45,
                    cursor: readOnly ? "default" : "pointer",
                  }}
                  onClick={() => openNew(d)}
                >
                  <div className="flex items-center justify-between mb-1">
                    <span
                      className="inline-flex items-center justify-center h-6 w-6 rounded-full text-xs font-semibold"
                      style={
                        isToday(d)
                          ? { backgroundImage: "var(--brand-gradient)", color: "white" }
                          : { color: "var(--ink-secondary)" }
                      }
                    >
                      {format(d, "d")}
                    </span>
                    {!readOnly && <Plus size={12} className="opacity-0 group-hover:opacity-60" style={{ color: "var(--ink-muted)" }} />}
                  </div>
                  <div className="space-y-0.5">
                    {dayEvents.slice(0, 3).map((e) => (
                      <button
                        key={e.id}
                        className="w-full text-left truncate rounded-md px-1.5 py-0.5 text-[11px] font-medium"
                        style={{ backgroundColor: `${colorOf(e)}1f`, color: colorOf(e) }}
                        title={e.title}
                        onClick={(ev) => {
                          ev.stopPropagation();
                          openEvent(e);
                        }}
                      >
                        {!e.allDay && isSameDay(new Date(e.startAt), d) && <span className="opacity-70 mr-1">{format(new Date(e.startAt), "HH:mm")}</span>}
                        {e.title}
                      </button>
                    ))}
                    {dayEvents.length > 3 && (
                      <p className="text-[10px] px-1.5" style={{ color: "var(--ink-muted)" }}>
                        +{dayEvents.length - 3} more
                      </p>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      ) : agenda.length === 0 ? (
        <div className="glass-card">
          <EmptyState icon={Calendar} title="Nothing scheduled this month" description="Add events, deadlines and reminders to plan your work." />
        </div>
      ) : (
        <div className="space-y-4">
          {agenda.map(({ day, items }) => (
            <div key={day.toISOString()} className="glass-card p-4 flex flex-col sm:flex-row gap-3 sm:gap-4">
              <div className="sm:w-28 shrink-0">
                <p className="text-2xl font-bold leading-none" style={{ color: isToday(day) ? "var(--brand-600)" : "var(--ink-primary)" }}>
                  {format(day, "d")}
                </p>
                <p className="text-xs mt-1" style={{ color: "var(--ink-muted)" }}>
                  {format(day, "EEEE")}
                </p>
              </div>
              <div className="flex-1 space-y-1.5 min-w-0">
                {items.map((e) => (
                  <button
                    key={e.id}
                    onClick={() => openEvent(e)}
                    className="w-full text-left flex items-start gap-3 p-2.5 rounded-xl hover:bg-[rgb(var(--panel-border)/0.04)]"
                  >
                    <span className="w-1 self-stretch rounded-full shrink-0" style={{ backgroundColor: colorOf(e) }} />
                    <span className="min-w-0 flex-1">
                      <span className="block text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                        {e.title}
                      </span>
                      <span className="flex flex-wrap items-center gap-3 text-[11px] mt-0.5" style={{ color: "var(--ink-muted)" }}>
                        <span className="inline-flex items-center gap-1">
                          <Clock size={11} />
                          {e.allDay ? "All day" : `${format(new Date(e.startAt), "HH:mm")}${e.endAt ? ` – ${format(new Date(e.endAt), "HH:mm")}` : ""}`}
                        </span>
                        {e.location && (
                          <span className="inline-flex items-center gap-1 truncate">
                            <MapPin size={11} /> {e.location}
                          </span>
                        )}
                        <span className="capitalize">{e.eventType}</span>
                      </span>
                    </span>
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      <Modal
        open={creating || !!editing}
        onClose={close}
        title={creating ? "New event" : editable ? "Edit event" : editing?.title ?? "Event"}
        width="32rem"
        footer={
          <>
            {editing && canEdit(editing) && (
              <button
                className="btn-danger mr-auto"
                onClick={async () => {
                  const ok = await confirm("Delete this event?", `"${editing.title}" will be removed.`);
                  if (ok) remove.mutate(editing.id);
                }}
              >
                <Trash2 size={14} /> Delete
              </button>
            )}
            <button className="btn-ghost" onClick={close}>
              {editable ? "Cancel" : "Close"}
            </button>
            {editable && (
              <button className="btn-primary" disabled={!draft.title?.trim() || !draft.startAt || save.isPending} onClick={() => save.mutate()}>
                {creating ? "Create event" : "Save"}
              </button>
            )}
          </>
        }
      >
        <fieldset disabled={!editable} className="space-y-4 min-w-0">
          <Field label="Title" required>
            <input className="input" value={draft.title ?? ""} onChange={(e) => setDraft((d) => ({ ...d, title: e.target.value }))} />
          </Field>
          <FieldGrid>
            <Field label="Type">
              <select className="select" value={draft.eventType ?? "event"} onChange={(e) => setDraft((d) => ({ ...d, eventType: e.target.value }))}>
                {EVENT_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Location">
              <input className="input" value={draft.location ?? ""} onChange={(e) => setDraft((d) => ({ ...d, location: e.target.value }))} />
            </Field>
            <Field label="Starts" required>
              <input
                className="input"
                type="datetime-local"
                value={toLocalInput(draft.startAt)}
                onChange={(e) => setDraft((d) => ({ ...d, startAt: fromLocalInput(e.target.value) ?? undefined }))}
              />
            </Field>
            <Field label="Ends">
              <input
                className="input"
                type="datetime-local"
                value={toLocalInput(draft.endAt)}
                onChange={(e) => setDraft((d) => ({ ...d, endAt: fromLocalInput(e.target.value) }))}
              />
            </Field>
          </FieldGrid>
          <label className="flex items-center gap-2 text-sm" style={{ color: "var(--ink-secondary)" }}>
            <input type="checkbox" className="checkbox" checked={!!draft.allDay} onChange={(e) => setDraft((d) => ({ ...d, allDay: e.target.checked }))} />
            All-day event
          </label>
          <Field label="Description">
            <textarea className="textarea" value={draft.description ?? ""} onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))} />
          </Field>
        </fieldset>
      </Modal>
      {confirmNode}
    </div>
  );
}
