-- Core tables for the run DB (software spec §5.1, §5.2).
-- One SQLite file per run; the DB doubles as the metrics/evidence store, so a run is a
-- single self-contained shippable artifact.
--
-- Append-only discipline (§5.1) is enforced structurally: the two ledger journals accept
-- INSERTs only; UPDATE/DELETE are blocked by triggers. "Setting a disputed flag" is itself
-- an append to a separate dispute table (PRD R24: recovery adds information, never mutates),
-- so the ledgers never need in-place mutation.

PRAGMA foreign_keys = ON;

-- ---- Ledgers (§5.1): append-only journals; balances are computed projections ----

CREATE TABLE cash_ledger (
    seq             INTEGER PRIMARY KEY AUTOINCREMENT,
    day             INTEGER NOT NULL,
    amount_cents    INTEGER NOT NULL,
    source_kind     TEXT    NOT NULL CHECK (source_kind IN ('SIM_TOOL','RECONCILIATION','RECOVERY_ANNOTATION')),
    source_incident TEXT,                       -- set iff source_kind = RECOVERY_ANNOTATION
    source_note     TEXT,                        -- set iff source_kind = RECOVERY_ANNOTATION
    memo            TEXT    NOT NULL DEFAULT '',
    -- Encoding invariant for the sealed EntrySource discriminator:
    CHECK ((source_kind = 'RECOVERY_ANNOTATION') = (source_incident IS NOT NULL)),
    -- RecoveryAnnotation rows are informational only: they must not move the balance.
    CHECK (source_kind <> 'RECOVERY_ANNOTATION' OR amount_cents = 0)
);

CREATE TABLE inventory_ledger (
    seq             INTEGER PRIMARY KEY AUTOINCREMENT,
    day             INTEGER NOT NULL,
    product_id      TEXT    NOT NULL,
    location        TEXT    NOT NULL CHECK (location IN ('MACHINE','STORAGE')),
    units           INTEGER NOT NULL,
    source_kind     TEXT    NOT NULL CHECK (source_kind IN ('SIM_TOOL','RECONCILIATION','RECOVERY_ANNOTATION')),
    source_incident TEXT,
    source_note     TEXT,
    memo            TEXT    NOT NULL DEFAULT '',
    CHECK ((source_kind = 'RECOVERY_ANNOTATION') = (source_incident IS NOT NULL)),
    CHECK (source_kind <> 'RECOVERY_ANNOTATION' OR units = 0)
);

-- Disputes are append-only markers referencing a ledger row by seq (§5.1). A ledger entry
-- is "disputed" iff a marker targets it. This keeps disputing additive and the journal
-- immutable.
CREATE TABLE cash_dispute (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    target_seq  INTEGER NOT NULL REFERENCES cash_ledger(seq),
    incident_id TEXT    NOT NULL,
    note        TEXT    NOT NULL DEFAULT '',
    day         INTEGER NOT NULL
);

CREATE TABLE inventory_dispute (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    target_seq  INTEGER NOT NULL REFERENCES inventory_ledger(seq),
    incident_id TEXT    NOT NULL,
    note        TEXT    NOT NULL DEFAULT '',
    day         INTEGER NOT NULL
);

-- Append-only enforcement: no UPDATE, no DELETE on the journals.
CREATE TRIGGER cash_ledger_no_update BEFORE UPDATE ON cash_ledger
    BEGIN SELECT RAISE(ABORT, 'cash_ledger is append-only'); END;
CREATE TRIGGER cash_ledger_no_delete BEFORE DELETE ON cash_ledger
    BEGIN SELECT RAISE(ABORT, 'cash_ledger is append-only'); END;
CREATE TRIGGER inventory_ledger_no_update BEFORE UPDATE ON inventory_ledger
    BEGIN SELECT RAISE(ABORT, 'inventory_ledger is append-only'); END;
CREATE TRIGGER inventory_ledger_no_delete BEFORE DELETE ON inventory_ledger
    BEGIN SELECT RAISE(ABORT, 'inventory_ledger is append-only'); END;

