import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "./api";
import { onLogout } from "./auth";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      // Retry transient failures once; never retry auth/permission/validation errors.
      retry: (count, error) => {
        const status = error instanceof ApiError ? error.status : undefined;
        if (status && status >= 400 && status < 500) return false;
        return count < 1;
      },
    },
    mutations: {
      retry: 0,
    },
  },
});

// Signing out must not leave the previous user's data in memory.
onLogout(() => queryClient.clear());
