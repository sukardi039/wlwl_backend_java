-- Second schema slice: the two views and one table the simulator/ module needs.
-- Kept in its own file because the production definitions must be transcribed
-- verbatim and schema-test.sql is already long; spring.sql.init loads both in order.

drop view  if exists staffprofile_view;
drop view  if exists impactprofile_view;
drop table if exists simulator;

-- ---------------------------------------------------------------- simulator
-- PK is (sessionId, seqNumber): one row per step of a what-if session.
create table simulator (
  sessionId              varchar(100) not null,
  seqNumber              int not null default 0,
  productName            varchar(100) not null,
  productExp             varchar(100) default null,
  taskName               varchar(20)  not null,
  taskExp                varchar(100) default null,
  staffId                bigint       not null,
  staffName              varchar(100) default null,
  skillsWeight           decimal(6,2) default null,
  moraleWeight           decimal(6,2) default null,
  skillsValue            int default null,
  skillsAddedValue       int default null,
  moraleValue            int default null,
  moraleAddedValue       int default null,
  efficiencyValue        int default null,
  efficiencyAddedValue   int default null,
  lastAction             varchar(100) default null,
  actionExp              varchar(100) default null,
  skillsImpact           int default null,
  moraleImpact           int default null,
  efficiencyImpact       int default null,
  skillsSensitivity      decimal(6,2) default null,
  moraleSensitivity      decimal(6,2) default null,
  efficiencySensitivity  decimal(6,2) default null,
  lastSkillsValue        int default null,
  lastMoraleValue        int default null,
  lastEfficiencyValue    int default null,
  modValue               int default '0',
  primary key (sessionId, seqNumber)
);

-- ------------------------------------------------- staffprofile_view
-- Production: staff JOIN profileclass x3 on the three profile codes (INNER joins, so a
-- staff row whose profile code is unknown DISAPPEARS from this view).
create view staffprofile_view as
  select s.staffId as staffId, s.staffName as staffName, s.staffPhoto as staffPhoto,
         s.skillsProfile as skillsProfile, s.moraleProfile as moraleProfile,
         s.efficiencyProfile as efficiencyProfile, s.skillsValue as skillsValue,
         s.moraleValue as moraleValue, s.efficiencyValue as efficiencyValue,
         s.staffDescription as staffDescription, s.yearsWorked as yearsWorked,
         s.experience as experience, s.newComer as newComer, s.oldTimer as oldTimer,
         sp.profileClassDisplayName as skillsProfileName,
         sp.profileClassMin as skillsMin, sp.profileClassMax as skillsMax,
         mp.profileClassDisplayName as moraleProfileName,
         mp.profileClassMin as moraleMin, mp.profileClassMax as moraleMax,
         ep.profileClassDisplayName as efficiencyProfileName,
         ep.profileClassMin as efficiencyMin, ep.profileClassMax as efficiencyMax
  from staff s
  join profileclass sp on s.skillsProfile = sp.profileClassCode
  join profileclass mp on s.moraleProfile = mp.profileClassCode
  join profileclass ep on s.efficiencyProfile = ep.profileClassCode;

-- ------------------------------------------------- impactprofile_view
-- Production: profileimpact JOIN profileclass x3 JOIN profilesens on all three class
-- codes. This is what simulator/ searches by range to find an action's effect.
create view impactprofile_view as
  select s.profileImpactKey as profileImpactKey, s.impactClass as impactClass,
         s.skillsClass as skillsClass, s.moraleClass as moraleClass,
         s.efficiencyClass as efficiencyClass,
         s.skillsImpact as skillsImpact, s.moraleImpact as moraleImpact,
         s.efficiencyImpact as efficiencyImpact, s.impactMsg as impactMsg,
         s.skillsAffect as skillsAffect, s.moraleAffect as moraleAffect,
         s.efficiencyAffect as efficiencyAffect, s.affectMsg as affectMsg,
         sp.profileClassDisplayName as skillsProfileName,
         sp.profileClassMin as skillsMin, sp.profileClassMax as skillsMax,
         mp.profileClassDisplayName as moraleProfileName,
         mp.profileClassMin as moraleMin, mp.profileClassMax as moraleMax,
         ep.profileClassDisplayName as efficiencyProfileName,
         ep.profileClassMin as efficiencyMin, ep.profileClassMax as efficiencyMax,
         ps.skillsSensitivity as skillsSensitivity,
         ps.moraleSensitivity as moraleSensitivity,
         ps.efficiencySensitivity as efficiencySensitivity,
         s.desiredStyle as desiredStyle
  from profileimpact s
  join profileclass sp on s.skillsClass = sp.profileClassCode
  join profileclass mp on s.moraleClass = mp.profileClassCode
  join profileclass ep on s.efficiencyClass = ep.profileClassCode
  join profilesens ps on s.skillsClass = ps.skillsClass
                     and s.moraleClass = ps.moraleClass
                     and s.efficiencyClass = ps.efficiencyClass;

-- ---------------------------------------------------------------- competency
-- Written by drill/competency.php::saveCompetency() - the persisted evaluation.
alter table company add column if not exists companyType varchar(20) default null;

drop table if exists competency;
create table competency (
  drillId              varchar(100) default null,
  userId               varchar(100) not null,
  seq                  int not null,
  duration             int default null,
  companyType          varchar(20) default null,
  updatedOn            datetime default null,
  startSkills          int default null,
  endSkills            int default null,
  startMorale          int default null,
  endMorale            int default null,
  startEfficiency      int default null,
  endEfficiency        int default null,
  desiredTotal         int default null,
  desiredPositive      int default null,
  salesTarget          int default null,
  salesAchieved        int default null,
  skillsCompetency     int default null,
  moraleCompetency     int default null,
  efficiencyCompetency int default null,
  leadershipCompetency int default null,
  driveCompetency      int default null,
  totalCompetency      int default null,
  primary key (userId, seq)
);

-- drillcontrol.completedDrill is written by timeout.php / action.php / environment.php
alter table drillcontrol add column if not exists completedDrill int default null;

-- readParams() reads the whole table
drop table if exists params_int;
create table params_int (`key` varchar(100) not null primary key, `value` varchar(100) default null);
