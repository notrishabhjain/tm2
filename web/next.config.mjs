/**
 * Security headers.
 *
 * This page shows a person's task list, the people who asked for each thing,
 * and the sentence they said it in. That is worth a few lines of defence even
 * behind a login:
 *
 *  - frame-ancestors 'none' stops the page being embedded, so a hostile site
 *    cannot overlay it and harvest clicks on Complete or Delete.
 *  - connect-src is pinned to Supabase, so a script that somehow got onto the
 *    page still has nowhere to send what it reads.
 *  - Referrer-Policy keeps the URL out of the referer header of anything the
 *    user opens from here.
 *
 * 'unsafe-inline' is present for styles because Next injects them; scripts
 * additionally need 'unsafe-eval' in development only.
 */
const supabaseOrigin = (() => {
  try {
    return new URL(process.env.NEXT_PUBLIC_SUPABASE_URL ?? "").origin;
  } catch {
    return "";
  }
})();

const connectSrc = ["'self'", supabaseOrigin, supabaseOrigin.replace(/^https:/, "wss:")]
  .filter(Boolean)
  .join(" ");

const csp = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${process.env.NODE_ENV === "development" ? " 'unsafe-eval'" : ""}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "font-src 'self' data:",
  `connect-src ${connectSrc}`,
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "object-src 'none'",
].join("; ");

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Content-Security-Policy", value: csp },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "no-referrer" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), interest-cohort=()" },
          {
            key: "Strict-Transport-Security",
            value: "max-age=63072000; includeSubDomains; preload",
          },
        ],
      },
    ];
  },
};

export default nextConfig;
