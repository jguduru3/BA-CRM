import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Linkedin, Mail, Phone, Plus, Settings2, Trash2, UserSquare2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { useCompaniesLookup, useContactCategories, useUsersLookup } from "@/lib/hooks";
import { formatDate, formatDateTime } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Contact, ContactCategory } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { StageBadge } from "@/components/ui/Badge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { useReason } from "@/components/ui/ReasonDialog";
import { SearchInput } from "@/components/ui/SearchInput";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { safeHref } from "@/components/ui/SafeLink";
import { changedFields, ExportButton, FilterSelect, ListToolbar, saveErrorMessage } from "@/features/crm/ListParts";
import { useServerList } from "@/features/crm/useServerList";
import { ContactDraft, ContactForm } from "./ContactForm";
import { AttachmentList } from "@/components/ui/AttachmentList";
import { RecordPagePanel } from "@/features/recordpages/RecordPagePanel";

export default function ContactsPage() {
  const [params, setParams] = useSearchParams();
  const [categoryFilter, setCategoryFilter] = useState("");
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
  const { askReason, node: reasonNode } = useReason();
  const role = useAuthStore((s) => s.user?.role);
  const readOnly = isReadOnly(role);
  const manager = isManagerUp(role);

  const table = useServerList<Contact>("contacts", {
    filters: { categoryId: categoryFilter, industry: industryFilter, country: countryFilter },
    defaultSort: { key: "name", dir: "asc" },
  });

  useEffect(() => {
    setDraft(selected ?? { status: "Active" });
  }, [selected, creating]);

  useEffect(() => {
    const id = params.get("open");
    if (!id) return;
    api
      .get<Contact>(`/contacts/${id}`)
      .then((r) => setSelected(r.data))
      .catch((e) => toast.error(e instanceof Error ? e.message : "Contact not found"));
    params.delete("open");
    setParams(params, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name;
  const companyName = (id: number | null) => companies.data?.data.find((c) => c.id === id)?.name;
  const categoryOf = (id: number | null) => categories.data?.find((c) => c.id === id);

  // Filter choices come from what's on screen, plus whatever is currently selected.
  const optionsFrom = (pick: (c: Contact) => string | null, current: string) =>
    Array.from(new Set([...table.rows.map(pick), current].filter(Boolean))) as string[];

  const closeDrawers = () => {
    setSelected(null);
    setCreating(false);
  };
  const invalidate = () => qc.invalidateQueries({ queryKey: ["contacts"] });

  const save = useMutation({
    mutationFn: async () =>
      selected
        ? (await api.patch<Contact>(`/contacts/${selected.id}`, changedFields(selected, draft))).data
        : (await api.post<Contact>("/contacts", draft)).data,
    onSuccess: () => {
      toast.success(selected ? "Contact updated" : "Contact created");
      invalidate();
      closeDrawers();
    },
    onError: (e: Error) => toast.error(saveErrorMessage(e, "contact")),
  });

  const remove = useMutation({
    mutationFn: async ({ id, reason }: { id: number; reason: string }) => api.delete(`/contacts/${id}`, { params: { reason } }),
    onSuccess: () => {
      toast.success("Contact moved to trash");
      invalidate();
      closeDrawers();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const columns: Column<Contact>[] = [
    {
      key: "name",
      header: "Contact",
      sortKey: "name",
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
      hideOnMobile: true,
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
      hideOnMobile: true,
      render: (r) => (
        <span className="text-sm truncate" style={{ color: "var(--ink-secondary)" }}>
          {[r.industry, r.country, r.state].filter(Boolean).join(" · ") || "—"}
        </span>
      ),
    },
    { key: "status", header: "Status", sortKey: "status", render: (r) => <StageBadge value={r.status} /> },
    {
      key: "contact",
      header: "Contact info",
      hideOnMobile: true,
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
      sortKey: "lastContactedAt",
      hideOnMobile: true,
      render: (r) => <span className="text-sm">{formatDate(r.lastContactedAt)}</span>,
    },
    {
      key: "owner",
      header: "Owner",
      hideOnMobile: true,
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

  const filtered = !!(table.search || categoryFilter || industryFilter || countryFilter);

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Contacts"
        description={table.query.data ? `${table.total.toLocaleString()} people${filtered ? " match" : ""}` : "The people you work with"}
        icon={<UserSquare2 size={20} />}
        actions={
          <>
            {manager && (
              <button className="btn-secondary" onClick={() => setCategoriesOpen(true)}>
                <Settings2 size={15} /> <span className="hidden md:inline">Categories</span>
              </button>
            )}
            <ExportButton type="contacts" />
            {!readOnly && (
              <button className="btn-primary" onClick={() => setCreating(true)}>
                <Plus size={15} /> <span className="hidden sm:inline">New contact</span>
              </button>
            )}
          </>
        }
      />

      <ListToolbar
        left={
          <>
            <FilterSelect
              ariaLabel="Category"
              allLabel="All categories"
              value={categoryFilter}
              onChange={setCategoryFilter}
              options={(categories.data ?? []).map((c) => ({ value: String(c.id), label: c.name }))}
            />
            <FilterSelect
              ariaLabel="Industry"
              allLabel="All industries"
              value={industryFilter}
              onChange={setIndustryFilter}
              options={optionsFrom((c) => c.industry, industryFilter)}
            />
            <FilterSelect
              ariaLabel="Country"
              allLabel="All countries"
              value={countryFilter}
              onChange={setCountryFilter}
              options={optionsFrom((c) => c.country, countryFilter)}
            />
          </>
        }
        right={<SearchInput value={table.search} onChange={table.setSearch} placeholder="Search name, email, phone…" className="w-full sm:w-72" />}
      />

      {table.query.isError ? (
        <div className="glass-card">
          <QueryError error={table.query.error} onRetry={() => table.query.refetch()} title="Couldn't load contacts" />
        </div>
      ) : (
        <DataTable
          columns={columns}
          rows={table.rows}
          loading={table.query.isLoading}
          fetching={table.query.isFetching && !table.query.isLoading}
          onRowClick={setSelected}
          pagination={table.pagination}
          sort={table.sort}
          onSortChange={table.setSort}
          emptyTitle={filtered ? "No contacts match" : "No contacts yet"}
          emptyDescription={filtered ? "Try another search or clear the filters." : "Add the people you work with at your accounts."}
          emptyAction={
            !filtered && !readOnly ? (
              <button className="btn-primary" onClick={() => setCreating(true)}>
                <Plus size={15} /> New contact
              </button>
            ) : undefined
          }
        />
      )}

      <Drawer
        open={!!selected || creating}
        onClose={closeDrawers}
        title={selected ? selected.name : "New contact"}
        subtitle={selected ? `Updated ${formatDateTime(selected.updatedAt)}` : "Add a person"}
        headerExtra={
          <>
            {selected?.linkedin && safeHref(selected.linkedin) && (
              <a href={safeHref(selected.linkedin)!} target="_blank" rel="noopener noreferrer nofollow" className="btn-icon" title="Open LinkedIn">
                <Linkedin size={16} />
              </a>
            )}
            {selected && manager && (
              <button
                className="btn-icon"
                style={{ color: "var(--critical)" }}
                title="Move to trash"
                onClick={async () => {
                  const reason = await askReason({
                    title: "Move this contact to trash?",
                    description: `"${selected.name}" can be restored from Trash later.`,
                    confirmLabel: "Move to trash",
                    danger: true,
                  });
                  if (reason) remove.mutate({ id: selected.id, reason });
                }}
              >
                <Trash2 size={16} />
              </button>
            )}
          </>
        }
      >
        <ContactForm value={draft} onChange={(p) => setDraft((d) => ({ ...d, ...p }))} />
        {selected && (
          <div className="mt-5 pt-4 hairline border-t space-y-5">
            <RecordPagePanel relatedType="Contact" relatedId={selected.id} readOnly={readOnly} />
            <AttachmentList relatedType="Contact" relatedId={selected.id} />
          </div>
        )}
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            Cancel
          </button>
          {!readOnly && (
            <button className="btn-primary" disabled={!draft.name || save.isPending} onClick={() => save.mutate()}>
              {save.isPending ? "Saving…" : selected ? "Save changes" : "Create contact"}
            </button>
          )}
        </div>
      </Drawer>

      <CategoryManager open={categoriesOpen} onClose={() => setCategoriesOpen(false)} />
      {reasonNode}
    </div>
  );
}

const CAT_COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#4a3aa7"];

function CategoryManager({ open, onClose }: { open: boolean; onClose: () => void }) {
  const categories = useContactCategories();
  const qc = useQueryClient();
  const [draft, setDraft] = useState<Partial<ContactCategory>>({ color: CAT_COLORS[0] });
  const { node: confirmNode } = useConfirm();

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
