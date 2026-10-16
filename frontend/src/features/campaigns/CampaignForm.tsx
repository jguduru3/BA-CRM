import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "@/lib/api";
import { Field, FieldGrid, SectionTitle } from "@/components/ui/Field";
import { type CampaignChannels, type CampaignRow, MESSAGE_VARIABLES, smsSegments } from "./campaignTypes";

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

export interface SenderMailbox {
  id: number;
  emailAddress: string;
  displayName?: string | null;
  active: boolean;
}

export function useSenderMailboxes(enabled = true) {
  return useQuery({
    queryKey: ["ai-automations", "email-accounts"],
    queryFn: async () => (await api.get<SenderMailbox[]>("/ai-automations/email-accounts")).data,
    enabled,
    retry: 0,
    staleTime: 60_000,
  });
}

export function useCampaignChannels() {
  return useQuery({
    queryKey: ["campaigns", "channels"],
    queryFn: async () => (await api.get<CampaignChannels>("/campaigns/channels")).data,
    retry: 0,
    staleTime: 60_000,
  });
}

export type CampaignDraft = Partial<
  Pick<
    CampaignRow,
    | "name"
    | "type"
    | "waAccountId"
    | "templateName"
    | "templateLanguage"
    | "templateParams"
    | "messageBody"
    | "emailAccountId"
    | "emailSubject"
    | "emailBody"
    | "scheduledAt"
    | "version"
  >
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
  const type = value.type ?? "whatsapp";
  const accounts = useSenderAccounts(type === "whatsapp");
  const mailboxes = useSenderMailboxes(type === "email");
  const channels = useCampaignChannels();
  const [paramsTouched, setParamsTouched] = useState(false);
  const set = (patch: CampaignDraft) => onChange({ ...value, ...patch });
  const paramsError = validateTemplateParams(value.templateParams);
  const smsReady = channels.data?.sms.configured ?? false;
  const sms = smsSegments(value.messageBody ?? "");
  const insertVar = (field: "emailBody" | "messageBody", v: string) =>
    set({ [field]: `${value[field] ?? ""}${value[field] && !/\s$/.test(value[field] ?? "") ? " " : ""}${v}` });
  const channelHint =
    type === "sms" && !smsReady
      ? channels.data?.sms.detail ?? "SMS is not configured on this server"
      : type === "email" && channels.data && !channels.data.email.configured
        ? channels.data.email.detail
        : type === "whatsapp" && channels.data && !channels.data.whatsapp.configured
          ? channels.data.whatsapp.detail
          : "How the campaign reaches recipients";

  return (
    <div className="space-y-4">
      <Field label="Campaign name" required>
        <input className="input" maxLength={200} value={value.name ?? ""} onChange={(e) => set({ name: e.target.value })} />
      </Field>

      <FieldGrid>
        <Field label="Channel" hint={channelHint}>
          <select className="select" value={type} disabled={!isNew} onChange={(e) => set({ type: e.target.value as CampaignDraft["type"] })}>
            <option value="whatsapp">WhatsApp</option>
            <option value="email">Email</option>
            <option value="sms" disabled={!smsReady && type !== "sms"}>
              SMS{smsReady ? "" : " (not configured)"}
            </option>
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

      {type === "email" && (
        <>
          <SectionTitle>Email</SectionTitle>
          <Field
            label="Send from"
            hint={
              mailboxes.isError
                ? "Couldn't load mailboxes"
                : (mailboxes.data ?? []).length === 0 && !mailboxes.isLoading
                  ? "Connect a mailbox under Automations → Email accounts first"
                  : `Sent as plain text${channels.data?.emailPerMinute ? `, up to ${channels.data.emailPerMinute} per minute per mailbox` : ""}`
            }
          >
            <select
              className="select"
              value={value.emailAccountId ?? ""}
              onChange={(e) => set({ emailAccountId: e.target.value ? Number(e.target.value) : null })}
            >
              <option value="">First active mailbox</option>
              {mailboxes.data?.map((m) => (
                <option key={m.id} value={m.id} disabled={!m.active}>
                  {m.displayName ? `${m.displayName} <${m.emailAddress}>` : m.emailAddress}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Subject" required>
            <input
              className="input"
              maxLength={300}
              placeholder="A quick update for {{contact.firstName}}"
              value={value.emailSubject ?? ""}
              onChange={(e) => set({ emailSubject: e.target.value })}
            />
          </Field>
          <Field label="Message" required hint="Plain text. An unsubscribe line is added automatically and opted-out addresses are skipped.">
            <textarea
              className="textarea"
              rows={8}
              maxLength={100_000}
              placeholder={"Hi {{contact.firstName}},\n\n…"}
              value={value.emailBody ?? ""}
              onChange={(e) => set({ emailBody: e.target.value })}
            />
          </Field>
          <VariableHints onPick={(v) => insertVar("emailBody", v)} />
        </>
      )}

      {type === "sms" && (
        <>
          <SectionTitle>SMS</SectionTitle>
          {!smsReady && (
            <p className="rounded-xl px-3 py-2 text-xs" style={{ backgroundColor: "rgb(201 133 0 / 0.08)", color: "var(--ink-secondary)" }}>
              SMS sending isn't configured on this server yet (Azure Communication Services). You can prepare the campaign, but it can't be
              launched until an administrator sets AZURE_COMMUNICATION_CONNECTION_STRING and AZURE_SMS_FROM.
            </p>
          )}
          <Field
            label="Message"
            required
            hint={`${sms.chars} characters · ${sms.segments} SMS${sms.segments === 1 ? "" : " segments"} (${sms.single} in one${
              sms.unicode ? ", Unicode" : `, ${sms.multi * 2} in two`
            })`}
          >
            <textarea
              className="textarea"
              rows={4}
              maxLength={1600}
              placeholder="Hi {{contact.firstName}}, … Reply STOP to opt out"
              value={value.messageBody ?? ""}
              onChange={(e) => set({ messageBody: e.target.value })}
            />
          </Field>
          <VariableHints onPick={(v) => insertVar("messageBody", v)} />
        </>
      )}

      {type === "whatsapp" && (
        <Field label="Internal notes">
          <textarea className="textarea" rows={3} value={value.messageBody ?? ""} onChange={(e) => set({ messageBody: e.target.value })} />
        </Field>
      )}
    </div>
  );
}

function VariableHints({ onPick }: { onPick: (v: string) => void }) {
  return (
    <div className="flex flex-wrap items-center gap-1.5 -mt-2">
      <span className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
        Insert:
      </span>
      {MESSAGE_VARIABLES.map((v) => (
        <button key={v} type="button" className="badge-neutral font-mono text-[11px] cursor-pointer" onClick={() => onPick(v)}>
          {v}
        </button>
      ))}
    </div>
  );
}
