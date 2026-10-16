import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowDown,
  ArrowUp,
  CheckSquare,
  GripVertical,
  Heading1,
  Heading2,
  Heading3,
  List,
  Minus,
  Plus,
  Trash2,
  Type,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { LucideIcon } from "lucide-react";
import { api } from "@/lib/api";
import { isReadOnly, useAuthStore } from "@/lib/auth";
import { toast } from "@/lib/toast";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { BLOCK_CHOICES, blockText, editorType, moveItem, type EditorBlockType, type PageBlock, type RecordPageResponse } from "./blocks";
import { LinkifiedText } from "./LinkifiedText";

const ICONS: Record<EditorBlockType, LucideIcon> = {
  paragraph: Type,
  heading_1: Heading1,
  heading_2: Heading2,
  heading_3: Heading3,
  bulleted_list: List,
  todo: CheckSquare,
  divider: Minus,
};

const TEXT_STYLE: Record<EditorBlockType, string> = {
  paragraph: "text-sm leading-relaxed",
  heading_1: "text-2xl font-bold tracking-tight",
  heading_2: "text-xl font-semibold tracking-tight",
  heading_3: "text-base font-semibold",
  bulleted_list: "text-sm leading-relaxed",
  todo: "text-sm leading-relaxed",
  divider: "",
};

const PLACEHOLDER: Record<EditorBlockType, string> = {
  paragraph: "Type something…",
  heading_1: "Heading 1",
  heading_2: "Heading 2",
  heading_3: "Heading 3",
  bulleted_list: "List item",
  todo: "To-do",
  divider: "",
};

/**
 * Reusable Notion-style block page for any CRM record (or a Document).
 * Blocks: headings H1–H3, paragraphs, bullets, to-dos and dividers; edit in
 * place, Enter adds a block below, Backspace on an empty block removes it,
 * reorder by drag-and-drop or the up/down buttons (one reorder call).
 *
 * Text is always rendered as text — links are auto-detected only through SafeLink.
 * Read-only when `readOnly` is set, for SUPPORT, or when the server says the
 * caller cannot edit the parent record.
 */
