ALTER TABLE places ADD COLUMN event_revision int NOT NULL DEFAULT 0 CHECK (event_revision >= 0);
ALTER TABLE places ADD COLUMN event_snapshot jsonb;
ALTER TABLE offers ADD COLUMN event_revision int NOT NULL DEFAULT 0 CHECK (event_revision >= 0);
ALTER TABLE offers ADD COLUMN event_snapshot jsonb;
ALTER TABLE catalog_outbox ALTER COLUMN version_id DROP NOT NULL;
ALTER TABLE catalog_outbox ADD COLUMN delivery_sequence bigint GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX catalog_outbox_delivery_sequence_idx ON catalog_outbox(delivery_sequence);
CREATE INDEX catalog_outbox_pending_order_idx ON catalog_outbox(topic, message_key, delivery_sequence)
    WHERE published_at IS NULL;
CREATE INDEX offers_expiration_idx ON offers(place_id, valid_until, id)
    WHERE status = 'ACTIVE' AND valid_until IS NOT NULL;
