create table measure_blood
(

    id           serial primary key,
    glucose      int                                 not null,
    insulin_unit int                                 not null,
    user_id      int                                 not null,
    _version     int       default 0                 not null,
    _created_at  timestamp default CURRENT_TIMESTAMP not null,
    _updated_at  timestamp default CURRENT_TIMESTAMP not null,
    _created_by  varchar(255)                        not null,
    _updated_by  varchar(255)                        not null,

    constraint user_fk_1
        foreign key (user_id) references users (id)
);