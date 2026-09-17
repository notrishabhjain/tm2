-- TaskMind web sync, part two: edits from the browser.
--
-- Run this ONCE in the Supabase SQL editor, after schema.sql. Like that file
-- it is idempotent, so running it twice is harmless.
--
-- THE ONE IDEA HERE
--
-- Every column below is written by the BROWSER and never by the phone. That is
-- what makes the direction of a change unambiguous. The phone's push does not
-- include these columns, so an upsert leaves them alone, and the phone can ask
-- "what has the browser touched since I last looked?" without having to guess
-- whether it is seeing its own echo.
--
-- Without that separation the phone would have to compare its own pushed rows
-- against what came back and infer intent from timestamps - which is exactly
-- the kind of reasoning that produces a sync loop nobody can reproduce.

-- ---------------------------------------------------------------------------
-- tasks: edits made in the browser
-- ---------------------------------------------------------------------------

-- Set by the browser on every edit it makes. NULL means the browser has never
-- touched this row. The phone pulls rows where this is newer than its
-- watermark, and compares it against the task's own updatedAt to decide which
-- side edited last.
alter table public.tasks
    add column if not exists web_updated_at timestamptz;

create index if not exists tasks_owner_web_updated_idx
    on public.tasks (owner, web_updated_at)
    where web_updated_at is not null;

-- ---------------------------------------------------------------------------
-- review_items: approve or reject from the browser
-- ---------------------------------------------------------------------------
--
-- A decision, not a result. Approving a candidate has to create a task, and
-- spec 5 says only the intake funnel may do that - so the browser records what
-- you decided and the phone carries it out through the same code path the
-- phone's own Approve button uses. The web never creates a task itself.

alter table public.review_items
    add column if not exists web_decision text
        check (web_decision is null or web_decision in ('APPROVED', 'REJECTED'));

alter table public.review_items
    add column if not exists web_decided_at timestamptz;

create index if not exists review_items_owner_decision_idx
    on public.review_items (owner, web_decision)
    where web_decision is not null;

-- ---------------------------------------------------------------------------
-- web_new_tasks: tasks typed into the browser, waiting for the phone
-- ---------------------------------------------------------------------------
--
-- A separate table rather than a row in `tasks`, for the same reason as above:
-- the funnel assigns a task its id, so a task invented in the browser cannot
-- keep the id the browser gave it. Draining from here means `tasks` only ever
-- holds canonical rows, with no id to reconcile and no placeholder appearing
-- in the list and then changing underneath you.
--
-- The phone creates the real task through the funnel and deletes the row here.
-- A row still present is a task the phone has not picked up yet, which is
-- exactly what the browser should show you.

create table if not exists public.web_new_tasks (
    id         text primary key,
    owner      uuid not null references auth.users (id) on delete cascade,
    title      text not null,
    notes      text,
    due_at     timestamptz,
    priority   text not null default 'MEDIUM',
    created_at timestamptz not null default now()
);

alter table public.web_new_tasks alter column owner set default auth.uid();

create index if not exists web_new_tasks_owner_created_idx
    on public.web_new_tasks (owner, created_at);

-- ---------------------------------------------------------------------------
-- Row-level security for the new table
-- ---------------------------------------------------------------------------

alter table public.web_new_tasks enable row level security;

drop policy if exists web_new_tasks_owner_all on public.web_new_tasks;
create policy web_new_tasks_owner_all
    on public.web_new_tasks
    for all
    using      (auth.uid() = owner)
    with check (auth.uid() = owner);
