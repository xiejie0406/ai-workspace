-- 历史库在 V12 增加若依用户列后，仍保留引用旧 confirmed_by/corrected_by
-- 的检查约束。新写入只使用 *_ruoyi_user_id，导致真实转写确认被旧约束拒绝。
-- 全新库在 V4 已直接创建正确约束，不含旧列，因此无需改动。
do $voice_ownership_constraints$
begin
    if exists (
        select 1 from information_schema.columns
         where table_schema = 'voice' and table_name = 'transcript'
           and column_name = 'confirmed_by'
    ) then
        alter table voice.transcript drop constraint if exists transcript_check1;
        if not exists (
            select 1 from pg_constraint
             where conrelid = 'voice.transcript'::regclass
               and conname = 'transcript_confirmed_ruoyi_user_check'
        ) then
            alter table voice.transcript
                add constraint transcript_confirmed_ruoyi_user_check
                check ((confirmed_version_id is null) = (confirmed_by_ruoyi_user_id is null)
                    and (confirmed_version_id is null) = (confirmed_at is null));
        end if;
    end if;

    if exists (
        select 1 from information_schema.columns
         where table_schema = 'voice' and table_name = 'transcript_version'
           and column_name = 'corrected_by'
    ) then
        alter table voice.transcript_version drop constraint if exists transcript_version_check1;
        if not exists (
            select 1 from pg_constraint
             where conrelid = 'voice.transcript_version'::regclass
               and conname = 'transcript_version_corrected_ruoyi_user_check'
        ) then
            alter table voice.transcript_version
                add constraint transcript_version_corrected_ruoyi_user_check
                check ((source = 'USER_CORRECTION') = (corrected_by_ruoyi_user_id is not null));
        end if;
    end if;
end;
$voice_ownership_constraints$;
