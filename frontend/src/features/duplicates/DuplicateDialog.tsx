import { AlertTriangle, ExternalLink, ShieldAlert } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { Avatar } from "@/components/ui/Avatar";
import { Modal } from "@/components/ui/Modal";
import { DuplicateError, matchLabel, recordHref, scorePercent } from "./duplicates";

const NOUN = { lead: "lead", contact: "contact", company: "company" } as const;

/**
 * Shown when a save hits a duplicate rule. WARN: lists the possible duplicates with
 * "Open existing" and "Save anyway". BLOCK: explains that the save is not allowed.
 */
export function DuplicateDialog({
  error,
  onClose,
  onSaveAnyway,
  saving,
}: {
  error: DuplicateError | null;
  onClose: () => void;
  onSaveAnyway: () => void;
  saving?: boolean;
}) {
  const navigate = useNavigate();
  if (!error) return null;
  const noun = NOUN[error.type];
  const blocked = error.blocked;

  return (
    <Modal
      open
      onClose={onClose}
      title={blocked ? `Duplicate ${noun} not allowed` : `Possible duplicate ${noun}`}
      width="34rem"
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            {blocked ? "Close" : "Go back and edit"}
          </button>
          {!blocked && (
            <button type="button" className="btn-primary" disabled={saving} onClick={onSaveAnyway}>
              {saving ? "Saving…" : "Save anyway"}
            </button>
          )}
        </>
      }
    >
      <div className="space-y-4">
        <div
          className="flex gap-3 rounded-xl p-3 text-sm"
          style={{
            background: blocked
              ? "color-mix(in srgb, var(--critical) 10%, transparent)"
              : "color-mix(in srgb, var(--warning) 12%, transparent)",
            color: "var(--ink-primary)",
          }}
          role="alert"
        >
          {blocked ? (
            <ShieldAlert size={18} style={{ color: "var(--critical)" }} className="shrink-0 mt-0.5" />
          ) : (
            <AlertTriangle size={18} style={{ color: "var(--warning)" }} className="shrink-0 mt-0.5" />
          )}
          <p>
            {blocked
              ? `Your organization blocks duplicate ${noun}s. Update the existing record instead${
                  error.candidates.length ? "" : " (it belongs to someone outside your view — ask a manager)"
                }.`
              : `We found ${error.candidates.length === 1 ? "a record" : `${error.candidates.length} records`} that look like this ${noun}. Open one to update it, or save a new ${noun} anyway.`}
          </p>
        </div>

        {error.candidates.length > 0 && (
          <ul className="divide-y hairline rounded-xl border" aria-label="Possible duplicates">
            {error.candidates.map((c) => (
              <li key={c.id} className="flex items-center gap-3 px-3 py-2.5">
                <Avatar name={c.name} size={30} />
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                    {c.name}
                  </p>
                  <div className="mt-1 flex flex-wrap gap-1">
                    {c.matchedOn.map((m) => (
                      <span key={m} className="badge-neutral text-[11px]">
                        {matchLabel(m)}
                      </span>
                    ))}
                    <span className="badge-brand text-[11px]" title="Match confidence">
                      {scorePercent(c.score)}
                    </span>
                  </div>
                </div>
                <button
                  type="button"
                  className="btn-ghost h-8 px-2.5 text-xs shrink-0"
                  onClick={() => {
                    onClose();
                    navigate(recordHref(error.type, c.id));
                  }}
                >
                  <ExternalLink size={13} /> Open existing
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Modal>
  );
}
