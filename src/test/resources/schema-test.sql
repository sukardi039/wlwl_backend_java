-- Minimal slice of the real wlwl schema, just enough to exercise the RPC contract
-- and the basicinformation aggregate. Column names/casing taken verbatim from the
-- production dump where known.

drop view  if exists agentlicense_view;
-- drop views before the tables they read, or the table drops fail on a reused DB
drop view  if exists license_view;
drop view  if exists batchlicense_view;
-- the simulator slice creates these; they must go before `drop table staff` below,
-- because two Spring contexts can share the same in-memory database (DB_CLOSE_DELAY=-1)
-- and H2 refuses to drop a table that a surviving view references.
drop view  if exists staffprofile_view;
drop view  if exists impactprofile_view;
drop table if exists simulator;
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

-- decimals + dates, to prove PhpRows emits PHP-compatible JSON types.
-- Column set/order matches the production dump (there is no creditBalance/lastSeen).
create table company (
  packageId      varchar(100) not null primary key,
  companyName    varchar(100) not null,
  agentId        varchar(45)  not null,
  active         int default 0,
  runOne         varchar(100) default null,
  activeOne      int default 0,
  runTwo         varchar(100) default null,
  activeTwo      int default 0,
  runThree       varchar(100) default null,
  activeThree    int default 0,
  availableFrom  date default '2023-01-01',
  availableTill  date default null,
  commonPW       varchar(45) default '123456',
  -- staffsignin joins backenduser to company and selects c.lang
  lang           varchar(20) default 'en',
  companyType    varchar(100) default null
);

insert into restsession (sessionId, userId, restkey, createdOn, usedOn, deletedOn)
values ('s1', 'u1', 'K1', '2026-01-01 00:00:00', null, null);

insert into baseinfo (baseclass, name, displayKey, orgKey) values
  ('styles', 'Analytical', 'AN', null),
  ('styles', 'Driver',     'DR', null),
  ('morale', 'High',       'HI', null),
  -- the catalogue modules filter on these PLURAL baseclass values
  ('products','Widget',    'W1', null),
  ('products','Ghost',     'GH', null),
  ('tasks',  'Inspect',    'TI', null),
  ('template','BasicTpl',  'BT', null),
  ('styles', 'Formal',     'FO', null),
  ('actions','Plan',       'PL', null),
  ('actions','Advise',     'AD', null),
  ('actions','Hidden',     'HD', null),
  ('environment','Offshore','OF', null),
  -- the three profile names the profile* modules read from baseclass='profiles';
  -- the profileClassCode -> displayKey map comes from these rows
  ('profiles','skills',    'SK', null),
  ('profiles','morale',    'MO', null),
  ('profiles','efficiency','EF', null);

-- pkg1 is live, pkg2 is expired -> only pkg1 must appear in livepackage_*
insert into company (packageId, companyName, agentId, active, availableFrom, commonPW,
                     availableTill)
values ('pkg1', 'Acme',   'a1', 1, '2026-01-15', '123456', '2099-12-31'),
       -- active = 0 (not 2): still in basicinformation's "active < 2" list, but it is
       -- what makes staffsignin's company-active gate fail for U4 (405).
       ('pkg2', 'OldCo',  'a1', 0, '2020-01-01', '123456', '2021-01-01'),
       ('pkg3', 'Deleted','a1', 2, '2020-01-01', '123456', '2099-12-31'),
       -- pkg5's agent has NO license batch, so backenduser/put.php cannot allocate a
       -- seat -> 405. active = 2 keeps it out of basicinformation's "active < 2" list.
       ('pkg5', 'NoAgent', 'zz', 2, '2020-01-01', '123456', '2099-12-31');

-- pkg1 runs model M1 in its first slot, so controldrill/ can resolve runOneName.
update company set runOne = 'M1' where packageId = 'pkg1';

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
  profileName             varchar(100) not null,
  profileClassMin         int default 0,
  profileClassMax         int default 0
);

-- Min/Max are the ranges the simulator rolls against (staffprofile_view exposes them)
insert into profileclass (profileClassCode, profileClassDisplayName, profileName,
                          profileClassMin, profileClassMax) values
  ('P1', 'Low',    'skills',      10,  90),
  ('P2', 'Medium', 'morale',      20,  80),
  -- two classes per profile => the seeding triple loop produces 2x2x2 = 8 rows
  ('P3', 'High',   'efficiency',  30, 100),
  ('P4', 'High',   'skills',      40,  95),
  ('P5', 'Low',    'morale',       5,  50),
  ('P6', 'Low',    'efficiency',   0,  40);

