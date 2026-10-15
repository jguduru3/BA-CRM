import { useState } from "react";
import { useCompaniesLookup, useUsersLookup } from "@/lib/hooks";
import type { Lead } from "@/lib/types";
import { Field, FieldGrid, SectionTitle } from "@/components/ui/Field";

export const LEAD_STAGES = ["New", "Contacted", "Meeting", "Proposal", "Negotiation", "Won", "Lost"];
export const LEAD_SOURCES = ["Direct", "Website", "Referral", "LinkedIn", "Cold Call", "Email", "Event", "Partner", "Other"];
export const LEAD_TYPES = ["Inbound", "Outbound", "Referral", "Partner"];
export const LEAD_CATEGORIES = ["Industry", "In-house", "Other services"];
export const PRIORITIES = ["Low", "Medium", "High"];
export const EMPLOYEE_BANDS = ["1-10", "11-50", "51-200", "201-1000", "1000+"];
export const REVENUE_BANDS = ["<1Cr", "1-5Cr", "5-25Cr", "25Cr+"];

export type LeadDraft = Partial<Lead>;

export function LeadForm({
  value,
  onChange,
  disableSourceEdit,
}: {
  value: LeadDraft;
  onChange: (patch: LeadDraft) => void;
  disableSourceEdit?: boolean;
}) {
  const users = useUsersLookup();
  const companies = useCompaniesLookup();
  const [showQualification, setShowQualification] = useState(
    !!(value.industry || value.requirement || value.painPoint)
  );

  const set = <K extends keyof Lead>(k: K, v: Lead[K]) => onChange({ [k]: v } as LeadDraft);

  return (
    <div className="space-y-5">
      <SectionTitle>Basic info</SectionTitle>
      <FieldGrid>
        <Field label="Name" required className="sm:col-span-2">
          <input className="input" value={value.name ?? ""} onChange={(e) => set("name", e.target.value)} placeholder="Jane Doe" />
        </Field>
        <Field label="Email">
          <input className="input" type="email" value={value.email ?? ""} onChange={(e) => set("email", e.target.value)} />
        </Field>
        <Field label="Phone">
          <input className="input" value={value.phone ?? ""} onChange={(e) => set("phone", e.target.value)} />
        </Field>
        <Field label="Business name">
          <input className="input" value={value.businessName ?? ""} onChange={(e) => set("businessName", e.target.value)} />
        </Field>
        <Field label="Company">
          <select
            className="select"
            value={value.companyId ?? ""}
            onChange={(e) => set("companyId", e.target.value ? Number(e.target.value) : (null as any))}
          >
            <option value="">— None —</option>
            {companies.data?.data.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </Field>
      </FieldGrid>

      <SectionTitle>Pipeline</SectionTitle>
      <FieldGrid>
        <Field label="Stage">
          <select className="select" value={value.stage ?? "New"} onChange={(e) => set("stage", e.target.value)}>
            {LEAD_STAGES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Priority">
          <select className="select" value={value.priority ?? "Medium"} onChange={(e) => set("priority", e.target.value)}>
            {PRIORITIES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Source" hint={disableSourceEdit ? "Locked after creation for your role" : undefined}>
          <select
            className="select"
            disabled={disableSourceEdit}
            value={value.source ?? "Direct"}
            onChange={(e) => set("source", e.target.value)}
          >
            {LEAD_SOURCES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Lead type">
          <select className="select" value={value.leadType ?? ""} onChange={(e) => set("leadType", e.target.value)}>
            <option value="">—</option>
            {LEAD_TYPES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Category">
          <select className="select" value={value.category ?? ""} onChange={(e) => set("category", e.target.value)}>
            <option value="">—</option>
            {LEAD_CATEGORIES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Owner">
          <select
            className="select"
            value={value.ownerId ?? ""}
            onChange={(e) => set("ownerId", e.target.value ? Number(e.target.value) : (null as any))}
          >
            <option value="">— Unassigned —</option>
            {users.data?.map((u) => (
              <option key={u.id} value={u.id}>
                {u.name}
              </option>
            ))}
          </select>
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
        <Field label="Follow-up on">
          <input
            className="input"
            type="datetime-local"
            value={value.followUpAt ? value.followUpAt.slice(0, 16) : ""}
            onChange={(e) => set("followUpAt", e.target.value ? new Date(e.target.value).toISOString() : (null as any))}
          />
        </Field>
      </FieldGrid>

      <Field label="Address">
        <input className="input" value={value.address ?? ""} onChange={(e) => set("address", e.target.value)} />
      </Field>
      <Field label="Notes">
        <textarea className="textarea" value={value.notes ?? ""} onChange={(e) => set("notes", e.target.value)} />
      </Field>

      <button
        type="button"
        className="text-xs font-semibold"
        style={{ color: "var(--brand-600)" }}
        onClick={() => setShowQualification((s) => !s)}
      >
        {showQualification ? "Hide" : "Show"} qualification & discovery fields
      </button>

      {showQualification && (
        <>
          <SectionTitle>Qualification &amp; discovery</SectionTitle>
          <FieldGrid>
            <Field label="Industry">
              <input className="input" value={value.industry ?? ""} onChange={(e) => set("industry", e.target.value)} />
            </Field>
            <Field label="Geography / territory">
              <input className="input" value={value.geography ?? ""} onChange={(e) => set("geography", e.target.value)} />
            </Field>
            <Field label="Employee band">
              <select className="select" value={value.employeeBand ?? ""} onChange={(e) => set("employeeBand", e.target.value)}>
                <option value="">—</option>
                {EMPLOYEE_BANDS.map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </Field>
            <Field label="Revenue band">
              <select className="select" value={value.revenueBand ?? ""} onChange={(e) => set("revenueBand", e.target.value)}>
                <option value="">—</option>
                {REVENUE_BANDS.map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </Field>
            <Field label="Decision maker">
              <input className="input" value={value.decisionMaker ?? ""} onChange={(e) => set("decisionMaker", e.target.value)} />
            </Field>
            <Field label="Technology used">
              <input className="input" value={value.technologyUsed ?? ""} onChange={(e) => set("technologyUsed", e.target.value)} />
            </Field>
          </FieldGrid>
          <Field label="Requirement">
            <textarea className="textarea" value={value.requirement ?? ""} onChange={(e) => set("requirement", e.target.value)} />
          </Field>
          <Field label="Pain point">
            <textarea className="textarea" value={value.painPoint ?? ""} onChange={(e) => set("painPoint", e.target.value)} />
          </Field>
        </>
      )}
    </div>
  );
}
