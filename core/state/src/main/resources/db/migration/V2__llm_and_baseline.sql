-- Stage 3 tables (software spec §11, §12): model qualification results and the naive baseline
-- arm's per-day trace. The run DB stays a single self-contained artifact — qualification scores
-- and baseline runs are attributable from the same file (CLAUDE.md #11).

-- Qualification battery results. One row per (model, battery) run; cases_json is the full
-- per-case breakdown so a score is auditable, not just a number.
CREATE TABLE qualification (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    model_id      TEXT    NOT NULL,
    battery_hash  TEXT    NOT NULL,
    total         INTEGER NOT NULL,
    passed        INTEGER NOT NULL,
    score         REAL    NOT NULL,
    threshold     REAL    NOT NULL,
    qualified     INTEGER NOT NULL,
    cases_json    TEXT    NOT NULL
);

-- Per-day sim outcome (the DayReport), captured for both baseline and (later) harness runs.
CREATE TABLE day_report (
    run_id      TEXT    NOT NULL,
    day         INTEGER NOT NULL,
    report_json TEXT    NOT NULL,
    PRIMARY KEY (run_id, day)
);

-- The naive baseline arm's decision trace: what the model was asked, what it said, and what the
-- parser managed to apply. This is the honest control's evidence (software spec §12).
CREATE TABLE baseline_action (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id        TEXT    NOT NULL,
    day           INTEGER NOT NULL,
    raw_response  TEXT    NOT NULL,
    parsed_ok     INTEGER NOT NULL,
    applied_json  TEXT    NOT NULL
);

-- Attribute a run to its kind/model/config without overloading the harness-shaped columns.
ALTER TABLE run_manifest ADD COLUMN run_kind       TEXT NOT NULL DEFAULT 'harness';
ALTER TABLE run_manifest ADD COLUMN model_id       TEXT NOT NULL DEFAULT '';
ALTER TABLE run_manifest ADD COLUMN sim_config_sha TEXT NOT NULL DEFAULT '';
