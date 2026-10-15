import { Building2, Clock, Flame } from "lucide-react";
import { formatCompactCurrency, formatDate, isOverdue } from "@/lib/format";
import type { Lead } from "@/lib/types";
import { Avatar } from "@/components/ui/Avatar";
import { PriorityBadge } from "@/components/ui/Badge";

export function LeadKanbanCard({ lead, companyName, ownerName }: { lead: Lead; companyName?: string; ownerName?: string }) {
  const overdue = isOverdue(lead.followUpAt);
  return (
    <div>
      <div className="flex items-start justify-between gap-2">
        <p className="text-[13px] font-semibold leading-snug line-clamp-2" style={{ color: "var(--ink-primary)" }}>
          {lead.name}
        </p>
        {(lead.leadScore ?? 0) >= 70 && <Flame size={14} style={{ color: "var(--critical)" }} className="shrink-0 mt-0.5" />}
      </div>
      {companyName && (
        <p className="flex items-center gap-1 text-xs mt-1 truncate" style={{ color: "var(--ink-muted)" }}>
          <Building2 size={11} /> {companyName}
        </p>
      )}
      <div className="flex items-center justify-between mt-2.5">
        <PriorityBadge value={lead.priority} />
        {lead.value > 0 && (
          <span className="text-xs font-bold tabular-nums" style={{ color: "var(--ink-primary)" }}>
            {formatCompactCurrency(lead.value)}
          </span>
        )}
      </div>
      <div className="flex items-center justify-between mt-2.5">
        {lead.followUpAt ? (
          <span
            className="inline-flex items-center gap-1 text-[11px] font-medium"
            style={{ color: overdue ? "var(--critical)" : "var(--ink-muted)" }}
          >
            <Clock size={11} /> {formatDate(lead.followUpAt)}
          </span>
        ) : (
          <span />
        )}
        <Avatar name={ownerName ?? null} size={22} />
      </div>
    </div>
  );
}
