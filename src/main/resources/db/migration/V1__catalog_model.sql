CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TYPE time_range AS RANGE (subtype = time);

CREATE TYPE offer_status AS ENUM ('ACTIVE', 'EXPIRED', 'SUSPENDED', 'ARCHIVED');
CREATE TYPE discount_type AS ENUM ('PERCENT', 'FIXED_AMOUNT', 'SPECIAL_PRICE', 'FREE_ITEM', 'OTHER');
CREATE TYPE source_type AS ENUM ('WEBSITE', 'SOCIAL_MEDIA', 'ORGANIZATION', 'PERSONAL_VISIT', 'OTHER');

CREATE TABLE categories (
    code       varchar(50)  PRIMARY KEY,
    name       varchar(100) NOT NULL,
    sort_order int          NOT NULL DEFAULT 0,
    is_active  boolean      NOT NULL DEFAULT true,
    CONSTRAINT categories_code_format CHECK (code ~ '^[a-z][a-z0-9_]*$')
);

CREATE TABLE places (
    id                 uuid                   PRIMARY KEY,
    name               varchar(200)           NOT NULL,
    city               varchar(100)           NOT NULL,
    address_line       varchar(300)           NOT NULL,
    address_normalized varchar(300)           NOT NULL,
    location           geography(Point, 4326) NOT NULL,
    timezone           varchar(64)            NOT NULL,
    category_code      varchar(50)            NOT NULL REFERENCES categories (code),
    organization_id    uuid,
    row_version        int                    NOT NULL DEFAULT 0,
    created_at         timestamptz            NOT NULL DEFAULT now(),
    updated_at         timestamptz            NOT NULL DEFAULT now(),
    CONSTRAINT places_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT places_city_not_blank CHECK (btrim(city) <> ''),
    CONSTRAINT places_address_not_blank CHECK (btrim(address_line) <> ''),
    CONSTRAINT places_timezone_format CHECK (timezone ~ '^[A-Za-z]+(/[A-Za-z0-9_+-]+)+$' OR timezone = 'UTC')
);

CREATE INDEX places_location_gist ON places USING gist (location);
CREATE INDEX places_city_idx ON places (city);
CREATE INDEX places_category_idx ON places (category_code);
CREATE INDEX places_address_normalized_idx ON places (address_normalized);

CREATE TABLE offers (
    id                  uuid          PRIMARY KEY,
    place_id            uuid          NOT NULL REFERENCES places (id),
    status              offer_status  NOT NULL,
    conditions          varchar(2000) NOT NULL,
    discount_type       discount_type NOT NULL DEFAULT 'OTHER',
    discount_value      numeric(10, 2),
    source_type         source_type,
    source_url          varchar(2048),
    source_note         varchar(1000),
    valid_from          date,
    valid_until         date,
    last_verified_at    timestamptz   NOT NULL,
    approved_version_id uuid          NOT NULL,
    row_version         int           NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT offers_conditions_not_blank CHECK (btrim(conditions) <> ''),
    CONSTRAINT offers_discount_value_range CHECK (
        discount_value IS NULL
        OR (discount_type = 'PERCENT' AND discount_value > 0 AND discount_value <= 100)
        OR (discount_type IN ('FIXED_AMOUNT', 'SPECIAL_PRICE') AND discount_value > 0)
    ),
    CONSTRAINT offers_source_complete CHECK (
        source_type IS NULL OR source_url IS NOT NULL OR source_note IS NOT NULL
    ),
    CONSTRAINT offers_source_url_scheme CHECK (source_url ~ '^https?://'),
    CONSTRAINT offers_validity_range CHECK (
        valid_from IS NULL OR valid_until IS NULL OR valid_until >= valid_from
    )
);

CREATE INDEX offers_place_idx ON offers (place_id);
CREATE INDEX offers_active_place_idx ON offers (place_id) WHERE status = 'ACTIVE';
CREATE INDEX offers_active_valid_until_idx ON offers (valid_until) WHERE status = 'ACTIVE';
CREATE INDEX offers_active_discount_type_idx ON offers (discount_type) WHERE status = 'ACTIVE';
CREATE INDEX offers_approved_version_idx ON offers (approved_version_id);

CREATE TABLE offer_schedule (
    id          uuid     PRIMARY KEY,
    offer_id    uuid     NOT NULL REFERENCES offers (id) ON DELETE CASCADE,
    day_of_week smallint NOT NULL,
    time_from   time     NOT NULL,
    time_until  time     NOT NULL,
    CONSTRAINT offer_schedule_day_range CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT offer_schedule_time_order CHECK (time_from < time_until),
    CONSTRAINT offer_schedule_no_overlap EXCLUDE USING gist (
        offer_id WITH =,
        day_of_week WITH =,
        time_range(time_from, time_until, '[)') WITH &&
    )
);

CREATE INDEX offer_schedule_offer_idx ON offer_schedule (offer_id);
