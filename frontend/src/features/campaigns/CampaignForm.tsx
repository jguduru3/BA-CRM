import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "@/lib/api";
import { Field, FieldGrid, SectionTitle } from "@/components/ui/Field";
import type { CampaignRow } from "./campaignTypes";

export interface SenderAccount {
  id: number;
  displayPhoneNumber: string | null;
  active: boolean;
}

export function useSenderAccounts(enabled = true) {
  return useQuery({
    queryKey: ["whatsapp", "accounts"],
    queryFn: async () => (await api.get<SenderAccount[]>("/whatsapp/accounts")).data,
    enabled,
    retry: 0,
    staleTime: 60_000,
  });
}

export type CampaignDraft = Partial<
  Pick<CampaignRow, "name" | "type" | "waAccountId" | "templateName" | "templateLanguage" | "templateParams" | "messageBody" | "scheduledAt" | "version">
>;

/** datetime-local value (local time, minutes) ↔ ISO instant */
function toLocalInput(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function validateTemplateParams(raw: string | null | undefined): string | null {
  if (!raw || !raw.trim()) return null;
  try {
    const v = JSON.parse(raw);
    if (!Array.isArray(v) || v.some((x) => typeof x !== "object" || x === null || Array.isArray(x)))
      return "Must be a JSON array of template component objects";
    return null;
  } catch {
    return "Not valid JSON";
  }
}

const PARAMS_PLACEHOLDER = `[
  { "type": "body",
    "parameters": [ { "type": "text", "text": "20%" } ] }
]`;

export function CampaignForm({
  value,
  onChange,
  isNew,
}: {
  value: CampaignDraft;
  onChange: (next: CampaignDraft) => void;
  isNew: boolean;
}) {
  const accounts = useSenderAccounts();
  const [paramsTouched, setParamsTouched] = useState(false);
  const set = (patch: CampaignDraft) => onChange({ ...value, ...patch });
  const paramsError = validateTemplateParams(value.templateParams);
  const type = value.type ?? "whatsapp";

  return (
    <div className="space-y-5">
      <Field label="Campaign name" required>
        <input className="input" maxLength={200} value={value.name ?? ""} onChange={(e) => set({ name: e.target.value })} />
      </Field>

      <FieldGrid>
        <Field label="Channel">
          <select className="select" value={type} disabled={!isNew} onChange={(e) => set({ type: e.target.value as CampaignDraft["type"] })}>
            <option value="whatsapp">WhatsApp</option>
            <option value="email">Email (tracking only — not dispatched)</option>
          </select>
        </Field>
        <Field label="Start" hint="Leave empty to start as soon as it is launched">
          <input
            className="input"
            type="datetime-local"
            value={toLocalInput(value.scheduledAt)}
            onChange={(e) => set({ scheduledAt: e.target.value ? new Date(e.target.value).toISOString() : null })}
          />
        </Field>
      </FieldGrid>

      {type === "whatsapp" && (
        <>
          <SectionTitle>WhatsApp template</SectionTitle>
          <FieldGrid>
            <Field label="Sender number" hint={accounts.isError ? "Couldn't load sender numbers" : "Which connected number sends it"}>
              <select
                className="select"
                value={value.waAccountId ?? ""}
                onChange={(e) => set({ waAccountId: e.target.value ? Number(e.target.value) : null })}
              >
                <option value="">First active sender</option>
                {accounts.data?.map((a) => (
                  <option key={a.id} value={a.id} disabled={!a.active}>
                    {a.displayPhoneNumber ?? `Sender #${a.id}`}
                    {a.active ? "" : " (inactive)"}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Language" hint="Template language code, e.g. en_US, hi_IN">
              <input
                className="input"
                placeholder="en_US"
                value={value.templateLanguage ?? ""}
                onChange={(e) => set({ templateLanguage: e.target.value })}
              />
            </Field>
            <Field label="Template name" required hint="Exactly as approved in Meta Business Manager" className="sm:col-span-2">
              <input
                className="input font-mono text-[13px]"
                placeholder="diwali_offer"
                value={value.templateName ?? ""}
                onChange={(e) => set({ templateName: e.target.value.replace(/\s+/g, "_") })}
              />
            </Field>
          </FieldGrid>
          <Field
            label="Template parameters (optional)"
            hint={paramsTouched && paramsError ? paramsError : "Meta's components array — values fill the template's {{1}}, {{2}} … placeholders"}
          >
            <textarea
              className="textarea font-mono text-[12px]"
              rows={5}
              placeholder={PARAMS_PLACEHOLDER}
              value={value.templateParams ?? ""}
              onBlur={() => setParamsTouched(true)}
              onChange={(e) => set({ templateParams: e.target.value })}
              style={paramsTouched && paramsError ? { borderColor: "var(--critical)" } : undefined}
            />
          </Field>
        </>
      )}

      <Field label="Internal notes / message preview">
        <textarea className="textarea" rows={3} value={value.messageBody ?? ""} onChange={(e) => set({ messageBody: e.target.value })} />
      </Field>
    </div>
  );
}
