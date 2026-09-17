"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { Session } from "@supabase/supabase-js";
import { supabase } from "@/lib/supabase";
import type { ReviewItem, Task } from "@/lib/types";
import { due, fullDate, isOverdue, isToday, priorityLabel, relative } from "@/lib/format";

/**
 * The whole web app.
 *
 * Read-only by design in this version: the phone owns the data and this is a
 * window onto it. That keeps it honest - there is no edit here that could be
 * silently lost to a sync conflict.
 */
export default function Page() {
  const [session, setSession] = useState<Session | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      setSession(data.session);
      setReady(true);
    });
    const { data: sub } = supabase.auth.onAuthStateChange((_event, s) => setSession(s));
    return () => sub.subscription.unsubscribe();
  }, []);

  // Render nothing rather than flashing the login form at someone who is
  // already signed in - reading the stored session takes a moment.
  if (!ready) return null;
  return session ? <Dashboard email={session.user.email ?? ""} /> : <Login />;
}

/* -- login --------------------------------------------------------------- */

function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const { error } = await supabase.auth.signInWithPassword({ email, password });
    if (error) setError(error.message);
    setBusy(false);
  }

  return (
    <div className="login">
      <h1>TaskMind</h1>
      <p className="sub">Sign in with the same account your phone is paired to.</p>
      <form onSubmit={submit}>
        <label className="field">
          <span>Email</span>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="username" />
        </label>
        <label className="field">
          <span>Password</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            autoComplete="current-password"
          />
        </label>
        <button className="primary" type="submit" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
        {error && <div className="error">{error}</div>}
      </form>
    </div>
  );
}

/* -- dashboard ----------------------------------------------------------- */

type Tab = "tasks" | "review";

function Dashboard({ email }: { email: string }) {
  const [tab, setTab] = useState<Tab>("tasks");
  const [tasks, setTasks] = useState<Task[]>([]);
  const [reviews, setReviews] = useState<ReviewItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [showDone, setShowDone] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    const [t, r] = await Promise.all([
      supabase.from("tasks").select("*").neq("status", "DELETED").order("due_at", { nullsFirst: false }),
      supabase.from("review_items").select("*").order("created_at", { ascending: false }),
    ]);
    if (t.error) setError(t.error.message);
    else if (r.error) setError(r.error.message);
    else {
      setTasks(t.data as Task[]);
      setReviews(r.data as ReviewItem[]);
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    void load();
    // The phone pushes on its own schedule, so the browser has no way to know
    // something arrived. A quiet poll beats a page that goes stale while you
    // are looking at it.
    const timer = setInterval(() => void load(), 60_000);
    return () => clearInterval(timer);
  }, [load]);

  const active = useMemo(() => tasks.filter((t) => t.status === "ACTIVE"), [tasks]);
  const done = useMemo(() => tasks.filter((t) => t.status === "COMPLETED"), [tasks]);

  // The same agenda split the phone uses, so the two read alike.
  const groups = useMemo(() => {
    const overdue = active.filter((t) => isOverdue(t.due_at));
    const today = active.filter((t) => !isOverdue(t.due_at) && isToday(t.due_at));
    const upcoming = active.filter((t) => t.due_at && !isOverdue(t.due_at) && !isToday(t.due_at));
    const undated = active.filter((t) => !t.due_at);
    return { overdue, today, upcoming, undated };
  }, [active]);

  const lastSync = useMemo(() => {
    const stamps = [...tasks, ...reviews].map((r) => r.synced_at ?? null).filter(Boolean) as string[];
    if (!stamps.length) return null;
    return stamps.reduce((a, b) => (a > b ? a : b));
  }, [tasks, reviews]);

  return (
    <div className="shell">
      <header className="top">
        <h1>TaskMind</h1>
        <div className="spacer" />
        <button className="link" onClick={() => void load()}>
          Refresh
        </button>
        <button className="link" onClick={() => void supabase.auth.signOut()}>
          Sign out
        </button>
      </header>

      <div className="tabs" role="tablist">
        <button className="tab" role="tab" aria-selected={tab === "tasks"} onClick={() => setTab("tasks")}>
          Tasks<span className="count">{active.length}</span>
        </button>
        <button className="tab" role="tab" aria-selected={tab === "review"} onClick={() => setTab("review")}>
          Review<span className="count">{reviews.length}</span>
        </button>
      </div>

      {error && <div className="error">{error}</div>}

      {loading ? (
        <div className="empty">Loading…</div>
      ) : tab === "tasks" ? (
        active.length === 0 && done.length === 0 ? (
          <Empty
            title="Nothing here yet"
            body="If your phone is paired and has tasks, open the app once and leave it — that is when it pushes."
          />
        ) : (
          <>
            <Group title="Overdue" alert items={groups.overdue} />
            <Group title="Today" items={groups.today} />
            <Group title="Upcoming" items={groups.upcoming} />
            <Group title="No date" items={groups.undated} />
            {done.length > 0 && (
              <>
                <div className="group-head">
                  Completed
                  <button className="link" onClick={() => setShowDone((v) => !v)}>
                    {showDone ? "hide" : `show ${done.length}`}
                  </button>
                </div>
                {showDone && (
                  <div className="card">
                    {done.map((t) => (
                      <TaskRow key={t.id} task={t} />
                    ))}
                  </div>
                )}
              </>
            )}
          </>
        )
      ) : reviews.length === 0 ? (
        <Empty title="Nothing waiting" body="Candidates the app was unsure about appear here for a yes or no on the phone." />
      ) : (
        <>
          <div className="group-head">Awaiting your decision</div>
          <div className="card">
            {reviews.map((item) => (
              <ReviewRow key={item.id} item={item} />
            ))}
          </div>
        </>
      )}

      <p className="foot">
        Signed in as {email}
        {lastSync ? ` · phone last sent ${relative(lastSync)}` : ""}
        <br />
        Read-only. Complete and edit on the phone.
      </p>
    </div>
  );
}

