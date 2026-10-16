import { SafeLink } from "@/components/ui/SafeLink";
import { splitLinks } from "./blocks";

/**
 * Renders user text as plain text (React escapes it), auto-linking http(s)/mailto
 * URLs strictly through SafeLink. Never uses innerHTML.
 */
export function LinkifiedText({ text, className = "" }: { text: string; className?: string }) {
  const parts = splitLinks(text);
  return (
    <span className={`whitespace-pre-wrap break-words ${className}`}>
      {parts.map((p, i) =>
        p.kind === "link" ? (
          <SafeLink key={i} href={p.value.toLowerCase().startsWith("www.") ? `https://${p.value}` : p.value}>
            {p.value}
          </SafeLink>
        ) : (
          <span key={i}>{p.value}</span>
        )
      )}
    </span>
  );
}