-- skillsProfile points at a real class; moraleProfile does NOT (proves the
-- unguarded $exp[...] lookup emits JSON null for a missing code).
-- staffId is a bigint AUTO_INCREMENT in production (the fixture used to pretend it was
-- a string). S1 keeps an UNKNOWN efficiency profile on purpose: staffprofile_view
-- INNER-joins all three profile classes, so that row must disappear from the view.
create table staff (
  staffId              int not null auto_increment primary key,
  staffName            varchar(100) not null,
  staffPhoto           varchar(255) default null,
  skillsProfile        varchar(100) not null,
  moraleProfile        varchar(100) not null,
  efficiencyProfile    varchar(100) not null,
  skillsValue          int default 0,
  moraleValue          int default 0,
  efficiencyValue      int default 0,
  staffDescription     text,
  staffSelfIntroduction text,
  staffInterview       text,
  yearsWorked          int default 0,
  experience           int default 0,
  newComer             int default 0,
  oldTimer             int default 0
);
insert into staff (staffId, staffName, skillsProfile, moraleProfile, efficiencyProfile,
                   skillsValue, moraleValue, efficiencyValue) values
  (1, 'Alice', 'P1', 'P2', 'NOPE', 70, 60, 50),
  (2, 'Bob',   'P1', 'P2', 'P3',   90, 70, 60);

create table lang (langCode varchar(20) not null primary key);
insert into lang (langCode) values ('en'), ('id');

create table companytype (companyTypeCode varchar(20) not null primary key);
insert into companytype (companyTypeCode) values ('agency'), ('direct');

-- 'Ghost' has a null productShortName and must be skipped by the join predicate
create table product (
  productId        int not null,
  productName      varchar(100) not null,
  productShortName varchar(100) default null,
  productDescription varchar(500) default null,
  ordersPerDay       int default 0,
  minOrderValue      decimal(6,2) default 0.00,
  maxOrderValue      decimal(6,2) default 0.00
);
insert into product (productId, productName, productShortName) values
  (1, 'Widget', 'W1'),
  (2, 'Ghost',  null);

create table template (
  templateName        varchar(100) not null primary key,
  templateDescription varchar(200) default null,
  -- the model module sizes its staff/environment/recruit arrays from these
  numberOfStaff     int default 0,
  environmentEvents int default 0,
  recruits          int default 0,
  duration          decimal(6,2) default 0.00,
  numberOfDays      int default 0,
  report            varchar(100) default null
);
insert into template (templateName, templateDescription, numberOfStaff, environmentEvents, recruits)
values ('BasicTpl', 'A basic template', 2, 1, 1);

create table task (
  taskName        varchar(100) not null primary key,
  taskDescription varchar(500) default null,
  -- drill/init.php reads task.stage (it is NOT a taskefficiency column) and uses it as
  -- the orderStatus key when params_int.target_method = 2
  stage           int default 0
);
insert into task (taskName, stage) values ('Inspect', 1);

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
  show         int default 0,
  actionDescription varchar(500) default null,
  duration          decimal(6,2) default 0.00,
  applyAll          int default 0,
  minStaff          int default 0,
  maxStaff          int default 0,
  withTask          int default 0,
  swapTask          int default 0,
  minMemberSize     int default 0,
  affectedWhen      varchar(100) default null,
  affectedIf        varchar(100) default null,
  away              int default 0,
  "1In4"            int default 0
);
insert into action (actionName, actionType, parentAction, seq, show, "1In4") values
  ('Plan',   1, 'Plan',    1, 1, 1),
  ('Advise', 2, 'Plan',    2, 1, 0),
  ('Hidden', 1, 'Plan',    3, 0, 0);

create table taskefficiency (
  taskEfficiencyId int not null auto_increment primary key,
  productName      varchar(100) not null,
  taskName         varchar(100) not null,
  efficiency       decimal(5,2) default null,
  skillsWeight     decimal(6,2) default 0.50,
  moraleWeight     decimal(6,2) default 0.50,
  duration         decimal(6,2) default 1.00,
  taskDisplayName  varchar(100) default null,
  passingRate      decimal(6,2) default 1.00,
  target           decimal(6,2) default 1.00
);
insert into taskefficiency (productName, taskName, efficiency) values ('W1', 'Inspect', 12.50);

create table environment (
  environmentName        varchar(100) not null primary key,
  environmentDescription varchar(500) default null,
  orderImpact            int default 0,
  moraleImpact           int default 0,
  duration               decimal(6,2) default 0.00,
  applyAll               int default 0,
  randomOne              int default 0,
  lowMorale              int default 0,
  lowEfficiency          int default 0,
  highEfficiency         int default 0,
  -- columns drill/environment.php reads (production also has combo/orgDescription)
  resignAction          varchar(100) default '',
  resignCheck           int default 0,
  setAway               int default 0,
  newComerImpact        int default 0,
  oldTimerImpact        int default 0,
  effectiveDays         int default 0
);
insert into environment (environmentName) values ('Offshore');

