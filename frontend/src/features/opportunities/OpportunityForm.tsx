import { useCompaniesLookup, useUsersLookup } from "@/lib/hooks";
import type { Opportunity } from "@/lib/types";
import { Field, FieldGrid, SectionTitle } from "@/components/ui/Field";

export const OPP_STAGES = ["Qualification", "Proposal", "Negotiation", "Verbal Commit", "Won", "Lost"];
export const PROPOSAL_STATUSES = ["Not Sent", "Sent", "Viewed", "Revised"];
export const NEGOTIATION_STATUSES = ["Not Started", "In Progress", "Final Offer", "Agreed"];
export const PO_STATUSES = ["Not Requested", "Requested", "Received", "Payment Pending", "Paid"];

export type OppDraft = Partial<Opportunity>;

export function OpportunityForm({ value, onChange }: { value: OppDraft; onChange: (patch: OppDraft) => void }) {
  const users = useUsersLookup();
  const companies = useCompaniesLookup();
  const set = <K extends keyof Opportunity>(k: K, v: Opportunity[K]) => onChange({ [k]: v } as OppDraft);

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
            onChange={(e) => set("companyId", e.target.value ? Number(e.target.value) : (null as never))}
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
            onChange={(e) => set("ownerId", e.target.value ? Number(e.target.value) : (null as never))}
          >
            <option value="">— Unassigned —</option>
            {users.data?.map((u) => (
              <option key={u.id} value={u.id}>
                {u.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Stage">
          <select className="select" value={value.stage ?? "Qualification"} onChange={(e) => set("stage", e.target.value)}>
            {OPP_STAGES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Probability (%)">
          <input
            className="input"
            type="number"
            min={0}
            max={100}
            value={value.probability ?? 50}
            onChange={(e) => set("probability", Number(e.target.value))}
          />
        </Field>
        <Field label="Deal value (₹)">
          <input
            className="input"
            type="number"
            min={0}
            value={value.value ?? 0}
            onChange={(e) => set("value", Number(e.target.value))}
          />
        </Field>
        <Field label="Expected close date">
          <input className="input" type="date" value={value.closeDate ?? ""} onChange={(e) => set("closeDate", e.target.value)} />
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
        <Field label="PO / payment status" hint="Manager and above only" className="sm:col-span-2">
          <select className="select" value={value.poStatus ?? ""} onChange={(e) => set("poStatus", e.target.value)}>
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
