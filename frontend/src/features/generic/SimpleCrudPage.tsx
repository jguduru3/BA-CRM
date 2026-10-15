import type { LucideIcon } from "lucide-react";
import { Plus, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useEntityCrud } from "@/lib/hooks";
import { PageHeader } from "@/components/layout/PageHeader";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { Field } from "@/components/ui/Field";
import { SearchInput } from "@/components/ui/SearchInput";
import { useConfirm } from "@/components/ui/ConfirmDialog";

export interface SimpleField {
  key: string;
  label: string;
  type: "text" | "textarea" | "number" | "date" | "datetime" | "select" | "checkbox" | "color";
  options?: string[];
  required?: boolean;
  hint?: string;
  span2?: boolean;
}

/**
 * Config-driven list+drawer CRUD page for the many backend modules that are
 * straightforward REST resources (Meetings, Notes, Departments…). Bespoke
 * pages exist where the module has real workflow beyond create/read/update/
 * delete (Leads, Opportunities, Tasks, Campaigns, Databases…).
 */
export function SimpleCrudPage<T extends { id: number }>({
  resource,
  label,
  icon: Icon,
  description,
  columns,
  fields,
  defaults,
  titleOf,
  searchable = true,
  softDelete = true,
}: {
  resource: string;
  label: string;
  icon: LucideIcon;
  description?: string;
  columns: Column<T>[];
  fields: SimpleField[];
  defaults: Partial<T>;
  titleOf: (row: T) => string;
  searchable?: boolean;
  softDelete?: boolean;
}) {
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<T | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<Partial<T>>({});
  const { confirm, node: confirmNode } = useConfirm();

  const { list, create, update, remove } = useEntityCrud<T>(resource, {
    params: searchable ? { search, limit: 300 } : { limit: 300 },
    label,
  });

  useEffect(() => {
    setDraft(selected ?? defaults);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, creating]);

  const closeDrawers = () => {
    setSelected(null);
    setCreating(false);
  };

  const save = () => {
    if (selected) update.mutate({ id: selected.id, payload: draft }, { onSuccess: closeDrawers });
    else create.mutate(draft, { onSuccess: closeDrawers });
  };

  const requiredOk = fields.filter((f) => f.required).every((f) => {
    const v = (draft as Record<string, unknown>)[f.key];
    return v !== undefined && v !== null && String(v).trim() !== "";
  });

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title={label}
        description={description ?? `${list.data?.count ?? 0} records`}
        icon={<Icon size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New {label.toLowerCase().replace(/s$/, "")}
          </button>
        }
      />
      {searchable && <SearchInput value={search} onChange={setSearch} placeholder={`Search ${label.toLowerCase()}…`} className="max-w-xs" />}

      <DataTable
        columns={columns}
        rows={list.data?.data ?? []}
        loading={list.isLoading}
        onRowClick={setSelected}
        emptyTitle={`No ${label.toLowerCase()} yet`}
        emptyAction={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New {label.toLowerCase().replace(/s$/, "")}
          </button>
        }
      />

      <Drawer
        open={!!selected || creating}
        onClose={closeDrawers}
        title={selected ? titleOf(selected) : `New ${label.toLowerCase().replace(/s$/, "")}`}
        headerExtra={
          selected &&
          softDelete && (
            <button
              className="btn-icon"
              style={{ color: "var(--critical)" }}
              title="Move to trash"
              onClick={async () => {
                const ok = await confirm(`Delete this ${label.toLowerCase().replace(/s$/, "")}?`, `"${titleOf(selected)}" will be moved to trash.`);
                if (ok) remove.mutate({ id: selected.id, reason: `Deleted from ${label} panel` }, { onSuccess: closeDrawers });
              }}
            >
              <Trash2 size={16} />
            </button>
          )
        }
      >
        <div className="space-y-4">
          {fields.map((f) => (
            <Field key={f.key} label={f.label} required={f.required} hint={f.hint} className={f.span2 ? "sm:col-span-2" : undefined}>
              {f.type === "textarea" ? (
                <textarea
                  className="textarea"
                  value={String((draft as Record<string, unknown>)[f.key] ?? "")}
                  onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))}
                />
              ) : f.type === "select" ? (
                <select
                  className="select"
                  value={String((draft as Record<string, unknown>)[f.key] ?? "")}
                  onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))}
                >
                  <option value="">—</option>
                  {f.options?.map((o) => (
                    <option key={o}>{o}</option>
                  ))}
                </select>
              ) : f.type === "checkbox" ? (
                <input
                  type="checkbox"
                  className="checkbox"
                  checked={!!(draft as Record<string, unknown>)[f.key]}
                  onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.checked }))}
                />
              ) : f.type === "color" ? (
                <input
                  type="color"
                  className="input h-10"
                  value={String((draft as Record<string, unknown>)[f.key] ?? "#1ca3e6")}
                  onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))}
                />
              ) : (
                <input
                  className="input"
                  type={f.type === "datetime" ? "datetime-local" : f.type}
                  value={String((draft as Record<string, unknown>)[f.key] ?? "")}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, [f.key]: f.type === "number" ? Number(e.target.value) : e.target.value }))
                  }
                />
              )}
            </Field>
          ))}
        </div>
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            Cancel
          </button>
          <button className="btn-primary" disabled={!requiredOk} onClick={save}>
            {selected ? "Save changes" : "Create"}
          </button>
        </div>
      </Drawer>
      {confirmNode}
    </div>
  );
}
