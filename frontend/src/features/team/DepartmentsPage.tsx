import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Building, CornerDownRight, Network, Pencil, Plus, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { api } from "@/lib/api";
import { isAdmin, useAuthStore } from "@/lib/auth";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { SearchInput } from "@/components/ui/SearchInput";
import { SkeletonRows } from "@/components/ui/Skeleton";

/** Shape returned by GET /api/departments (the entity, serialized as-is). */
export interface DepartmentRow {
  id: number;
  organizationId: number;
  name: string;
  description: string | null;
  headId: number | null;
  parentId: number | null;
  active: boolean;
}

interface UserLite {
  id: number;
  name: string;
  color: string | null;
  active?: boolean;
}

interface Draft {
  name: string;
  description: string;
  headId: string;
  parentId: string;
  active: boolean;
}

const EMPTY: Draft = { name: "", description: "", headId: "", parentId: "", active: true };

/** Ids of `id` and every department below it — none of them may become its parent. */
function descendantsOf(id: number, rows: DepartmentRow[]): Set<number> {
  const out = new Set<number>([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const r of rows) {
      if (r.parentId != null && out.has(r.parentId) && !out.has(r.id)) {
        out.add(r.id);
        grew = true;
      }
    }
  }
  return out;
}

/** Rows ordered as a tree (parents first, children indented under them). */
function asTree(rows: DepartmentRow[]): { row: DepartmentRow; depth: number }[] {
  const ids = new Set(rows.map((r) => r.id));
  const byParent = new Map<number | null, DepartmentRow[]>();
  for (const r of rows) {
    const key = r.parentId != null && ids.has(r.parentId) ? r.parentId : null;
    byParent.set(key, [...(byParent.get(key) ?? []), r]);
  }
  for (const list of byParent.values()) list.sort((a, b) => a.name.localeCompare(b.name));
  const out: { row: DepartmentRow; depth: number }[] = [];
  const seen = new Set<number>();
  const walk = (parent: number | null, depth: number) => {
    for (const r of byParent.get(parent) ?? []) {
      if (seen.has(r.id)) continue;
      seen.add(r.id);
      out.push({ row: r, depth });
      walk(r.id, depth + 1);
    }
  };
  walk(null, 0);
  // Anything left over is part of a loop in legacy data — still show it.
  for (const r of rows) if (!seen.has(r.id)) out.push({ row: r, depth: 0 });
  return out;
}

