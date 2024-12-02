create table users
(

    id          serial primary key,
    name        varchar(255)                        not null unique,
    email       varchar(255)                        not null,
    cell_phone  varchar(255)                        not null,
    _version    int       default 0                 not null,
    _created_at timestamp default CURRENT_TIMESTAMP not null,
    _updated_at timestamp default CURRENT_TIMESTAMP not null,
    _created_by varchar(255)                        not null,
    _updated_by varchar(255)                        not null
);