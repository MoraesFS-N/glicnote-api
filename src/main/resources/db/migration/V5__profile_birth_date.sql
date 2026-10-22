-- Never invent a birth year for existing month/day-only birthdays.
ALTER TABLE profiles ADD COLUMN birth_date date;
ALTER TABLE profiles ADD CONSTRAINT profiles_birth_date_matches_birthday CHECK (
    birth_date IS NULL OR (
        birthday_month IS NOT NULL AND birthday_day IS NOT NULL
        AND birthday_month = extract(month FROM birth_date)
        AND birthday_day = extract(day FROM birth_date)
    )
);
