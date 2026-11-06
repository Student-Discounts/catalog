ALTER TABLE categories ADD CONSTRAINT categories_name_not_blank CHECK (btrim(name) <> '');
ALTER TABLE places ADD CONSTRAINT places_address_normalized_not_blank CHECK (btrim(address_normalized) <> '');
ALTER TABLE places ADD CONSTRAINT places_row_version_nonnegative CHECK (row_version >= 0);
ALTER TABLE offers ADD CONSTRAINT offers_row_version_nonnegative CHECK (row_version >= 0);
ALTER TABLE offers ADD CONSTRAINT offers_source_note_not_blank CHECK (source_note IS NULL OR btrim(source_note) <> '');
ALTER TABLE offers DROP CONSTRAINT offers_source_url_scheme;
ALTER TABLE offers ADD CONSTRAINT offers_source_url_scheme CHECK (source_url ~* '^https?://');
ALTER TABLE offers ADD CONSTRAINT offers_source_fields_match CHECK (
    source_type IS NOT NULL OR (source_url IS NULL AND source_note IS NULL)
);
ALTER TABLE offers ADD CONSTRAINT offers_percent_minimum CHECK (
    discount_type <> 'PERCENT' OR discount_value IS NULL OR discount_value >= 1
);
ALTER TABLE offer_schedule ADD CONSTRAINT offer_schedule_whole_minutes CHECK (
    extract(second FROM time_from) = 0 AND extract(second FROM time_until) = 0
);

CREATE FUNCTION categories_keep_code() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.code IS DISTINCT FROM OLD.code THEN
        RAISE EXCEPTION 'category code is immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER categories_code_immutable BEFORE UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION categories_keep_code();

CREATE FUNCTION places_validate_timezone() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.timezone) THEN
        RAISE EXCEPTION 'unknown timezone' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER places_timezone_valid BEFORE INSERT OR UPDATE OF timezone ON places
    FOR EACH ROW EXECUTE FUNCTION places_validate_timezone();

CREATE TABLE catalog_publications (
    version_id uuid PRIMARY KEY,
    submission_id uuid NOT NULL,
    version_no int NOT NULL CHECK (version_no >= 1),
    command_hash char(64) NOT NULL CHECK (command_hash ~ '^[0-9a-f]{64}$'),
    place_id uuid REFERENCES places(id),
    offer_ids uuid[],
    published_at timestamptz,
    UNIQUE (submission_id, version_no)
);

CREATE TABLE catalog_offer_snapshots (
    offer_id uuid PRIMARY KEY REFERENCES offers(id),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object')
);

CREATE TABLE catalog_outbox (
    id uuid PRIMARY KEY,
    version_id uuid NOT NULL UNIQUE REFERENCES catalog_publications(version_id),
    topic varchar(100) NOT NULL,
    message_key uuid NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    occurred_at timestamptz NOT NULL,
    published_at timestamptz
);

CREATE INDEX catalog_outbox_pending_idx ON catalog_outbox (occurred_at, id) WHERE published_at IS NULL;
