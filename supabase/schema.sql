-- Scribe schema. Run once in the Supabase SQL editor.
--
-- Only text is stored server-side: transcripts and generated notes. Audio never
-- leaves the device except as a short-lived segment upload to the transcription
-- function, which does not persist it.

create table if not exists public.sessions (
  id          uuid primary key,
  user_id     uuid not null references auth.users (id) on delete cascade,
  title       text not null default 'Untitled',
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  duration_ms bigint not null default 0,
  language    text not null default '',
  transcript  text not null default '',
  notes       jsonb
);

create index if not exists sessions_user_created_idx
  on public.sessions (user_id, created_at desc);

alter table public.sessions enable row level security;

-- Each policy is scoped to the owner; there is no shared read path.
drop policy if exists "own sessions: select" on public.sessions;
create policy "own sessions: select" on public.sessions
  for select using (auth.uid() = user_id);

drop policy if exists "own sessions: insert" on public.sessions;
create policy "own sessions: insert" on public.sessions
  for insert with check (auth.uid() = user_id);

drop policy if exists "own sessions: update" on public.sessions;
create policy "own sessions: update" on public.sessions
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own sessions: delete" on public.sessions;
create policy "own sessions: delete" on public.sessions
  for delete using (auth.uid() = user_id);
