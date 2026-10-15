import { useUsersLookup } from "@/lib/hooks";
import type { Company } from "@/lib/types";
import { Field, FieldGrid } from "@/components/ui/Field";

export const COMPANY_STATUSES = ["Prospect", "Active", "Customer", "Churned"];
export const COMPANY_SIZES = ["1-10", "11-50", "51-200", "201-1000", "1000+"];

export type CompanyDraft = Partial<Company>;

export function CompanyForm({ value, onChange }: { value: CompanyDraft; onChange: (patch: CompanyDraft) => void }) {
  const users = useUsersLookup();
  const set = <K extends keyof Company>(k: K, v: Company[K]) => onChange({ [k]: v } as CompanyDraft);

  return (
    <div className="space-y-4">
      <Field label="Company name" required>
        <input className="input" value={value.name ?? ""} onChange={(e) => set("name", e.target.value)} placeholder="Acme Inc." />
      </Field>
      <FieldGrid>
        <Field label="Industry">
          <input className="input" value={value.industry ?? ""} onChange={(e) => set("industry", e.target.value)} />
        </Field>
        <Field label="Size">
          <select className="select" value={value.size ?? ""} onChange={(e) => set("size", e.target.value)}>
            <option value="">—</option>
            {COMPANY_SIZES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Website">
          <input className="input" value={value.website ?? ""} onChange={(e) => set("website", e.target.value)} placeholder="https://" />
        </Field>
        <Field label="Status">
          <select className="select" value={value.status ?? "Prospect"} onChange={(e) => set("status", e.target.value)}>
            {COMPANY_STATUSES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Phone">
          <input className="input" value={value.phone ?? ""} onChange={(e) => set("phone", e.target.value)} />
        </Field>
        <Field label="Email">
          <input className="input" type="email" value={value.email ?? ""} onChange={(e) => set("email", e.target.value)} />
        </Field>
        <Field label="Location">
          <input className="input" value={value.location ?? ""} onChange={(e) => set("location", e.target.value)} />
        </Field>
        <Field label="Annual revenue (₹)">
          <input
            className="input"
            type="number"
            min={0}
            value={value.revenue ?? 0}
            onChange={(e) => set("revenue", Number(e.target.value))}
          />
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
      </FieldGrid>
      <Field label="Notes">
        <textarea className="textarea" value={value.notes ?? ""} onChange={(e) => set("notes", e.target.value)} />
      </Field>
    </div>
  );
}
