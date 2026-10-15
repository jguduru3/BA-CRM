import { Check, Circle, Info } from "lucide-react";

/** Mirrors the server's PasswordPolicy so users see the rules before submitting. */
export function passwordChecks(pw: string, email?: string | null) {
  const local = (email ?? "").split("@")[0].toLowerCase();
  return [
    { label: "At least 10 characters", ok: pw.length >= 10 },
    { label: "Contains a letter and a number", ok: /[A-Za-z]/.test(pw) && /\d/.test(pw) },
    { label: "Doesn't contain your email name", ok: !(local.length >= 4 && pw.toLowerCase().includes(local)) },
  ];
}

export function PasswordRules({ checks }: { checks: { label: string; ok: boolean }[] }) {
  return (
    <div className="space-y-1.5">
      <ul className="space-y-1" aria-label="Password requirements">
        {checks.map((c) => (
          <li key={c.label} className="flex items-center gap-2 text-xs" style={{ color: c.ok ? "var(--good)" : "var(--ink-muted)" }}>
            {c.ok ? <Check size={13} aria-hidden /> : <Circle size={11} aria-hidden />}
            <span>
              {c.label}
              <span className="sr-only">{c.ok ? " (met)" : " (not met)"}</span>
            </span>
          </li>
        ))}
      </ul>
      {/* Checked on the server only (common-password list, your name). */}
      <p className="flex items-center gap-2 text-xs" style={{ color: "var(--ink-muted)" }}>
        <Info size={12} className="shrink-0" aria-hidden />
        Avoid common passwords and your name.
      </p>
    </div>
  );
}
