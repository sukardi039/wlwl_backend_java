-- Minimal slice of the real wlwl schema, just enough to exercise the RPC contract
-- and the basicinformation aggregate. Column names/casing taken verbatim from the
-- production dump where known.

drop view  if exists agentlicense_view;
-- drop views before the tables they read, or the table drops fail on a reused DB
drop view  if exists license_view;
drop view  if exists batchlicense_view;
drop table if exists baseinfo;
drop table if exists baseclass;
drop table if exists restsession;
drop table if exists company;
drop table if exists staff;
drop table if exists profileclass;
drop table if exists lang;
drop table if exists companytype;
drop table if exists product;
drop table if exists template;
drop table if exists task;
drop table if exists style;
drop table if exists action;
drop table if exists actiontype;
drop table if exists taskefficiency;
drop table if exists environment;
drop table if exists model;
drop table if exists params;
drop table if exists bossmessage;
drop table if exists agent;
drop table if exists license;
drop table if exists backenduser;

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
  lastSeen       datetime default null,
  availableTill  date default null,
  -- staffsignin joins backenduser to company and selects c.lang
  lang           varchar(20) default 'en'
);

insert into restsession (sessionId, userId, restkey, createdOn, usedOn, deletedOn)
values ('s1', 'u1', 'K1', '2026-01-01 00:00:00', null, null);

insert into baseinfo (baseclass, name, displayKey, orgKey) values
  ('styles', 'Analytical', 'AN', null),
  ('styles', 'Driver',     'DR', null),
  ('morale', 'High',       'HI', null),
  ('product','Widget',     'W1', null),
  ('product','Ghost',      'GH', null),
  ('task',   'Inspect',    'TI', null),
  ('template','BasicTpl',  'BT', null),
  ('style',  'Formal',     'FO', null),
  ('action', 'Plan',       'PL', null),
  ('action', 'Advise',     'AD', null),
  ('action', 'Hidden',     'HD', null),
  ('environment','Offshore','OF', null);

-- pkg1 is live, pkg2 is expired -> only pkg1 must appear in livepackage_*
insert into company (packageId, companyName, agentId, active, availableFrom, commonPW,
                     creditBalance, lastSeen, availableTill)
values ('pkg1', 'Acme',   'a1', 1, '2026-01-15', '123456', 1.50, '2026-10-26 15:47:01', '2099-12-31'),
       -- active = 0 (not 2): still in basicinformation's "active < 2" list, but it is
       -- what makes staffsignin's company-active gate fail for U4 (405).
       ('pkg2', 'OldCo',  'a1', 0, '2020-01-01', '123456', 0.00, null,                 '2021-01-01'),
       ('pkg3', 'Deleted','a1', 2, '2020-01-01', '123456', 0.00, null,                 '2099-12-31'),
       -- pkg5's agent has NO license batch, so backenduser/put.php cannot allocate a
       -- seat -> 405. active = 2 keeps it out of basicinformation's "active < 2" list.
       ('pkg5', 'NoAgent', 'zz', 2, '2020-01-01', '123456', 0.00, null,                '2099-12-31');

-- ---------------------------------------------------------------- lookups

create table baseclass (
  baseClassType         varchar(100) not null,
  baseClassDisplayKey   varchar(100) not null,
  baseClassName         varchar(100) not null,
  baseActionType        varchar(100) default null
);

insert into baseclass (baseClassType, baseClassDisplayKey, baseClassName, baseActionType) values
  ('styles', 'AN', 'Analytical', 'analyse'),
  ('styles', 'DR', 'Driver',     'drive');

create table profileclass (
  profileClassCode        varchar(100) not null primary key,
  profileClassDisplayName varchar(100) not null,
  profileName             varchar(100) not null
);

insert into profileclass (profileClassCode, profileClassDisplayName, profileName) values
  ('P1', 'Low',    'skills'),
  ('P2', 'Medium', 'morale');

-- skillsProfile points at a real class; moraleProfile does NOT (proves the
-- unguarded $exp[...] lookup emits JSON null for a missing code).
create table staff (
  staffId           varchar(100) not null primary key,
  staffName         varchar(100) not null,
  skillsProfile     varchar(100) default null,
  moraleProfile     varchar(100) default null,
  efficiencyProfile varchar(100) default null
);

insert into staff (staffId, staffName, skillsProfile, moraleProfile, efficiencyProfile) values
  ('S1', 'Alice', 'P1', 'P2', 'NOPE');

