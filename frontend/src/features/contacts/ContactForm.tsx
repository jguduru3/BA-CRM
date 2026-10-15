import { useCompaniesLookup, useContactCategories, useUsersLookup } from "@/lib/hooks";
import type { Contact } from "@/lib/types";
import { Field, FieldGrid, SectionTitle } from "@/components/ui/Field";

export const CONTACT_STATUSES = ["Active", "Inactive"];

export type ContactDraft = Partial<Contact>;

export function ContactForm({ value, onChange }: { value: ContactDraft; onChange: (patch: ContactDraft) => void }) {
  const users = useUsersLookup();
  const companies = useCompaniesLookup();
  const categories = useContactCategories();
  const set = <K extends keyof Contact>(k: K, v: Contact[K]) => onChange({ [k]: v } as ContactDraft);

  return (
    <div className="space-y-4">
      <FieldGrid>
        <Field label="Full name" required>
          <input className="input" value={value.name ?? ""} onChange={(e) => set("name", e.target.value)} placeholder="Jane Doe" />
        </Field>
        <Field label="Title">
          <input className="input" value={value.title ?? ""} onChange={(e) => set("title", e.target.value)} placeholder="VP Engineering" />
        </Field>
        <Field label="Email">
          <input className="input" type="email" value={value.email ?? ""} onChange={(e) => set("email", e.target.value)} />
        </Field>
        <Field label="Phone">
          <input className="input" value={value.phone ?? ""} onChange={(e) => set("phone", e.target.value)} />
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
        <Field label="Status">
          <select className="select" value={value.status ?? "Active"} onChange={(e) => set("status", e.target.value)}>
            {CONTACT_STATUSES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Source">
          <input className="input" value={value.source ?? ""} onChange={(e) => set("source", e.target.value)} />
        </Field>
        <Field label="LinkedIn">
          <input className="input" value={value.linkedin ?? ""} onChange={(e) => set("linkedin", e.target.value)} placeholder="linkedin.com/in/…" />
        </Field>
        <Field label="Tags" hint="Comma-separated">
          <input className="input" value={value.tags ?? ""} onChange={(e) => set("tags", e.target.value)} />
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

      <SectionTitle>Segmentation — for WhatsApp &amp; campaign audiences</SectionTitle>
      <FieldGrid>
        <Field label="Category">
          <select
            className="select"
            value={value.categoryId ?? ""}
            onChange={(e) => set("categoryId", e.target.value ? Number(e.target.value) : (null as any))}
          >
            <option value="">— None —</option>
            {categories.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Nature of business">
          <input className="input" value={value.natureOfBusiness ?? ""} onChange={(e) => set("natureOfBusiness", e.target.value)} placeholder="e.g. Manufacturing" />
        </Field>
        <Field label="Industry">
          <input className="input" value={value.industry ?? ""} onChange={(e) => set("industry", e.target.value)} />
        </Field>
        <Field label="Country">
          <input className="input" value={value.country ?? ""} onChange={(e) => set("country", e.target.value)} />
        </Field>
        <Field label="State / region">
          <input className="input" value={value.state ?? ""} onChange={(e) => set("state", e.target.value)} />
        </Field>
      </FieldGrid>

      <Field label="Notes">
        <textarea className="textarea" value={value.notes ?? ""} onChange={(e) => set("notes", e.target.value)} />
      </Field>
    </div>
  );
}
