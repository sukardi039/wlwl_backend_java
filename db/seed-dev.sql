-- DEVELOPMENT SEED — synthetic data, NOT production configuration.
--
-- Generated from src/test/resources/schema-test.sql by db/tools/make-seed.py, then
-- machine-checked against db/schema.sql (every INSERT/UPDATE column must exist).
--
-- These rows exist to make the parity suite meaningful: fake companies (pkg1..pkg5),
-- fake staff, and just enough catalogue rows (baseinfo, profileclass, action, params)
-- for each module to answer. Do NOT load this into a production database: it would
-- overwrite real configuration with test fixtures.
--
-- For a real deployment the schema comes from db/schema.sql and the DATA comes from
-- the legacy database, e.g.
--   mysqldump --no-create-info --complete-insert wlwl > wlwl-data.sql
--   mysql wlwl_new < wlwl-data.sql
--

SET NAMES utf8mb3;
SET FOREIGN_KEY_CHECKS = 0;

insert into restsession (sessionId, userId, restkey, createdOn, usedOn, deletedOn)
values ('s1', 'u1', 'K1', '2026-01-01 00:00:00', null, null);
insert into baseinfo (baseclass, name, displayKey, orgKey) values
  ('styles', 'Analytical', 'AN', null),
  ('styles', 'Driver',     'DR', null),
  ('morale', 'High',       'HI', null),
  
  ('products','Widget',    'W1', null),
  ('products','Ghost',     'GH', null),
  ('tasks',  'Inspect',    'TI', null),
  ('template','BasicTpl',  'BT', null),
  ('styles', 'Formal',     'FO', null),
  ('actions','Plan',       'PL', null),
  ('actions','Advise',     'AD', null),
  ('actions','Hidden',     'HD', null),
  ('environment','Offshore','OF', null),
  
  
  ('profiles','skills',    'SK', null),
  ('profiles','morale',    'MO', null),
  ('profiles','efficiency','EF', null);
insert into company (packageId, companyName, agentId, active, availableFrom, commonPW,
                     availableTill)
values ('pkg1', 'Acme',   'a1', 1, '2026-01-15', '123456', '2099-12-31'),
       
       
       ('pkg2', 'OldCo',  'a1', 0, '2020-01-01', '123456', '2021-01-01'),
       ('pkg3', 'Deleted','a1', 2, '2020-01-01', '123456', '2099-12-31'),
       
       
       ('pkg5', 'NoAgent', 'zz', 2, '2020-01-01', '123456', '2099-12-31');
insert into baseclass (baseClassType, baseClassDisplayKey, baseClassName, baseActionType) values
  ('styles', 'AN', 'Analytical', 'analyse'),
  ('styles', 'DR', 'Driver',     'drive');
insert into profileclass (profileClassCode, profileClassDisplayName, profileName,
                          profileClassMin, profileClassMax) values
  ('P1', 'Low',    'skills',      10,  90),
  ('P2', 'Medium', 'morale',      20,  80),
  
  ('P3', 'High',   'efficiency',  30, 100),
  ('P4', 'High',   'skills',      40,  95),
  ('P5', 'Low',    'morale',       5,  50),
  ('P6', 'Low',    'efficiency',   0,  40);
insert into staff (staffId, staffName, skillsProfile, moraleProfile, efficiencyProfile,
                   skillsValue, moraleValue, efficiencyValue) values
  (1, 'Alice', 'P1', 'P2', 'NOPE', 70, 60, 50),
  (2, 'Bob',   'P1', 'P2', 'P3',   90, 70, 60);
insert into lang (langCode) values ('en'), ('id');
insert into companytype (companyTypeCode) values ('agency'), ('direct');
insert into product (productId, productName, productShortName) values
  (1, 'Widget', 'W1'),
  (2, 'Ghost',  null);
