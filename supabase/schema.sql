-- TaskMind web sync: the schema for the Supabase project.
--
-- Run this ONCE, whole, in the Supabase SQL editor. It is written to be safe
-- to run twice - every statement is idempotent - so if a step fails halfway
-- you can fix it and run the file again.
--
-- WHAT IS HERE AND WHAT IS NOT
--
-- Tasks and review items, including the evidence quote that justified each
-- one. NOT here: raw captures, full message bodies, or call transcripts.
-- Those stay on the phone. The phone never uploads them and there is no table
-- for them to land in, so a bug cannot start sending them by accident.
--
-- EVERY table has row-level security on, with a policy that compares the row's
-- owner to the caller's own user id. Without a signed-in session these tables
-- return nothing at all - not an error, an empty result - which is what you
-- want if the anon key ever leaks. The anon key is designed to be public; RLS
-- is the thing that actually protects the data.

-- ---------------------------------------------------------------------------
-- tasks
-- ---------------------------------------------------------------------------

create table if not exists public.tasks (
    -- The phone's own row id. Sync is an upsert on this, so pushing the same
    -- task twice updates it instead of duplicating it.
    id              text primary key,
    owner           uuid not null references auth.users (id) on delete cascade,

    title           text not null,
    notes           text,
    due_at          timestamptz,
    reminder_at     timestamptz,
    priority        text not null,
    status          text not null,
    tags            text[] not null default '{}',
    recurrence_rule text,
    parent_task_id  text,

    -- Provenance. This is the app's whole point, so it travels with the task.
    source_type      text not null,
    source_label     text,
    source_app       text,
    evidence         text,
    confidence       double precision,
    inference_origin text,

    completed_at    timestamptz,
    created_at      timestamptz not null,
    -- The phone's updatedAt, NOT the time the row reached this table. Sync
    -- ordering and any future conflict resolution have to reason about when
    -- the edit happened, not when the network got around to it.
    updated_at      timestamptz not null,

    -- Server-side bookkeeping, for "when did my phone last reach this?".
    synced_at       timestamptz not null default now()
);

create index if not exists tasks_owner_status_due_idx
    on public.tasks (owner, status, due_at);

create index if not exists tasks_owner_updated_idx
    on public.tasks (owner, updated_at desc);

-- ---------------------------------------------------------------------------
-- review_items - the queue of candidates awaiting a yes or no
-- ---------------------------------------------------------------------------

create table if not exists public.review_items (
    id           text primary key,
    owner        uuid not null references auth.users (id) on delete cascade,

    title        text not null,
    notes        text,
    due_at       timestamptz,
    priority     text not null,

    evidence     text,
    confidence   double precision,
    reasoning    text,

    source_type      text not null,
    source_label     text,
    source_app       text,
    inference_origin text,

    occurred_at  timestamptz not null,
    state        text not null,
    created_at   timestamptz not null,
    synced_at    timestamptz not null default now()
);

-- NOTE: review_items.sourceText is deliberately NOT mirrored. On the phone it
-- holds the full message or transcript excerpt the candidate came from, which
-- is exactly the category of data that is staying on the device.

create index if not exists review_items_owner_state_idx
    on public.review_items (owner, state, created_at desc);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
--
-- Policies are dropped and recreated so re-running this file cannot leave a
-- stale policy behind granting more than the current version intends.

alter table public.tasks        enable row level security;
alter table public.review_items enable row level security;

drop policy if exists tasks_owner_all on public.tasks;
create policy tasks_owner_all
    on public.tasks
    for all
    using      (auth.uid() = owner)
    with check (auth.uid() = owner);

drop policy if exists review_items_owner_all on public.review_items;
create policy review_items_owner_all
    on public.review_items
    for all
    using      (auth.uid() = owner)
    with check (auth.uid() = owner);

-- ---------------------------------------------------------------------------
-- owner defaults
-- ---------------------------------------------------------------------------
--
-- So the phone never has to send its own user id and cannot get it wrong. The
-- WITH CHECK policy above still rejects any attempt to write someone else's
-- row, default or no default.

alter table public.tasks        alter column owner set default auth.uid();
alter table public.review_items alter column owner set default auth.uid();

-- ---------------------------------------------------------------------------
-- synced_at maintenance
-- ---------------------------------------------------------------------------
--
-- Set on the server rather than trusted from the phone, so "last seen" means
-- what it says even if a device clock is wrong.

create or replace function public.touch_synced_at()
returns trigger
language plpgsql
as $$
begin
    new.synced_at := now();
    return new;
end;
$$;

drop trigger if exists tasks_touch_synced_at on public.tasks;
create trigger tasks_touch_synced_at
    before insert or update on public.tasks
    for each row execute function public.touch_synced_at();

drop trigger if exists review_items_touch_synced_at on public.review_items;
create trigger review_items_touch_synced_at
    before insert or update on public.review_items
    for each row execute function public.touch_synced_at();