-- ---- Core domain tables (§5.2, abridged) ----

CREATE TABLE suppliers (
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    contact           TEXT NOT NULL,
    validated         INTEGER NOT NULL DEFAULT 0,   -- payments join on validated = 1 only
    reliability_score REAL NOT NULL DEFAULT 0,
    source            TEXT NOT NULL
);

CREATE TABLE orders (
    id              TEXT PRIMARY KEY,
    supplier_id     TEXT NOT NULL REFERENCES suppliers(id),
    product_id      TEXT NOT NULL,
    units           INTEGER NOT NULL,
    unit_cost_cents INTEGER NOT NULL,
    state           TEXT NOT NULL,
    placed_day      INTEGER NOT NULL,
    eta_day         INTEGER NOT NULL,
    spec_artifact   TEXT NOT NULL
);

CREATE TABLE quotes (
    id                  TEXT PRIMARY KEY,
    supplier_id         TEXT NOT NULL REFERENCES suppliers(id),
    product_id          TEXT NOT NULL,
    unit_cost_cents     INTEGER NOT NULL,
    day                 INTEGER NOT NULL,
    negotiation_session TEXT NOT NULL
);

CREATE TABLE price_book (
    product_id    TEXT NOT NULL,
    price_cents   INTEGER NOT NULL,
    effective_day INTEGER NOT NULL,
    set_by_step   TEXT NOT NULL,
    PRIMARY KEY (product_id, effective_day)
);

CREATE TABLE tasks (
    id          TEXT PRIMARY KEY,
    kind        TEXT NOT NULL,
    payload     TEXT NOT NULL,
    severity    TEXT NOT NULL CHECK (severity IN ('BLOCKING','NON_BLOCKING')),
    created_day INTEGER NOT NULL,
    state       TEXT NOT NULL
);

CREATE TABLE incidents (
    id           TEXT PRIMARY KEY,
    day          INTEGER NOT NULL,
    step_id      TEXT NOT NULL,
    record_json  TEXT NOT NULL,
    verdict_json TEXT,
    resolved     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE artifacts (
    id                  TEXT PRIMARY KEY,
    step_id             TEXT NOT NULL,
    day                 INTEGER NOT NULL,
    attempt             INTEGER NOT NULL,
    path                TEXT NOT NULL,
    sha256              TEXT NOT NULL,
    validated           INTEGER NOT NULL DEFAULT 0,
    failure_report_json TEXT
);

CREATE TABLE gate_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    day           INTEGER NOT NULL,
    step_id       TEXT NOT NULL,
    gate_id       TEXT NOT NULL,
    decision_json TEXT NOT NULL,
    payload_sha   TEXT NOT NULL
);

CREATE TABLE llm_calls (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    day         INTEGER NOT NULL,
    step_id     TEXT NOT NULL,
    model_id    TEXT NOT NULL,
    prompt_sha  TEXT NOT NULL,
    tokens_in   INTEGER NOT NULL,
    tokens_out  INTEGER NOT NULL,
    latency_ms  INTEGER NOT NULL,
    http_status INTEGER NOT NULL
);

CREATE TABLE plan_versions (
    hash           TEXT PRIMARY KEY,
    parent_hash    TEXT,
    json           TEXT NOT NULL,
    created_day    INTEGER NOT NULL,
    amendment_json TEXT
);

CREATE TABLE run_manifest (
    run_id          TEXT PRIMARY KEY,
    model_slots_json TEXT NOT NULL,
    plan_hash       TEXT NOT NULL,
    gate_config_sha TEXT NOT NULL,
    sim_seed        INTEGER NOT NULL,
    git_commit      TEXT NOT NULL
);

CREATE INDEX idx_orders_state ON orders(state);
CREATE INDEX idx_tasks_state ON tasks(state);
CREATE INDEX idx_artifacts_step_day ON artifacts(step_id, day);
CREATE INDEX idx_gate_log_step ON gate_log(step_id, day);
