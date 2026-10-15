import { useRef, useState } from "react";
import { Modal } from "./Modal";

interface ReasonRequest {
  title: string;
  description?: string;
  label?: string;
  confirmLabel?: string;
  danger?: boolean;
  /** Minimum characters required (default 3). */
  minLength?: number;
}

/**
 * Promise-based "give a reason" dialog — the companion of useConfirm for actions
 * that must record why (deletes with reason, score overrides, rejections,
 * won/lost reasons). Resolves to the trimmed reason, or null when cancelled.
 *
 *   const { askReason, node } = useReason();
 *   const reason = await askReason({ title: "Reject campaign?" });
 *   if (reason) reject.mutate(reason);
 */
export function useReason() {
  const [req, setReq] = useState<ReasonRequest | null>(null);
  const [text, setText] = useState("");
  const resolver = useRef<((v: string | null) => void) | null>(null);

  const askReason = (r: ReasonRequest) =>
    new Promise<string | null>((resolve) => {
      resolver.current = resolve;
      setText("");
      setReq(r);
    });

  const close = (value: string | null) => {
    resolver.current?.(value);
    resolver.current = null;
    setReq(null);
  };

  const min = req?.minLength ?? 3;
  const valid = text.trim().length >= min;

  const node = (
    <Modal
      open={!!req}
      onClose={() => close(null)}
      title={req?.title ?? ""}
      width="28rem"
      footer={
        <>
          <button className="btn-ghost" onClick={() => close(null)}>
            Cancel
          </button>
          <button
            className={req?.danger ? "btn-danger" : "btn-primary"}
            disabled={!valid}
            onClick={() => close(text.trim())}
          >
            {req?.confirmLabel ?? "Confirm"}
          </button>
        </>
      }
    >
      <div className="space-y-3">
        {req?.description && (
          <p className="text-sm" style={{ color: "var(--ink-secondary)" }}>
            {req.description}
          </p>
        )}
        <label className="field-label" htmlFor="reason-text">
          {req?.label ?? "Reason"}
        </label>
        <textarea
          id="reason-text"
          className="textarea"
          autoFocus
          maxLength={1000}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.metaKey || e.ctrlKey) && valid) close(text.trim());
          }}
          placeholder="Explain briefly — this is kept in the audit log."
        />
        {!valid && text.length > 0 && (
          <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
            At least {min} characters.
          </p>
        )}
      </div>
    </Modal>
  );

  return { askReason, node };
}
