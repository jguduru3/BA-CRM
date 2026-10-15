import { AlertTriangle } from "lucide-react";
import { useState } from "react";
import { Modal } from "./Modal";

export function useConfirm() {
  const [state, setState] = useState<{
    open: boolean;
    title: string;
    description?: string;
    danger?: boolean;
    resolve?: (v: boolean) => void;
  }>({ open: false, title: "" });

  const confirm = (title: string, description?: string, danger = true) =>
    new Promise<boolean>((resolve) => {
      setState({ open: true, title, description, danger, resolve });
    });

  const node = (
    <Modal
      open={state.open}
      onClose={() => {
        state.resolve?.(false);
        setState((s) => ({ ...s, open: false }));
      }}
      title={state.title}
      width="26rem"
      footer={
        <>
          <button
            className="btn-ghost"
            onClick={() => {
              state.resolve?.(false);
              setState((s) => ({ ...s, open: false }));
            }}
          >
            Cancel
          </button>
          <button
            className={state.danger ? "btn-danger" : "btn-primary"}
            onClick={() => {
              state.resolve?.(true);
              setState((s) => ({ ...s, open: false }));
            }}
          >
            Confirm
          </button>
        </>
      }
    >
      <div className="flex gap-3">
        {state.danger && (
          <div
            className="flex items-center justify-center h-10 w-10 rounded-xl shrink-0"
            style={{ backgroundColor: "rgb(208 59 59 / 0.12)" }}
          >
            <AlertTriangle size={18} style={{ color: "var(--critical)" }} />
          </div>
        )}
        <p className="text-sm pt-1" style={{ color: "var(--ink-secondary)" }}>
          {state.description}
        </p>
      </div>
    </Modal>
  );

  return { confirm, node };
}
