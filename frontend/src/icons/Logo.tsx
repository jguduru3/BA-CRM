/**
 * The Better Analytics brand mark — the official artwork (public/brand),
 * rendered as an image with no accompanying text wordmark. The PNG has a
 * transparent background, so the same asset works on light and dark themes;
 * the "white" tone is for placement on the brand-gradient panels.
 */
export function Logo({
  height = 32,
  tone = "color",
  className = "",
}: {
  height?: number;
  tone?: "color" | "white";
  className?: string;
}) {
  return (
    <img
      src={tone === "white" ? "/brand/logo-white.png" : "/brand/logo.png"}
      alt="Better Analytics"
      height={height}
      style={{ height, width: "auto" }}
      className={`select-none object-contain ${className}`}
      draggable={false}
      decoding="async"
    />
  );
}
