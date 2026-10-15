/**
 * Renders user-supplied URLs as links ONLY when they use http, https or mailto.
 * Anything else (javascript:, data:, vbscript:, ...) is shown as plain text, so
 * stored data can never execute script when clicked. Use this everywhere a
 * value from the API becomes an href.
 */
const SAFE = /^(https?:|mailto:)/i;

export function safeHref(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const v = raw.trim();
  // Whitespace or control characters can hide a scheme from naive checks — never link those.
  if (!v || /\s/.test(v) || [...v].some((ch) => ch.charCodeAt(0) < 0x20)) return null;
  if (SAFE.test(v)) {
    try {
      const u = new URL(v);
      return u.protocol === "http:" || u.protocol === "https:" || u.protocol === "mailto:" ? u.href : null;
    } catch {
      return null;
    }
  }
  // Bare domains ("acme.com") are treated as web addresses; anything with another scheme is not a link.
  if (/^[a-z][a-z0-9+.-]*:/i.test(v)) return null;
  if (/^[\w-]+(\.[\w-]+)+(\/.*)?$/.test(v)) return `https://${v}`;
  return null;
}

export function SafeLink({
  href,
  children,
  className = "",
}: {
  href: string | null | undefined;
  children?: React.ReactNode;
  className?: string;
}) {
  const safe = safeHref(href);
  const label = children ?? href ?? "";
  if (!safe) return <span className={className}>{label}</span>;
  const external = !safe.startsWith("mailto:");
  return (
    <a
      href={safe}
      className={`underline-offset-2 hover:underline ${className}`}
      style={{ color: "var(--brand-600)" }}
      {...(external ? { target: "_blank", rel: "noopener noreferrer nofollow" } : {})}
    >
      {label}
    </a>
  );
}
