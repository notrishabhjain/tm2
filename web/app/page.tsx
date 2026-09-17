"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { Session } from "@supabase/supabase-js";
import { supabase } from "@/lib/supabase";
import type { NewTask, Priority, ReviewItem, Task } from "@/lib/types";
import {
  addTask,
  cancelNewTask,
  decideReview,
  isoToIstLocal,
  istLocalToIso,
  patchTask,
  type TaskPatch,
} from "@/lib/mutations";
import { due, fullDate, isOverdue, isToday, priorityLabel, relative } from "@/lib/format";

const PRIORITIES: Priority[] = ["URGENT", "HIGH", "MEDIUM", "LOW"];

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
  const [queued, setQueued] = useState<NewTask[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [showDone, setShowDone] = useState(false);
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    const [t, r, q] = await Promise.all([
      supabase.from("tasks").select("*").neq("status", "DELETED").order("due_at", { nullsFirst: false }),
      supabase.from("review_items").select("*").order("created_at", { ascending: false }),
      supabase.from("web_new_tasks").select("*").order("created_at", { ascending: true }),
    ]);
    const failed = t.error ?? r.error ?? q.error;
    if (failed) {
      setError(failed.message);
    } else {
      setError(null);
      setTasks(t.data as Task[]);
      setReviews(r.data as ReviewItem[]);
      setQueued(q.data as NewTask[]);
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    void load();
    // The phone pushes on its own schedule and the browser has no way to know
    // something arrived, so poll quietly rather than let the page go stale
    // while it is being looked at.
    const timer = setInterval(() => void load(), 60_000);
    return () => clearInterval(timer);
  }, [load]);

  /**
   * Runs a write, then re-reads.
   *
   * Deliberately not optimistic beyond the immediate state the caller sets:
   * a write that fails silently and leaves the screen looking right is the one
   * failure mode that would make this page untrustworthy.
   */
  const mutate = useCallback(
    async (fn: () => Promise<void>) => {
      try {
        setError(null);
        await fn();
      } catch (e) {
        setError(e instanceof Error ? e.message : "That change did not save.");
      }
      await load();
    },
    [load],
  );

  const active = useMemo(() => tasks.filter((t) => t.status === "ACTIVE"), [tasks]);
  const done = useMemo(() => tasks.filter((t) => t.status === "COMPLETED"), [tasks]);

  const groups = useMemo(() => {
    const overdue = active.filter((t) => isOverdue(t.due_at));
    const today = active.filter((t) => !isOverdue(t.due_at) && isToday(t.due_at));
    const upcoming = active.filter((t) => t.due_at && !isOverdue(t.due_at) && !isToday(t.due_at));
    const undated = active.filter((t) => !t.due_at);
    return { overdue, today, upcoming, undated };
  }, [active]);

  const lastSync = useMemo(() => {
    const stamps = [...tasks, ...reviews].map((r) => r.synced_at).filter(Boolean);
    return stamps.length ? stamps.reduce((a, b) => (a > b ? a : b)) : null;
  }, [tasks, reviews]);

  const pendingDecisions = reviews.filter((r) => r.web_decision !== null).length;

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
          Tasks<span className="count">{active.length + queued.length}</span>
        </button>
        <button className="tab" role="tab" aria-selected={tab === "review"} onClick={() => setTab("review")}>
          Review<span className="count">{reviews.length}</span>
        </button>
        <div className="spacer" />
        {tab === "tasks" && !adding && (
          <button className="tab add" onClick={() => setAdding(true)}>
            + Add task
          </button>
        )}
      </div>

      {error && <div className="error">{error}</div>}

      {loading ? (
        <div className="empty">Loading…</div>
      ) : tab === "tasks" ? (
        <>
          {adding && (
            <NewTaskForm
              onCancel={() => setAdding(false)}
              onSave={async (input) => {
                await mutate(() => addTask(input));
                setAdding(false);
              }}
            />
          )}

          {queued.length > 0 && (
            <>
              <div className="group-head">
                Waiting for your phone
                <span className="count">{queued.length}</span>
              </div>
              <div className="card">
                {queued.map((q) => (
                  <div className="row static" key={q.id}>
                    <span className={`bar ${q.priority}`} />
                    <span className="body">
                      <span className="title">{q.title}</span>
                      <span className="meta">
                        <span className="chip">{due(q.due_at)}</span>
                        <span className="chip">{priorityLabel(q.priority)}</span>
                        <span className="chip pending">not on your phone yet</span>
                      </span>
                    </span>
                    <button className="link" onClick={() => void mutate(() => cancelNewTask(q.id))}>
                      Cancel
                    </button>
                  </div>
                ))}
              </div>
            </>
          )}

          {active.length === 0 && done.length === 0 && queued.length === 0 ? (
            <Empty
              title="Nothing here yet"
              body="If your phone is paired and has tasks, open the app once and leave it — that is when it pushes."
            />
          ) : (
            <>
              <Group title="Overdue" alert items={groups.overdue} mutate={mutate} />
              <Group title="Today" items={groups.today} mutate={mutate} />
              <Group title="Upcoming" items={groups.upcoming} mutate={mutate} />
              <Group title="No date" items={groups.undated} mutate={mutate} />
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
                        <TaskRow key={t.id} task={t} mutate={mutate} />
                      ))}
                    </div>
                  )}
                </>
              )}
            </>
          )}
        </>
      ) : reviews.length === 0 ? (
        <Empty title="Nothing waiting" body="Candidates the app was unsure about appear here for a yes or no." />
      ) : (
        <>
          <div className="group-head">Awaiting your decision</div>
          <div className="card">
            {reviews.map((item) => (
              <ReviewRow key={item.id} item={item} mutate={mutate} />
            ))}
          </div>
          {pendingDecisions > 0 && (
            <p className="hint">
              {pendingDecisions} decision{pendingDecisions === 1 ? "" : "s"} waiting for your phone to carry out.
              Approving has to create a task, and only the phone does that.
            </p>
          )}
        </>
      )}

      <p className="foot">
        Signed in as {email}
        {lastSync ? ` · phone last sent ${relative(lastSync)}` : ""}
        <br />
        Changes here reach your phone on its next sync.
      </p>
    </div>
  );
}

