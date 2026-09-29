CREATE TABLE task (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    prompt TEXT NOT NULL,
    branch TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX task_status ON task(status);

CREATE TABLE agent_run (
    id TEXT NOT NULL PRIMARY KEY,
    task_id TEXT NOT NULL,
    state TEXT NOT NULL,
    mode TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    finished_at INTEGER,
    elapsed_millis INTEGER NOT NULL,
    cost_micros INTEGER NOT NULL,
    cost_known INTEGER NOT NULL
);
CREATE INDEX agent_run_task ON agent_run(task_id);

CREATE TABLE tool_call (
    id TEXT NOT NULL PRIMARY KEY,
    run_id TEXT NOT NULL,
    tool TEXT NOT NULL,
    outcome TEXT NOT NULL,
    required_approval INTEGER NOT NULL,
    duration_millis INTEGER NOT NULL,
    at INTEGER NOT NULL
);
CREATE INDEX tool_call_run ON tool_call(run_id);
CREATE INDEX tool_call_tool ON tool_call(tool);

CREATE TABLE review_decision (
    packet_id TEXT NOT NULL,
    packet_revision INTEGER NOT NULL,
    scope TEXT NOT NULL,
    target_hunk_id TEXT,
    value TEXT NOT NULL,
    client_platform TEXT NOT NULL,
    decided_at INTEGER NOT NULL,
    PRIMARY KEY (packet_id, packet_revision, scope, target_hunk_id, decided_at)
);
CREATE INDEX review_decision_packet ON review_decision(packet_id);
CREATE INDEX review_decision_platform ON review_decision(client_platform);

CREATE TABLE tool_permission (
    tool TEXT NOT NULL PRIMARY KEY,
    read_permission TEXT NOT NULL,
    write_permission TEXT NOT NULL
);
