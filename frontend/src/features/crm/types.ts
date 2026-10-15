// Fields the CRM-core API returns beyond the shared lib/types definitions
// (kept feature-local on purpose so lib/types.ts stays untouched).
import type { Lead, Opportunity } from "@/lib/types";

export type LeadRecord = Lead & {
  /** Optimistic-locking version: send it back on update to get 409 instead of overwriting. */
  version?: number;
  followUpNotifiedAt?: string | null;
};

export type OpportunityRecord = Omit<Opportunity, "value" | "probability"> & {
  /** null when the reader's role may not see it (Inside Sales). */
  value: number | null;
  probability: number | null;
  version?: number;
  probabilityOverridden?: boolean;
  closeReason?: string | null;
  closedAt?: string | null;
};

export interface PagedList<T> {
  count: number;
  data: T[];
  page?: number;
  size?: number;
}

export interface PipelineSettings {
  stages: string[];
  stageProbabilities: Record<string, number>;
  bigDealThreshold: number | null;
}

export const CLOSED_STAGES = ["Won", "Lost"];
