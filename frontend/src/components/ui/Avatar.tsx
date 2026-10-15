import { colorFor, initialsOf } from "@/lib/format";

export function Avatar({
  name,
  color,
  size = 28,
  className = "",
}: {
  name: string | null | undefined;
  color?: string | null;
  size?: number;
  className?: string;
}) {
  const bg = color || colorFor(name);
  return (
    <div
      className={`inline-flex items-center justify-center shrink-0 rounded-full font-semibold text-white ring-2 ring-[rgb(var(--surface))] ${className}`}
      style={{
        width: size,
        height: size,
        fontSize: Math.max(10, size * 0.38),
        backgroundColor: bg,
      }}
      title={name ?? undefined}
    >
      {initialsOf(name)}
    </div>
  );
}

export function AvatarStack({
  names,
  max = 4,
}: {
  names: (string | null | undefined)[];
  max?: number;
}) {
  const shown = names.slice(0, max);
  const extra = names.length - shown.length;
  return (
    <div className="flex items-center -space-x-2">
      {shown.map((n, i) => (
        <Avatar key={i} name={n} size={24} />
      ))}
      {extra > 0 && (
        <div
          className="inline-flex items-center justify-center rounded-full ring-2 ring-[rgb(var(--surface))] font-semibold"
          style={{
            width: 24,
            height: 24,
            fontSize: 10,
            backgroundColor: "rgb(var(--panel-border) / 0.12)",
            color: "var(--ink-secondary)",
          }}
        >
          +{extra}
        </div>
      )}
    </div>
  );
}
