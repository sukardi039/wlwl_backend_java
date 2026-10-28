# wlwl production schema — as dumped 2026-10-26 (MySQL 8.0.40, utf8mb3)

Condensed from the `--no-data` mysqldump. `v(n)` = varchar(n), `NN` = NOT NULL,
`AI` = AUTO_INCREMENT, `PK`/`UQ` = key. Defaults shown only where they matter.

NOTE: table charset is **utf8mb3**, not utf8mb4.

## Core / identity

- **baseinfo**: baseclass v100, name v100 PK NN, displayKey v100 NN, orgKey v100
- **baseclass**: baseClassName v100 PK NN, baseClassDisplayKey v100 NN,
  baseClassType v45 NN, baseActionType v45 NN
- **basekey**: baseKeyName v100 PK NN, baseKeyDisplayKey v100 NN
- **backenduser**: userId v45 PK NN, userName v45 NN, companyName v100, loginName v45 NN,
  loginPassword v45 NN, level int d0, active int d1, instructor int d0, email v255
  - FK companyName -> company.packageId ON DELETE CASCADE
- **restsession**: sessionId v100 PK NN, userId v100 NN, restkey v100 NN,
  createdOn datetime NN, **usedOn varchar(100)** (!), deletedOn datetime
- **company**: packageId v100 PK NN, companyName v100 NN, agentId v45 NN, active int d0,
  runOne v100 NN, activeOne int d0, runTwo v100, activeTwo int d0, runThree v100,
  activeThree int d0, availableFrom date d'2023-01-01', availableTill date d'2099-12-31',
  commonPW v45 d'123456', lang char(2) d'cn', companyType v45 d'it'
  - NB there is no creditBalance / lastSeen
- **lang**: langCode char(2) PK, langDesc v45 NN
- **companytype**: companyTypeCode v45 PK
- **params**: `key` v100 PK NN, value v100
- **params_int**: `key` v100 PK NN, value v100  (same shape; separate table)
- **phplog**: logId int PK AI, url v255, method v20, log text, logTime timestamp

## Profile / staff

- **profileclass**: profileClassCode v100 PK NN, profileName v100 NN,
  profileClassDisplayName v100 NN, profileClassMin int, profileClassMax int, orgName v100
  - FK profileName -> baseinfo.name
- **profilesens**: profileSensitivityKey **bigint PK AI**, skillsClass/moraleClass/
  efficiencyClass v100 NN, skillsSensitivity/moraleSensitivity/efficiencySensitivity
  decimal(6,2) d0.00 — **UQ (skillsClass, moraleClass, efficiencyClass)**; 3 FKs -> profileclass
- **profileimpact**: profileImpactKey **bigint PK AI**, impactClass v100 NN,
  desiredStyle v100, skillsClass/moraleClass/efficiencyClass v100 NN,
  skillsImpact/moraleImpact/efficiencyImpact decimal(6,2) d0.00, impactMsg text,
  skillsAffect/moraleAffect/efficiencyAffect decimal(6,2) d0.00, affectMsg text, bossMsg text
  - FK impactClass -> baseinfo.name (CASCADE), desiredStyle -> baseinfo.name,
    3 FKs -> profileclass
- **profileimpact_original**: same columns, no AI/PK (orphan copy)
- **staff**: staffId bigint PK AI, staffName v100 NN UQ, staffPhoto v255, skillsProfile/
  moraleProfile/efficiencyProfile v100 NN, skillsValue/moraleValue/efficiencyValue int d0,
  staffDescription text, staffSelfIntroduction text, staffInterview text,
  yearsWorked int, experience int, newComer int, oldTimer int
- **staffx**: subset of staff (no experience/newComer/oldTimer); FKs to profileclass
- **skillseffect**: skillsEffectId bigint PK AI, minMorale int, maxMorale int,
  skillsEffect decimal(5,2), efficiencyEffect decimal(5,2)

## Catalogue / content