create table lang (langCode varchar(20) not null primary key);
insert into lang (langCode) values ('en'), ('id');

create table companytype (companyTypeCode varchar(20) not null primary key);
insert into companytype (companyTypeCode) values ('agency'), ('direct');

-- 'Ghost' has a null productShortName and must be skipped by the join predicate
create table product (
  productId        int not null,
  productName      varchar(100) not null,
  productShortName varchar(100) default null
);
insert into product (productId, productName, productShortName) values
  (1, 'Widget', 'W1'),
  (2, 'Ghost',  null);

create table template (
  templateName        varchar(100) not null primary key,
  templateDescription varchar(200) default null
);
insert into template (templateName, templateDescription) values ('BasicTpl', 'A basic template');

create table task (taskName varchar(100) not null primary key);
insert into task (taskName) values ('Inspect');

create table style (
  styleName        varchar(100) not null primary key,
  styleDescription varchar(200) default null
);
insert into style (styleName, styleDescription) values ('Formal', 'Formal style');

create table actiontype (
  actionType      int not null primary key,
  typeDescription varchar(100) not null
);
insert into actiontype (actionType, typeDescription) values (1, 'Primary'), (2, 'Secondary');

-- 'Plan' is its own parent -> mainAction. 'Advise' hangs off 'Plan' -> subAction.
-- 'Hidden' has show = 0 -> appears in action_det.action only.
create table action (
  actionName   varchar(100) not null primary key,
  actionType   int default null,
  parentAction varchar(100) default null,
  seq          int default 0,
  show         int default 0
);
insert into action (actionName, actionType, parentAction, seq, show) values
  ('Plan',   1, 'Plan',    1, 1),
  ('Advise', 2, 'Plan',    2, 1),
  ('Hidden', 1, 'Plan',    3, 0);

create table taskefficiency (
  productName varchar(100) not null,
  taskName    varchar(100) not null,
  efficiency  decimal(5,2) default null
);
insert into taskefficiency (productName, taskName, efficiency) values ('W1', 'Inspect', 12.50);

create table environment (environmentName varchar(100) not null primary key);
insert into environment (environmentName) values ('Offshore');

-- modelContent holds JSON TEXT and is decoded+re-embedded by the PHP
create table model (
  modelId       varchar(100) not null primary key,
  modelName     varchar(100) not null,
  modelTemplate varchar(100) not null,
  modelContent  varchar(2000) default null
);
insert into model (modelId, modelName, modelTemplate, modelContent) values
  ('M1', 'Model One', 'BasicTpl', '{"nodes":[1,2],"label":"x"}');

-- `key` is a reserved word in MySQL and H2 alike; quoted so the DDL is accepted
-- while the column label still comes back as `key`.
create table params (
  "key"   varchar(100) not null primary key,
  "value" varchar(200) default null
);
insert into params ("key", "value") values ('timezone', 'Asia/Jakarta'), ('123', 'numeric-key');

create table bossmessage (
  messageCode varchar(100) not null primary key,
  messageText varchar(2000) default null
);
insert into bossmessage (messageCode, messageText) values ('WELCOME', 'Welcome aboard');

-- agentlicense_view is a read-only join in production; a2 is expired.
create table agent (
  agentId   varchar(45) not null primary key,
  agentName varchar(100) not null
);
insert into agent (agentId, agentName) values ('a1', 'Acme Agent'), ('a2', 'Old Agent');

-- agentId is NULLABLE: backenduser/put.php inserts a license without one.
create table license (
  licenseId     varchar(45) not null primary key,
  batchId       varchar(45)  default null,
  agentId       varchar(45)  default null,
  packageId     varchar(100) default null,
  companyName   varchar(100) default null,
  userId        varchar(45)  default null,
  userName      varchar(100) default null,
  availableFrom date default null,
  availableTill date default null
);
insert into license (licenseId, agentId, availableFrom, availableTill) values
  ('L1', 'a1', '2026-01-01', '2099-12-31'),
  ('L2', 'a2', '2020-01-01', '2021-01-01');

create view agentlicense_view as
  select a.agentId, a.agentName, l.availableFrom, l.availableTill
  from agent a join license l on l.agentId = a.agentId;

-- ------------------------------------------------------------ licenses
-- The two views backenduser/put.php (and registerdemo/put.php) depend on. The real
-- definitions live in MySQL and are not in the repo; these are the minimal shapes
-- the PHP reads: license_view(userId, packageId, batchId) and
-- batchlicense_view(agentId, batchId, licenseVolume, licenseUsed, issueDate, availableTill).