export default function DepartmentsPage() {
  const role = useAuthStore((s) => s.user?.role);
  const canManage = isAdmin(role);
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();
  const [search, setSearch] = useState("");
  const [editing, setEditing] = useState<DepartmentRow | "new" | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);

  const list = useQuery({
    queryKey: ["departments"],
    queryFn: async () => {
      const res = await api.get<DepartmentRow[] | { data: DepartmentRow[] }>("/departments");
      return Array.isArray(res.data) ? res.data : res.data.data;
    },
  });
  // People are optional decoration (head names) — a failure here must not block the page.
  const users = useQuery({
    queryKey: ["users"],
    queryFn: async () => (await api.get<UserLite[]>("/users")).data,
    staleTime: 5 * 60_000,
  });

  const rows = useMemo(() => list.data ?? [], [list.data]);
  const userById = useMemo(() => new Map((users.data ?? []).map((u) => [u.id, u])), [users.data]);
  const deptById = useMemo(() => new Map(rows.map((d) => [d.id, d])), [rows]);

  const visible = useMemo(() => {
    const q = search.trim().toLowerCase();
    const tree = asTree(rows);
    if (!q) return tree;
    return tree
      .filter(({ row }) => row.name.toLowerCase().includes(q) || (row.description ?? "").toLowerCase().includes(q))
      .map((t) => ({ ...t, depth: 0 }));
  }, [rows, search]);

  const invalidate = () => qc.invalidateQueries({ queryKey: ["departments"] });

  const save = useMutation({
    mutationFn: async () => {
      const body = {
        name: draft.name.trim(),
        description: draft.description.trim(),
        headId: draft.headId ? Number(draft.headId) : null,
        parentId: draft.parentId ? Number(draft.parentId) : null,
        active: draft.active,
      };
      if (editing === "new") return (await api.post<DepartmentRow>("/departments", body)).data;
      return (await api.put<DepartmentRow>(`/departments/${(editing as DepartmentRow).id}`, body)).data;
    },
    onSuccess: () => {
      toast.success(editing === "new" ? "Department created" : "Department updated");
      invalidate();
      setEditing(null);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/departments/${id}`),
    onSuccess: () => {
      toast.success("Department deleted");
      invalidate();
    },
    // 409: still has sub-departments or teams — the server explains which.
    onError: (e: Error) => toast.error(e.message),
  });

  function openEditor(d: DepartmentRow | "new") {
    setDraft(
      d === "new"
        ? EMPTY
        : {
            name: d.name,
            description: d.description ?? "",
            headId: d.headId ? String(d.headId) : "",
            parentId: d.parentId ? String(d.parentId) : "",
            active: d.active !== false,
          }
    );
    setEditing(d);
  }

  async function onDelete(d: DepartmentRow) {
    const ok = await confirm(
      `Delete “${d.name}”?`,
      "This removes the department permanently. Departments that still have sub-departments or teams can't be deleted."
    );
    if (ok) remove.mutate(d.id);
  }

  const blockedParents = editing && editing !== "new" ? descendantsOf(editing.id, rows) : new Set<number>();
  const editingRow = editing && editing !== "new" ? editing : null;
  // The API merges partial updates, so an existing head/parent can be changed but not cleared.
  const cannotClearHead = !!editingRow?.headId && !draft.headId;
  const cannotClearParent = !!editingRow?.parentId && !draft.parentId;
  const activeCount = rows.filter((r) => r.active !== false).length;

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Departments"
        description={
          list.isSuccess
            ? `${rows.length} department${rows.length === 1 ? "" : "s"} · ${activeCount} active`
            : "How your organization is structured"
        }
        icon={<Network size={20} />}
        actions={
          canManage && (
            <button className="btn-primary" onClick={() => openEditor("new")}>
              <Plus size={15} /> New department
            </button>
          )
        }
      />

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load departments" />
        </div>
      ) : list.isLoading ? (
        <div className="glass-card overflow-hidden">
          <SkeletonRows rows={5} cols={4} />
        </div>
      ) : rows.length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={Network}
            title="No departments yet"
            description={
              canManage
                ? "Model your org chart — Sales, Marketing, Support — and nest teams under them."
                : "Your administrator hasn't set up departments yet."
            }
            action={
              canManage && (
                <button className="btn-primary" onClick={() => openEditor("new")}>
                  <Plus size={15} /> Create the first department
                </button>
              )
            }
          />
        </div>
      ) : (
        <>
          {rows.length > 6 && (
            <SearchInput value={search} onChange={setSearch} placeholder="Search departments…" className="w-full sm:max-w-xs" />
          )}
          {visible.length === 0 ? (
            <div className="glass-card">
              <EmptyState icon={Network} title="No matches" description="Try a different search." />
            </div>
          ) : (
            <>
              {/* Table on sm+ */}
              <div className="hidden md:block glass-card overflow-hidden">
                <div className="overflow-x-auto thin-scroll">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Department</th>
                        <th>Head</th>
                        <th>Parent</th>
                        <th>Status</th>
                        {canManage && <th className="w-24" aria-label="Actions" />}
                      </tr>
                    </thead>
                    <tbody>
                      {visible.map(({ row, depth }) => {
                        const head = row.headId ? userById.get(row.headId) : undefined;
                        const parent = row.parentId ? deptById.get(row.parentId) : undefined;
                        return (
                          <tr
                            key={row.id}
                            className={canManage ? undefined : "cursor-default"}
                            onClick={canManage ? () => openEditor(row) : undefined}
                          >
                            <td>
                              <div className="flex items-start gap-2 min-w-0" style={{ paddingLeft: depth * 20 }}>
                                {depth > 0 ? (
                                  <CornerDownRight size={14} className="mt-0.5 shrink-0" style={{ color: "var(--ink-muted)" }} />
                                ) : (
                                  <Building size={14} className="mt-0.5 shrink-0" style={{ color: "var(--brand-600)" }} />
                                )}
                                <div className="min-w-0">
                                  <p className="font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                                    {row.name}
                                  </p>
                                  {row.description && (
                                    <p className="text-xs truncate max-w-md" style={{ color: "var(--ink-muted)" }}>
                                      {row.description}
                                    </p>
                                  )}
                                </div>
                              </div>
                            </td>
                            <td>
                              <HeadCell head={head} headId={row.headId} />
                            </td>
                            <td style={{ color: "var(--ink-secondary)" }}>{parent?.name ?? "—"}</td>
                            <td>{row.active !== false ? <span className="badge-good">Active</span> : <span className="badge-neutral">Inactive</span>}</td>
                            {canManage && (
                              <td className="text-right whitespace-nowrap">
                                <button
                                  className="btn-icon"
                                  aria-label={`Edit ${row.name}`}
                                  title="Edit"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    openEditor(row);
                                  }}
                                >
                                  <Pencil size={15} />
                                </button>
                                <button
                                  className="btn-icon"
                                  aria-label={`Delete ${row.name}`}
                                  title="Delete"
                                  style={{ color: "var(--critical)" }}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    void onDelete(row);
                                  }}
                                >
                                  <Trash2 size={15} />
                                </button>
                              </td>
                            )}
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>

              {/* Cards on phones */}
              <ul className="md:hidden space-y-3">
                {visible.map(({ row }) => {
                  const head = row.headId ? userById.get(row.headId) : undefined;
                  const parent = row.parentId ? deptById.get(row.parentId) : undefined;
                  return (
                    <li key={row.id} className="glass-card p-4">
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <p className="font-semibold text-sm truncate" style={{ color: "var(--ink-primary)" }}>
                            {row.name}
                          </p>
                          {parent && (
                            <p className="text-xs mt-0.5 flex items-center gap-1" style={{ color: "var(--ink-muted)" }}>
                              <CornerDownRight size={12} /> {parent.name}
                            </p>
                          )}
                        </div>
                        {row.active !== false ? <span className="badge-good">Active</span> : <span className="badge-neutral">Inactive</span>}
                      </div>
                      {row.description && (
                        <p className="text-sm mt-2" style={{ color: "var(--ink-secondary)" }}>
                          {row.description}
                        </p>
                      )}
                      <div className="flex items-center justify-between gap-2 mt-3 pt-3 hairline border-t">
                        <HeadCell head={head} headId={row.headId} />
                        {canManage && (
                          <div className="flex items-center gap-1">
                            <button className="btn-icon h-11 w-11" aria-label={`Edit ${row.name}`} onClick={() => openEditor(row)}>
                              <Pencil size={16} />
                            </button>
                            <button
                              className="btn-icon h-11 w-11"
                              aria-label={`Delete ${row.name}`}
                              style={{ color: "var(--critical)" }}
                              onClick={() => void onDelete(row)}
                            >
                              <Trash2 size={16} />
                            </button>
                          </div>
                        )}
                      </div>
                    </li>
                  );
                })}
              </ul>
            </>
          )}
        </>
      )}

      <Modal
        open={!!editing}
        onClose={() => setEditing(null)}
        title={editing === "new" ? "New department" : "Edit department"}
        width="34rem"
        footer={
          <>
            <button className="btn-ghost" onClick={() => setEditing(null)}>
              Cancel
            </button>
            <button
              className="btn-primary"
              disabled={!draft.name.trim() || save.isPending || cannotClearHead || cannotClearParent}
              onClick={() => save.mutate()}
            >
              {save.isPending ? "Saving…" : editing === "new" ? "Create department" : "Save changes"}
            </button>
          </>
        }
      >
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (draft.name.trim() && !save.isPending) save.mutate();
          }}
        >
          <Field label="Name" required>
            <input
              className="input"
              autoFocus
              maxLength={150}
              value={draft.name}
              onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))}
              placeholder="e.g. Enterprise Sales"
            />
          </Field>
          <Field label="Description">
            <textarea
              className="textarea"
              maxLength={1000}
              value={draft.description}
              onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))}
              placeholder="What this department is responsible for"
            />
          </Field>
          <FieldGrid>
            <Field
              label="Head"
              hint={cannotClearHead ? "A head can be changed but not removed." : users.isError ? "Couldn't load people." : undefined}
            >
              <select
                className="select"
                value={draft.headId}
                onChange={(e) => setDraft((d) => ({ ...d, headId: e.target.value }))}
              >
                <option value="">— None —</option>
                {(users.data ?? [])
                  .filter((u) => u.active !== false || String(u.id) === draft.headId)
                  .map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.name}
                    </option>
                  ))}
              </select>
            </Field>
            <Field label="Parent department" hint={cannotClearParent ? "Move it under another department instead." : undefined}>
              <select
                className="select"
                value={draft.parentId}
                onChange={(e) => setDraft((d) => ({ ...d, parentId: e.target.value }))}
              >
                <option value="">— Top level —</option>
                {rows
                  .filter((r) => !blockedParents.has(r.id))
                  .sort((a, b) => a.name.localeCompare(b.name))
                  .map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.name}
                    </option>
                  ))}
              </select>
            </Field>
          </FieldGrid>
          <label className="flex items-center gap-2.5 text-sm cursor-pointer select-none" style={{ color: "var(--ink-secondary)" }}>
            <input
              type="checkbox"
              className="checkbox"
              checked={draft.active}
              onChange={(e) => setDraft((d) => ({ ...d, active: e.target.checked }))}
            />
            Active
          </label>
        </form>
      </Modal>
      {confirmNode}
    </div>
  );
}

function HeadCell({ head, headId }: { head: UserLite | undefined; headId: number | null }) {
  if (!headId) return <span className="text-sm" style={{ color: "var(--ink-muted)" }}>No head</span>;
  const name = head?.name ?? `User #${headId}`;
  return (
    <span className="inline-flex items-center gap-2 min-w-0">
      <Avatar name={name} color={head?.color ?? null} size={24} />
      <span className="text-sm truncate" style={{ color: "var(--ink-secondary)" }}>
        {name}
      </span>
    </span>
  );
}