/* -- pieces -------------------------------------------------------------- */

type Mutate = (fn: () => Promise<void>) => Promise<void>;

function Empty({ title, body }: { title: string; body: string }) {
  return (
    <div className="empty">
      <strong>{title}</strong>
      {body}
    </div>
  );
}

function Group({
  title,
  items,
  mutate,
  alert = false,
}: {
  title: string;
  items: Task[];
  mutate: Mutate;
  alert?: boolean;
}) {
  if (items.length === 0) return null;
  return (
    <>
      <div className={alert ? "group-head alert" : "group-head"}>
        {title}
        <span className="count">{items.length}</span>
      </div>
      <div className="card">
        {items.map((t) => (
          <TaskRow key={t.id} task={t} mutate={mutate} />
        ))}
      </div>
    </>
  );
}

function TaskRow({ task, mutate }: { task: Task; mutate: Mutate }) {
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const completed = task.status === "COMPLETED";
  const overdue = !completed && isOverdue(task.due_at);

  return (
    <div className="row-wrap">
      <div className="row">
        <input
          type="checkbox"
          className="tick"
          checked={completed}
          aria-label={completed ? `Reopen ${task.title}` : `Complete ${task.title}`}
          onChange={() =>
            void mutate(() => patchTask(task.id, { status: completed ? "ACTIVE" : "COMPLETED" }))
          }
        />
        <span className={`bar ${task.priority}`} />
        <button
          className="body as-button"
          onClick={() => {
            setOpen((v) => !v);
            setEditing(false);
          }}
          aria-expanded={open}
        >
          <span className={completed ? "title done" : "title"}>{task.title}</span>
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
        </button>
      </div>

      {open && !editing && (
        <div className="detail">
          {task.evidence && (
            <div className="quote">
              “{task.evidence}”
              <div className="note">
                The words that created this task, checked against the original before it was made.
              </div>
            </div>
          )}
          {task.notes && <p className="notes">{task.notes}</p>}
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

          <div className="actions">
            <button className="btn" onClick={() => setEditing(true)}>
              Edit
            </button>
            <button className="btn" onClick={() => void mutate(() => patchTask(task.id, { status: "ARCHIVED" }))}>
              Archive
            </button>
            <button
              className="btn danger"
              onClick={() => void mutate(() => patchTask(task.id, { status: "DELETED" }))}
            >
              Delete
            </button>
          </div>

          <p className="hint">
            The full message or transcript stays on your phone and is not sent here.
          </p>
        </div>
      )}

      {open && editing && (
        <TaskEditor
          task={task}
          onCancel={() => setEditing(false)}
          onSave={async (patch) => {
            await mutate(() => patchTask(task.id, patch));
            setEditing(false);
          }}
        />
      )}
    </div>
  );
}

