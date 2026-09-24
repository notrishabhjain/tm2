import type { Metadata, Viewport } from "next";
import "./globals.css";

/**
 * The icons are conventions, not configuration: Next serves app/icon.svg and
 * app/apple-icon.png automatically and writes the <link> tags, so naming them
 * here would only be a second place to keep in step.
 */
export const metadata: Metadata = {
  title: "TaskMind",
  description: "Your tasks and pending reviews, mirrored from your phone.",
  applicationName: "TaskMind",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  // Paints the phone browser's chrome to match the page instead of leaving a
  // white bar above a dark layout.
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#fbfaf8" },
    { media: "(prefers-color-scheme: dark)", color: "#131312" },
  ],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
