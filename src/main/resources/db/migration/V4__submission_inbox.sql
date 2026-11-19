CREATE TABLE catalog_submission_inbox (
    event_id uuid PRIMARY KEY,
    topic varchar(100) NOT NULL,
    payload jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE catalog_submission_state (
    submission_id uuid PRIMARY KEY,
    aggregate_sequence bigint NOT NULL DEFAULT 0,
    version_id uuid,
    version_no int,
    status varchar(40),
    row_version int,
    correlation_id varchar(64),
    publication_outcome varchar(40),
    publication_error varchar(100)
);
CREATE TABLE catalog_submission_versions (
    version_id uuid PRIMARY KEY,
    submission_id uuid NOT NULL,
    version_no int NOT NULL CHECK(version_no > 0),
    payload jsonb NOT NULL,
    base_offer_row_version int CHECK(base_offer_row_version >= 0),
    UNIQUE(submission_id,version_no)
);
CREATE TABLE catalog_submission_metadata (
    version_id uuid PRIMARY KEY,
    submission_id uuid NOT NULL,
    version_no int NOT NULL CHECK(version_no > 0),
    submission_type varchar(20) NOT NULL CHECK(submission_type IN ('NEW_PLACE','NEW_OFFER','CHANGE')),
    resolved_timezone varchar(64) NOT NULL,
    base_place_row_version int CHECK(base_place_row_version >= 0),
    UNIQUE(submission_id,version_no)
);
CREATE UNIQUE INDEX catalog_submission_sequence_idx ON catalog_submission_inbox
    ((payload->'data'->>'submissionId'),((payload->>'aggregateSequence')::bigint))
    WHERE topic='submission.events.v1';
CREATE INDEX catalog_approved_pending_idx ON catalog_submission_state(submission_id)
    WHERE status='APPROVED' AND publication_outcome IS NULL;