-- drill/commonLib.php::cal_impacts() and cal_affect() look up a morale band here to turn a
-- skills change into an efficiency change. Production ships 10 rows (AUTO_INCREMENT=11).
drop table if exists skillseffect;
create table skillseffect (
  skillsEffectId   bigint not null auto_increment primary key,
  minMorale        int default null,
  maxMorale        int default null,
  skillsEffect     decimal(5,2) default null,
  efficiencyEffect decimal(5,2) default null
);
insert into skillseffect (minMorale, maxMorale, skillsEffect, efficiencyEffect) values
  (  0,  30, 0.50, 0.50),
  ( 31,  60, 0.80, 0.80),
  ( 61, 100, 1.00, 1.00);

-- modelContent holds JSON TEXT and is decoded+re-embedded by the PHP
create table model (
  modelId       varchar(100) not null primary key,
  modelName     varchar(100) not null,
  modelTemplate varchar(100) not null,
  modelProduct  varchar(100) default null,
  modelContent  varchar(4000) default null
);
-- M1 has ONE staff entry but the template wants TWO, one environment (matching)
-- and one recruit (matching) — so only the staff list gets padded by the repair.
insert into model (modelId, modelName, modelTemplate, modelProduct, modelContent) values
  ('M1', 'Model One', 'BasicTpl', 'W1',
   '{"nodes":[1,2],"label":"x","staffs":[{"staffName":"A","task":"t","staffExp":"e","taskExp":"x"}],"environments":[{"environmentName":"Off","environmentExp":"ee"}],"recruits":[{"staffName":"R","staffExp":"re"}]}');

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
  agentName varchar(100) not null,
  availableTill date default null,
  active        int default 0
);
-- availableTill is NOT NULL in production and is the ONLY meaningful half of
-- basicinformation's agent "live" filter (availableFrom is a phantom column there).
insert into agent (agentId, agentName, availableTill, active) values
  ('a1', 'Acme Agent', '2099-12-31', 1),
  ('a2', 'Old Agent',  '2021-01-01', 0);

-- Production license has NO agentId and NO date columns: it hangs off a batch, and
-- seat/date information comes from licensebatch through the views.
create table license (
  licenseId   varchar(45)  not null primary key,
  batchId     varchar(45)  not null,
  packageId   varchar(45)  default null,
  companyName varchar(100) default null,
  userId      varchar(45)  default null,
  userName    varchar(100) default null,
  usedOn      timestamp    not null default current_timestamp
);

-- ------------------------------------------------------------ licenses
-- The three views below are transcribed from the production dump. watch out:
--   * licensebatch has NO licenseUsed column — it is count(license) in the view;
--   * agentlicense_view is a LEFT JOIN and groups per BATCH, so an agent with two
--     batches appears twice and an agent with none still appears (NULL batch cols);
--   * batchlicense_view is ORDER BY availableTill.
-- H2 requires every non-aggregated column in GROUP BY (MySQL infers it from the PK),
-- so the GROUP BY lists are longer here than in production. Semantics are identical.

drop view  if exists license_view;
drop view  if exists batchlicense_view;
drop view  if exists agentlicense_view;
drop table if exists licensebatch;

create table licensebatch (
  batchId       varchar(45) not null primary key,
  agentId       varchar(45) not null,
  batchDesc     varchar(100) not null,
  issueDate     date not null,
  availableTill date not null,
  licenseVolume int default 0
);

-- B1 has 10 seats and 3 licences (so 7 available); B2 has 1 seat and 1 licence, so it
-- is exhausted and must be skipped by the seat allocator.
insert into licensebatch (batchId, agentId, batchDesc, issueDate, availableTill, licenseVolume) values
  ('B1', 'a1', 'Batch One', '2026-01-01', '2099-12-31', 10),
  ('B2', 'a1', 'Batch Two', '2025-01-01', '2099-12-31',  1);

insert into license (licenseId, batchId, packageId, companyName, userId, userName) values
  ('L1', 'B1', 'pkg1', 'Acme', 'U1', 'Alice'),
  ('L3', 'B1', 'pkg1', 'Acme', 'U2', 'Bob'),
  ('L4', 'B1', 'pkg3', 'Deleted', 'U3', 'Cara'),
  -- U4 belongs to pkg2, so his licence must be for pkg2: that way his login passes the
  -- licence gate and stops at the company-active gate (405), not at "no licence" (406).
  ('L2', 'B2', 'pkg2', 'OldCo', 'U4', 'Dan');

