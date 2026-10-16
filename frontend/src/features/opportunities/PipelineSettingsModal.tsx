import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { toast } from "@/lib/toast";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import type { PipelineSettings as BasePipelineSettings } from "@/features/crm/types";
import { CLOSED_STAGES } from "@/features/crm/types";
import { FORECAST_CATEGORIES, type ForecastCategoryKey } from "./pipelineTypes";

export type PipelineSettings = BasePipelineSettings & {
  /** Stage → forecast category (Won and Lost are fixed). */
  stageForecastCategories?: Record<string, ForecastCategoryKey>;
};

export function usePipelineSettings() {
  return useQuery({
    queryKey: ["pipeline-settings"],
    queryFn: async () => (await api.get<PipelineSettings>("/pipeline-settings")).data,
    staleTime: 5 * 60_000,
  });
}

/** Manager+ editor for the organisation's stage probabilities and big-deal threshold. */
export function PipelineSettingsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const settings = usePipelineSettings();
  const qc = useQueryClient();
  const [probs, setProbs] = useState<Record<string, number>>({});
  const [categories, setCategories] = useState<Record<string, ForecastCategoryKey>>({});
  const [threshold, setThreshold] = useState<string>("");

  useEffect(() => {
    if (settings.data && open) {
      setProbs(settings.data.stageProbabilities);
      setThreshold(settings.data.bigDealThreshold == null ? "" : String(settings.data.bigDealThreshold));
      setCategories(settings.data.stageForecastCategories ?? {});
    }
  }, [settings.data, open]);

  const save = useMutation({
    mutationFn: async () =>
      (
        await api.put<PipelineSettings>("/pipeline-settings", {
          stageProbabilities: probs,
          stageForecastCategories: Object.fromEntries(Object.entries(categories).filter(([s]) => !CLOSED_STAGES.includes(s))),
          bigDealThreshold: threshold === "" ? null : Number(threshold),
        })
      ).data,
    onSuccess: () => {
      toast.success("Pipeline settings saved");
      qc.invalidateQueries({ queryKey: ["pipeline-settings"] });
      onClose();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const invalid = Object.values(probs).some((v) => !Number.isFinite(v) || v < 0 || v > 100) || (threshold !== "" && Number(threshold) < 0);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Pipeline settings"
      width="30rem"
      footer={
        <>
          <button className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn-primary" disabled={invalid || save.isPending || !settings.data} onClick={() => save.mutate()}>
            {save.isPending ? "Saving…" : "Save settings"}
          </button>
        </>
      }
    >
      {settings.isError ? (
        <QueryError error={settings.error} onRetry={() => settings.refetch()} compact />
      ) : (
        <div className="space-y-5">
          <div>
            <p className="field-label">Default win probability by stage</p>
            <p className="text-xs mb-3" style={{ color: "var(--ink-muted)" }}>
              Applied when a deal is created or changes stage — unless a manager set its probability by hand.
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-2.5">
              {(settings.data?.stages ?? []).map((stage) => (
                <label key={stage} className="flex items-center justify-between gap-3 text-sm" style={{ color: "var(--ink-secondary)" }}>
                  <span className="truncate">{stage}</span>
                  <span className="flex items-center gap-1">
                    <input
                      type="number"
                      min={0}
                      max={100}
                      className="input w-20 text-right tabular-nums"
                      value={probs[stage] ?? 0}
                      onChange={(e) => setProbs((p) => ({ ...p, [stage]: Number(e.target.value) }))}
                    />
                    <span style={{ color: "var(--ink-muted)" }}>%</span>
                  </span>
                </label>
              ))}
            </div>
          </div>
          <div>
            <p className="field-label">Forecast category by stage</p>
            <p className="text-xs mb-3" style={{ color: "var(--ink-muted)" }}>
              Ordered Not forecasted &lt; Pipeline &lt; Best case &lt; Commit &lt; Closed won. Deals follow their stage unless the rep picks a
              category by hand. Won is always Closed won, Lost is never forecast.
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-2.5">
              {(settings.data?.stages ?? [])
                .filter((s) => !CLOSED_STAGES.includes(s))
                .map((stage) => (
                  <label key={stage} className="flex items-center justify-between gap-3 text-sm" style={{ color: "var(--ink-secondary)" }}>
                    <span className="truncate">{stage}</span>
                    <select
                      className="select w-36"
                      aria-label={`Forecast category for ${stage}`}
                      value={categories[stage] ?? "PIPELINE"}
                      onChange={(e) => setCategories((c) => ({ ...c, [stage]: e.target.value as ForecastCategoryKey }))}
                    >
                      {FORECAST_CATEGORIES.filter((c) => c.key !== "CLOSED_WON").map((c) => (
                        <option key={c.key} value={c.key}>
                          {c.label}
                        </option>
                      ))}
                    </select>
                  </label>
                ))}
            </div>
          </div>
          <div>
            <label className="field-label" htmlFor="big-deal-threshold">
              Big-deal alert threshold (₹)
            </label>
            <input
              id="big-deal-threshold"
              type="number"
              min={0}
              className="input"
              value={threshold}
              placeholder="No alerts"
              onChange={(e) => setThreshold(e.target.value)}
            />
            <p className="text-xs mt-1.5" style={{ color: "var(--ink-muted)" }}>
              Deals at or above this value notify the owner's managers and VP / Super Admins when created or moved.
            </p>
          </div>
        </div>
      )}
    </Modal>
  );
}
