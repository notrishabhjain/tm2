-- TaskMind web sync, part four: matters.
--
-- Run this ONCE in the Supabase SQL editor, after schema.sql, schema_v2.sql
-- and schema_v3.sql. Idempotent, like the others.
--
-- A matter is an ongoing piece of work - "Vendor PO", "SRE hiring" - that the
-- phone files tasks under. What comes here is the matter's name and the
-- one-line "where this stands" the phone writes from its tasks. Nothing else:
-- no messages, no transcripts.
--
-- The phone checks for this table before each sync and only sends matters,
-- and the matter on each task, once it exists. So sync keeps working before
-- this script is run; the web app simply shows no matters until it is.

create table if not exists public.matters (
    -- The phone's own id for the matter, so pushing again updates it.
    id          text primary key,
    owner       uuid not null default auth.uid() references auth.users (id) on delete cascade,
    name        text not null,
    -- "You owe 2 - next: Send the signed PO, due Fri. Waiting on Rahul."
    summary     text,
    open_count  integer not null default 0,
    updated_at  timestamptz not null,
    synced_at   timestamptz not null default now()
);

create index if not exists matters_owner_idx on public.matters (owner, name);

alter table public.matters enable row level security;

drop policy if exists matters_owner_all on public.matters;
create policy matters_owner_all
    on public.matters
    for all
    using      (auth.uid() = owner)
    with check (auth.uid() = owner);

drop trigger if exists matters_touch_synced_at on public.matters;
create trigger matters_touch_synced_at
    before insert or update on public.matters
    for each row execute function public.touch_synced_at();

-- Which matter each task belongs to. Text, not a foreign key: the phone may
-- send a task a moment before its matter, and a constraint would reject it.
alter table public.tasks
    add column if not exists matter_id text;

create index if not exists tasks_matter_idx on public.tasks (owner, matter_id);
