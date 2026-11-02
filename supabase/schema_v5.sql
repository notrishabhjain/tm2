-- TaskMind web sync, part five: Today, the Inbox and areas.
--
-- Run this ONCE in the Supabase SQL editor, after schema.sql and schema_v2.sql
-- to schema_v4.sql. Idempotent, like the others.
--
-- Three columns on tasks, all of them things the phone already keeps:
--   area         Work, Home, Money... the area a task is filed under
--   planned_for  the IST day it is on the Today list for
--   inbox        captured and not yet decided on
--
-- The phone checks for these columns before each sync and only sends them once
-- they exist, so sync keeps working before this script is run. The first sync
-- after it re-sends every task once, so older tasks get them too. The web page
-- then shows the Inbox (Today / Later / Drop), today's list, and an area
-- filter, and its changes travel back to the phone like any other edit.
--
-- As before: no messages, no transcripts, nothing beyond the task itself.

alter table public.tasks
    add column if not exists area text;

alter table public.tasks
    add column if not exists planned_for date;

alter table public.tasks
    add column if not exists inbox boolean not null default false;

create index if not exists tasks_planned_idx on public.tasks (owner, planned_for);