create view license_view as
  select l.licenseId as licenseId, l.batchId as batchId, l.packageId as packageId,
         l.companyName as companyName, l.userId as userId, l.userName as userName,
         l.usedOn as usedOn, b.batchDesc as batchDesc
  from license l join licensebatch b on l.batchId = b.batchId;

create view batchlicense_view as
  select a.agentName as agentName, b.batchId as batchId, b.agentId as agentId,
         b.batchDesc as batchDesc, b.issueDate as issueDate,
         b.availableTill as availableTill, b.licenseVolume as licenseVolume,
         count(l.licenseId) as licenseUsed
  from agent a
  join licensebatch b on a.agentId = b.agentId
  left join license l on b.batchId = l.batchId
  group by a.agentName, b.batchId, b.agentId, b.batchDesc, b.issueDate,
           b.availableTill, b.licenseVolume
  order by b.availableTill;

create view agentlicense_view as
  select a.agentId as agentId, a.agentName as agentName, a.active as active,
         a.availableTill as availableTill, b.batchId as batchId,
         b.batchDesc as batchDesc, sum(b.licenseVolume) as licenseVolume,
         (select count(*) from license l where l.batchId = b.batchId) as licenseUsed
  from agent a
  left join licensebatch b on a.agentId = b.agentId
  group by a.agentId, a.agentName, a.active, a.availableTill, b.batchId, b.batchDesc;

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

-- The seat-reuse path reads license_view(userId, packageId) -> batchId. L1 (U1/pkg1)
-- and L4 (U3/pkg3) above already cover it; no extra rows needed here.

-- a live session for the GET lookup path; the query string is the sessionId
insert into restsession (sessionId, userId, restkey, createdOn, usedOn, deletedOn)
values ('sess1', 'U1', 'RK1', '2026-01-01 00:00:00', null, null);

-- ------------------------------------------------- profileimpact/profilesens

drop table if exists profileimpact;
drop table if exists profilesens;

-- One row per (skillsClass, moraleClass, efficiencyClass) combination. The impact
-- and affect column sets BOTH live here: profileimpact/put.php writes the Impact
-- columns, profileaffect/put.php writes the Affect columns, on the SAME rows.
create table profileimpact (
  profileImpactKey   int not null auto_increment primary key,
  impactClass        varchar(100) default null,
  desiredStyle       varchar(100) default null,
  skillsClass        varchar(100) default null,
  moraleClass        varchar(100) default null,
  efficiencyClass    varchar(100) default null,
  skillsImpact       decimal(6,2) default 0.00,
  moraleImpact       decimal(6,2) default 0.00,
  efficiencyImpact   decimal(6,2) default 0.00,
  impactMsg          varchar(500) default null,
  skillsAffect       decimal(6,2) default 0.00,
  moraleAffect       decimal(6,2) default 0.00,
  efficiencyAffect   decimal(6,2) default 0.00,
  affectMsg          varchar(500) default null,
  bossMsg            varchar(500) default null
);

create table profilesens (
  profileSensitivityKey  int not null auto_increment primary key,
  skillsClass            varchar(100) default null,
  moraleClass            varchar(100) default null,
  efficiencyClass        varchar(100) default null,
  skillsSensitivity      decimal(6,2) default 0.00,
  moraleSensitivity      decimal(6,2) default 0.00,
  efficiencySensitivity  decimal(6,2) default 0.00
);

-- ------------------------------------------------------------- drill engine
-- params_int is the engine's tuning table: `value` is varchar(100) even though the name
-- says int, so every consumer receives a STRING ("1", not 1) — drill/init.php's
-- `switch ($param['target_method'])` compares it loosely.
drop table if exists params_int;
create table params_int ("key" varchar(100) not null primary key, "value" varchar(100) default null);
insert into params_int ("key", "value") values
  ('target_method',           '1'),
  ('dejected_tolerance_days', '10'),
  ('dejected_warning_days',   '8'),
  ('ignored_tolerance_days',  '5');

-- ---------------------------------------------------------- drilloverview

drop table if exists drilldetail;
drop table if exists drill;
drop table if exists drillcontrol;

create table drillcontrol (
  drillControlId varchar(45)  not null primary key,
  userId         varchar(100) not null,
  -- production also carries these; drill/init.php writes currentDrill + drillStartedOn
  currentDrill   varchar(100) default null,
  createdOn      datetime     not null default current_timestamp,
  drillStartedOn datetime     default null
);

