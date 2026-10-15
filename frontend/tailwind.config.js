/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ["class", '[data-theme="dark"]'],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: [
          "'Segoe UI Variable'",
          "'Segoe UI'",
          "system-ui",
          "-apple-system",
          "sans-serif",
        ],
      },
      colors: {
        brand: {
          50: "#eef9ff",
          100: "#d9f1ff",
          200: "#b8e6ff",
          300: "#84d6ff",
          400: "#48c0fb",
          500: "#1ca3e6",
          600: "#0f84c4",
          700: "#0d699e",
          800: "#115880",
          900: "#134a6a",
          950: "#0c2e46",
        },
      },
      borderRadius: {
        xl2: "1.25rem",
      },
      boxShadow: {
        glass: "0 1px 1px rgba(11,11,11,0.04), 0 8px 24px -8px rgba(11,11,11,0.18)",
        "glass-lg": "0 2px 4px rgba(11,11,11,0.05), 0 24px 48px -16px rgba(11,11,11,0.28)",
        "glow-brand": "0 0 0 1px rgba(28,163,230,0.35), 0 8px 24px -4px rgba(28,163,230,0.35)",
      },
      backdropBlur: {
        xs: "2px",
      },
      keyframes: {
        "fade-in": { from: { opacity: 0 }, to: { opacity: 1 } },
        "slide-up": {
          from: { opacity: 0, transform: "translateY(6px)" },
          to: { opacity: 1, transform: "translateY(0)" },
        },
        "slide-in-right": {
          from: { opacity: 0, transform: "translateX(16px)" },
          to: { opacity: 1, transform: "translateX(0)" },
        },
        shimmer: {
          "0%": { backgroundPosition: "-200% 0" },
          "100%": { backgroundPosition: "200% 0" },
        },
      },
      animation: {
        "fade-in": "fade-in 0.18s ease-out",
        "slide-up": "slide-up 0.22s cubic-bezier(0.16,1,0.3,1)",
        "slide-in-right": "slide-in-right 0.26s cubic-bezier(0.16,1,0.3,1)",
        shimmer: "shimmer 1.6s linear infinite",
      },
    },
  },
  plugins: [],
};
