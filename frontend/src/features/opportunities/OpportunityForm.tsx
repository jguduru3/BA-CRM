import { RotateCcw } from "lucide-react";
import { useCompaniesLookup, useUsersLookup } from "@/lib/hooks";
import { Field, FieldGrid, SectionTitle } from "@/components/ui/Field";
import { CLOSED_STAGES } from "@/features/crm/types";
import { categoryLabel, FORECAST_CATEGORIES, type DealRecord, type ForecastCategoryKey } from "./pipelineTypes";

export const OPP_STAGES = ["Qualification", "Proposal", "Negotiation", "Verbal Commit", "Won", "Lost"];
export const PROPOSAL_STATUSES = ["Not Sent", "Sent", "Viewed", "Revised"];
export const NEGOTIATION_STATUSES = ["Not Started", "In Progress", "Final Offer", "Agreed"];
export const PO_STATUSES = ["Not Requested", "Requested", "Received", "Payment Pending", "Paid"];

export type OppDraft = Partial<DealRecord>;

export function OpportunityForm({
  value,
  onChange,
  canEditProbability,
  canEditPoStatus,
  stageDefault,
  stageCategory,
  valueHidden,
}: {
  value: OppDraft;
  onChange: (patch: OppDraft) => void;
  /** Manager+ may set probability by hand (which marks the deal as overridden). */
  canEditProbability: boolean;
  canEditPoStatus: boolean;
  /** The organisation's default probability for the selected stage. */
  stageDefault?: number;
  /** The organisation's forecast category for the selected stage. */
  stageCategory?: ForecastCategoryKey;
  /** The reader's role can't see deal value / probability (Inside Sales). */
  valueHidden?: boolean;
}) {
  const closed = CLOSED_STAGES.includes(value.stage ?? "");
  const categoryOverridden = !!value.forecastCategoryOverridden && !closed;
  const users = useUsersLookup();
  const companies = useCompaniesLookup();
  const set = <K extends keyof DealRecord>(k: K, v: DealRecord[K]) => onChange({ [k]: v } as OppDraft);
  const overridden = !!value.probabilityOverridden;
  const probabilityHint = valueHidden
    ? "Hidden for your role"
    : overridden
    ? "Set by hand — stage changes keep it"
    : `Automatic from the stage${stageDefault != null ? ` (${stageDefault}%)` : ""}`;

  return (
    <div className="space-y-5">
      <FieldGrid>
        <Field label="Deal name" required className="sm:col-span-2">
          <input className="input" value={value.name ?? ""} onChange={(e) => set("name", e.target.value)} placeholder="Acme — Platform rollout" />
        </Field>
        <Field label="Company">
          <select
            className="select"
            value={value.companyId ?? ""}
            onChange={(e) => set("companyId", e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">— None —</option>
            {companies.data?.data.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Owner">
          <select
            className="select"
            value={value.ownerId ?? ""}
            onChange={(e) => set("ownerId", e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">— Me —</option>
            {users.data?.map((u) => (
              <option key={u.id} value={u.id}>
                {u.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Stage" hint={value.stage === "Won" || value.stage === "Lost" ? "You'll be asked for the reason" : undefined}>
          <select className="select" value={value.stage ?? "Qualification"} onChange={(e) => set("stage", e.target.value)}>
            {OPP_STAGES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Win probability (%)" hint={probabilityHint}>
          <div className="flex items-center gap-1.5">
            <input
              className="input"
              type="number"
              min={0}
              max={100}
              disabled={!canEditProbability || valueHidden}
              value={valueHidden ? "" : overridden ? value.probability ?? "" : stageDefault ?? value.probability ?? ""}
              onChange={(e) => onChange({ probability: Number(e.target.value), probabilityOverridden: true })}
            />
            {canEditProbability && overridden && (
              <button
                type="button"
                className="btn-icon shrink-0"
                title="Back to the stage default"
                onClick={() => onChange({ probabilityOverridden: false, probability: stageDefault ?? value.probability })}
              >
                <RotateCcw size={15} />
              </button>
            )}
          </div>
        </Field>
        <Field label="Deal value (₹)" hint={valueHidden ? "Hidden for your role" : undefined}>
          <input
            className="input"
            type="number"
            min={0}
            disabled={valueHidden}
            value={valueHidden ? "" : value.value ?? 0}
            onChange={(e) => set("value", Number(e.target.value))}
          />
        </Field>
        <Field
          label="Forecast category"
          hint={
            closed
              ? "Set by the closed stage"
              : categoryOverridden
              ? "Set by hand — stage changes keep it"
              : `Automatic from the stage${stageCategory ? ` (${categoryLabel(stageCategory)})` : ""}`
          }
        >
          <select
            className="select"
            disabled={closed}
            aria-label="Forecast category"
            value={categoryOverridden ? value.forecastCategory ?? "" : ""}
            onChange={(e) =>
              e.target.value
                ? onChange({ forecastCategory: e.target.value as ForecastCategoryKey, forecastCategoryOverridden: true })
                : onChange({ forecastCategoryOverridden: false, forecastCategory: stageCategory ?? value.forecastCategory })
            }
          >
            <option value="">
              Automatic{stageCategory ? ` — ${categoryLabel(closed ? value.forecastCategory : stageCategory)}` : ""}
            </option>
            {FORECAST_CATEGORIES.filter((c) => c.key !== "CLOSED_WON").map((c) => (
              <option key={c.key} value={c.key}>
                {c.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Expected close date">
          <input className="input" type="date" value={value.closeDate ?? ""} onChange={(e) => set("closeDate", e.target.value || null)} />
        </Field>
      </FieldGrid>

      <SectionTitle>Deal context</SectionTitle>
      <FieldGrid>
        <Field label="Service / product">
          <input className="input" value={value.serviceProduct ?? ""} onChange={(e) => set("serviceProduct", e.target.value)} />
        </Field>
        <Field label="Current vendor">
          <input className="input" value={value.currentVendor ?? ""} onChange={(e) => set("currentVendor", e.target.value)} />
        </Field>
        <Field label="Competitors" className="sm:col-span-2">
          <input className="input" value={value.competitors ?? ""} onChange={(e) => set("competitors", e.target.value)} />
        </Field>
        <Field label="Proposal status">
          <select className="select" value={value.proposalStatus ?? ""} onChange={(e) => set("proposalStatus", e.target.value)}>
            <option value="">—</option>
            {PROPOSAL_STATUSES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Negotiation status">
          <select className="select" value={value.negotiationStatus ?? ""} onChange={(e) => set("negotiationStatus", e.target.value)}>
            <option value="">—</option>
            {NEGOTIATION_STATUSES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="PO / payment status" hint={canEditPoStatus ? undefined : "Confirmed by a Manager"} className="sm:col-span-2">
          <select
            className="select"
            disabled={!canEditPoStatus}
            value={value.poStatus ?? ""}
            onChange={(e) => set("poStatus", e.target.value)}
          >
            <option value="">—</option>
            {PO_STATUSES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
      </FieldGrid>
      <Field label="Notes">
        <textarea className="textarea" value={value.notes ?? ""} onChange={(e) => set("notes", e.target.value)} />
      </Field>
    </div>
  );
}