-- Column set from the production dump. drillStatus is an INT (FK drillstatus), not a
-- string — the blank records the report modules emit therefore carry a NUMBER.
-- The fixture gives the NOT NULL text columns defaults so the inserts stay short.
create table drill (
  drillId        varchar(100) not null primary key,
  drillControlId varchar(100) not null,
  seq            int not null,
  run            varchar(45)  not null default 'R1',
  drillStatus    int not null,
  modelName      varchar(100) not null default 'Model One',
  modelTemplate  varchar(100) not null default 'BasicTpl',
  templateName   varchar(100) not null default 'BasicTpl',
  modelProduct   varchar(100) default null,
  duration       int not null default 20,
  startOn        datetime default null,
  expireOn       datetime default null,
  endOn          datetime default null
);

-- drilldetail.content is the JSON blob the drill engine writes; longtext in MySQL.
-- The key column is drillDetailId (capital D), and `action` is NOT NULL.
create table drilldetail (
  drillDetailId varchar(100) not null primary key,
  drillId       varchar(100) not null,
  stepNumber    int default 0,
  dayNumber     int not null default 0,
  action        varchar(100) not null default '',
  content       longtext,
  createdOn     datetime default null,
  updatedOn     datetime default null
);

insert into drillcontrol (drillControlId, userId) values
  ('DC1', 'U1'),   -- Alice
  ('DC2', 'U2'),   -- Bob
  ('DC3', 'U6');   -- Boom: no drilldetail rows -> blank record

-- drillStatus values are ids from the drillstatus table (3 = "running" here).
insert into drill (drillId, drillControlId, seq, drillStatus, startOn) values
  ('D1', 'DC1', 1, 3, '2026-01-01 08:00:00'),
  ('D2', 'DC2', 1, 3, '2026-01-01 08:00:00'),
  ('D3', 'DC3', 2, 1, '2026-01-02 08:00:00');

-- D1: sales 5*100 = 500, skills 70 vs 50 (+20, +40.0%), day 3 of 10 -> daysLeft 7
-- D2: sales 9*100 = 900, skills 90 vs 50 (+40, +80.0%), day 2 of 8  -> daysLeft 6
--      -> Bob must rank 1, Alice rank 2.
-- D1 also has an EARLIER step (1) which must be ignored: only max(stepNumber) counts.
insert into drilldetail (drillDetailId, drillId, stepNumber, dayNumber, action, content) values
  ('DD1a', 'D1', 1, 1, 'Plan',
   '{"control":{"status":"old","numberOfDays":10,"remainingMinutes":999},"orderStatus":{"ordersCompleted":0},"orderValue":100,"current":{"teamAvg":{"skills":10,"morale":10,"efficiency":10}},"summary":[{"teamAvg":{"skills":10,"morale":10,"efficiency":10}}]}'),
  ('DD1b', 'D1', 2, 3, 'Plan',
   '{"control":{"status":"running","numberOfDays":10,"remainingMinutes":120},"orderStatus":{"ordersCompleted":5},"orderValue":100,"current":{"teamAvg":{"skills":70,"morale":60,"efficiency":50}},"summary":[{"teamAvg":{"skills":50,"morale":50,"efficiency":40}}],"competency":{"skillsCompetency":80,"moraleCompetency":70,"efficiencyCompetency":60,"leaderShipCompetency":50,"driveCompetency":40,"totalCompetency":300},"analysis":{"action":{"salesAction":{"name":"salesAction","count":4,"positive":2,"negative":2,"oneInFour":"0","skillsAdded":0,"moraleAdded":0},"coachAction":{"name":"coachAction","count":2,"positive":1,"negative":1,"oneInFour":"1","skillsAdded":0,"moraleAdded":0},"total":6}}}'),
  ('DD2', 'D2', 1, 2, 'Plan',
   '{"control":{"status":"running","numberOfDays":8,"remainingMinutes":45},"orderStatus":{"ordersCompleted":9},"orderValue":100,"current":{"teamAvg":{"skills":90,"morale":70,"efficiency":60}},"summary":[{"teamAvg":{"skills":50,"morale":50,"efficiency":40}}],"competency":{"skillsCompetency":70,"moraleCompetency":60,"efficiencyCompetency":50,"leaderShipCompetency":40,"driveCompetency":30,"totalCompetency":250},"analysis":{"action":{"salesAction":{"name":"salesAction","count":4,"positive":4,"negative":0,"oneInFour":"0","skillsAdded":0,"moraleAdded":0},"coachAction":{"name":"coachAction","count":0,"positive":0,"negative":0,"oneInFour":"1","skillsAdded":0,"moraleAdded":0},"total":4}}}');
