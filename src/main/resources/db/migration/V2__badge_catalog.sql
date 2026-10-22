INSERT INTO badges(code,icon_key,rule_type,target,sort_order) VALUES
 ('first_reading','first_reading','reading_count',1,10),
 ('readings_10','readings','reading_count',10,20),
 ('readings_50','readings','reading_count',50,30),
 ('readings_100','readings','reading_count',100,40),
 ('streak_3','streak','day_streak',3,50),
 ('streak_7','streak','day_streak',7,60),
 ('streak_14','streak','day_streak',14,70),
 ('streak_30','streak','day_streak',30,80);
INSERT INTO special_dates(code,kind) VALUES ('birthday','birthday');
INSERT INTO special_dates(code,kind,month,day) VALUES ('new_year','fixed_annual',1,1);
INSERT INTO badges(code,icon_key,rule_type,special_date_id,recurrence,sort_order)
 SELECT 'birthday_reading','birthday','special_date',id,'annual',90 FROM special_dates WHERE code='birthday';
INSERT INTO badges(code,icon_key,rule_type,special_date_id,recurrence,sort_order)
 SELECT 'new_year_reading','calendar','special_date',id,'annual',100 FROM special_dates WHERE code='new_year';
