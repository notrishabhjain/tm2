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
}
