CREATE TABLE catalog_inbox_quarantine (
    topic varchar(100) NOT NULL,
    partition_no int NOT NULL,
    record_offset bigint NOT NULL,
    message_key text,
    payload text,
    failure_type varchar(100) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(topic,partition_no,record_offset)
);