- **action**: actionName v100 PK NN, actionDescription text, parentAction v100,
  duration **int d1**, applyAll int d0, minStaff int d1, maxStaff int d6,
  **actionType varchar(100)** (FK actiontype.actionType), withTask int d0, swapTask int d0,
  minMemberSize int d1, affectedWhen v45 (FK baseinfo.name), **affectedIf varchar(10) d'>'**
  (a COMPARISON OPERATOR, not a baseinfo key), away int d0, show int d1, evaluation int,
  evaluated int, perWeek int, checkTrend int, noImpact int, anyTime int,
  actualStyle v100 (FK baseinfo.name), maximumUse int d9999, managementAction int,
  combo v45, effectiveDays int, **`1In4` int**, seq int d1, useLimit int d9999,
  focusedStaff int, vacancyOK int
- **action_original**: same, no FKs (orphan copy)
- **actiontype**: actionType v100 PK NN, typeDescription v100 NN
- **environment**: environmentName v100 PK NN, environmentDescription text NN,
  orderImpact/moraleImpact decimal(6,2) d0.00, duration int d1, applyAll int, randomOne int,
  lowMorale int, lowEfficiency int, highEfficiency int, resignAction v100 d'',
  resignCheck int, setAway int, orgDescription text, newComerImpact int, oldTimerImpact int,
  combo v45, effectiveDays int
- **product**: **productName v100 NN**, productDescription text NN, ordersPerDay int d1,
  minOrderValue int d1, maxOrderValue int d999999, **productShortName v100 PK NN UQ**,
  productId int AI
  - FK productName -> baseinfo.name
- **style**: styleName v100 PK NN, styleDescription text
- **task**: taskName v100 PK NN, taskDescription **v45**, stage int d0
  - FK -> baseinfo.name
- **template**: templateName v100 PK NN, templateDescription text,
  numberOfStaff int d10, numberOfDays int d20, duration int d20, environmentEvents int d0,
  recruits int d2, **report int d1**
  - FK -> baseinfo.name
- **taskefficiency**: taskEfficiencyId **bigint PK AI**, productName v100 NN,
  taskName v100 NN, skillsWeight/moraleWeight decimal(6,2) d0.00, duration decimal(6,2) d1.00,
  taskDisplayName v100, **passingRate int d1**, target decimal(5,2) d1.00
  - FKs productName -> product.productShortName, taskName -> task.taskName
  - NB there is **no `efficiency` column**
- **model**: modelId v100 PK NN, modelName v100 NN **UQ**, modelTemplate v100 NN,
  modelProduct v100, modelContent text NN — 2 FKs
- **modeltype**: modelTypeName v100 PK NN, modelTypeDescription v100 NN
- **bossmessage**: messageCode v100 PK UQ NN, messageText text NN, orgText text
- **bossreview**: reviewId bigint PK AI, reviewName v100 NN, action v100 d'MORE_ACTIONS',
  applyAll int, impactAppFrom/To, impactRejFrom/To, affectAppFrom/To, affectRejFrom/To,
  emotion, impactEmotion, affectEmotion, bossMsg v100 (FK bossmessage), managementAction,
  efficiencyChange

## Licences / agents

- **agent**: agentId v45 PK NN, agentName v100 NN, active int d1,
  **availableTill date NN** (no default — must be supplied)
- **licensebatch**: batchId v45 PK NN, agentId v45 NN, batchDesc v100 NN,
  issueDate date NN, availableTill date NN, licenseVolume int d0
  - NB there is **no licenseUsed column** — it is computed by the views
- **license**: licenseId v45 PK NN, batchId v45 NN, packageId v45, companyName v100,
  userId v45, userName v100, usedOn timestamp NN dCURRENT_TIMESTAMP
  - FK batchId -> licensebatch

## Drill / simulation

- **drillcontrol**: drillControlId v100 PK NN, userId v100 NN, completedDrill int,
  currentDrill v100, createdOn datetime NN, drillStartedOn datetime, drillEndedOn datetime
  - FK userId -> backenduser