drop view  if exists license_view;
drop view  if exists batchlicense_view;
drop table if exists licensebatch;

create table licensebatch (
  batchId       varchar(45) not null primary key,
  agentId       varchar(45) default null,
  licenseVolume int default 0,
  licenseUsed   int default 0,
  issueDate     date default null,
  availableTill date default null
);

-- B1 has 10 seats, 3 used (so 7 available). B2 is exhausted and must be skipped.
insert into licensebatch (batchId, agentId, licenseVolume, licenseUsed, issueDate, availableTill) values
  ('B1', 'a1', 10, 3, '2026-01-01', '2099-12-31'),
  ('B2', 'a1',  1, 1, '2025-01-01', '2099-12-31');

create view license_view as
  select licenseId, batchId, packageId, companyName, userId, userName,
         availableFrom, availableTill, agentId
  from license;

create view batchlicense_view as
  select batchId, agentId, licenseVolume, licenseUsed, issueDate, availableTill
  from licensebatch;

-- ------------------------------------------------------------ staffsignin

-- loginPassword holds MySQL 4.1 PASSWORD() hashes: '*' + hex(SHA1(SHA1(pw))).
--   secret   -> *14E65567ABDB5135D0CFD9A70B3032C179A49EE7
--   hunter2  -> *58815970BE77B3720276F63DB198B1FA42E5CC02
--   letmein  -> *D37C49F9CBEFBF8B6F4B165AC703AA271E079004
create table backenduser (
  userId        varchar(100) not null primary key,
  userName      varchar(100) default null,
  loginName     varchar(100) not null,
  loginPassword varchar(100) default null,
  level         int default 0,
  active        int default 1,
  companyName   varchar(100) default null,
  instructor    int default 0,
  email         varchar(100) default null
);

-- U1: general user, licensed company -> login succeeds
-- U2: instructor (level 1) -> license gate skipped entirely
-- U3: general user with NO license row -> 406
-- U4: general user licensed, but company inactive -> 405
insert into backenduser (userId, userName, loginName, loginPassword, level, active,
                         companyName, instructor, email) values
  ('U1', 'Alice', 'alice',  '*14E65567ABDB5135D0CFD9A70B3032C179A49EE7', 0, 1, 'pkg1', 0, 'alice@example.com'),
  ('U2', 'Bob',   'bob',    '*58815970BE77B3720276F63DB198B1FA42E5CC02', 1, 1, 'pkg1', 1, 'bob@example.com'),
  ('U3', 'Carol', 'carol',  '*D37C49F9CBEFBF8B6F4B165AC703AA271E079004', 0, 1, 'pkg2', 0, 'carol@example.com'),
  ('U4', 'Dave',  'dave',   '*14E65567ABDB5135D0CFD9A70B3032C179A49EE7', 0, 1, 'pkg2', 0, 'dave@example.com'),
  ('U5', 'Erin',  'erin',   '*14E65567ABDB5135D0CFD9A70B3032C179A49EE7', 0, 0, 'pkg1', 0, 'erin@example.com');
  -- U6/U7 exist only to drive the GETAUTH webhook failure branches
insert into backenduser (userId, userName, loginName, loginPassword, level, active,
                         companyName, instructor, email) values
  ('U6', 'Boom',     'boom',     '*14E65567ABDB5135D0CFD9A70B3032C179A49EE7', 1, 1, 'pkg1', 1, 'boom@example.com'),
  ('U7', 'MissingK', 'missingk', '*14E65567ABDB5135D0CFD9A70B3032C179A49EE7', 1, 1, 'pkg1', 1, 'missingk@example.com');

insert into license (licenseId, agentId, availableFrom, availableTill, userId, packageId) values
  -- agentId 'a9' is not in the agent table, so these do NOT multiply the rows in
  -- agentlicense_view (which is an inner join on agentId).
  ('L3', 'a9', '2026-01-01', '2099-12-31', 'U1', 'pkg1'),
  ('L4', 'a9', '2026-01-01', '2099-12-31', 'U4', 'pkg2');

-- a live session for the GET lookup path; the query string is the sessionId
insert into restsession (sessionId, userId, restkey, createdOn, usedOn, deletedOn)
values ('sess1', 'U1', 'RK1', '2026-01-01 00:00:00', null, null);
