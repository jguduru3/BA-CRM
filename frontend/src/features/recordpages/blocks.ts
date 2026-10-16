/** Block model shared by RecordPagePanel and its tests. Matches /api/pages. */

export type EditorBlockType = "paragraph" | "heading_1" | "heading_2" | "heading_3" | "bulleted_list" | "todo" | "divider";

export interface PageBlock {
  id: number;
  pageId: number;
  blockType: string;
  /** JSON text, {"text": "..."} for editor blocks; legacy blocks may hold plain text. */
  content: string | null;
  position: number;
  checked: boolean;
}

export interface RecordPageResponse {
  page: { id: number | null; relatedType: string; relatedId: number; icon: string | null; coverColor: string | null };
  blocks: PageBlock[];
  canEdit: boolean;
}

export const BLOCK_CHOICES: { type: EditorBlockType; label: string; hint: string }[] = [
  { type: "paragraph", label: "Text", hint: "Plain paragraph" },
  { type: "heading_1", label: "Heading 1", hint: "Large section title" },
  { type: "heading_2", label: "Heading 2", hint: "Medium heading" },
  { type: "heading_3", label: "Heading 3", hint: "Small heading" },
  { type: "bulleted_list", label: "Bullet", hint: "Bulleted list item" },
  { type: "todo", label: "To-do", hint: "Checkbox item" },
  { type: "divider", label: "Divider", hint: "Horizontal rule" },
];

/** Legacy block types (pre block-editor) shown with the closest editor type. */
export function editorType(blockType: string): EditorBlockType {
  switch (blockType) {
    case "paragraph":
    case "heading_1":
    case "heading_2":
    case "heading_3":
    case "bulleted_list":
    case "todo":
    case "divider":
      return blockType;
    case "heading":
      return "heading_2";
    case "checklist":
      return "todo";
    default:
      return "paragraph";
  }
}

/** Reads a block's text from its JSON content; anything unparseable is shown verbatim as text. */
export function blockText(content: string | null | undefined): string {
  if (!content) return "";
  const t = content.trim();
  if (t.startsWith("{")) {
    try {
      const parsed = JSON.parse(t) as unknown;
      if (parsed && typeof parsed === "object" && "text" in parsed) {
        const text = (parsed as { text: unknown }).text;
        return typeof text === "string" ? text : "";
      }
    } catch {
      /* not JSON — fall through */
    }
  }
  return content;
}

/** Moves the item at index `from` to index `to` (returns a new array). */
export function moveItem<T>(items: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= items.length || to >= items.length) return items;
  const copy = [...items];
  const [it] = copy.splice(from, 1);
  copy.splice(to, 0, it);
  return copy;
}

const URL_RE = /\b((?:https?:\/\/|mailto:)[^\s<>"']+|www\.[^\s<>"']+)/gi;

export type TextPart = { kind: "text"; value: string } | { kind: "link"; value: string };

/**
 * Splits plain text into text and link-candidate parts. The candidates are only
 * ever rendered through SafeLink (which re-validates the scheme), never as raw
 * HTML, so this is presentation only — not a security boundary.
 */
export function splitLinks(text: string): TextPart[] {
  const parts: TextPart[] = [];
  let last = 0;
  for (const m of text.matchAll(URL_RE)) {
    const idx = m.index ?? 0;
    let url = m[0];
    // trailing punctuation belongs to the sentence, not the URL
    const trail = /[.,;:!?)\]]+$/.exec(url);
    if (trail) url = url.slice(0, -trail[0].length);
    if (idx > last) parts.push({ kind: "text", value: text.slice(last, idx) });
    parts.push({ kind: "link", value: url });
    last = idx + url.length;
  }
  if (last < text.length) parts.push({ kind: "text", value: text.slice(last) });
  return parts;
}
