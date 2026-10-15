import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Filter, Linkedin, Mail, Phone, Plus, Settings2, Trash2, UserSquare2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { useCompaniesLookup, useContactCategories, useEntityCrud, useUsersLookup } from "@/lib/hooks";
import { formatDate } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Contact, ContactCategory } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { StageBadge } from "@/components/ui/Badge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { SearchInput } from "@/components/ui/SearchInput";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { ContactDraft, ContactForm } from "./ContactForm";

export default function ContactsPage() {
  const [search, setSearch] = useState("");
  const [categoryFilter, setCategoryFilter] = useState<number | "">("");
  const [industryFilter, setIndustryFilter] = useState("");
  const [countryFilter, setCountryFilter] = useState("");
  const [selected, setSelected] = useState<Contact | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<ContactDraft>({});
  const [categoriesOpen, setCategoriesOpen] = useState(false);
  const users = useUsersLookup();
  const companies = useCompaniesLookup();
  const categories = useContactCategories();
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const { list, create, update, remove } = useEntityCrud<Contact>("contacts", {
    params: { search, limit: 500 },
    label: "Contact",
  });

  useEffect(() => {
    setDraft(selected ?? { status: "Active" });
  }, [selected, creating]);

  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name;
  const companyName = (id: number | null) => companies.data?.data.find((c) => c.id === id)?.name;
  const categoryOf = (id: number | null) => categories.data?.find((c) => c.id === id);

  const filtered = useMemo(() => {
    return (list.data?.data ?? []).filter((c) => {
      if (categoryFilter && c.categoryId !== categoryFilter) return false;
      if (industryFilter && c.industry?.toLowerCase() !== industryFilter.toLowerCase()) return false;
      if (countryFilter && c.country?.toLowerCase() !== countryFilter.toLowerCase()) return false;
      return true;
    });
  }, [list.data, categoryFilter, industryFilter, countryFilter]);

  const industryOptions = Array.from(new Set((list.data?.data ?? []).map((c) => c.industry).filter(Boolean))) as string[];
  const countryOptions = Array.from(new Set((list.data?.data ?? []).map((c) => c.country).filter(Boolean))) as string[];

  const columns: Column<Contact>[] = [
    {
      key: "name",
      header: "Contact",
      sortValue: (r) => r.name,
      render: (r) => (
        <div className="flex items-center gap-2.5 min-w-0">
          <Avatar name={r.name} size={28} />
          <div className="min-w-0">
            <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
              {r.name}
            </p>
            <p className="text-xs truncate" style={{ color: "var(--ink-muted)" }}>
              {r.title ?? "—"} {companyName(r.companyId) ? `· ${companyName(r.companyId)}` : ""}
            </p>
          </div>
        </div>
      ),
    },
    {
      key: "category",
      header: "Category",
      render: (r) => {
        const cat = categoryOf(r.categoryId);
        return cat ? (
          <span className="badge" style={{ backgroundColor: `${cat.color || "#999"}22`, color: cat.color || "var(--ink-secondary)" }}>
            {cat.name}
          </span>
        ) : (
          <span className="badge-neutral">—</span>
        );
      },
    },
    {
      key: "geo",
      header: "Industry / Geography",
      render: (r) => (
        <span className="text-sm truncate" style={{ color: "var(--ink-secondary)" }}>
          {[r.industry, r.country, r.state].filter(Boolean).join(" · ") || "—"}
        </span>
      ),
    },
    { key: "status", header: "Status", sortValue: (r) => r.status, render: (r) => <StageBadge value={r.status} /> },
    {
      key: "contact",
      header: "Contact info",
      render: (r) => (
        <div className="flex items-center gap-3 text-xs" style={{ color: "var(--ink-secondary)" }}>
          {r.email && (
            <a href={`mailto:${r.email}`} onClick={(e) => e.stopPropagation()} className="inline-flex items-center gap-1 hover:underline">
              <Mail size={12} /> {r.email}
            </a>
          )}
          {r.phone && (
            <span className="inline-flex items-center gap-1">
              <Phone size={12} /> {r.phone}
            </span>
          )}
        </div>
      ),
    },
    {
      key: "lastContactedAt",
      header: "Last contacted",
      sortValue: (r) => r.lastContactedAt ?? "",
      render: (r) => <span className="text-sm">{formatDate(r.lastContactedAt)}</span>,
    },
    {
      key: "owner",
      header: "Owner",
      render: (r) =>
        r.ownerId ? (
          <div className="flex items-center gap-2">
            <Avatar name={userName(r.ownerId)} size={22} />
            <span className="text-sm truncate">{userName(r.ownerId)}</span>
          </div>
        ) : (
          <span className="badge-neutral">Unassigned</span>
        ),
    },
  ];

  const closeDrawers = () => {
    setSelected(null);
    setCreating(false);
  };

  const save = () => {
    if (selected) {
      update.mutate({ id: selected.id, payload: draft }, { onSuccess: closeDrawers });
    } else {
      create.mutate(draft, { onSuccess: closeDrawers });
    }
  };

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Contacts"
        description={`${filtered.length} of ${list.data?.count ?? 0} people`}
        icon={<UserSquare2 size={20} />}
        actions={
          <>
            <button className="btn-secondary" onClick={() => setCategoriesOpen(true)}>
              <Settings2 size={15} /> Categories
            </button>
            <button className="btn-primary" onClick={() => setCreating(true)}>
              <Plus size={15} /> New contact
            </button>
          </>
        }
      />

      <div className="flex flex-wrap items-center gap-2">
        <SearchInput value={search} onChange={setSearch} placeholder="Search contacts…" className="w-full sm:w-64" />
        <div className="inline-flex items-center gap-1.5 text-xs" style={{ color: "var(--ink-muted)" }}>
          <Filter size={13} /> Filter:
        </div>
        <select className="select w-auto" value={categoryFilter} onChange={(e) => setCategoryFilter(e.target.value ? Number(e.target.value) : "")}>
          <option value="">All categories</option>
          {categories.data?.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
        <select className="select w-auto" value={industryFilter} onChange={(e) => setIndustryFilter(e.target.value)}>
          <option value="">All industries</option>
          {industryOptions.map((i) => (
            <option key={i} value={i}>
              {i}
            </option>
          ))}
        </select>
        <select className="select w-auto" value={countryFilter} onChange={(e) => setCountryFilter(e.target.value)}>
          <option value="">All countries</option>
          {countryOptions.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
      </div>

      <DataTable
        columns={columns}
        rows={filtered}
        loading={list.isLoading}
        onRowClick={setSelected}
        emptyTitle="No contacts yet"
        emptyDescription="Add the people you work with at your accounts."
        emptyAction={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New contact
          </button>
        }
      />

      <Drawer
        open={!!selected || creating}
        onClose={closeDrawers}
        title={selected ? selected.name : "New contact"}
        headerExtra={
          <>
            {selected?.linkedin && (
              <a
                href={selected.linkedin.startsWith("http") ? selected.linkedin : `https://${selected.linkedin}`}
                target="_blank"
                rel="noreferrer"
                className="btn-icon"
                title="Open LinkedIn"
              >
                <Linkedin size={16} />
              </a>
            )}
            {selected && (
              <button
                className="btn-icon"
                style={{ color: "var(--critical)" }}
                title="Move to trash"
                onClick={async () => {
                  const ok = await confirm("Delete this contact?", `"${selected.name}" will be moved to trash.`);
                  if (ok) remove.mutate({ id: selected.id, reason: "Deleted from contact panel" }, { onSuccess: closeDrawers });
                }}
              >
                <Trash2 size={16} />
              </button>
            )}
          </>
        }
      >
        <ContactForm value={draft} onChange={(p) => setDraft((d) => ({ ...d, ...p }))} />
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            Cancel
          </button>
          <button className="btn-primary" disabled={!draft.name} onClick={save}>
            {selected ? "Save changes" : "Create contact"}
          </button>
        </div>
      </Drawer>

      <CategoryManager open={categoriesOpen} onClose={() => setCategoriesOpen(false)} />
      {confirmNode}
    </div>
  );
}

const CAT_COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#4a3aa7"];

function CategoryManager({ open, onClose }: { open: boolean; onClose: () => void }) {
  const categories = useContactCategories();
  const qc = useQueryClient();
  const [draft, setDraft] = useState<Partial<ContactCategory>>({ color: CAT_COLORS[0] });
  const { confirm, node: confirmNode } = useConfirm();

  const create = useMutation({
    mutationFn: async () => api.post("/contact-categories", draft),
    onSuccess: () => {
      toast.success("Category added");
      qc.invalidateQueries({ queryKey: ["contact-categories"] });
      setDraft({ color: CAT_COLORS[Math.floor(Math.random() * CAT_COLORS.length)] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/contact-categories/${id}`),
    onSuccess: () => {
      toast.success("Category removed");
      qc.invalidateQueries({ queryKey: ["contact-categories"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  return (
    <Modal open={open} onClose={onClose} title="Contact categories" width="26rem">
      <p className="text-xs mb-4" style={{ color: "var(--ink-muted)" }}>
        Used to segment contacts for WhatsApp campaigns and audience building. A starter set is seeded automatically by industry.
      </p>
      <div className="space-y-1.5 max-h-64 overflow-y-auto thin-scroll mb-4">
        {categories.data?.map((c) => (
          <div key={c.id} className="flex items-center justify-between py-1.5 px-2 rounded-lg hover:bg-[rgb(var(--panel-border)/0.05)]">
            <span className="flex items-center gap-2 text-sm">
              <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: c.color || "#999" }} />
              {c.name}
              {c.system && <span className="badge-neutral">built-in</span>}
            </span>
            {!c.system && (
              <button className="btn-icon h-7 w-7" style={{ color: "var(--critical)" }} onClick={() => remove.mutate(c.id)}>
                <Trash2 size={13} />
              </button>
            )}
          </div>
        ))}
      </div>
      <div className="pt-3 hairline border-t space-y-3">
        <p className="field-label">Add category</p>
        <FieldGrid>
          <Field label="Name" className="sm:col-span-2">
            <input className="input" value={draft.name ?? ""} onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))} />
          </Field>
          <Field label="Industry default">
            <input className="input" value={draft.industry ?? ""} onChange={(e) => setDraft((d) => ({ ...d, industry: e.target.value }))} />
          </Field>
          <Field label="Color">
            <input type="color" className="input h-10" value={draft.color ?? CAT_COLORS[0]} onChange={(e) => setDraft((d) => ({ ...d, color: e.target.value }))} />
          </Field>
        </FieldGrid>
        <button className="btn-primary w-full" disabled={!draft.name?.trim()} onClick={() => create.mutate()}>
          <Plus size={14} /> Add category
        </button>
      </div>
      {confirmNode}
    </Modal>
  );
}
