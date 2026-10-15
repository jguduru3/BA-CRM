import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { getList } from "@/lib/api";
import { sortParam, type ServerSort } from "@/components/ui/DataTable";
import type { PagedList } from "./types";

/** Debounces a fast-changing value (search box) so we don't query on every keystroke. */
export function useDebounced<T>(value: T, delay = 300): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return v;
}

/**
 * Server-side list state for the CRM tables: page, page size, sort, search and
 * filters all go to the API (`page`, `size`, `sort`, `search`, ...), which
 * filters/sorts/paginates in the database and answers {count, data}.
 */
export function useServerList<T>(
  resource: string,
  {
    filters = {},
    pageSize = 25,
    defaultSort = null,
    enabled = true,
  }: {
    filters?: Record<string, string | number | boolean | undefined | null>;
    pageSize?: number;
    defaultSort?: ServerSort | null;
    enabled?: boolean;
  } = {}
) {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState<ServerSort | null>(defaultSort);
  const debounced = useDebounced(search.trim());

  const cleanFilters: Record<string, string | number | boolean> = {};
  for (const [k, v] of Object.entries(filters)) {
    if (v !== undefined && v !== null && v !== "" && v !== false) cleanFilters[k] = v;
  }
  const filterKey = JSON.stringify(cleanFilters);

  // Any change of what we're looking at starts again from the first page.
  useEffect(() => setPage(0), [debounced, filterKey, sort?.key, sort?.dir]);

  const params = {
    ...cleanFilters,
    search: debounced || undefined,
    page,
    size: pageSize,
    sort: sortParam(sort),
  };

  const query = useQuery({
    queryKey: [resource, "list", params],
    queryFn: () => getList<T>(`/${resource}`, params) as Promise<PagedList<T>>,
    placeholderData: keepPreviousData,
    enabled,
  });

  return {
    query,
    rows: query.data?.data ?? [],
    total: query.data?.count ?? 0,
    page,
    setPage,
    pageSize,
    search,
    setSearch,
    sort,
    setSort,
    pagination: { page, pageSize, total: query.data?.count ?? 0, onPageChange: setPage },
  };
}
