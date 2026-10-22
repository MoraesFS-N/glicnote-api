-- Preserve all historical data: migration fails if existing duplicates need review.
CREATE UNIQUE INDEX readings_one_moment_per_day ON readings(user_id, local_date, moment);
CREATE UNIQUE INDEX readings_unique_measurement ON readings(user_id, glucose_mg_dl, moment, measured_at);