- **drill**: drillId v100 PK NN, drillControlId v100 NN, seq int NN, run v45 NN,
  **drillStatus int NN** (FK drillstatus), modelName v100 NN, modelTemplate v100 NN,
  templateName v100 NN, modelProduct v100, duration int NN, startOn datetime,
  expireOn datetime, endOn datetime
- **drilldetail**: **drillDetailId v100 PK NN**, drillId v100 NN, stepNumber int d0,
  dayNumber int NN, **action v100 NN**, content **longtext**, createdOn datetime, updatedOn datetime
  - UQ (drillId, stepNumber); FK drillId -> drill
- **drillstatus**: drillStatus int PK NN, drillStatusDesc v100 NN
- **competency**: **PK is drillId** (!), userId v100 NN, seq int NN, duration int NN,
  companyType v45 NN, updatedOn datetime, startSkills/endSkills/startMorale/endMorale/
  startEfficiency/endEfficiency int, desiredTotal/desiredPositive/salesTarget/salesAchieved int,
  skillsCompetency/moraleCompetency/efficiencyCompetency/**leadershipCompetency**/
  driveCompetency/totalCompetency int
- **simulator**: PK (sessionId, seqNumber) — sessionId v100 NN, seqNumber int NN d0,
  productName v100 NN, productExp v100, taskName v20 NN, taskExp v100, staffId bigint NN,
  staffName v100, skillsWeight/moraleWeight decimal(6,2), skillsValue/skillsAddedValue/
  moraleValue/moraleAddedValue/efficiencyValue/efficiencyAddedValue int, lastAction v100,
  actionExp v100, skillsImpact/moraleImpact/efficiencyImpact int,
  skillsSensitivity/moraleSensitivity/efficiencySensitivity decimal(6,2),
  lastSkillsValue/lastMoraleValue/lastEfficiencyValue int, modValue int d0

## Views (exact definitions)

- **agentlicense_view** = `agent a LEFT JOIN licensebatch b ON a.agentId = b.agentId`,
  `GROUP BY a.agentId, b.batchId`. Columns: agentId, agentName, active, availableTill,
  batchId, batchDesc, `sum(b.licenseVolume)` AS licenseVolume,
  `(select count(*) from license l where l.batchId = b.batchId)` AS licenseUsed.
  => one row PER BATCH; an agent with no batch still appears (batch columns NULL).
- **batchlicense_view** = `agent JOIN (licensebatch LEFT JOIN license)`, GROUP BY
  `licensebatch.batchId`, **ORDER BY licensebatch.availableTill**.
  Columns: agentName, batchId, agentId, batchDesc, issueDate, availableTill, licenseVolume,
  `count(license.licenseId)` AS licenseUsed.
- **license_view** = `license l JOIN licensebatch b ON l.batchId = b.batchId`.
  Columns: licenseId, batchId, packageId, companyName, userId, userName, usedOn, batchDesc.
- **staffprofile_view** = staff JOIN profileclass x3 (skills/morale/efficiency).
  Columns: staffId..oldTimer, skillsProfileName, skillsMin, skillsMax,
  moraleProfileName, moraleMin, moraleMax, efficiencyProfileName, efficiencyMin, efficiencyMax.
  INNER joins => a staff row with an unknown profile code VANISHES.
- **impactprofile_view** = profileimpact JOIN profileclass x3 JOIN profilesens on all three
  class codes. Columns: profileImpactKey..affectMsg, skillsProfileName/skillsMin/skillsMax,
  moraleProfileName/moraleMin/moraleMax, efficiencyProfileName/efficiencyMin/efficiencyMax,
  skillsSensitivity/moraleSensitivity/efficiencySensitivity, desiredStyle.

## Tables that DO NOT exist (modules referencing them are dead in production)

`tasks`, `wxuser`, `studentlist` (signin/, staffsignin/post_xxx.php), and there is no
`messages`, `consoleuser`, `package`, `drilloverview`, `drillcompare` or `registerdemo`
table — those modules read other tables or views.
