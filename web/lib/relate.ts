/**
 * Related tasks and waiting-on, as the phone works them out.
 *
 * Ports of `relate/TaskRelations.kt` and `core/WaitingOn.kt`, kept as close
 * to line-for-line as the languages allow so the browser groups and labels
 * tasks the way the phone does. The phone's own tests pin the behaviour;
 * if these drift, it is these that are wrong.
 */
import type { Task } from "./types";

// -- waiting on ---------------------------------------------------------------

const DONE_WORDS =
  "(confirmed|sent|shared|replied|responded|paid|delivered|called( back)?|done|completed|" +
  "finished|signed|approved|uploaded|submitted|fixed|arranged|booked|forwarded|got back)";

const WAITING_TITLES = [
  /^follow[ -]?up\b/i,
  /^chase\b/i,
  /^check (if|whether)\b/i,
  new RegExp(`^check (that )?[\\p{L}.' ]{1,40}? (has |have |had )?${DONE_WORDS}\\b`, "iu"),
  /^make sure [\p{L}.']+ /iu,
  /^remind [\p{L}.' ]{1,40}? to\b/iu,
  /^wait(ing)? (for|on)\b/i,
];

/** Somebody else's move: a promise made to you, something to chase. */
export function isWaitingOn(task: Pick<Task, "title" | "tags">): boolean {
  if (task.tags.some((t) => t.toLowerCase() === "waiting-on")) return true;
  const title = task.title.trim();
  return WAITING_TITLES.some((re) => re.test(title));
}

// -- who it came from -----------------------------------------------------------

/** "Sharma Ji in Vendors - WhatsApp" -> person "Sharma Ji", group "Vendors". */
export function parseLabel(label: string | null): { person: string | null; group: string | null } {
  const text = (label ?? "").trim();
  if (!text) return { person: null, group: null };
  const head = text.includes(" - ") ? text.slice(0, text.lastIndexOf(" - ")) : text;
  const at = head.indexOf(" in ");
  const person = (at >= 0 ? head.slice(0, at) : head).trim() || null;
  const group = at >= 0 ? head.slice(at + 4).trim() || null : null;
  return { person, group };
}

// -- related ---------------------------------------------------------------

const TOPICS = new Set(["payment", "document", "meeting", "call-back", "send", "follow-up"]);

const STOPWORDS = new Set([
  "the", "and", "for", "with", "you", "your", "please", "kindly", "need", "needs", "will", "can",
  "could", "would", "should", "have", "has", "had", "are", "was", "were", "this", "that", "them",
  "they", "from", "about", "into", "out", "not", "but", "all", "any", "get", "got", "let", "make",
  "sure", "asap", "today", "tomorrow", "yesterday", "morning", "evening", "night", "day", "week",
  "month", "time", "also", "just", "very", "some",
  "kar", "karo", "karna", "kare", "karke", "hai", "hain", "tha", "thi", "kya", "koi", "abhi", "aap",
  "aapka", "aapke", "mera", "mere", "meri", "unka", "unke", "iska", "iske", "nahi", "nahin", "haan",
  "bhi", "aur", "par", "wala", "wale", "jaldi", "zaroor", "zarur", "dena", "dijiye", "kijiye",
  "chahiye", "raha", "rahe", "rahi", "liye",
  "है", "हैं", "और", "कर", "करो", "करना", "दो", "दें", "लिए", "नहीं", "आप", "मेरा", "मेरे", "यह",
  "कृपया", "जल्दी", "अभी",
]);

export function words(text: string): Set<string> {
  return new Set(
    text
      .toLowerCase()
      .split(/[\s,.;:!?\-–—()[\]{}/\\"'`|।]+/u)
      // Letters, digits and combining marks: Devanagari vowel signs are marks,
      // and dropping them turns words into different words.
      .map((part) => part.replace(/[^\p{L}\p{N}\p{M}]/gu, ""))
      .filter((w) => [...w].length >= 3 && !STOPWORDS.has(w))
      .map((w) => (w.length > 3 && w.endsWith("s") && !w.endsWith("ss") ? w.slice(0, -1) : w)),
  );
}

function overlap(a: string, b: string): number {
  const left = words(a);
  const right = words(b);
  if (left.size === 0 || right.size === 0) return 0;
  let shared = 0;
  left.forEach((w) => {
    if (right.has(w)) shared++;
  });
  const total = new Set([...left, ...right]).size;
  return total === 0 ? 0 : shared / total;
}

function istDay(iso: string | null): string | null {
  if (!iso) return null;
  return new Date(new Date(iso).getTime() + 5.5 * 3600_000).toISOString().slice(0, 10);
}

interface Relatable {
  task: Task;
  person: string | null;
  group: string | null;
  topics: string[];
  day: string | null;
}

function same(a: string | null, b: string | null): boolean {
  return !!a && !!b && a.toLowerCase() === b.toLowerCase();
}

function points(a: Relatable, b: Relatable): number {
  let p = 0;
  if (same(a.person, b.person)) p += 45;
  if (same(a.group, b.group)) p += 30;
  p += Math.round(35 * overlap(a.task.title, b.task.title));
  if (a.topics.some((t) => b.topics.includes(t))) p += 15;
  if (a.day && a.day === b.day) p += 10;
  return Math.min(p, 100);
}

export interface Bundle {
  key: string;
  label: string;
  reason: string;
  tasks: Task[];
}

/** Groups of two or more tasks that belong together, biggest first. */
export function bundles(tasks: Task[]): { bundles: Bundle[]; loose: Task[] } {
  const items: Relatable[] = tasks.map((task) => {
    const { person, group } = parseLabel(task.source_label);
    return {
      task,
      person,
      group,
      topics: (task.auto_tags ?? []).filter((t) => TOPICS.has(t.toLowerCase())),
      day: istDay(task.due_at),
    };
  });
  const parent = items.map((_, i) => i);
  const find = (i: number): number => {
    while (parent[i] !== i) {
      parent[i] = parent[parent[i]];
      i = parent[i];
    }
    return i;
  };
  for (let i = 0; i < items.length; i++) {
    for (let j = i + 1; j < items.length; j++) {
      if (points(items[i], items[j]) >= 45) {
        const ri = find(i);
        const rj = find(j);
        if (ri !== rj) parent[ri] = rj;
      }
    }
  }
  const groups = new Map<number, Relatable[]>();
  items.forEach((item, i) => {
    const root = find(i);
    groups.set(root, [...(groups.get(root) ?? []), item]);
  });
  const out: Bundle[] = [];
  const loose: Task[] = [];
  groups.forEach((members) => {
    if (members.length < 2) {
      loose.push(members[0].task);
      return;
    }
    out.push(describe(members));
  });
  out.sort((a, b) => b.tasks.length - a.tasks.length || a.label.localeCompare(b.label));
  return { bundles: out, loose };
}

function describe(members: Relatable[]): Bundle {
  const tasks = members.map((m) => m.task);
  const key = tasks.map((t) => t.id).sort().join("|");
  const person = members[0].person;
  if (person && members.every((m) => same(m.person, person))) {
    return { key, label: person, reason: `All from ${person}`, tasks };
  }
  const group = members[0].group;
  if (group && members.every((m) => same(m.group, group))) {
    return { key, label: group, reason: `All in ${group}`, tasks };
  }
  const topic = members[0].topics.find((t) => members.every((m) => m.topics.includes(t)));
  if (topic) return { key, label: topic[0].toUpperCase() + topic.slice(1), reason: "Same topic", tasks };
  return { key, label: "Related", reason: "Similar wording", tasks };
}
