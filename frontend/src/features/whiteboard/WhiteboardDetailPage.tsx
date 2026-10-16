import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, Image as ImageIcon, MousePointer2, Shapes, Square, StickyNote, Trash2, Type } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "@/lib/api";
import { isReadOnly, useAuthStore } from "@/lib/auth";
import { toast } from "@/lib/toast";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { connectorLine } from "./geometry";

interface Item {
  id: number;
  boardId: number;
  type: "sticky" | "text" | "shape" | "image" | "connector";
  x: number;
  y: number;
  w: number;
  h: number;
  rotation: number;
  zIndex: number;
  content: string | null;
  color: string | null;
  attachmentId: number | null;
  fromItemId: number | null;
  toItemId: number | null;
  arrowhead: "none" | "end" | "start" | "both" | null;
}

type Tool = "select" | "connector";

const STICKY_COLORS = ["#ffe066", "#ff9f9f", "#9ad6ff", "#b5f2c4", "#d6b4ff", "#ffd1a9"];
const CANVAS_SIZE = { w: 3200, h: 2000 };

/** Loads an attachment image through the authenticated API (a plain src would carry no token). */
function AuthImage({ attachmentId, alt }: { attachmentId: number; alt: string }) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let url: string | null = null;
    let cancelled = false;
    api
      .get(`/attachments/${attachmentId}/download`, { responseType: "blob" })
      .then((res) => {
        if (cancelled) return;
        url = URL.createObjectURL(res.data as Blob);
        setSrc(url);
      })
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [attachmentId]);
  if (failed) return <div className="w-full h-full flex items-center justify-center text-xs" style={{ color: "var(--ink-muted)" }}>Image unavailable</div>;
  if (!src) return <Skeleton className="w-full h-full rounded-xl" />;
  return <img src={src} alt={alt} className="w-full h-full object-cover rounded-xl pointer-events-none" draggable={false} />;
}

