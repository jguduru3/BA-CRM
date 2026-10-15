import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Image as ImageIcon, Shapes, Square, StickyNote, Trash2, Type } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "@/lib/api";
import { toast } from "@/lib/toast";
import { Skeleton } from "@/components/ui/Skeleton";

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
}

const STICKY_COLORS = ["#ffe066", "#ff9f9f", "#9ad6ff", "#b5f2c4", "#d6b4ff", "#ffd1a9"];
const CANVAS_SIZE = { w: 3200, h: 2000 };

export default function WhiteboardDetailPage() {
  const { id } = useParams();
  const boardId = Number(id);
  const qc = useQueryClient();
  const containerRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const dragState = useRef<{ id: number; startX: number; startY: number; origX: number; origY: number } | null>(null);
  const resizeState = useRef<{ id: number; startX: number; startY: number; origW: number; origH: number } | null>(null);
  const [localItems, setLocalItems] = useState<Record<number, Partial<Item>>>({});

  const board = useQuery({
    queryKey: ["whiteboard", boardId],
    queryFn: async () => (await api.get<{ board: { name: string }; items: Item[] }>(`/whiteboards/${boardId}`)).data,
  });

  const items = (board.data?.items ?? []).map((it) => ({ ...it, ...localItems[it.id] }));

  const addItem = useMutation({
    mutationFn: async (body: Partial<Item>) => (await api.post<Item>(`/whiteboards/${boardId}/items`, body)).data,
    onSuccess: (item) => {
      qc.invalidateQueries({ queryKey: ["whiteboard", boardId] });
      setSelectedId(item.id);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const patchItem = useMutation({
    mutationFn: async ({ id: itemId, body }: { id: number; body: Partial<Item> }) => api.put(`/whiteboards/items/${itemId}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["whiteboard", boardId] }),
  });

  const deleteItem = useMutation({
    mutationFn: async (itemId: number) => api.delete(`/whiteboards/items/${itemId}`),
    onSuccess: () => {
      setSelectedId(null);
      qc.invalidateQueries({ queryKey: ["whiteboard", boardId] });
    },
  });

  const addAt = (type: Item["type"], x: number, y: number) => {
    addItem.mutate({
      type,
      x,
      y,
      w: type === "text" ? 220 : 200,
      h: type === "text" ? 40 : type === "sticky" ? 160 : 140,
      content: type === "text" ? "Text" : type === "sticky" ? "New note" : "",
      color: type === "sticky" ? STICKY_COLORS[Math.floor(Math.random() * STICKY_COLORS.length)] : type === "shape" ? "#2a78d6" : undefined,
    });
  };

  const onCanvasDoubleClick = (e: React.MouseEvent) => {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const x = e.clientX - rect.left + containerRef.current.scrollLeft - 100;
    const y = e.clientY - rect.top + containerRef.current.scrollTop - 80;
    addAt("sticky", Math.max(0, x), Math.max(0, y));
  };

  const onItemPointerDown = useCallback((e: React.PointerEvent, item: Item) => {
    e.stopPropagation();
    setSelectedId(item.id);
    (e.target as Element).setPointerCapture(e.pointerId);
    dragState.current = { id: item.id, startX: e.clientX, startY: e.clientY, origX: item.x, origY: item.y };
  }, []);

  const onItemPointerMove = useCallback((e: React.PointerEvent) => {
    if (!dragState.current) return;
    const { id: itemId, startX, startY, origX, origY } = dragState.current;
    const dx = e.clientX - startX;
    const dy = e.clientY - startY;
    setLocalItems((s) => ({ ...s, [itemId]: { ...s[itemId], x: origX + dx, y: origY + dy } }));
  }, []);

  const onItemPointerUp = useCallback(
    (e: React.PointerEvent, itemId: number) => {
      (e.target as Element).releasePointerCapture(e.pointerId);
      dragState.current = null;
      const patch = localItems[itemId];
      if (patch) patchItem.mutate({ id: itemId, body: patch });
    },
    [localItems] // eslint-disable-line react-hooks/exhaustive-deps
  );

  const onResizePointerDown = useCallback((e: React.PointerEvent, item: Item) => {
    e.stopPropagation();
    (e.target as Element).setPointerCapture(e.pointerId);
    resizeState.current = { id: item.id, startX: e.clientX, startY: e.clientY, origW: item.w, origH: item.h };
  }, []);

  const onResizePointerMove = useCallback((e: React.PointerEvent) => {
    if (!resizeState.current) return;
    const { id: itemId, startX, startY, origW, origH } = resizeState.current;
    const dw = e.clientX - startX;
    const dh = e.clientY - startY;
    setLocalItems((s) => ({ ...s, [itemId]: { ...s[itemId], w: Math.max(60, origW + dw), h: Math.max(40, origH + dh) } }));
  }, []);

  const onResizePointerUp = useCallback(
    (e: React.PointerEvent, itemId: number) => {
      (e.target as Element).releasePointerCapture(e.pointerId);
      resizeState.current = null;
      const patch = localItems[itemId];
      if (patch) patchItem.mutate({ id: itemId, body: patch });
    },
    [localItems] // eslint-disable-line react-hooks/exhaustive-deps
  );

  const uploadImage = async (file: File) => {
    const form = new FormData();
    form.append("file", file);
    form.append("relatedType", "Whiteboard");
    form.append("relatedId", String(boardId));
    const res = await api.post("/attachments", form, { headers: { "Content-Type": "multipart/form-data" } });
    addItem.mutate({ type: "image", x: 200, y: 200, w: 260, h: 200, attachmentId: res.data.id, content: res.data.fileName });
  };

  if (board.isLoading) return <Skeleton className="h-[70vh]" />;

  return (
    <div className="flex flex-col gap-3 flex-1 min-h-0 -m-4 sm:-m-6 lg:-m-8">
      <div className="flex items-center justify-between gap-3 px-4 sm:px-6 lg:px-8 pt-4 sm:pt-6 lg:pt-8">
        <div className="flex items-center gap-3 min-w-0">
          <Link to="/whiteboards" className="btn-icon shrink-0">
            <ArrowLeft size={17} />
          </Link>
          <h1 className="text-lg font-bold truncate" style={{ color: "var(--ink-primary)" }}>
            {board.data?.board.name}
          </h1>
        </div>
        <div className="flex items-center gap-1.5">
          <button className="btn-secondary" onClick={() => addAt("sticky", 400, 300)} title="Add sticky note">
            <StickyNote size={15} />
          </button>
          <button className="btn-secondary" onClick={() => addAt("text", 400, 300)} title="Add text">
            <Type size={15} />
          </button>
          <button className="btn-secondary" onClick={() => addAt("shape", 400, 300)} title="Add shape">
            <Square size={15} />
          </button>
          <button className="btn-secondary" onClick={() => fileInputRef.current?.click()} title="Add image">
            <ImageIcon size={15} />
          </button>
          <input ref={fileInputRef} type="file" accept="image/*" hidden onChange={(e) => e.target.files?.[0] && uploadImage(e.target.files[0])} />
        </div>
      </div>

      <div
        ref={containerRef}
        className="flex-1 overflow-auto thin-scroll relative mx-4 sm:mx-6 lg:mx-8 mb-4 sm:mb-6 lg:mb-8 rounded-2xl"
        style={{
          backgroundColor: "var(--surface-sunken)",
          backgroundImage: "radial-gradient(rgb(var(--panel-border) / 0.18) 1px, transparent 1px)",
          backgroundSize: "22px 22px",
          border: "1px solid rgb(var(--panel-border) / 0.08)",
        }}
        onDoubleClick={onCanvasDoubleClick}
        onPointerMove={(e) => {
          onItemPointerMove(e);
          onResizePointerMove(e);
        }}
        onClick={(e) => {
          if (e.target === e.currentTarget) setSelectedId(null);
        }}
      >
        <div style={{ width: CANVAS_SIZE.w, height: CANVAS_SIZE.h, position: "relative" }}>
          {items.map((item) => (
            <div
              key={item.id}
              onPointerDown={(e) => onItemPointerDown(e, item)}
              onPointerUp={(e) => onItemPointerUp(e, item.id)}
              className="absolute shadow-glass rounded-xl select-none"
              style={{
                left: item.x,
                top: item.y,
                width: item.w,
                height: item.h,
                zIndex: item.id === selectedId ? 50 : item.zIndex,
                outline: item.id === selectedId ? "2px solid var(--brand-500)" : undefined,
                outlineOffset: 2,
                cursor: "grab",
                backgroundColor:
                  item.type === "sticky" ? item.color ?? "#ffe066" : item.type === "shape" ? undefined : "rgb(var(--surface) / 0.9)",
                border: item.type === "text" ? "1px dashed rgb(var(--panel-border) / 0.2)" : undefined,
              }}
            >
              {item.type === "shape" ? (
                <div
                  className="w-full h-full rounded-lg"
                  style={{ backgroundColor: item.color ?? "#2a78d6", opacity: 0.85 }}
                />
              ) : item.type === "image" && item.attachmentId ? (
                <img
                  src={`/api/attachments/${item.attachmentId}/download`}
                  alt={item.content ?? ""}
                  className="w-full h-full object-cover rounded-xl"
                  draggable={false}
                />
              ) : (
                <textarea
                  className="w-full h-full bg-transparent outline-none resize-none p-3 text-sm"
                  style={{ color: item.type === "sticky" ? "#2b2404" : "var(--ink-primary)", fontWeight: item.type === "text" ? 600 : 400 }}
                  value={item.content ?? ""}
                  onPointerDown={(e) => e.stopPropagation()}
                  onChange={(e) => {
                    setLocalItems((s) => ({ ...s, [item.id]: { ...s[item.id], content: e.target.value } }));
                  }}
                  onBlur={() => {
                    const patch = localItems[item.id];
                    if (patch) patchItem.mutate({ id: item.id, body: patch });
                  }}
                />
              )}
              {item.id === selectedId && (
                <>
                  <button
                    className="absolute -top-3 -right-3 h-6 w-6 rounded-full flex items-center justify-center text-white shadow-glass"
                    style={{ backgroundColor: "var(--critical)" }}
                    onPointerDown={(e) => e.stopPropagation()}
                    onClick={() => deleteItem.mutate(item.id)}
                  >
                    <Trash2 size={11} />
                  </button>
                  <div
                    className="absolute -bottom-1.5 -right-1.5 h-4 w-4 rounded-sm cursor-se-resize"
                    style={{ backgroundColor: "var(--brand-500)" }}
                    onPointerDown={(e) => onResizePointerDown(e, item)}
                    onPointerUp={(e) => onResizePointerUp(e, item.id)}
                  />
                  {item.type === "sticky" && (
                    <div className="absolute -top-10 left-0 flex gap-1 glass-strong rounded-lg p-1 shadow-glass" onPointerDown={(e) => e.stopPropagation()}>
                      {STICKY_COLORS.map((c) => (
                        <button
                          key={c}
                          className="h-5 w-5 rounded-full"
                          style={{ backgroundColor: c }}
                          onClick={() => patchItem.mutate({ id: item.id, body: { color: c } })}
                        />
                      ))}
                    </div>
                  )}
                </>
              )}
            </div>
          ))}
          {items.length === 0 && (
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="text-center">
                <Shapes size={28} className="mx-auto mb-2" style={{ color: "var(--ink-muted)" }} />
                <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
                  Double-click anywhere, or use the toolbar, to add your first note.
                </p>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
