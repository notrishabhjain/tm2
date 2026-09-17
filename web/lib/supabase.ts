import { createClient } from "@supabase/supabase-js";

/**
 * One browser client for the whole app.
 *
 * Both values are public by design: the anon key ships in every Supabase web
 * app's bundle, and row-level security - not secrecy - is what keeps one
 * account's rows away from anyone else. Without a signed-in session these
 * queries return an empty list, not an error.
 *
 * WHY THIS DOES NOT THROW WHEN THEY ARE MISSING
 *
 * It used to, at import time, on the reasoning that a missing variable would
 * otherwise look like an empty task list. That was the wrong trade. Next
 * prerenders this page at build time, so the throw turned a configuration
 * problem into a *build* failure - and since preview deployments do not get
 * production environment variables, it broke every preview build by default,
 * with the reason buried in a build log rather than on screen.
 *
 * The page checks [isConfigured] instead and says plainly what is missing.
 * Same diagnosis, visible to whoever opens the page, and nothing fails to
 * build.
 */
const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
const anonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;

/** False when either variable is missing. The page refuses to pretend. */
export const isConfigured = Boolean(url && anonKey);

/** Names the variables that are actually missing, for the on-screen message. */
export const missingConfig: string[] = [
  ...(url ? [] : ["NEXT_PUBLIC_SUPABASE_URL"]),
  ...(anonKey ? [] : ["NEXT_PUBLIC_SUPABASE_ANON_KEY"]),
];

// Placeholders keep createClient from throwing during a build that has no
// variables. Nothing reaches them: the page renders the configuration notice
// instead of ever calling this client.
export const supabase = createClient(
  url || "https://placeholder.invalid",
  anonKey || "placeholder-anon-key",
  { auth: { persistSession: true, autoRefreshToken: true } },
);
