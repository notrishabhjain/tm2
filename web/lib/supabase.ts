import { createClient } from "@supabase/supabase-js";

/**
 * One browser client for the whole app.
 *
 * Both values are public by design: the anon key ships in every Supabase web
 * app's bundle, and row-level security - not secrecy - is what keeps one
 * account's rows away from anyone else. Without a signed-in session these
 * queries return an empty list, not an error.
 */
const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
const anonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;

if (!url || !anonKey) {
  // Thrown at import time on purpose. A missing variable otherwise surfaces as
  // an empty task list, which is indistinguishable from "you have no tasks" -
  // the exact ambiguity that wastes an afternoon.
  throw new Error(
    "NEXT_PUBLIC_SUPABASE_URL and NEXT_PUBLIC_SUPABASE_ANON_KEY must be set. " +
      "Add them in the Vercel project settings, then redeploy.",
  );
}

export const supabase = createClient(url, anonKey, {
  auth: { persistSession: true, autoRefreshToken: true },
});
