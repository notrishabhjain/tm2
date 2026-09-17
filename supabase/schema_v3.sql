-- TaskMind web sync, part three: the tags the app works out for itself.
--
-- Run this ONCE in the Supabase SQL editor, after schema.sql and schema_v2.sql.
-- Idempotent, like the others.
--
-- WHY THE TAGS ARE SENT RATHER THAN RE-DERIVED HERE
--
-- The phone computes these from the source label, the app package, the title
-- and the evidence quote. The web app could run the same rules - but then the
-- rules would exist twice, in two languages, and would drift the first time
-- one side was changed and the other was not. Sending them means one
-- implementation, tested once, in the place that already has all the inputs.
--
-- They are still derived, not authored: nothing here is a tag anyone typed,
-- and the phone recomputes and re-sends them whenever a task changes. Editing
-- this column by hand would simply be overwritten.

alter table public.tasks
    add column if not exists auto_tags text[] not null default '{}';

-- Supports `auto_tags @> '{whatsapp}'` - the containment query the web app's
-- tag filter uses. A plain btree index cannot answer that; GIN can.
create index if not exists tasks_auto_tags_idx
    on public.tasks using gin (auto_tags);
