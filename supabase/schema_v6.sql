-- TaskMind web sync, part six: the Twin on the web page (v1.36).
--
-- Run this ONCE in the Supabase SQL editor, after schema.sql and schema_v2.sql
-- to schema_v5.sql. Idempotent, like the others.
--
-- The page writes a question here. The phone - where the memory and the AI
-- key live - picks it up, answers it, and writes the answer back. What is
-- stored: the question, the answer, the titles of tasks it names, how many
-- changes it proposed (applied on the phone, never from here), and the
-- message snippets the answer was given: at most 8, each at most 280
-- characters, with phone numbers, emails and links masked. Rows older than
-- 30 days are deleted by the phone; the page shows only the last 30 days.

create table if not exists public.twin_questions (
    id               text primary key,
    owner            uuid not null default auth.uid() references auth.users (id) on delete cascade,
    question         text not null check (char_length(question) between 1 and 2000),
    -- asked -> answering -> answered | failed
    status           text not null default 'asked'
                     check (status in ('asked', 'answering', 'answered', 'failed')),
    answer           text,
    note             text,
    task_titles      text[] not null default '{}',
    proposed_changes integer not null default 0,
    snippets         jsonb not null default '[]'::jsonb,
    asked_at         timestamptz not null default now(),
    answered_at      timestamptz
);

create index if not exists twin_questions_owner_asked_idx
    on public.twin_questions (owner, asked_at desc);

create index if not exists twin_questions_open_idx
    on public.twin_questions (owner, status)
    where status = 'asked';

alter table public.twin_questions enable row level security;

drop policy if exists twin_questions_owner_all on public.twin_questions;
create policy twin_questions_owner_all
    on public.twin_questions
    for all
    using      (auth.uid() = owner)
    with check (auth.uid() = owner);
