DROP INDEX readings_one_moment_per_day;
CREATE UNIQUE INDEX readings_one_moment_per_day ON readings(user_id, local_date, moment)
    WHERE moment <> 'other';
CREATE UNIQUE INDEX readings_other_unique_time ON readings(user_id, measured_at)
    WHERE moment = 'other';
