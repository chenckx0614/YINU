begin;

create sequence if not exists public.yinu_record_revision;
revoke all on sequence public.yinu_record_revision from public, anon, authenticated;

create table if not exists public.yinu_sync_records (
    user_id uuid not null references auth.users(id) on delete cascade,
    record_id text not null check (char_length(record_id) between 1 and 128),
    revision bigint not null,
    updated_at_ms bigint not null check (updated_at_ms >= 0),
    digest text not null check (digest ~ '^[0-9a-f]{64}$'),
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    primary key (user_id, record_id)
);

create index if not exists yinu_sync_records_user_revision
    on public.yinu_sync_records (user_id, revision);

alter table public.yinu_sync_records enable row level security;
revoke all on table public.yinu_sync_records from public, anon, authenticated;
grant select on table public.yinu_sync_records to authenticated;
drop policy if exists yinu_owner_read on public.yinu_sync_records;
create policy yinu_owner_read on public.yinu_sync_records
    for select to authenticated using ((select auth.uid()) = user_id);

create or replace function public.yinu_sync_push(changes jsonb)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    owner_id uuid := auth.uid();
begin
    if owner_id is null then
        raise exception 'Sign in required' using errcode = '42501';
    end if;
    if changes is null or pg_catalog.jsonb_typeof(changes) <> 'array' then
        raise exception 'Expected a change array' using errcode = '22023';
    end if;
    if pg_catalog.jsonb_array_length(changes) > 25 or pg_catalog.octet_length(changes::text) > 4194304 then
        raise exception 'Change batch too large' using errcode = '22023';
    end if;

    -- Serialize writes for each account so a pull cursor cannot skip a late commit.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(owner_id::text, 0));
    insert into public.yinu_sync_records as current_row
        (user_id, record_id, revision, updated_at_ms, digest, payload)
    select owner_id, item.record_id, pg_catalog.nextval('public.yinu_record_revision'), item.updated_at_ms, item.digest, item.payload
    from pg_catalog.jsonb_to_recordset(changes) as item(record_id text, updated_at_ms bigint, digest text, payload jsonb)
    on conflict (user_id, record_id) do update
        set revision = excluded.revision,
            updated_at_ms = excluded.updated_at_ms,
            digest = excluded.digest,
            payload = excluded.payload
        where (current_row.updated_at_ms, current_row.digest collate "C")
            < (excluded.updated_at_ms, excluded.digest collate "C");
end;
$$;

revoke all on function public.yinu_sync_push(jsonb) from public, anon, authenticated;
grant execute on function public.yinu_sync_push(jsonb) to authenticated;

commit;
