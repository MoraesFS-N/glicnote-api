-- Private API schema; do not add it to Supabase's exposed schemas.
REVOKE ALL ON SCHEMA glicnote FROM PUBLIC;

CREATE TABLE profiles (
    id uuid PRIMARY KEY,
    name varchar(120),
    username varchar(30) UNIQUE CHECK (username ~ '^[a-z0-9_]{3,30}$'),
    email varchar(320),
    photo_url varchar(2048),
    time_zone varchar(80) NOT NULL DEFAULT 'UTC',
    birthday_month smallint,
    birthday_day smallint,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((birthday_month IS NULL AND birthday_day IS NULL) OR
      (birthday_month IS NOT NULL AND birthday_day IS NOT NULL AND birthday_month BETWEEN 1 AND 12
       AND birthday_day BETWEEN 1 AND extract(day FROM (make_date(2000, birthday_month, 1) + interval '1 month - 1 day'))))
);
CREATE TABLE emergency_contacts (
    user_id uuid PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL,
    phone varchar(16) NOT NULL CHECK (phone ~ '^\+[1-9][0-9]{6,14}$'),
    relationship varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE readings (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    glucose_mg_dl integer NOT NULL CHECK (glucose_mg_dl > 0),
    insulin_units numeric(8,3) CHECK (insulin_units >= 0),
    moment varchar(30) NOT NULL CHECK (moment IN ('fasting','after_breakfast','after_lunch','after_snack','after_dinner','other')),
    measured_at timestamptz NOT NULL,
    time_zone varchar(80) NOT NULL,
    local_date date NOT NULL,
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(id, user_id)
);
CREATE INDEX readings_timeline ON readings(user_id, measured_at DESC, id DESC);
CREATE INDEX readings_calendar ON readings(user_id, local_date, moment);
CREATE TABLE follows (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    target_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    status varchar(15) NOT NULL CHECK(status IN ('pending','accepted','declined','cancelled','ended')),
    version integer NOT NULL DEFAULT 0,
    requested_at timestamptz NOT NULL DEFAULT now(),
    responded_at timestamptz,
    ended_at timestamptz,
    CHECK (requester_id <> target_id),
    UNIQUE(requester_id, target_id)
);
CREATE INDEX follows_incoming ON follows(target_id, status, requested_at DESC);
CREATE INDEX follows_outgoing ON follows(requester_id, status, target_id);
CREATE TABLE blocks (
    blocker_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    blocked_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(blocker_id, blocked_id),
    CHECK(blocker_id <> blocked_id)
);
CREATE TABLE special_dates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(80) NOT NULL UNIQUE,
    kind varchar(20) NOT NULL CHECK(kind IN ('fixed_annual','specific_date','birthday')),
    month smallint,
    day smallint,
    specific_date date,
    active boolean NOT NULL DEFAULT true,
    CHECK (
      (kind='birthday' AND month IS NULL AND day IS NULL AND specific_date IS NULL) OR
      (kind='specific_date' AND specific_date IS NOT NULL AND month IS NULL AND day IS NULL) OR
      (kind='fixed_annual' AND specific_date IS NULL AND month IS NOT NULL AND day IS NOT NULL
        AND month BETWEEN 1 AND 12 AND day BETWEEN 1 AND extract(day FROM (make_date(2000, month, 1) + interval '1 month - 1 day')))
    )
);
CREATE TABLE badges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(80) NOT NULL UNIQUE,
    icon_key varchar(80) NOT NULL,
    rule_type varchar(20) NOT NULL CHECK(rule_type IN ('reading_count','day_streak','special_date')),
    target integer,
    special_date_id uuid REFERENCES special_dates(id),
    recurrence varchar(10) NOT NULL DEFAULT 'once' CHECK(recurrence IN ('once','annual')),
    rule_version integer NOT NULL DEFAULT 1,
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    CHECK (
      (rule_type IN ('reading_count','day_streak') AND target IS NOT NULL AND target > 0 AND special_date_id IS NULL AND recurrence='once') OR
      (rule_type='special_date' AND target IS NULL AND special_date_id IS NOT NULL)
    )
);
CREATE TABLE earned_badges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    badge_id uuid NOT NULL REFERENCES badges(id),
    edition varchar(20) NOT NULL,
    rule_version integer NOT NULL,
    source_reading_id uuid,
    achieved_at timestamptz NOT NULL,
    earned_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(source_reading_id, user_id) REFERENCES readings(id, user_id) ON DELETE SET NULL(source_reading_id),
    UNIQUE(user_id, badge_id, edition),
    UNIQUE(id, user_id)
);
CREATE TABLE posts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    type varchar(10) NOT NULL CHECK(type IN ('reading','badge')),
    reading_id uuid UNIQUE,
    earned_badge_id uuid UNIQUE,
    caption varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(reading_id, author_id) REFERENCES readings(id, user_id) ON DELETE CASCADE,
    FOREIGN KEY(earned_badge_id, author_id) REFERENCES earned_badges(id, user_id) ON DELETE CASCADE,
    CHECK ((type='reading' AND reading_id IS NOT NULL AND earned_badge_id IS NULL) OR
      (type='badge' AND reading_id IS NULL AND earned_badge_id IS NOT NULL))
);
CREATE INDEX posts_feed ON posts(author_id, created_at DESC, id DESC);
CREATE TABLE notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    actor_id uuid REFERENCES profiles(id) ON DELETE CASCADE,
    type varchar(25) NOT NULL CHECK(type IN ('follow_requested','follow_accepted','badge_earned')),
    follow_id uuid REFERENCES follows(id) ON DELETE CASCADE,
    request_version integer,
    earned_badge_id uuid REFERENCES earned_badges(id) ON DELETE CASCADE,
    event_key varchar(200) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz,
    CHECK ((type IN ('follow_requested','follow_accepted') AND follow_id IS NOT NULL AND request_version IS NOT NULL AND earned_badge_id IS NULL) OR
      (type='badge_earned' AND earned_badge_id IS NOT NULL AND follow_id IS NULL AND request_version IS NULL))
);
CREATE INDEX notifications_inbox ON notifications(recipient_id, created_at DESC, id DESC);
-- No anon/authenticated grants. Google identities and sessions belong to Supabase Auth.
REVOKE ALL ON ALL TABLES IN SCHEMA glicnote FROM PUBLIC;

-- Supabase roles may already have explicit grants; schema isolation must not rely on their defaults.
DO $$
DECLARE api_role text;
BEGIN
  FOREACH api_role IN ARRAY ARRAY['anon','authenticated','service_role'] LOOP
    IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname=api_role) THEN
      EXECUTE format('REVOKE ALL ON SCHEMA glicnote FROM %I', api_role);
      EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA glicnote FROM %I', api_role);
      EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA glicnote FROM %I', api_role);
    END IF;
  END LOOP;
END $$;