export function RecordPagePanel({
  relatedType,
  relatedId,
  readOnly = false,
}: {
  relatedType: string;
  relatedId: number;
  readOnly?: boolean;
}) {
  const qc = useQueryClient();
  const role = useAuthStore((s) => s.user?.role);
  const key = ["record-page", relatedType, relatedId];
  const base = `/pages/${encodeURIComponent(relatedType)}/${relatedId}`;

  const page = useQuery({
    queryKey: key,
    queryFn: async () => (await api.get<RecordPageResponse>(base)).data,
  });

  const [blocks, setBlocks] = useState<PageBlock[]>([]);
  const [focusId, setFocusId] = useState<number | null>(null);
  const [dragId, setDragId] = useState<number | null>(null);
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());

  useEffect(() => {
    if (!page.data) return;
    const server = page.data.blocks;
    // Keep text the user typed but that is still waiting for its debounced save.
    setBlocks((prev) =>
      server.map((sb) => {
        if (!timers.current.has(sb.id)) return sb;
        const local = prev.find((p) => p.id === sb.id);
        return local ? { ...sb, content: local.content } : sb;
      })
    );
  }, [page.data]);

  useEffect(() => {
    const pending = timers.current;
    return () => pending.forEach((t) => clearTimeout(t));
  }, []);

  const editable = !readOnly && !isReadOnly(role) && !!page.data?.canEdit;
  const refresh = () => qc.invalidateQueries({ queryKey: key });
  const fail = (e: Error) => toast.error(e.message);

  const addBlock = useMutation({
    mutationFn: async (vars: { blockType: EditorBlockType; afterBlockId?: number; text?: string }) =>
      (
        await api.post<PageBlock>(`${base}/blocks`, {
          blockType: vars.blockType,
          afterBlockId: vars.afterBlockId,
          content: vars.blockType === "divider" ? undefined : { text: vars.text ?? "" },
        })
      ).data,
    onSuccess: (b) => {
      setFocusId(b.id);
      refresh();
    },
    onError: fail,
  });

  const updateBlock = useMutation({
    mutationFn: async (vars: { id: number; payload: Record<string, unknown> }) =>
      (await api.put<PageBlock>(`${base}/blocks/${vars.id}`, vars.payload)).data,
    onError: (e: Error) => {
      fail(e);
      refresh();
    },
  });

  const removeBlock = useMutation({
    mutationFn: async (id: number) => api.delete(`${base}/blocks/${id}`),
    onSuccess: refresh,
    onError: fail,
  });

  const reorder = useMutation({
    mutationFn: async (ids: number[]) => (await api.put<PageBlock[]>(`${base}/blocks/order`, { blockIds: ids })).data,
    onSuccess: (fresh) => setBlocks(fresh),
    onError: (e: Error) => {
      fail(e);
      refresh();
    },
  });

  const setText = (id: number, text: string) => {
    setBlocks((bs) => bs.map((b) => (b.id === id ? { ...b, content: JSON.stringify({ text }) } : b)));
    const prev = timers.current.get(id);
    if (prev) clearTimeout(prev);
    timers.current.set(
      id,
      setTimeout(() => {
        timers.current.delete(id);
        updateBlock.mutate({ id, payload: { content: { text } } });
      }, 600)
    );
  };

  const setType = (b: PageBlock, type: EditorBlockType) => {
    setBlocks((bs) => bs.map((x) => (x.id === b.id ? { ...x, blockType: type } : x)));
    updateBlock.mutate({ id: b.id, payload: { blockType: type } });
  };

  const toggle = (b: PageBlock) => {
    setBlocks((bs) => bs.map((x) => (x.id === b.id ? { ...x, checked: !x.checked } : x)));
    updateBlock.mutate({ id: b.id, payload: { checked: !b.checked } });
  };

  const move = (from: number, to: number) => {
    const next = moveItem(blocks, from, to);
    if (next === blocks) return;
    setBlocks(next);
    reorder.mutate(next.map((b) => b.id));
  };

  if (page.isLoading) {
    return (
      <div className="space-y-3" aria-busy="true">
        <Skeleton className="h-7 w-1/2" />
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-4 w-5/6" />
      </div>
    );
  }
  if (page.isError) {
    return <QueryError error={page.error} onRetry={() => page.refetch()} title="Couldn't load this page" compact />;
  }

  return (
    <div className="flex flex-col gap-0.5" data-testid="record-page">
      {blocks.length === 0 && (
        <p className="text-sm py-6 text-center rounded-xl" style={{ color: "var(--ink-muted)", backgroundColor: "rgb(var(--panel-border) / 0.03)" }}>
          {editable ? "This page is empty — add a heading or start writing below." : "Nothing written here yet."}
        </p>
      )}

      {blocks.map((b, i) => {
        const type = editorType(b.blockType);
        const text = blockText(b.content);
        return (
          <div
            key={b.id}
            className="group relative flex items-start gap-1.5 rounded-lg -mx-2 px-2 py-0.5 transition-colors"
            style={{
              backgroundColor: dragId === b.id ? "rgb(var(--ring-brand) / 0.08)" : undefined,
            }}
            draggable={editable}
            onDragStart={(e) => {
              setDragId(b.id);
              e.dataTransfer.effectAllowed = "move";
            }}
            onDragOver={(e) => editable && dragId != null && e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              const from = blocks.findIndex((x) => x.id === dragId);
              setDragId(null);
              if (from >= 0) move(from, i);
            }}
            onDragEnd={() => setDragId(null)}
          >
            {editable && (
              <div className="flex items-center gap-0.5 pt-1 opacity-100 sm:opacity-0 sm:group-hover:opacity-100 sm:group-focus-within:opacity-100 transition-opacity">
                <span className="cursor-grab hidden sm:inline-flex" title="Drag to reorder" style={{ color: "var(--ink-muted)" }}>
                  <GripVertical size={14} />
                </span>
                <select
                  aria-label="Block type"
                  className="text-[11px] rounded-md px-1 py-0.5 bg-transparent cursor-pointer"
                  style={{ color: "var(--ink-muted)", border: "1px solid rgb(var(--panel-border) / 0.1)" }}
                  value={type}
                  onChange={(e) => setType(b, e.target.value as EditorBlockType)}
                >
                  {BLOCK_CHOICES.map((c) => (
                    <option key={c.type} value={c.type}>
                      {c.label}
                    </option>
                  ))}
                </select>
              </div>
            )}

            <div className="flex-1 min-w-0 flex items-start gap-2">
              {type === "bulleted_list" && (
                <span className="mt-[0.55rem] h-1.5 w-1.5 rounded-full shrink-0" style={{ backgroundColor: "var(--ink-secondary)" }} />
              )}
              {type === "todo" && (
                <input
                  type="checkbox"
                  className="checkbox mt-1.5"
                  checked={b.checked}
                  disabled={!editable}
                  onChange={() => toggle(b)}
                  aria-label="Done"
                />
              )}
              {type === "divider" ? (
                <hr className="flex-1 my-3" style={{ borderColor: "rgb(var(--panel-border) / 0.12)" }} />
              ) : editable ? (
                <AutoTextarea
                  value={text}
                  autoFocus={focusId === b.id}
                  placeholder={PLACEHOLDER[type]}
                  className={`${TEXT_STYLE[type]} ${type === "todo" && b.checked ? "line-through opacity-60" : ""}`}
                  onChange={(v) => setText(b.id, v)}
                  onEnter={() => addBlock.mutate({ blockType: type === "bulleted_list" || type === "todo" ? type : "paragraph", afterBlockId: b.id })}
                  onBackspaceEmpty={() => {
                    removeBlock.mutate(b.id);
                    setFocusId(blocks[i - 1]?.id ?? null);
                  }}
                />
              ) : (
                <div
                  className={`flex-1 min-w-0 py-1 ${TEXT_STYLE[type]} ${type === "todo" && b.checked ? "line-through opacity-60" : ""}`}
                  style={{ color: type.startsWith("heading") ? "var(--ink-primary)" : "var(--ink-secondary)" }}
                >
                  {text ? <LinkifiedText text={text} /> : <span style={{ color: "var(--ink-muted)" }}>&nbsp;</span>}
                </div>
              )}
            </div>

            {editable && (
              <div className="flex items-center gap-0.5 pt-0.5 opacity-100 sm:opacity-0 sm:group-hover:opacity-100 sm:group-focus-within:opacity-100 transition-opacity">
                <button className="btn-icon h-7 w-7" title="Move up" aria-label="Move up" disabled={i === 0} onClick={() => move(i, i - 1)}>
                  <ArrowUp size={13} />
                </button>
                <button
                  className="btn-icon h-7 w-7"
                  title="Move down"
                  aria-label="Move down"
                  disabled={i === blocks.length - 1}
                  onClick={() => move(i, i + 1)}
                >
                  <ArrowDown size={13} />
                </button>
                <button
                  className="btn-icon h-7 w-7"
                  title="Delete block"
                  aria-label="Delete block"
                  style={{ color: "var(--critical)" }}
                  onClick={() => removeBlock.mutate(b.id)}
                >
                  <Trash2 size={13} />
                </button>
              </div>
            )}
          </div>
        );
      })}

      {editable && (
        <div className="flex flex-wrap items-center gap-1.5 mt-3 pt-3 hairline border-t">
          <span className="text-[11px] font-semibold uppercase tracking-wide mr-1" style={{ color: "var(--ink-muted)" }}>
            <Plus size={11} className="inline -mt-0.5" /> Add
          </span>
          {BLOCK_CHOICES.map((c) => {
            const Icon = ICONS[c.type];
            return (
              <button
                key={c.type}
                className="btn-ghost px-2 py-1 text-xs"
                title={c.hint}
                disabled={addBlock.isPending}
                onClick={() => addBlock.mutate({ blockType: c.type })}
              >
                <Icon size={13} /> {c.label}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** Borderless textarea that grows with its content; Enter/Backspace drive block creation/removal. */
function AutoTextarea({
  value,
  onChange,
  onEnter,
  onBackspaceEmpty,
  placeholder,
  className,
  autoFocus,
}: {
  value: string;
  onChange: (v: string) => void;
  onEnter: () => void;
  onBackspaceEmpty: () => void;
  placeholder: string;
  className: string;
  autoFocus: boolean;
}) {
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight}px`;
  }, [value]);
  useEffect(() => {
    if (autoFocus) ref.current?.focus();
  }, [autoFocus]);
  return (
    <textarea
      ref={ref}
      rows={1}
      value={value}
      placeholder={placeholder}
      className={`flex-1 min-w-0 resize-none bg-transparent outline-none py-1 ${className}`}
      style={{ color: "var(--ink-primary)" }}
      onChange={(e) => onChange(e.target.value)}
      onKeyDown={(e) => {
        if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
          e.preventDefault();
          onEnter();
        } else if (e.key === "Backspace" && value === "") {
          e.preventDefault();
          onBackspaceEmpty();
        }
      }}
    />
  );
}

export default RecordPagePanel;
