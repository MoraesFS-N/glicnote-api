ALTER TABLE profiles ADD COLUMN diabetes_type varchar(10)
    CHECK (diabetes_type IN ('type_1', 'type_2'));
