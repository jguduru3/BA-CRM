/** Geometry helpers for whiteboard connectors (pure — unit tested). */

export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** Point where the ray from the box centre towards (tx, ty) leaves the box. */
export function edgePoint(b: Box, tx: number, ty: number): { x: number; y: number } {
  const cx = b.x + b.w / 2;
  const cy = b.y + b.h / 2;
  const dx = tx - cx;
  const dy = ty - cy;
  if (dx === 0 && dy === 0) return { x: cx, y: cy };
  const sx = dx === 0 ? Infinity : b.w / 2 / Math.abs(dx);
  const sy = dy === 0 ? Infinity : b.h / 2 / Math.abs(dy);
  const s = Math.min(sx, sy);
  return { x: cx + dx * s, y: cy + dy * s };
}

/** Connector segment between two boxes, from edge to edge (follows the boxes as they move). */
export function connectorLine(from: Box, to: Box) {
  const fc = { x: from.x + from.w / 2, y: from.y + from.h / 2 };
  const tc = { x: to.x + to.w / 2, y: to.y + to.h / 2 };
  const a = edgePoint(from, tc.x, tc.y);
  const b = edgePoint(to, fc.x, fc.y);
  return { x1: a.x, y1: a.y, x2: b.x, y2: b.y, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 };
}
