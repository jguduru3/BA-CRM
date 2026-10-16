import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Building2, KeyRound, LogOut, Settings as SettingsIcon, User as UserIcon } from "lucide-react";
import { passwordChecks, PasswordRules } from "@/features/auth/passwordRules";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { toast } from "@/lib/toast";
import type { Organization } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Tabs } from "@/components/ui/Tabs";
import { AiSettingsTab } from "./AiSettingsTab";

export default function SettingsPage() {
  const [tab, setTab] = useState<"profile" | "ai" | "organization">("profile");
  const user = useAuthStore((s) => s.user);
  const updateUser = useAuthStore((s) => s.updateUser);
  const isAdmin = user?.role === "SUPER_ADMIN";
  const qc = useQueryClient();

  // ── Profile ──────────────────────────────────────────────────────────────
  const [profile, setProfile] = useState({ name: "", initials: "", color: "#1ca3e6" });
  const [pw, setPw] = useState({ current: "", next: "" });
  const setSession = useAuthStore((s) => s.setSession);
  useEffect(() => {
    if (user) setProfile({ name: user.name, initials: user.initials ?? "", color: user.color ?? "#1ca3e6" });
  }, [user]);

  const saveProfile = useMutation({
    mutationFn: async () => (await api.put(`/users/${user!.id}`, profile)).data,
    onSuccess: (updated) => {
      toast.success("Profile updated");
      updateUser(updated);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  // Changing the password revokes every other session; the response carries a fresh token for this one.
  const changePassword = useMutation({
    mutationFn: async () =>
      (await api.post("/auth/change-password", { currentPassword: pw.current, newPassword: pw.next })).data,
    onSuccess: (data) => {
      setSession(data.token, data.user);
      toast.success("Password updated — other devices have been signed out");
      setPw({ current: "", next: "" });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const signOutEverywhere = useMutation({
    mutationFn: async () => api.post("/auth/logout-all"),
    onSuccess: () => {
      toast.success("Signed out on every device");
      useAuthStore.getState().logout();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  // ── Organization ─────────────────────────────────────────────────────────
  const org = useQuery({
    queryKey: ["organizations", "me"],
    queryFn: async () => (await api.get<Organization>("/organizations/me")).data,
  });
  const [orgDraft, setOrgDraft] = useState<Partial<Organization>>({});
  useEffect(() => {
    if (org.data) setOrgDraft(org.data);
  }, [org.data]);

  const saveOrg = useMutation({
    mutationFn: async () => (await api.put("/organizations/me", orgDraft)).data,
    onSuccess: () => {
      toast.success("Organization updated");
      qc.invalidateQueries({ queryKey: ["organizations", "me"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  return (
    <div className="flex flex-col gap-5 max-w-3xl">
      <PageHeader title="Settings" description="Your profile, AI assistant and workspace configuration." icon={<SettingsIcon size={20} />} />

      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "profile", label: "Profile" },
          ...(isAdmin ? [{ value: "ai" as const, label: "AI Assistant" }, { value: "organization" as const, label: "Organization" }] : []),
        ]}
      />

      {tab === "profile" && (
        <div className="glass-card p-5 space-y-5">
          <div className="flex items-center gap-4">
            <Avatar name={profile.name} color={profile.color} size={56} />
            <div>
              <p className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
                {user?.email}
              </p>
              <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                Member since your organization's setup
              </p>
            </div>
          </div>
          <FieldGrid>
            <Field label="Full name">
              <input className="input" value={profile.name} onChange={(e) => setProfile((p) => ({ ...p, name: e.target.value }))} />
            </Field>
            <Field label="Initials">
              <input className="input" maxLength={4} value={profile.initials} onChange={(e) => setProfile((p) => ({ ...p, initials: e.target.value.toUpperCase() }))} />
            </Field>
            <Field label="Avatar color">
              <input type="color" className="input h-10 w-16 p-1 cursor-pointer" aria-label="Avatar color" value={profile.color} onChange={(e) => setProfile((p) => ({ ...p, color: e.target.value }))} />
            </Field>
          </FieldGrid>
          <div className="flex justify-end">
            <button className="btn-primary" onClick={() => saveProfile.mutate()}>
              <UserIcon size={14} /> Save profile
            </button>
          </div>

          <div className="pt-5 hairline border-t space-y-3">
            <p className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
              Password & sessions
            </p>
            <FieldGrid>
              <Field label="Current password">
                <input className="input" type="password" autoComplete="current-password" value={pw.current} onChange={(e) => setPw((p) => ({ ...p, current: e.target.value }))} />
              </Field>
              <Field label="New password">
                <input className="input" type="password" autoComplete="new-password" value={pw.next} onChange={(e) => setPw((p) => ({ ...p, next: e.target.value }))} />
              </Field>
            </FieldGrid>
            <PasswordRules checks={passwordChecks(pw.next, user?.email)} />
            <div className="flex flex-wrap justify-between gap-2">
              <button className="btn-ghost" onClick={() => signOutEverywhere.mutate()} disabled={signOutEverywhere.isPending}>
                <LogOut size={14} /> Sign out on all devices
              </button>
              <button
                className="btn-secondary"
                disabled={!pw.current || !passwordChecks(pw.next, user?.email).every((c) => c.ok) || changePassword.isPending}
                onClick={() => changePassword.mutate()}
              >
                <KeyRound size={14} /> Update password
              </button>
            </div>
          </div>
        </div>
      )}

      {tab === "ai" && isAdmin && <AiSettingsTab />}

      {tab === "organization" && isAdmin && (
        <div className="glass-card p-5 space-y-5">
          <div className="flex items-center gap-2">
            <Building2 size={16} style={{ color: "var(--brand-600)" }} />
            <span className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
              Organization
            </span>
          </div>
          <FieldGrid>
            <Field label="Name">
              <input className="input" value={orgDraft.name ?? ""} onChange={(e) => setOrgDraft((d) => ({ ...d, name: e.target.value }))} />
            </Field>
            <Field label="Industry">
              <input className="input" value={orgDraft.industry ?? ""} onChange={(e) => setOrgDraft((d) => ({ ...d, industry: e.target.value }))} />
            </Field>
            <Field label="Plan" hint="Read-only">
              <input className="input" value={orgDraft.plan ?? ""} disabled />
            </Field>
            <Field label="Slug" hint="Read-only">
              <input className="input" value={orgDraft.slug ?? ""} disabled />
            </Field>
          </FieldGrid>
          <div className="flex justify-end">
            <button className="btn-primary" onClick={() => saveOrg.mutate()}>
              Save organization
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
