import { AlertCircle, ArrowRight, Eye, EyeOff, Lock, Mail, ShieldCheck } from "lucide-react";
import { useState } from "react";
import { Navigate, useNavigate, useSearchParams } from "react-router-dom";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { Logo } from "@/icons/Logo";

/** Only same-app relative paths are valid post-login targets (no open redirects). */
export function safeNext(raw: string | null): string {
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) return "/";
  if (raw.startsWith("/login") || raw.startsWith("/change-password")) return "/";
  return raw;
}

export function LoginPage() {
  const [params] = useSearchParams();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(
    params.get("reason") === "expired" ? "Your session ended. Please sign in again." : null
  );
  const [loading, setLoading] = useState(false);
  const setSession = useAuthStore((s) => s.setSession);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const navigate = useNavigate();
  const next = safeNext(params.get("next"));

  if (isAuthenticated) return <Navigate to={next} replace />;

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const res = await api.post("/auth/login", { email: email.trim(), password });
      const user = { ...res.data.user, mustChangePassword: !!res.data.mustChangePassword };
      setSession(res.data.token, user);
      navigate(user.mustChangePassword ? "/change-password" : next, { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to sign in");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen w-full app-mesh flex items-center justify-center p-4 sm:p-6">
      <div className="w-full max-w-[960px] grid lg:grid-cols-[1fr_1.1fr] rounded-[28px] overflow-hidden shadow-glass-lg glass-strong glass-reveal">
        {/* Brand panel */}
        <div
          className="hidden lg:flex flex-col justify-between p-10 relative overflow-hidden"
          style={{ backgroundImage: "var(--brand-gradient)" }}
        >
          <div
            className="absolute inset-0 opacity-[0.18]"
            style={{
              backgroundImage:
                "radial-gradient(circle at 15% 15%, white 0%, transparent 38%), radial-gradient(circle at 85% 90%, white 0%, transparent 42%)",
            }}
            aria-hidden
          />
          <svg className="absolute -right-24 -bottom-24 opacity-[0.12]" width="420" height="420" viewBox="0 0 420 420" aria-hidden>
            <circle cx="210" cy="210" r="200" fill="none" stroke="white" strokeWidth="1.5" />
            <circle cx="210" cy="210" r="150" fill="none" stroke="white" strokeWidth="1.5" />
            <circle cx="210" cy="210" r="100" fill="none" stroke="white" strokeWidth="1.5" />
          </svg>
          <div className="relative">
            <Logo height={52} tone="white" />
          </div>
          <div className="relative space-y-4">
            <h2 className="text-white text-[28px] font-semibold leading-tight tracking-tight">
              Navigating digital complexity.
              <br />
              Shaping enterprise futures.
            </h2>
            <p className="text-white/85 text-sm leading-relaxed max-w-sm">
              Pipeline, accounts and execution in one secure workspace.
            </p>
          </div>
          <div className="relative flex items-center gap-2 text-white/75 text-xs">
            <ShieldCheck size={14} />
            Encrypted, audited and role-based access
          </div>
        </div>

        {/* Form panel */}
        <div className="p-8 sm:p-12 flex flex-col justify-center">
          <div className="mb-8">
            <Logo height={44} />
          </div>
          <h1 className="text-[26px] font-semibold tracking-tight" style={{ color: "var(--ink-primary)" }}>
            Welcome back
          </h1>
          <p className="text-sm mt-1.5 mb-8" style={{ color: "var(--ink-muted)" }}>
            Sign in to continue to your workspace.
          </p>

          <form onSubmit={onSubmit} className="space-y-4" noValidate>
            <div>
              <label className="field-label" htmlFor="login-email">
                Email address
              </label>
              <div className="relative">
                <Mail size={16} className="absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" style={{ color: "var(--ink-muted)" }} />
                <input
                  id="login-email"
                  type="email"
                  required
                  autoFocus
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="input pl-9 py-2.5"
                  placeholder="you@company.com"
                  autoComplete="username"
                />
              </div>
            </div>
            <div>
              <label className="field-label" htmlFor="login-password">
                Password
              </label>
              <div className="relative">
                <Lock size={16} className="absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" style={{ color: "var(--ink-muted)" }} />
                <input
                  id="login-password"
                  type={showPassword ? "text" : "password"}
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="input pl-9 pr-10 py-2.5"
                  placeholder="••••••••••"
                  autoComplete="current-password"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((s) => !s)}
                  className="absolute right-1.5 top-1/2 -translate-y-1/2 btn-icon h-8 w-8"
                  aria-label={showPassword ? "Hide password" : "Show password"}
                >
                  {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>

            {error && (
              <div
                role="alert"
                className="flex items-start gap-2 rounded-lg px-3 py-2.5 text-sm"
                style={{ backgroundColor: "rgb(208 59 59 / 0.1)", color: "var(--critical)" }}
              >
                <AlertCircle size={15} className="shrink-0 mt-0.5" />
                {error}
              </div>
            )}

            <button type="submit" disabled={loading || !email || !password} className="btn-primary w-full py-2.5 mt-2">
              {loading ? "Signing in…" : "Sign in"}
              {!loading && <ArrowRight size={16} />}
            </button>
          </form>

          <p className="text-xs mt-8 text-center" style={{ color: "var(--ink-muted)" }}>
            Trouble signing in? Contact your workspace administrator.
          </p>
        </div>
      </div>
    </div>
  );
}
