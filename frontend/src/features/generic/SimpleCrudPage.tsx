import type { LucideIcon } from "lucide-react";
import { Plus, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useEntityCrud } from "@/lib/hooks";
import { isReadOnly, useAuthStore } from "@/lib/auth";
import { PageHeader } from "@/components/layout/PageHeader";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { Field } from "@/components/ui/Field";
import { QueryError } from "@/components/ui/QueryError";
import { SearchInput } from "@/components/ui/SearchInput";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { fromLocalInput, toLocalInput } from "./datetime";

export interface SimpleField {
  key: string;
  label: string;
  type: "text" | "textarea" | "number" | "date" | "datetime" | "select" | "checkbox" | "color" | "url";
  options?: string[];
  required?: boolean;
  hint?: string;
  span2?: boolean;
}

/**
 * Config-driven list+drawer CRUD page for the many backend modules that are
 * straightforward REST resources (Meetings, Departments…). Bespoke pages exist
 * where the module has real workflow beyond create/read/update/delete.
 *
 * Failed loads show QueryError with Retry; write actions are hidden for
 * read-only roles (or when `canWrite` is false) and the drawer becomes a
 * read-only view. `renderDetail` adds extra content under the form (e.g.
 * linkified text, attachments).
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
  canWrite,
  renderDetail,
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
  canWrite?: boolean;
  renderDetail?: (row: T) => React.ReactNode;
}) {
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<T | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<Partial<T>>({});
  const { confirm, node: confirmNode } = useConfirm();
  const role = useAuthStore((s) => s.user?.role);
  const writable = (canWrite ?? true) && !isReadOnly(role);
  const singular = label.toLowerCase().replace(/s$/, "");

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
    if (!writable) return;
    if (selected) update.mutate({ id: selected.id, payload: draft }, { onSuccess: closeDrawers });
    else create.mutate(draft, { onSuccess: closeDrawers });
  };

  const requiredOk = fields.filter((f) => f.required).every((f) => {
    const v = (draft as Record<string, unknown>)[f.key];
    return v !== undefined && v !== null && String(v).trim() !== "";
  });

  const value = (key: string) => (draft as Record<string, unknown>)[key];
  const set = (key: string, v: unknown) => setDraft((d) => ({ ...d, [key]: v }));

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={label}
        description={description ?? `${list.data?.count ?? 0} records`}
        icon={<Icon size={20} />}
        actions={
          writable && (
            <button className="btn-primary" onClick={() => setCreating(true)}>
              <Plus size={15} /> New {singular}
            </button>
          )
        }
      />
      {searchable && <SearchInput value={search} onChange={setSearch} placeholder={`Search ${label.toLowerCase()}…`} className="max-w-xs" />}

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title={`Couldn't load ${label.toLowerCase()}`} />
        </div>
      ) : (
        <DataTable
          columns={columns}
          rows={list.data?.data ?? []}
          loading={list.isLoading}
          onRowClick={setSelected}
          emptyTitle={`No ${label.toLowerCase()} yet`}
          emptyAction={
            writable && (
              <button className="btn-primary" onClick={() => setCreating(true)}>
                <Plus size={15} /> New {singular}
              </button>
            )
          }
        />
      )}

      <Drawer
        open={!!selected || creating}
        onClose={closeDrawers}
        title={selected ? titleOf(selected) : `New ${singular}`}
        headerExtra={
          selected &&
          softDelete &&
          writable && (
            <button
              className="btn-icon"
              style={{ color: "var(--critical)" }}
              title="Move to trash"
              aria-label="Move to trash"
              onClick={async () => {
                const ok = await confirm(`Delete this ${singular}?`, `"${titleOf(selected)}" will be moved to trash.`);
                if (ok) remove.mutate({ id: selected.id, reason: `Deleted from ${label} panel` }, { onSuccess: closeDrawers });
              }}
            >
              <Trash2 size={16} />
            </button>
          )
        }
      >
        <fieldset disabled={!writable} className="space-y-4 min-w-0">
          {fields.map((f) => (
            <Field key={f.key} label={f.label} required={f.required} hint={f.hint} className={f.span2 ? "sm:col-span-2" : undefined}>
              {f.type === "textarea" ? (
                <textarea className="textarea" value={String(value(f.key) ?? "")} onChange={(e) => set(f.key, e.target.value)} />
              ) : f.type === "select" ? (
                <select className="select" value={String(value(f.key) ?? "")} onChange={(e) => set(f.key, e.target.value)}>
                  <option value="">—</option>
                  {f.options?.map((o) => (
                    <option key={o}>{o}</option>
                  ))}
                </select>
              ) : f.type === "checkbox" ? (
                <input type="checkbox" className="checkbox" checked={!!value(f.key)} onChange={(e) => set(f.key, e.target.checked)} />
              ) : f.type === "color" ? (
                <input type="color" className="input h-10" value={String(value(f.key) ?? "#1ca3e6")} onChange={(e) => set(f.key, e.target.value)} />
              ) : f.type === "datetime" ? (
                <input
                  className="input"
                  type="datetime-local"
                  value={toLocalInput(value(f.key))}
                  onChange={(e) => set(f.key, fromLocalInput(e.target.value))}
                />
              ) : (
                <input
                  className="input"
                  type={f.type === "url" ? "url" : f.type}
                  inputMode={f.type === "url" ? "url" : undefined}
                  value={String(value(f.key) ?? "")}
                  onChange={(e) =>
                    set(f.key, f.type === "number" ? (e.target.value === "" ? null : Number(e.target.value)) : e.target.value)
                  }
                />
              )}
            </Field>
          ))}
        </fieldset>
        {selected && renderDetail && <div className="mt-4">{renderDetail(selected)}</div>}
        <div className="flex items-center justify-end gap-2 mt-4 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            {writable ? "Cancel" : "Close"}
          </button>
          {writable && (
            <button className="btn-primary" disabled={!requiredOk || create.isPending || update.isPending} onClick={save}>
              {selected ? "Save changes" : "Create"}
            </button>
          )}
        </div>
      </Drawer>
      {confirmNode}
    </div>
  );
}
