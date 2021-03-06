DROP INDEX IF EXISTS
idx_qrtz_triggers_job_group_sched_name_job_name,
idx_qrtz_triggers_job_group_sched_name_job_name,
idx_qrtz_simple_triggers_trigger_group_sched_name_trigger_name,
idx_qrtz_simprop_triggers_trigger_name_trigger_group_sched_name CASCADE;

DROP TABLE IF EXISTS
qrtz_blob_triggers,
qrtz_calendars,
qrtz_cron_triggers,
qrtz_fired_triggers,
qrtz_job_details,
qrtz_locks,
qrtz_paused_trigger_grps,
qrtz_scheduler_state,
qrtz_simple_triggers,
qrtz_simprop_triggers,
qrtz_triggers CASCADE;