function TaskEditor({
  task,
  onSave,
  onCancel,
}: {
  task: Task;
  onSave: (patch: TaskPatch) => Promise<void>;
  onCancel: () => void;
}) {
  const [title, setTitle] = useState(task.title);
  const [notes, setNotes] = useState(task.notes ?? "");
  const [dueLocal, setDueLocal] = useState(isoToIstLocal(task.due_at));
  const [priority, setPriority] = useState<Priority>(task.priority);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    setBusy(true);
    await onSave({
      title: title.trim(),
      notes: notes.trim() || null,
      due_at: istLocalToIso(dueLocal),
      priority,
    });
    setBusy(false);
  }

  return (
    <form className="detail editor" onSubmit={submit}>
      <label className="field">
        <span>Title</span>
        <input value={title} onChange={(e) => setTitle(e.target.value)} required />
      </label>
      <label className="field">
        <span>Notes</span>
        <textarea rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </label>
      <div className="two-up">
        <label className="field">
          <span>Due (IST)</span>
          <input type="datetime-local" value={dueLocal} onChange={(e) => setDueLocal(e.target.value)} />
        </label>
        <label className="field">
          <span>Priority</span>
          <select value={priority} onChange={(e) => setPriority(e.target.value as Priority)}>
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {priorityLabel(p)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="actions">
        <button className="btn strong" type="submit" disabled={busy || !title.trim()}>
          {busy ? "Saving…" : "Save"}
        </button>
        <button className="btn" type="button" onClick={onCancel}>
          Cancel
        </button>
        {dueLocal && (
          <button className="btn" type="button" onClick={() => setDueLocal("")}>
            Clear date
          </button>
        )}
      </div>
      <p className="hint">Dates are read as IST, the same way your phone reads them.</p>
    </form>
  );
}

function NewTaskForm({
  onSave,
  onCancel,
}: {
  onSave: (input: { title: string; notes: string | null; due_at: string | null; priority: Priority }) => Promise<void>;
  onCancel: () => void;
}) {
  const [title, setTitle] = useState("");
  const [notes, setNotes] = useState("");
  const [dueLocal, setDueLocal] = useState("");
  const [priority, setPriority] = useState<Priority>("MEDIUM");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    setBusy(true);
    await onSave({
      title: title.trim(),
      notes: notes.trim() || null,
      due_at: istLocalToIso(dueLocal),
      priority,
    });
    setBusy(false);
  }

  return (
    <form className="card editor standalone" onSubmit={submit}>
      <label className="field">
        <span>New task</span>
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="What needs doing?"
          autoFocus
          required
        />
      </label>
      <label className="field">
        <span>Notes</span>
        <textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </label>
      <div className="two-up">
        <label className="field">
          <span>Due (IST)</span>
          <input type="datetime-local" value={dueLocal} onChange={(e) => setDueLocal(e.target.value)} />
        </label>
        <label className="field">
          <span>Priority</span>
          <select value={priority} onChange={(e) => setPriority(e.target.value as Priority)}>
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {priorityLabel(p)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="actions">
        <button className="btn strong" type="submit" disabled={busy || !title.trim()}>
          {busy ? "Adding…" : "Add"}
        </button>
        <button className="btn" type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
      <p className="hint">It appears on your phone at the next sync, and in this list until then.</p>
    </form>
  );
}

function ReviewRow({ item, mutate }: { item: ReviewItem; mutate: Mutate }) {
  const [open, setOpen] = useState(false);
  const decided = item.web_decision;

  return (
    <div className="row-wrap">
      <div className="row">
        <span className={`bar ${item.priority}`} />
        <button className="body as-button" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
          <span className="title">{item.title}</span>
          <span className="meta">
            <span className="chip">{due(item.due_at)}</span>
            {item.confidence != null && (
              <span className="chip strong">{Math.round(item.confidence * 100)}% sure</span>
            )}
            {item.source_label && <span className="chip">{item.source_label}</span>}
            {decided && (
              <span className="chip pending">
                {decided === "APPROVED" ? "approved" : "rejected"} — waiting for your phone
              </span>
            )}
          </span>
        </button>
      </div>

      {open && (
        <div className="detail">
          {item.evidence && <div className="quote">“{item.evidence}”</div>}
          {item.reasoning && <p className="reasoning">{item.reasoning}</p>}
          <dl className="facts">
            <Fact label="Happened" value={fullDate(item.occurred_at)} />
            <Fact label="Source" value={item.source_type.toLowerCase()} />
            {item.source_app && <Fact label="App" value={item.source_app} />}
            {item.inference_origin && <Fact label="Engine" value={item.inference_origin} />}
          </dl>

          {decided ? (
            <div className="actions">
              <button className="btn" onClick={() => void mutate(() => decideReview(item.id, decided))}>
                Still waiting — leave as {decided === "APPROVED" ? "approved" : "rejected"}
              </button>
              <button
                className="btn"
                onClick={() =>
                  void mutate(() => decideReview(item.id, decided === "APPROVED" ? "REJECTED" : "APPROVED"))
                }
              >
                Change to {decided === "APPROVED" ? "reject" : "approve"}
              </button>
            </div>
          ) : (
            <div className="actions">
              <button className="btn strong" onClick={() => void mutate(() => decideReview(item.id, "APPROVED"))}>
                Approve
              </button>
              <button className="btn danger" onClick={() => void mutate(() => decideReview(item.id, "REJECTED"))}>
                Reject
              </button>
            </div>
          )}
        </div>
      )}
    </div>
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
