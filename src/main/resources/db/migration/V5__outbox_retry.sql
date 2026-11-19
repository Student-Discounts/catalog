ALTER TABLE catalog_outbox ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT '-infinity';
ALTER TABLE catalog_outbox ADD COLUMN delivery_failures int NOT NULL DEFAULT 0 CHECK(delivery_failures >= 0);
