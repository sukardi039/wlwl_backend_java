-- Minimal slice of the real wlwl schema, just enough to exercise the RPC contract.
-- Column names/casing taken verbatim from the production dump.

drop table if exists baseinfo;
drop table if exists restsession;
drop table if exists company;

create table baseinfo (
  baseclass  varchar(100) default null,
  name       varchar(100) not null primary key,
  displayKey varchar(100) not null,
  orgKey     varchar(100) default null
);

create table restsession (
  sessionId varchar(100) not null primary key,
  userId    varchar(100) not null,
  restkey   varchar(100) not null,
  createdOn datetime     not null,
  usedOn    varchar(100) default null,
  deletedOn datetime     default null
);

-- decimals + dates, to prove PhpRows emits PHP-compatible JSON types
create table company (
  packageId      varchar(100) not null primary key,
  companyName    varchar(100) not null,
  agentId        varchar(45)  not null,
  active         int default 0,
  availableFrom  date default '2023-01-01',
  commonPW       varchar(45) default '123456',
  creditBalance  decimal(6,2) default 0.00,
  lastSeen       datetime default null
);

insert into restsession (sessionId, userId, restkey, createdOn, usedOn, deletedOn)
values ('s1', 'u1', 'K1', '2026-01-01 00:00:00', null, null);

insert into baseinfo (baseclass, name, displayKey, orgKey) values
  ('styles', 'Analytical', 'AN', null),
  ('styles', 'Driver',     'DR', null),
  ('morale', 'High',       'HI', null);

insert into company (packageId, companyName, agentId, active, availableFrom, commonPW, creditBalance, lastSeen)
values ('pkg1', 'Acme', 'a1', 1, '2026-01-15', '123456', 1.50, '2026-10-26 15:47:01');