insert into template (templateName, templateDescription, numberOfStaff, environmentEvents, recruits)
values ('BasicTpl', 'A basic template', 2, 1, 1);
insert into task (taskName, stage) values ('Inspect', 1);
insert into style (styleName, styleDescription) values ('Formal', 'Formal style');
insert into actiontype (actionType, typeDescription) values (1, 'Primary'), (2, 'Secondary');
insert into action (actionName, actionType, parentAction, seq, show, "1In4") values
  ('Plan',   1, 'Plan',    1, 1, 1),
  ('Advise', 2, 'Plan',    2, 1, 0),
  ('Hidden', 1, 'Plan',    3, 0, 0);
insert into taskefficiency (productName, taskName, target) values ('W1', 'Inspect', 12.50);
insert into environment (environmentName) values ('Offshore');
insert into skillseffect (minMorale, maxMorale, skillsEffect, efficiencyEffect) values
  (  0,  30, 0.50, 0.50),
  ( 31,  60, 0.80, 0.80),
  ( 61, 100, 1.00, 1.00);
insert into model (modelId, modelName, modelTemplate, modelProduct, modelContent) values
  ('M1', 'Model One', 'BasicTpl', 'W1',
   '{"nodes":[1,2],"label":"x","staffs":[{"staffName":"A","task":"t","staffExp":"e","taskExp":"x"}],"environments":[{"environmentName":"Off","environmentExp":"ee"}],"recruits":[{"staffName":"R","staffExp":"re"}]}');
insert into params ("key", "value") values ('timezone', 'Asia/Jakarta'), ('123', 'numeric-key');
insert into bossreview (reviewName, action, applyAll, impactAppFrom, impactAppTo,
                        impactRejFrom, impactRejTo, affectAppFrom, affectAppTo,
                        affectRejFrom, affectRejTo, managementAction, efficiencyChange,
                        bossMsg) values
  ('allGood',   'SELECT_STYLE',  0,  100, 100,   0,  0,   0, 100,   0, 100, 1, 1, 'Nice pick'),
  ('allGood2',  'SELECT_STYLE',  0,    0, 100,   0, 100, 0, 100,   0, 100, 1, 0, 'Boss shrugs'),
  ('mixedMgmt', 'MORE_ACTIONS',  1,    0, 100,   0, 100, 0, 100,   0, 100, 2, 0, 'Keep going'),
  
  ('styleMgmt0','SELECT_STYLE',  0,  100, 100,   0,   0, 0, 100,   0, 100, 0, 1, 'Style locked in');
insert into bossmessage (messageCode, messageText) values ('WELCOME', 'Welcome aboard');
insert into agent (agentId, agentName, availableTill, active) values
  ('a1', 'Acme Agent', '2099-12-31', 1),
  ('a2', 'Old Agent',  '2021-01-01', 0);
insert into licensebatch (batchId, agentId, batchDesc, issueDate, availableTill, licenseVolume) values
  ('B1', 'a1', 'Batch One', '2026-01-01', '2099-12-31', 10),
  ('B2', 'a1', 'Batch Two', '2025-01-01', '2099-12-31',  1);
insert into license (licenseId, batchId, packageId, companyName, userId, userName) values
  ('L1', 'B1', 'pkg1', 'Acme', 'U1', 'Alice'),
  ('L3', 'B1', 'pkg1', 'Acme', 'U2', 'Bob'),
  ('L4', 'B1', 'pkg3', 'Deleted', 'U3', 'Cara'),
  
  
  ('L2', 'B2', 'pkg2', 'OldCo', 'U4', 'Dan');
insert into backenduser (userId, userName, loginName, loginPassword, level, active,
                         companyName, instructor, email) values
  ('U1', 'Alice', 'alice',  '*14E65567ABDB5135D0CFD9A70B3032C179A49EE7', 0, 1, 'pkg1', 0, 'alice@example.com'),
  ('U2', 'Bob',   'bob',    '*58815970BE77B3720276F63DB198B1FA42E5CC02', 1, 1, 'pkg1', 1, 'bob@example.com'),
  ('U3', 'Carol', 'carol',  '*D37C49F9CBEFBF8B6F4B165AC703AA271E079004', 0, 1, 'pkg2', 0, 'carol@example.com'),
  ('U4', 'Dave',  'dave',   '*14E65567ABDB5135D0CFD9A70B3032C179A49EE7', 0, 1, 'pkg2', 0, 'dave@example.com'),
  ('U5', 'Erin',  'erin',   '*14E65567ABDB5135D0CFD9A70B3032C179A49EE7', 0, 0, 'pkg1', 0, 'erin@example.com');
