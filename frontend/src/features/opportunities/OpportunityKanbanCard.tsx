import { Building2, Calendar } from "lucide-react";
import { formatCompactCurrency, formatDate } from "@/lib/format";
import type { Opportunity } from "@/lib/types";
import { Avatar } from "@/components/ui/Avatar";

export function OpportunityKanbanCard({
  opp,
  companyName,
  ownerName,
}: {
  opp: Opportunity;
  companyName?: string;
  ownerName?: string;
}) {
  return (
    <div>
      <p className="text-[13px] font-semibold leading-snug line-clamp-2" style={{ color: "var(--ink-primary)" }}>
        {opp.name}
      </p>
      {companyName && (
        <p className="flex items-center gap-1 text-xs mt-1 truncate" style={{ color: "var(--ink-muted)" }}>
          <Building2 size={11} /> {companyName}
        </p>
      )}
      <div className="flex items-center justify-between mt-2.5">
        <span className="text-sm font-bold tabular-nums" style={{ color: "var(--brand-600)" }}>
          {formatCompactCurrency(opp.value)}
        </span>
        <span
          className="text-[11px] font-bold px-1.5 py-0.5 rounded-full"
          style={{ backgroundColor: "rgb(var(--panel-border) / 0.08)", color: "var(--ink-muted)" }}
        >
          {opp.probability}%
        </span>
      </div>
      <div className="mt-2 h-1 rounded-full overflow-hidden" style={{ backgroundColor: "rgb(var(--panel-border) / 0.08)" }}>
        <div
          className="h-full rounded-full"
          style={{ width: `${opp.probability}%`, backgroundImage: "var(--brand-gradient)" }}
        />
      </div>
      <div className="flex items-center justify-between mt-2.5">
        {opp.closeDate ? (
          <span className="inline-flex items-center gap-1 text-[11px] font-medium" style={{ color: "var(--ink-muted)" }}>
            <Calendar size={11} /> {formatDate(opp.closeDate)}
          </span>
        ) : (
          <span />
        )}
        <Avatar name={ownerName ?? null} size={22} />
      </div>
    </div>
  );
}
