import { AlertTriangle, RotateCw } from "lucide-react";

/** Standard error state for a failed React Query request — never leave the user on an endless skeleton. */
export function QueryError({
  error,
  onRetry,
  title = "Couldn't load this",
  compact = false,
}: {
  error: unknown;
  onRetry?: () => void;
  title?: string;
  compact?: boolean;
}) {
  const message = error instanceof Error ? error.message : "Something went wrong.";
  return (
    <div
      role="alert"
      className={`flex flex-col items-center justify-center text-center ${compact ? "py-6 px-4" : "py-14 px-6"}`}
    >
      <div
        className="flex items-center justify-center h-12 w-12 rounded-2xl mb-3"
        style={{ backgroundColor: "rgb(208 59 59 / 0.1)" }}
      >
        <AlertTriangle size={22} style={{ color: "var(--critical)" }} strokeWidth={1.75} />
      </div>
      <h3 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
        {title}
      </h3>
      <p className="mt-1 text-sm max-w-md" style={{ color: "var(--ink-muted)" }}>
        {message}
      </p>
      {onRetry && (
        <button className="btn-secondary mt-4" onClick={onRetry}>
          <RotateCw size={14} /> Try again
        </button>
      )}
    </div>
  );
}
