import { supabase } from "@/lib/supabase";
import type { Priority } from "@/lib/types";

/**
 * Every write this app makes.
 *
 * All of them stamp a `web_*` column the phone never writes. That is what
 * makes the direction of a change unambiguous when the phone next syncs - it
 * can ask "what did the browser touch?" without having to work out whether it
 * is looking at an echo of its own push.
 */

const now = () => new Date().toISOString();

export interface TaskPatch {
  title?: string;
  notes?: string | null;
  due_at?: string | null;
  priority?: Priority;
  status?: "ACTIVE" | "COMPLETED" | "ARCHIVED" | "DELETED";
}

export async function patchTask(id: string, patch: TaskPatch): Promise<void> {
  const { error } = await supabase
    .from("tasks")
    .update({ ...patch, web_updated_at: now() })
    .eq("id", id);
  if (error) throw new Error(error.message);
}

export async function decideReview(id: string, decision: "APPROVED" | "REJECTED"): Promise<void> {
  const { error } = await supabase
    .from("review_items")
    .update({ web_decision: decision, web_decided_at: now() })
    .eq("id", id);
  if (error) throw new Error(error.message);
}

export async function addTask(input: {
  title: string;
  notes: string | null;
  due_at: string | null;
  priority: Priority;
}): Promise<void> {
  // `owner` is filled in by the column default (auth.uid()), so it is never
  // sent from here and cannot be got wrong.
  const { error } = await supabase.from("web_new_tasks").insert({
    id: crypto.randomUUID(),
    ...input,
  });
  if (error) throw new Error(error.message);
}

/** Withdraws a task typed here before the phone has picked it up. */
export async function cancelNewTask(id: string): Promise<void> {
  const { error } = await supabase.from("web_new_tasks").delete().eq("id", id);
  if (error) throw new Error(error.message);
}

/* -- dates ---------------------------------------------------------------- */

/**
 * A `datetime-local` value means whatever the laptop's clock says, but the
 * phone resolves "kal" and "Monday" against IST (spec 14.1). Reading the input
 * as IST keeps a deadline on the day it was meant for, whatever timezone the
 * browser happens to be in.
 */
export function istLocalToIso(local: string): string | null {
  if (!local) return null;
  const parsed = new Date(`${local}:00+05:30`);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

/** The inverse, for filling the input in. */
export function isoToIstLocal(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  // hourCycle h23 on purpose: hour12:false still yields "24" for midnight in
  // some ICU versions, which is not a value the input will accept.
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(d);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")}T${get("hour")}:${get("minute")}`;
}
