import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, getList } from "./api";
import { toast } from "./toast";

/**
 * Generic CRUD hook for the many `/api/<resource>` endpoints that all share
 * the same REST shape (list -> {count,data}|array, GET/POST/PUT/DELETE /{id}).
 * Bespoke features (claim, convert, pipeline...) are added per-module on top
 * of this, not instead of it.
 */
export function useEntityCrud<T extends { id: number }>(
  resource: string,
  options?: { params?: Record<string, unknown>; label?: string }
) {
  const qc = useQueryClient();
  const key = [resource, options?.params ?? {}];
  const label = options?.label ?? "Record";

  const list = useQuery({
    queryKey: key,
    queryFn: () => getList<T>(`/${resource}`, options?.params),
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: [resource] });

  const create = useMutation({
    mutationFn: async (payload: Partial<T>) => {
      const res = await api.post<T>(`/${resource}`, payload);
      return res.data;
    },
    onSuccess: () => {
      invalidate();
      toast.success(`${label} created`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const update = useMutation({
    mutationFn: async ({ id, payload }: { id: number; payload: Partial<T> }) => {
      const res = await api.put<T>(`/${resource}/${id}`, payload);
      return res.data;
    },
    onSuccess: () => {
      invalidate();
      toast.success(`${label} updated`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async ({ id, reason }: { id: number; reason?: string }) => {
      await api.delete(`/${resource}/${id}`, { params: reason ? { reason } : undefined });
    },
    onSuccess: () => {
      invalidate();
      toast.success(`${label} moved to trash`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  return { list, create, update, remove, invalidate };
}

export function useUsersLookup() {
  return useQuery({
    queryKey: ["users"],
    queryFn: async () => {
      const res = await api.get("/users");
      return res.data as { id: number; name: string; initials: string | null; color: string | null; role: string }[];
    },
    staleTime: 5 * 60_000,
  });
}

export function useContactCategories() {
  return useQuery({
    queryKey: ["contact-categories"],
    queryFn: async () => (await api.get("/contact-categories")).data as import("./types").ContactCategory[],
    staleTime: 60_000,
  });
}

export function useCompaniesLookup() {
  return useQuery({
    queryKey: ["companies", { limit: 500 }],
    queryFn: () => getList<{ id: number; name: string }>("/companies", { limit: 500 }),
    staleTime: 60_000,
  });
}