/* -- pieces -------------------------------------------------------------- */

function Empty({ title, body }: { title: string; body: string }) {
  return (
    <div className="empty">
      <strong>{title}</strong>
      {body}
    </div>
  );
}

function Group({ title, items, alert = false }: { title: string; items: Task[]; alert?: boolean }) {
  if (items.length === 0) return null;
  return (
    <>
      <div className={alert ? "group-head alert" : "group-head"}>
        {title}
        <span className="count">{items.length}</span>
      </div>
      <div className="card">
        {items.map((t) => (
          <TaskRow key={t.id} task={t} />
        ))}
      </div>
    </>
  );
}

function TaskRow({ task }: { task: Task }) {
  const [open, setOpen] = useState(false);
  const overdue = task.status === "ACTIVE" && isOverdue(task.due_at);

  return (
    <>
      <button className="row" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span className={`bar ${task.priority}`} />
        <span className="body">
          <span className={task.status === "COMPLETED" ? "title done" : "title"}>{task.title}</span>
          <span className="meta">
            <span className={overdue ? "chip due-over" : "chip"}>{due(task.due_at)}</span>
            <span className="chip">{priorityLabel(task.priority)}</span>
            {task.source_label && <span className="chip">{task.source_label}</span>}
            {task.tags.map((tag) => (
              <span className="chip" key={tag}>
                #{tag}
              </span>
            ))}
          </span>
        </span>
      </button>

      {open && (
        <div className="detail">
          {task.evidence && (
            <div className="quote">
              “{task.evidence}”
              <div className="note">
                The words that created this task, checked against the original before it was made.
              </div>
            </div>
          )}
          {task.notes && <p style={{ whiteSpace: "pre-wrap", margin: "10px 0" }}>{task.notes}</p>}
          <dl className="facts">
            <Fact label="Due" value={fullDate(task.due_at)} />
            {task.reminder_at && <Fact label="Reminder" value={fullDate(task.reminder_at)} />}
            {task.recurrence_rule && <Fact label="Repeats" value={task.recurrence_rule} />}
            {task.completed_at && <Fact label="Completed" value={fullDate(task.completed_at)} />}
            <Fact label="Source" value={task.source_type.toLowerCase()} />
            {task.source_app && <Fact label="App" value={task.source_app} />}
            {task.confidence != null && <Fact label="Confidence" value={`${Math.round(task.confidence * 100)}%`} />}
            {task.inference_origin && <Fact label="Engine" value={task.inference_origin} />}
            <Fact label="Created" value={fullDate(task.created_at)} />
          </dl>
          <p className="note" style={{ fontSize: 11.5, color: "var(--text-dim)", marginTop: 12 }}>
            The full message or transcript stays on your phone and is not sent here.
          </p>
        </div>
      )}
    </>
  );
}

function ReviewRow({ item }: { item: ReviewItem }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className="row" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span className={`bar ${item.priority}`} />
        <span className="body">
          <span className="title">{item.title}</span>
          <span className="meta">
            <span className="chip">{due(item.due_at)}</span>
            {item.confidence != null && (
              <span className="chip strong">{Math.round(item.confidence * 100)}% sure</span>
            )}
            {item.source_label && <span className="chip">{item.source_label}</span>}
          </span>
        </span>
      </button>
      {open && (
        <div className="detail">
          {item.evidence && <div className="quote">“{item.evidence}”</div>}
          {item.reasoning && (
            <p style={{ margin: "10px 0", fontSize: 13.5, color: "var(--text-dim)" }}>{item.reasoning}</p>
          )}
          <dl className="facts">
            <Fact label="Happened" value={fullDate(item.occurred_at)} />
            <Fact label="Source" value={item.source_type.toLowerCase()} />
            {item.source_app && <Fact label="App" value={item.source_app} />}
            {item.inference_origin && <Fact label="Engine" value={item.inference_origin} />}
          </dl>
          <p style={{ fontSize: 11.5, color: "var(--text-dim)", marginTop: 12 }}>
            Approve or reject this on the phone — the web page cannot decide for you yet.
          </p>
        </div>
      )}
    </>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="fact">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}
