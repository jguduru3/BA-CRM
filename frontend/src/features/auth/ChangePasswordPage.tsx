import { AlertCircle, Check, KeyRound, LogOut } from "lucide-react";
import { useState } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { Logo } from "@/icons/Logo";
import { passwordChecks, PasswordRules } from "./passwordRules";

/**
 * Shown when the account must set a new password before doing anything else
 * (the seeded admin, or after an admin reset). The API refuses every other
 * call with PASSWORD_CHANGE_REQUIRED until this succeeds.
 */
export default function ChangePasswordPage() {
  const user = useAuthStore((s) => s.user);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const setSession = useAuthStore((s) => s.setSession);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  if (!isAuthenticated) return <Navigate to="/login" replace />;

  const checks = passwordChecks(next, user?.email);
  const valid = checks.every((c) => c.ok) && next === confirm && current.length > 0;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!valid) return;
    setSaving(true);
    setError(null);
    try {
      const res = await api.post("/auth/change-password", { currentPassword: current, newPassword: next });
      setSession(res.data.token, { ...res.data.user, mustChangePassword: false });
      navigate("/", { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not change password");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="min-h-screen w-full app-mesh flex items-center justify-center p-4">
      <form onSubmit={submit} className="w-full max-w-md glass-strong glass-reveal rounded-3xl shadow-glass-lg p-8 sm:p-10">
        <Logo height={40} />
        <div
          className="mt-8 h-11 w-11 rounded-2xl flex items-center justify-center"
          style={{ backgroundColor: "rgb(var(--ring-brand) / 0.12)", color: "var(--brand-600)" }}
        >
          <KeyRound size={20} />
        </div>
        <h1 className="mt-4 text-xl font-semibold tracking-tight" style={{ color: "var(--ink-primary)" }}>
          Set a new password
        </h1>
        <p className="text-sm mt-1.5 mb-6" style={{ color: "var(--ink-muted)" }}>
          {user?.mustChangePassword
            ? "For your security, choose a new password before continuing."
            : "Choose a new password for your account."}
        </p>

        <div className="space-y-4">
          <div>
            <label className="field-label" htmlFor="cp-current">Current password</label>
            <input id="cp-current" className="input" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} autoFocus />
          </div>
          <div>
            <label className="field-label" htmlFor="cp-new">New password</label>
            <input id="cp-new" className="input" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
          </div>
          <div>
            <label className="field-label" htmlFor="cp-confirm">Confirm new password</label>
            <input id="cp-confirm" className="input" type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
            {confirm && confirm !== next && (
              <p className="mt-1 text-xs" style={{ color: "var(--critical)" }}>Passwords don't match.</p>
            )}
          </div>
          <PasswordRules checks={checks} />

          {error && (
            <div role="alert" className="flex items-start gap-2 rounded-lg px-3 py-2.5 text-sm" style={{ backgroundColor: "rgb(208 59 59 / 0.1)", color: "var(--critical)" }}>
              <AlertCircle size={15} className="shrink-0 mt-0.5" />
              {error}
            </div>
          )}

          <button type="submit" className="btn-primary w-full py-2.5" disabled={!valid || saving}>
            <Check size={16} /> {saving ? "Saving…" : "Save and continue"}
          </button>
          <button
            type="button"
            className="btn-ghost w-full"
            onClick={() => {
              logout();
              navigate("/login", { replace: true });
            }}
          >
            <LogOut size={15} /> Sign out
          </button>
        </div>
      </form>
    </div>
  );
}