insert into backenduser (userId, userName, loginName, loginPassword, level, active,
                         companyName, instructor, email) values
  ('U6', 'Boom',     'boom',     '*14E65567ABDB5135D0CFD9A70B3032C179A49EE7', 1, 1, 'pkg1', 1, 'boom@example.com'),
  ('U7', 'MissingK', 'missingk', '*14E65567ABDB5135D0CFD9A70B3032C179A49EE7', 1, 1, 'pkg1', 1, 'missingk@example.com');
insert into restsession (sessionId, userId, restkey, createdOn, usedOn, deletedOn)
values ('sess1', 'U1', 'RK1', '2026-01-01 00:00:00', null, null);
insert into params_int ("key", "value") values
  ('target_method',           '1'),
  ('dejected_tolerance_days', '10'),
  ('dejected_warning_days',   '8'),
  ('ignored_tolerance_days',  '5');
insert into drillcontrol (drillControlId, userId) values
  ('DC1', 'U1'),   
  ('DC2', 'U2'),   
  ('DC3', 'U6');
insert into drill (drillId, drillControlId, seq, drillStatus, startOn) values
  ('D1', 'DC1', 1, 3, '2026-01-01 08:00:00'),
  ('D2', 'DC2', 1, 3, '2026-01-01 08:00:00'),
  ('D3', 'DC3', 2, 1, '2026-01-02 08:00:00');
insert into drilldetail (drillDetailId, drillId, stepNumber, dayNumber, action, content) values
  ('DD1a', 'D1', 1, 1, 'Plan',
   '{"control":{"status":"old","numberOfDays":10,"remainingMinutes":999},"orderStatus":{"ordersCompleted":0},"orderValue":100,"current":{"teamAvg":{"skills":10,"morale":10,"efficiency":10}},"summary":[{"teamAvg":{"skills":10,"morale":10,"efficiency":10}}]}'),
  ('DD1b', 'D1', 2, 3, 'Plan',
   '{"control":{"status":"running","numberOfDays":10,"remainingMinutes":120},"orderStatus":{"ordersCompleted":5},"orderValue":100,"current":{"teamAvg":{"skills":70,"morale":60,"efficiency":50}},"summary":[{"teamAvg":{"skills":50,"morale":50,"efficiency":40}}],"competency":{"skillsCompetency":80,"moraleCompetency":70,"efficiencyCompetency":60,"leaderShipCompetency":50,"driveCompetency":40,"totalCompetency":300},"analysis":{"action":{"salesAction":{"name":"salesAction","count":4,"positive":2,"negative":2,"oneInFour":"0","skillsAdded":0,"moraleAdded":0},"coachAction":{"name":"coachAction","count":2,"positive":1,"negative":1,"oneInFour":"1","skillsAdded":0,"moraleAdded":0},"total":6}}}'),
  ('DD2', 'D2', 1, 2, 'Plan',
   '{"control":{"status":"running","numberOfDays":8,"remainingMinutes":45},"orderStatus":{"ordersCompleted":9},"orderValue":100,"current":{"teamAvg":{"skills":90,"morale":70,"efficiency":60}},"summary":[{"teamAvg":{"skills":50,"morale":50,"efficiency":40}}],"competency":{"skillsCompetency":70,"moraleCompetency":60,"efficiencyCompetency":50,"leaderShipCompetency":40,"driveCompetency":30,"totalCompetency":250},"analysis":{"action":{"salesAction":{"name":"salesAction","count":4,"positive":4,"negative":0,"oneInFour":"0","skillsAdded":0,"moraleAdded":0},"coachAction":{"name":"coachAction","count":0,"positive":0,"negative":0,"oneInFour":"1","skillsAdded":0,"moraleAdded":0},"total":4}}}');

update company set runOne = 'M1' where packageId = 'pkg1';
SET FOREIGN_KEY_CHECKS = 1;
