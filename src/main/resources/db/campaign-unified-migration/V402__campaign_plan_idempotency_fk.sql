-- Add a real foreign key from campaign_plan_compile_idempotency.plan_id to
-- campaign_plan(plan_id), so the database enforces referential integrity and
-- an orphan idempotency row (with no corresponding plan) cannot exist.
--
-- The migration uses an idempotent guard: if the constraint already exists the
-- block is a no-op. It never drops or rewrites an accepted object.

do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conname = 'campaign_plan_compile_idempotency_plan_id_fk'
           and conrelid = 'campaign_plan_compile_idempotency'::regclass
    ) then
        alter table campaign_plan_compile_idempotency
            add constraint campaign_plan_compile_idempotency_plan_id_fk
                foreign key (plan_id) references campaign_plan (plan_id);
    end if;
end
$$;