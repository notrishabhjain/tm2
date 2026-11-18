"use client";

import { useCallback, useEffect, useState } from "react";
import { supabase } from "@/lib/supabase";
import { fullDate, relative } from "@/lib/format";

/**
 * The Twin on the web page (v1.36).
 *
 * The question goes into `twin_questions`. The phone - where the memory and
 * the AI key live - picks it up within about a minute, answers it, and writes
 * the answer back here with the message snippets it was given. Changes the
 * Twin proposes are applied on the phone, never from this page. Kept 30 days.
 */

type Snippet = { at: string; from: string; text: string };

type Question = {
  id: string;
  question: string;
  status: "asked" | "answering" | "answered" | "failed";
  answer: string | null;
  note: string | null;
  task_titles: string[];
  proposed_changes: number;
  snippets: Snippet[];
  asked_at: string;
  answered_at: string | null;
};

const KEEP_DAYS = 30;
const SUGGESTIONS = ["What is on my plate today?", "Who owes me what?", "What did we agree with the vendor last week?"];

export default function TwinPanel() {
  const [rows, setRows] = useState<Question[]>([]);
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [missing, setMissing] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    const since = new Date(Date.now() - KEEP_DAYS * 24 * 60 * 60 * 1000).toISOString();
    const { data, error: err } = await supabase
      .from("twin_questions")
      .select("*")
      .gte("asked_at", since)
      .order("asked_at", { ascending: false })
      .limit(50);
    setLoaded(true);
    if (err) {
      // 42P01 / PGRST205: the table is not there yet (schema_v6.sql not run).
      if (err.code === "42P01" || err.code === "PGRST205" || /twin_questions/.test(err.message)) setMissing(true);
      else setError(err.message);
      return;
    }
    setMissing(false);
    setRows((data ?? []) as Question[]);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // While anything waits for the phone, look again every few seconds.
  const waiting = rows.some((r) => r.status === "asked" || r.status === "answering");
  useEffect(() => {
    if (!waiting) return;
    const t = setInterval(() => void load(), 5000);
    return () => clearInterval(t);
  }, [waiting, load]);

  async function ask(question: string) {
    const q = question.trim();
    if (!q || sending) return;
    setSending(true);
    setError(null);
    const { error: err } = await supabase
      .from("twin_questions")
      .insert({ id: crypto.randomUUID(), question: q.slice(0, 2000) });
    setSending(false);
    if (err) {
      setError(err.message);
      return;
    }
    setText("");
    await load();
  }

  async function remove(id: string) {
    const { error: err } = await supabase.from("twin_questions").delete().eq("id", id);
    if (err) setError(err.message);
    else setRows((r) => r.filter((x) => x.id !== id));
  }

  if (loaded && missing) {
    return (
      <div className="empty">
        <strong>One more step for the Twin</strong>
        Run <code>supabase/schema_v6.sql</code> once in your Supabase SQL editor, then refresh this page.
      </div>
    );
  }

  return (
    <div className="twin">
      <form
        className="card twin-ask"
        onSubmit={(e) => {
          e.preventDefault();
          void ask(text);
        }}
      >
        <textarea
          rows={2}
          value={text}
          maxLength={2000}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              void ask(text);
            }
          }}
          placeholder="Ask your Twin anything about your tasks, people and chats"
          aria-label="Ask your Twin"
        />
        <div className="actions">
          <button className="btn strong" type="submit" disabled={sending || !text.trim()}>
            {sending ? "Sending…" : "Ask"}
          </button>
          {SUGGESTIONS.map((s) => (
            <button key={s} type="button" className="tagchip" onClick={() => void ask(s)}>
              {s}
            </button>
          ))}
        </div>
        <p className="hint">
          Your phone answers, using its memory, usually within a minute while it is on and online. Changes the Twin
          suggests are applied on the phone. Questions and answers are kept here for {KEEP_DAYS} days.
        </p>
      </form>

      {error && <div className="error">{error}</div>}

      {loaded && rows.length === 0 && (
        <div className="empty">
          <strong>No questions yet</strong>
          Ask one above.
        </div>
      )}

      {rows.map((r) => (
        <div key={r.id} className="card twin-item">
          <div className="twin-q">
            <span className="title">{r.question}</span>
            <span className="chip" title={fullDate(r.asked_at)}>
              {relative(r.asked_at)}
            </span>
          </div>
          {r.status === "asked" && <p className="twin-wait">Waiting for your phone…</p>}
          {r.status === "answering" && <p className="twin-wait">Your phone is answering…</p>}
          {r.status === "failed" && <div className="error">{r.note ?? "The phone could not answer this."}</div>}
          {r.status === "answered" && (
            <>
              <p className="twin-a">{r.answer}</p>
              {r.task_titles.length > 0 && (
                <div className="meta">
                  {r.task_titles.map((t, i) => (
                    <span key={i} className="chip strong">
                      {t}
                    </span>
                  ))}
                </div>
              )}
              {r.proposed_changes > 0 && (
                <p className="hint">
                  The Twin suggested {r.proposed_changes} change{r.proposed_changes === 1 ? "" : "s"} to your list. Ask
                  again in the Twin tab on your phone to review and apply them.
                </p>
              )}
              {r.snippets.length > 0 && (
                <details className="twin-sources">
                  <summary>
                    From your messages ({r.snippets.length})
                  </summary>
                  {r.snippets.map((s, i) => (
                    <div key={i} className="quote">
                      {s.text}
                      <div className="note">
                        {s.from} · {fullDate(s.at)}
                      </div>
                    </div>
                  ))}
                </details>
              )}
              {r.note && <p className="hint">{r.note}</p>}
            </>
          )}
          <div className="actions">
            <button className="link" onClick={() => void remove(r.id)}>
              Delete
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}
