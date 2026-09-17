/**
 * Dates render in IST, matching the phone.
 *
 * The app resolves "kal" and "Monday" against Asia/Kolkata (spec 14.1). A
 * browser in another timezone showing its own local time would move a deadline
 * by a day with nothing to indicate it had.
 */
const ZONE = "Asia/Kolkata";

const time = new Intl.DateTimeFormat("en-GB", {
  timeZone: ZONE,
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

const dayMonth = new Intl.DateTimeFormat("en-GB", {
  timeZone: ZONE,
  day: "numeric",
  month: "short",
});

const full = new Intl.DateTimeFormat("en-GB", {
  timeZone: ZONE,
  weekday: "short",
  day: "numeric",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

/** The IST calendar date of an instant, as a comparable YYYY-MM-DD string. */
function istDay(d: Date): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(d);
}

function shiftDays(from: Date, days: number): Date {
  return new Date(from.getTime() + days * 24 * 60 * 60 * 1000);
}

export function due(iso: string | null, now = new Date()): string {
  if (!iso) return "No date";
  const d = new Date(iso);
  const day = istDay(d);
  if (day === istDay(now)) return `Today ${time.format(d)}`;
  if (day === istDay(shiftDays(now, 1))) return `Tomorrow ${time.format(d)}`;
  if (day === istDay(shiftDays(now, -1))) return `Yesterday ${time.format(d)}`;
  return `${dayMonth.format(d)} ${time.format(d)}`;
}

export function fullDate(iso: string | null): string {
  return iso ? `${full.format(new Date(iso))} IST` : "—";
}

export function isOverdue(iso: string | null, now = new Date()): boolean {
  return iso !== null && new Date(iso).getTime() < now.getTime();
}

/** True when the instant falls on today's IST date. */
export function isToday(iso: string | null, now = new Date()): boolean {
  return iso !== null && istDay(new Date(iso)) === istDay(now);
}

export function relative(iso: string | null): string {
  if (!iso) return "never";
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 90) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return `${Math.round(hours / 24)} d ago`;
}

export function priorityLabel(p: string): string {
  return p.charAt(0) + p.slice(1).toLowerCase();
}
