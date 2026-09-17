/** The two tables the phone pushes. Column names are snake_case, as Postgres. */

export type Priority = "URGENT" | "HIGH" | "MEDIUM" | "LOW";
export type TaskStatus = "ACTIVE" | "COMPLETED" | "ARCHIVED" | "DELETED";

export interface Task {
  id: string;
  title: string;
  notes: string | null;
  due_at: string | null;
  reminder_at: string | null;
  priority: Priority;
  status: TaskStatus;
  tags: string[];
  recurrence_rule: string | null;
  parent_task_id: string | null;
  source_type: string;
  source_label: string | null;
  source_app: string | null;
  /** The verbatim quote that justified this task. The app's whole point. */
  evidence: string | null;
  confidence: number | null;
  inference_origin: string | null;
  completed_at: string | null;
  created_at: string;
  updated_at: string;
  synced_at: string;
  /** Set by this app on every edit, never by the phone. How the phone knows
   *  a change came from here rather than seeing its own push echoed back. */
  web_updated_at: string | null;
  /**
   * Tags the phone worked out: who said it, which app, whether it was a call,
   * and roughly what sort of thing it is. Derived on the phone and re-sent on
   * every push, so the rules exist in one place rather than two.
   */
  auto_tags: string[];
}

export interface ReviewItem {
  id: string;
  title: string;
  notes: string | null;
  due_at: string | null;
  priority: Priority;
  evidence: string | null;
  confidence: number | null;
  reasoning: string | null;
  source_type: string;
  source_label: string | null;
  source_app: string | null;
  inference_origin: string | null;
  occurred_at: string;
  state: string;
  created_at: string;
  synced_at: string;
  /** What you decided here. The phone carries it out - approving has to create
   *  a task, and only the intake funnel on the phone may do that. */
  web_decision: "APPROVED" | "REJECTED" | null;
  web_decided_at: string | null;
}

/**
 * A task typed here, waiting for the phone to pick it up.
 *
 * Its own table rather than a row in `tasks`, because the phone's intake
 * funnel assigns a task its id - so a task invented here cannot keep the id
 * this app gave it. While a row is still here, the phone has not created it
 * yet, which is exactly what the list should say.
 */
export interface NewTask {
  id: string;
  title: string;
  notes: string | null;
  due_at: string | null;
  priority: Priority;
  created_at: string;
}