export default function WhiteboardDetailPage() {
  const { id } = useParams();
  const boardId = Number(id);
  const qc = useQueryClient();
  const role = useAuthStore((s) => s.user?.role);
  const containerRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [tool, setTool] = useState<Tool>("select");
  const [connectFrom, setConnectFrom] = useState<number | null>(null);
  const dragState = useRef<{ id: number; startX: number; startY: number; origX: number; origY: number } | null>(null);
  const resizeState = useRef<{ id: number; startX: number; startY: number; origW: number; origH: number } | null>(null);
  const [localItems, setLocalItems] = useState<Record<number, Partial<Item>>>({});
  const key = ["whiteboard", boardId];

  const board = useQuery({
    queryKey: key,
    queryFn: async () =>
      (await api.get<{ board: { name: string }; items: Item[]; canEdit?: boolean }>(`/whiteboards/${boardId}`)).data,
  });

  const canEdit = !isReadOnly(role) && board.data?.canEdit !== false;
  const all = (board.data?.items ?? []).map((it) => ({ ...it, ...localItems[it.id] }));
  const items = all.filter((i) => i.type !== "connector");
  const connectors = all.filter((i) => i.type === "connector");
  const byId = new Map(items.map((i) => [i.id, i]));
  const refresh = () => qc.invalidateQueries({ queryKey: key });

  const addItem = useMutation({
    mutationFn: async (body: Partial<Item>) => (await api.post<Item>(`/whiteboards/${boardId}/items`, body)).data,
    onSuccess: (item) => {
      refresh();
      setSelectedId(item.id);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const patchItem = useMutation({
    mutationFn: async ({ id: itemId, body }: { id: number; body: Partial<Item> }) => api.put(`/whiteboards/items/${itemId}`, body),
    onSuccess: (_r, vars) => {
      setLocalItems((s) => {
        const next = { ...s };
        delete next[vars.id];
        return next;
      });
      refresh();
    },
    onError: (e: Error) => {
      toast.error(e.message);
      refresh();
    },
  });

  const deleteItem = useMutation({
    mutationFn: async (itemId: number) => api.delete(`/whiteboards/items/${itemId}`),
    onSuccess: () => {
      setSelectedId(null);
      refresh();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  // Escape cancels a half-made connector; Delete removes the selection.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const typing = (e.target as HTMLElement)?.tagName === "TEXTAREA" || (e.target as HTMLElement)?.tagName === "INPUT";
      if (e.key === "Escape") {
        setConnectFrom(null);
        setTool("select");
      } else if ((e.key === "Delete" || e.key === "Backspace") && !typing && selectedId != null && canEdit) {
        deleteItem.mutate(selectedId);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [selectedId, canEdit]); // eslint-disable-line react-hooks/exhaustive-deps

  const addAt = (type: Item["type"], x: number, y: number) => {
    if (!canEdit) return;
    addItem.mutate({
      type,
      x,
      y,
      w: type === "text" ? 220 : 200,
      h: type === "text" ? 48 : type === "sticky" ? 160 : 120,
      content: type === "text" ? "Text" : type === "sticky" ? "New note" : "",
      color: type === "sticky" ? STICKY_COLORS[Math.floor(Math.random() * STICKY_COLORS.length)] : type === "shape" ? "#2a78d6" : undefined,
    });
  };

  const viewportCenter = () => {
    const el = containerRef.current;
    if (!el) return { x: 400, y: 300 };
    return { x: el.scrollLeft + el.clientWidth / 2 - 100, y: el.scrollTop + el.clientHeight / 2 - 70 };
  };

  const onCanvasDoubleClick = (e: React.MouseEvent) => {
    if (!containerRef.current || !canEdit || e.target !== e.currentTarget.firstChild) return;
    const rect = containerRef.current.getBoundingClientRect();
    const x = e.clientX - rect.left + containerRef.current.scrollLeft - 100;
    const y = e.clientY - rect.top + containerRef.current.scrollTop - 80;
    addAt("sticky", Math.max(0, x), Math.max(0, y));
  };

  const connectTo = (item: Item) => {
    if (connectFrom == null) {
      setConnectFrom(item.id);
      return;
    }
    if (connectFrom !== item.id) {
      addItem.mutate({ type: "connector", fromItemId: connectFrom, toItemId: item.id, arrowhead: "end" });
    }
    setConnectFrom(null);
  };

  const onItemPointerDown = (e: React.PointerEvent, item: Item) => {
    e.stopPropagation();
    if (tool === "connector" && canEdit) {
      connectTo(item);
      return;
    }
    setSelectedId(item.id);
    if (!canEdit) return;
    (e.currentTarget as Element).setPointerCapture(e.pointerId);
    dragState.current = { id: item.id, startX: e.clientX, startY: e.clientY, origX: item.x, origY: item.y };
  };

  const onPointerMove = useCallback((e: React.PointerEvent) => {
    if (dragState.current) {
      const { id: itemId, startX, startY, origX, origY } = dragState.current;
      setLocalItems((s) => ({ ...s, [itemId]: { ...s[itemId], x: Math.max(0, origX + e.clientX - startX), y: Math.max(0, origY + e.clientY - startY) } }));
    } else if (resizeState.current) {
      const { id: itemId, startX, startY, origW, origH } = resizeState.current;
      setLocalItems((s) => ({ ...s, [itemId]: { ...s[itemId], w: Math.max(60, origW + e.clientX - startX), h: Math.max(40, origH + e.clientY - startY) } }));
    }
  }, []);

  const finishGesture = (itemId: number) => {
    const moved = dragState.current?.id === itemId || resizeState.current?.id === itemId;
    dragState.current = null;
    resizeState.current = null;
    const patch = localItems[itemId];
    if (moved && patch) {
      const body: Partial<Item> = {};
      for (const k of ["x", "y", "w", "h"] as const) if (patch[k] != null) body[k] = patch[k];
      if (Object.keys(body).length) patchItem.mutate({ id: itemId, body });
    }
  };

  const uploadImage = async (file: File) => {
    try {
      const form = new FormData();
      form.append("file", file);
      form.append("relatedType", "Whiteboard");
      form.append("relatedId", String(boardId));
      const res = await api.post("/attachments", form, { headers: { "Content-Type": "multipart/form-data" } });
      const c = viewportCenter();
      addItem.mutate({ type: "image", x: c.x, y: c.y, w: 260, h: 200, attachmentId: res.data.id, content: res.data.fileName });
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Upload failed");
    }
  };

  if (board.isLoading) return <Skeleton className="h-[70vh] rounded-2xl" />;
  if (board.isError) {
    return (
      <div className="glass-card">
        <QueryError error={board.error} onRetry={() => board.refetch()} title="Couldn't load this board" />
      </div>
    );
  }

  const selectedConnector = connectors.find((c) => c.id === selectedId);

  return (
    <div className="flex flex-col gap-3 flex-1 min-h-0 -m-4 sm:-m-6 lg:-m-8">
      <div className="flex flex-wrap items-center justify-between gap-3 px-4 sm:px-6 lg:px-8 pt-4 sm:pt-6 lg:pt-8">
        <div className="flex items-center gap-3 min-w-0">
          <Link to="/whiteboards" className="btn-icon shrink-0" aria-label="Back to whiteboards">
            <ArrowLeft size={17} />
          </Link>
          <div className="min-w-0">
            <h1 className="text-lg font-bold truncate" style={{ color: "var(--ink-primary)" }}>
              {board.data?.board.name}
            </h1>
            <p className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
              {tool === "connector"
                ? connectFrom == null
                  ? "Connector: click the first element"
                  : "Now click the element to connect to (Esc cancels)"
                : canEdit
                ? "Drag to move · double-click the canvas to add a note"
                : "View only"}
            </p>
          </div>
        </div>
        {canEdit && (
          <div className="flex items-center gap-1 glass-solid rounded-xl p-1" role="toolbar" aria-label="Whiteboard tools">
            <ToolButton active={tool === "select"} title="Select & move" onClick={() => { setTool("select"); setConnectFrom(null); }}>
              <MousePointer2 size={15} />
            </ToolButton>
            <ToolButton active={tool === "connector"} title="Connector: click element A, then element B" onClick={() => { setTool("connector"); setConnectFrom(null); }}>
              <ArrowRight size={15} />
            </ToolButton>
            <span className="w-px h-5 mx-0.5" style={{ backgroundColor: "rgb(var(--panel-border) / 0.12)" }} />
            <ToolButton title="Add sticky note" onClick={() => { const c = viewportCenter(); addAt("sticky", c.x, c.y); }}>
              <StickyNote size={15} />
            </ToolButton>
            <ToolButton title="Add text" onClick={() => { const c = viewportCenter(); addAt("text", c.x, c.y); }}>
              <Type size={15} />
            </ToolButton>
            <ToolButton title="Add shape" onClick={() => { const c = viewportCenter(); addAt("shape", c.x, c.y); }}>
              <Square size={15} />
            </ToolButton>
            <ToolButton title="Add image" onClick={() => fileInputRef.current?.click()}>
              <ImageIcon size={15} />
            </ToolButton>
            <input ref={fileInputRef} type="file" accept="image/*" hidden onChange={(e) => e.target.files?.[0] && uploadImage(e.target.files[0])} />
          </div>
        )}
      </div>

      {selectedConnector && canEdit && (
        <div className="mx-4 sm:mx-6 lg:mx-8 flex flex-wrap items-center gap-2 glass-solid rounded-xl p-2 text-sm">
          <span className="field-label mb-0">Connector</span>
          <select
            className="select w-auto py-1 text-sm"
            value={selectedConnector.arrowhead ?? "end"}
            onChange={(e) => patchItem.mutate({ id: selectedConnector.id, body: { arrowhead: e.target.value as Item["arrowhead"] } })}
            aria-label="Arrowheads"
          >
            <option value="none">No arrows</option>
            <option value="end">Arrow at end</option>
            <option value="start">Arrow at start</option>
            <option value="both">Arrows both ends</option>
          </select>
          <input
            className="input py-1 text-sm w-48"
            placeholder="Label"
            defaultValue={selectedConnector.content ?? ""}
            key={selectedConnector.id}
            onBlur={(e) => e.target.value !== (selectedConnector.content ?? "") && patchItem.mutate({ id: selectedConnector.id, body: { content: e.target.value } })}
          />
          <button className="btn-ghost px-2 py-1 text-xs" style={{ color: "var(--critical)" }} onClick={() => deleteItem.mutate(selectedConnector.id)}>
            <Trash2 size={12} /> Remove
          </button>
        </div>
      )}

      <div
        ref={containerRef}
        className="flex-1 overflow-auto thin-scroll relative mx-4 sm:mx-6 lg:mx-8 mb-4 sm:mb-6 lg:mb-8 rounded-2xl min-h-[60vh]"
        style={{
          backgroundColor: "var(--surface-sunken)",
          backgroundImage: "radial-gradient(rgb(var(--panel-border) / 0.18) 1px, transparent 1px)",
          backgroundSize: "22px 22px",
          border: "1px solid rgb(var(--panel-border) / 0.08)",
          cursor: tool === "connector" ? "crosshair" : undefined,
          touchAction: "none",
        }}
        onDoubleClick={onCanvasDoubleClick}
        onPointerMove={onPointerMove}
        onClick={(e) => {
          if (e.target === e.currentTarget || e.target === e.currentTarget.firstChild) setSelectedId(null);
        }}
      >
        <div style={{ width: CANVAS_SIZE.w, height: CANVAS_SIZE.h, position: "relative" }}>
          {/* Connector layer: drawn from the endpoints' current (local, mid-drag) geometry. */}
          <svg className="absolute inset-0 pointer-events-none" width={CANVAS_SIZE.w} height={CANVAS_SIZE.h} style={{ zIndex: 1 }}>
            <defs>
              <marker id="wb-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
                <path d="M 0 0 L 10 5 L 0 10 z" fill="context-stroke" />
              </marker>
            </defs>
            {connectors.map((c) => {
              const from = c.fromItemId != null ? byId.get(c.fromItemId) : undefined;
              const to = c.toItemId != null ? byId.get(c.toItemId) : undefined;
              if (!from || !to) return null;
              const l = connectorLine(from, to);
              const selected = c.id === selectedId;
              const stroke = selected ? "var(--brand-500)" : c.color || "#64748b";
              const head = c.arrowhead ?? "end";
              return (
                <g key={c.id}>
                  <line
                    x1={l.x1}
                    y1={l.y1}
                    x2={l.x2}
                    y2={l.y2}
                    stroke="transparent"
                    strokeWidth={14}
                    style={{ pointerEvents: "stroke", cursor: "pointer" }}
                    onPointerDown={(e) => {
                      e.stopPropagation();
                      setSelectedId(c.id);
                    }}
                  />
                  <line
                    x1={l.x1}
                    y1={l.y1}
                    x2={l.x2}
                    y2={l.y2}
                    stroke={stroke}
                    strokeWidth={selected ? 2.5 : 2}
                    strokeLinecap="round"
                    markerEnd={head === "end" || head === "both" ? "url(#wb-arrow)" : undefined}
                    markerStart={head === "start" || head === "both" ? "url(#wb-arrow)" : undefined}
                  />
                  {c.content && (
                    <text
                      x={l.mx}
                      y={l.my - 6}
                      textAnchor="middle"
                      fontSize={12}
                      fontWeight={600}
                      fill="var(--ink-secondary)"
                      stroke="var(--surface-sunken)"
                      strokeWidth={4}
                      paintOrder="stroke"
                    >
                      {c.content}
                    </text>
                  )}
                </g>
              );
            })}
          </svg>

          {items.map((item) => {
            const pendingSource = connectFrom === item.id;
            return (
              <div
                key={item.id}
                onPointerDown={(e) => onItemPointerDown(e, item)}
                onPointerUp={() => finishGesture(item.id)}
                className="absolute shadow-glass rounded-xl select-none transition-shadow"
                style={{
                  left: item.x,
                  top: item.y,
                  width: item.w,
                  height: item.h,
                  zIndex: item.id === selectedId ? 50 : 2 + item.zIndex,
                  outline: pendingSource
                    ? "2px dashed var(--brand-500)"
                    : item.id === selectedId
                    ? "2px solid var(--brand-500)"
                    : undefined,
                  outlineOffset: 2,
                  cursor: tool === "connector" ? "crosshair" : canEdit ? "grab" : "default",
                  backgroundColor:
                    item.type === "sticky" ? item.color ?? "#ffe066" : item.type === "shape" ? undefined : "rgb(var(--surface) / 0.95)",
                  border: item.type === "text" ? "1px dashed rgb(var(--panel-border) / 0.2)" : undefined,
                }}
              >
                {item.type === "shape" ? (
                  <div className="w-full h-full rounded-xl flex items-center justify-center p-2 text-sm font-semibold text-white text-center" style={{ backgroundColor: item.color ?? "#2a78d6" }}>
                    {canEdit && item.id === selectedId && tool === "select" ? (
                      <ItemText item={item} light onSave={(content) => patchItem.mutate({ id: item.id, body: { content } })} />
                    ) : (
                      item.content
                    )}
                  </div>
                ) : item.type === "image" && item.attachmentId ? (
                  <AuthImage attachmentId={item.attachmentId} alt={item.content ?? ""} />
                ) : canEdit && tool === "select" ? (
                  <ItemText item={item} onSave={(content) => patchItem.mutate({ id: item.id, body: { content } })} />
                ) : (
                  <p className="w-full h-full p-3 text-sm whitespace-pre-wrap break-words overflow-hidden" style={{ color: item.type === "sticky" ? "#2b2404" : "var(--ink-primary)", fontWeight: item.type === "text" ? 600 : 400 }}>
                    {item.content}
                  </p>
                )}
                {item.id === selectedId && canEdit && tool === "select" && (
                  <>
                    <button
                      className="absolute -top-3 -right-3 h-6 w-6 rounded-full flex items-center justify-center text-white shadow-glass"
                      style={{ backgroundColor: "var(--critical)" }}
                      aria-label="Delete element"
                      onPointerDown={(e) => e.stopPropagation()}
                      onClick={() => deleteItem.mutate(item.id)}
                    >
                      <Trash2 size={11} />
                    </button>
                    <div
                      className="absolute -bottom-1.5 -right-1.5 h-4 w-4 rounded-sm cursor-se-resize"
                      style={{ backgroundColor: "var(--brand-500)" }}
                      onPointerDown={(e) => {
                        e.stopPropagation();
                        (e.currentTarget as Element).setPointerCapture(e.pointerId);
                        resizeState.current = { id: item.id, startX: e.clientX, startY: e.clientY, origW: item.w, origH: item.h };
                      }}
                      onPointerUp={(e) => {
                        e.stopPropagation();
                        finishGesture(item.id);
                      }}
                    />
                    {(item.type === "sticky" || item.type === "shape") && (
                      <div className="absolute -top-10 left-0 flex gap-1 glass-strong rounded-lg p-1 shadow-glass" onPointerDown={(e) => e.stopPropagation()}>
                        {(item.type === "sticky" ? STICKY_COLORS : ["#2a78d6", "#1baf7a", "#eb6834", "#4a3aa7", "#e34948", "#64748b"]).map((c) => (
                          <button
                            key={c}
                            className="h-5 w-5 rounded-full"
                            style={{ backgroundColor: c }}
                            aria-label={`Colour ${c}`}
                            onClick={() => patchItem.mutate({ id: item.id, body: { color: c } })}
                          />
                        ))}
                      </div>
                    )}
                  </>
                )}
              </div>
            );
          })}
          {items.length === 0 && (
            <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
              <div className="text-center">
                <Shapes size={28} className="mx-auto mb-2" style={{ color: "var(--ink-muted)" }} />
                <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
                  {canEdit ? "Double-click anywhere, or use the toolbar, to add your first note." : "This board is empty."}
                </p>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function ToolButton({ active, title, onClick, children }: { active?: boolean; title: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      className="btn-icon h-8 w-8"
      title={title}
      aria-label={title}
      aria-pressed={active}
      onClick={onClick}
      style={active ? { backgroundImage: "var(--brand-gradient)", color: "white" } : undefined}
    >
      {children}
    </button>
  );
}

/** In-place text editing for an element; saves on blur only when the text changed. */
function ItemText({ item, onSave, light }: { item: Item; onSave: (content: string) => void; light?: boolean }) {
  const [value, setValue] = useState(item.content ?? "");
  useEffect(() => setValue(item.content ?? ""), [item.content]);
  return (
    <textarea
      className="w-full h-full bg-transparent outline-none resize-none p-3 text-sm"
      style={{
        color: light ? "white" : item.type === "sticky" ? "#2b2404" : "var(--ink-primary)",
        fontWeight: item.type === "text" || light ? 600 : 400,
        textAlign: light ? "center" : undefined,
      }}
      value={value}
      aria-label="Element text"
      onPointerDown={(e) => e.stopPropagation()}
      onChange={(e) => setValue(e.target.value)}
      onBlur={() => value !== (item.content ?? "") && onSave(value)}
    />
  );
}
