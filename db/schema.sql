-- wlwl schema for MySQL 8 — generated from the production `mysqldump --no-data`
-- (2026-10-26, MySQL 8.0.40, db `wlwl`). Regenerate with db/tools/from-dump.py.
--
-- Safe to run more than once: tables use CREATE TABLE IF NOT EXISTS, views use
-- CREATE OR REPLACE VIEW, and nothing is dropped. The original dump's
-- `DROP TABLE IF EXISTS` statements were deliberately removed — they would
-- destroy a populated database.
--
-- Charset is utf8mb3 on purpose. The PHP connected with `SET NAMES utf8`, which
-- is utf8mb3 in MySQL, so utf8mb4 here would change 4-byte-character handling
-- and break parity. application.yml pins the same collation on the JDBC side.
--
-- Column order is load-bearing (the backend has ~52 `SELECT *` paths).

SET NAMES utf8mb3;
SET SQL_MODE = 'NO_AUTO_VALUE_ON_ZERO';
SET FOREIGN_KEY_CHECKS = 0;   -- tables are alphabetical, FKs point both ways

CREATE TABLE IF NOT EXISTS `action` (
  `actionName` varchar(100) NOT NULL,
  `actionDescription` text,
  `parentAction` varchar(100) DEFAULT NULL,
  `duration` int DEFAULT '1',
  `applyAll` int DEFAULT '0',
  `minStaff` int DEFAULT '1',
  `maxStaff` int DEFAULT '6',
  `actionType` varchar(100) DEFAULT NULL,
  `withTask` int DEFAULT '0',
  `swapTask` int DEFAULT '0',
  `minMemberSize` int DEFAULT '1',
  `affectedWhen` varchar(45) DEFAULT NULL,
  `affectedIf` varchar(10) DEFAULT '>',
  `away` int DEFAULT '0',
  `show` int DEFAULT '1',
  `evaluation` int DEFAULT '0',
  `evaluated` int DEFAULT '0',
  `perWeek` int DEFAULT '0',
  `checkTrend` int DEFAULT '0',
  `noImpact` int DEFAULT '0',
  `anyTime` int DEFAULT '0',
  `actualStyle` varchar(100) DEFAULT NULL,
  `maximumUse` int DEFAULT '9999',
  `managementAction` int DEFAULT '0',
  `combo` varchar(45) DEFAULT NULL,
  `effectiveDays` int DEFAULT '0',
  `1In4` int DEFAULT '0',
  `seq` int DEFAULT '1',
  `useLimit` int DEFAULT '9999',
  `focusedStaff` int DEFAULT '0',
  `vacancyOK` int DEFAULT '0',
  PRIMARY KEY (`actionName`),
  KEY `action_type_idx` (`actionType`),
  KEY `action_parent_idx` (`parentAction`),
  KEY `action_affect_idx` (`affectedWhen`),
  KEY `action_style_idx` (`actualStyle`),
  CONSTRAINT `action_affect` FOREIGN KEY (`affectedWhen`) REFERENCES `baseinfo` (`name`) ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT `action_parent` FOREIGN KEY (`parentAction`) REFERENCES `action` (`actionName`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `action_profile` FOREIGN KEY (`actionName`) REFERENCES `baseinfo` (`name`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `action_style` FOREIGN KEY (`actualStyle`) REFERENCES `baseinfo` (`name`),
  CONSTRAINT `action_type` FOREIGN KEY (`actionType`) REFERENCES `actiontype` (`actionType`) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `action_original` (
  `actionName` varchar(100) NOT NULL,
  `actionDescription` text,
  `parentAction` varchar(100) DEFAULT NULL,
  `duration` int DEFAULT '1',
  `applyAll` int DEFAULT '0',
  `minStaff` int DEFAULT '1',
  `maxStaff` int DEFAULT '6',
  `actionType` varchar(100) DEFAULT NULL,
  `withTask` int DEFAULT '0',
  `swapTask` int DEFAULT '0',
  `minMemberSize` int DEFAULT '1',
  `affectedWhen` varchar(45) DEFAULT NULL,
  `affectedIf` varchar(10) DEFAULT '>',
  `away` int DEFAULT '0',
  `show` int DEFAULT '1',
  `evaluation` int DEFAULT '0',
  `evaluated` int DEFAULT '0',
  `perWeek` int DEFAULT '0',
  `checkTrend` int DEFAULT '0',
  `noImpact` int DEFAULT '0',
  `anyTime` int DEFAULT '0',
  `actualStyle` varchar(45) DEFAULT NULL,
  `maximumUse` int DEFAULT '9999',
  `managementAction` int DEFAULT '0',
  `combo` varchar(45) DEFAULT NULL,
  `effectiveDays` int DEFAULT '0',
  `1In4` int DEFAULT '0',
  `seq` int DEFAULT '1',
  `useLimit` int DEFAULT '9999',
  `focusedStaff` int DEFAULT '0',
  `vacancyOK` int DEFAULT '0',
  PRIMARY KEY (`actionName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `actiontype` (
  `actionType` varchar(100) NOT NULL,
  `typeDescription` varchar(100) NOT NULL,
  PRIMARY KEY (`actionType`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `agent` (
  `agentId` varchar(45) NOT NULL,
  `agentName` varchar(100) NOT NULL,
  `active` int DEFAULT '1',
  `availableTill` date NOT NULL,
  PRIMARY KEY (`agentId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `backenduser` (
  `userId` varchar(45) NOT NULL,
  `userName` varchar(45) NOT NULL,
  `companyName` varchar(100) DEFAULT NULL,
  `loginName` varchar(45) NOT NULL,
  `loginPassword` varchar(45) NOT NULL,
  `level` int DEFAULT '0',
  `active` int DEFAULT '1',
  `instructor` int DEFAULT '0',
  `email` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`userId`),
  KEY `user_company_idx` (`companyName`),
  CONSTRAINT `user_company` FOREIGN KEY (`companyName`) REFERENCES `company` (`packageId`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `baseclass` (
  `baseClassName` varchar(100) NOT NULL,
  `baseClassDisplayKey` varchar(100) NOT NULL,
  `baseClassType` varchar(45) NOT NULL,
  `baseActionType` varchar(45) NOT NULL,
  PRIMARY KEY (`baseClassName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `baseinfo` (
  `baseclass` varchar(100) DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `displayKey` varchar(100) NOT NULL,
  `orgKey` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`name`),
  KEY `baseclasses` (`baseclass`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `basekey` (
  `baseKeyName` varchar(100) NOT NULL,
  `baseKeyDisplayKey` varchar(100) NOT NULL,
  PRIMARY KEY (`baseKeyName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `bossmessage` (
  `messageCode` varchar(100) NOT NULL,
  `messageText` text NOT NULL,
  `orgText` text,
  PRIMARY KEY (`messageCode`),
  UNIQUE KEY `messageCode_UNIQUE` (`messageCode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `bossreview` (
  `reviewId` bigint NOT NULL AUTO_INCREMENT,
  `reviewName` varchar(100) NOT NULL,
  `action` varchar(100) DEFAULT 'MORE_ACTIONS',
  `applyAll` int DEFAULT '0',
  `impactAppFrom` int DEFAULT NULL,
  `impactAppTo` int DEFAULT NULL,
  `impactRejFrom` int DEFAULT NULL,
  `impactRejTo` int DEFAULT NULL,
  `affectAppFrom` int DEFAULT NULL,
  `affectAppTo` int DEFAULT NULL,
  `affectRejFrom` int DEFAULT NULL,
  `affectRejTo` int DEFAULT NULL,
  `emotion` int DEFAULT NULL,
  `impactEmotion` int DEFAULT NULL,
  `affectEmotion` int DEFAULT NULL,
  `bossMsg` varchar(100) DEFAULT NULL,
  `managementAction` int DEFAULT '0',
  `efficiencyChange` int DEFAULT '0',
  PRIMARY KEY (`reviewId`),
  KEY `bossMsg_idx` (`bossMsg`),
  CONSTRAINT `bossMsg` FOREIGN KEY (`bossMsg`) REFERENCES `bossmessage` (`messageCode`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=30 DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `company` (
  `packageId` varchar(100) NOT NULL,
  `companyName` varchar(100) NOT NULL,
  `agentId` varchar(45) NOT NULL,
  `active` int DEFAULT '0',
  `runOne` varchar(100) NOT NULL,
  `activeOne` int DEFAULT '0',
  `runTwo` varchar(100) DEFAULT NULL,
  `activeTwo` int DEFAULT '0',
  `runThree` varchar(100) DEFAULT NULL,
  `activeThree` int DEFAULT '0',
  `availableFrom` date DEFAULT '2023-01-01',
  `availableTill` date DEFAULT '2099-12-31',
  `commonPW` varchar(45) DEFAULT '123456',
  `lang` char(2) DEFAULT 'cn',
  `companyType` varchar(45) DEFAULT 'it',
  PRIMARY KEY (`packageId`),
  KEY `company_runOne_idx` (`runOne`),
  KEY `company_runTwo_idx` (`runTwo`),
  KEY `company_runThree_idx` (`runThree`),
  KEY `company_name` (`companyName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `companytype` (
  `companyTypeCode` varchar(45) NOT NULL,
  PRIMARY KEY (`companyTypeCode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `competency` (
  `drillId` varchar(100) NOT NULL,
  `userId` varchar(100) NOT NULL,
  `seq` int NOT NULL,
  `duration` int NOT NULL,
  `companyType` varchar(45) NOT NULL,
  `updatedOn` datetime DEFAULT NULL,
  `startSkills` int DEFAULT NULL,
  `endSkills` int DEFAULT NULL,
  `startMorale` int DEFAULT NULL,
  `endMorale` int DEFAULT NULL,
  `startEfficiency` int DEFAULT NULL,
  `endEfficiency` int DEFAULT NULL,
  `desiredTotal` int DEFAULT NULL,
  `desiredPositive` int DEFAULT NULL,
  `salesTarget` int DEFAULT NULL,
  `salesAchieved` int DEFAULT NULL,
  `skillsCompetency` int DEFAULT NULL,
  `moraleCompetency` int DEFAULT NULL,
  `efficiencyCompetency` int DEFAULT NULL,
  `leadershipCompetency` int DEFAULT NULL,
  `driveCompetency` int DEFAULT NULL,
  `totalCompetency` int DEFAULT NULL,
  PRIMARY KEY (`drillId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `drill` (
  `drillId` varchar(100) NOT NULL,
  `drillControlId` varchar(100) NOT NULL,
  `seq` int NOT NULL,
  `run` varchar(45) NOT NULL,
  `drillStatus` int NOT NULL,
  `modelName` varchar(100) NOT NULL,
  `modelTemplate` varchar(100) NOT NULL,
  `templateName` varchar(100) NOT NULL,
  `modelProduct` varchar(100) DEFAULT NULL,
  `duration` int NOT NULL,
  `startOn` datetime DEFAULT NULL,
  `expireOn` datetime DEFAULT NULL,
  `endOn` datetime DEFAULT NULL,
  PRIMARY KEY (`drillId`),
  KEY `drill_status_idx` (`drillStatus`),
  KEY `drill_control_idx` (`drillControlId`),
  KEY `drill_model_idx` (`modelName`),
  KEY `drill_product_idx` (`modelProduct`),
  CONSTRAINT `drill_control` FOREIGN KEY (`drillControlId`) REFERENCES `drillcontrol` (`drillControlId`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `drill_model` FOREIGN KEY (`modelName`) REFERENCES `model` (`modelName`) ON UPDATE CASCADE,
  CONSTRAINT `drill_product` FOREIGN KEY (`modelProduct`) REFERENCES `product` (`productShortName`) ON UPDATE CASCADE,
  CONSTRAINT `drill_status` FOREIGN KEY (`drillStatus`) REFERENCES `drillstatus` (`drillStatus`) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `drillcontrol` (
  `drillControlId` varchar(100) NOT NULL,
  `userId` varchar(100) NOT NULL,
  `completedDrill` int DEFAULT NULL,
  `currentDrill` varchar(100) DEFAULT NULL,
  `createdOn` datetime NOT NULL,
  `drillStartedOn` datetime DEFAULT NULL,
  `drillEndedOn` datetime DEFAULT NULL,
  PRIMARY KEY (`drillControlId`),
  KEY `drill_user_idx` (`userId`),
  CONSTRAINT `drill_user` FOREIGN KEY (`userId`) REFERENCES `backenduser` (`userId`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `drilldetail` (
  `drillDetailId` varchar(100) NOT NULL,
  `drillId` varchar(100) NOT NULL,
  `stepNumber` int DEFAULT '0',
  `dayNumber` int NOT NULL,
  `action` varchar(100) NOT NULL,
  `content` longtext,
  `createdOn` datetime DEFAULT NULL,
  `updatedOn` datetime DEFAULT NULL,
  PRIMARY KEY (`drillDetailId`),
  UNIQUE KEY `step_unique` (`drillId`,`stepNumber`),
  KEY `detail_drill_idx` (`drillId`),
  CONSTRAINT `detail_drill` FOREIGN KEY (`drillId`) REFERENCES `drill` (`drillId`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 AVG_ROW_LENGTH=150000;

CREATE TABLE IF NOT EXISTS `drillstatus` (
  `drillStatus` int NOT NULL,
  `drillStatusDesc` varchar(100) NOT NULL,
  PRIMARY KEY (`drillStatus`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `environment` (
  `environmentName` varchar(100) NOT NULL,
  `environmentDescription` text NOT NULL,
  `orderImpact` decimal(6,2) DEFAULT '0.00',
  `moraleImpact` decimal(6,2) DEFAULT '0.00',
  `duration` int DEFAULT '1',
  `applyAll` int DEFAULT '0',
  `randomOne` int DEFAULT '0',
  `lowMorale` int DEFAULT '0',
  `lowEfficiency` int DEFAULT '0',
  `highEfficiency` int DEFAULT '0',
  `resignAction` varchar(100) DEFAULT '',
  `resignCheck` int DEFAULT '0',
  `setAway` int DEFAULT '0',
  `orgDescription` text,
  `newComerImpact` int DEFAULT '0',
  `oldTimerImpact` int DEFAULT '0',
  `combo` varchar(45) DEFAULT NULL,
  `effectiveDays` int DEFAULT '0',
  PRIMARY KEY (`environmentName`),
  KEY `env_followup_idx` (`resignAction`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `lang` (
  `langCode` char(2) NOT NULL,
  `langDesc` varchar(45) NOT NULL,
  PRIMARY KEY (`langCode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `license` (
  `licenseId` varchar(45) NOT NULL,
  `batchId` varchar(45) NOT NULL,
  `packageId` varchar(45) DEFAULT NULL,
  `companyName` varchar(100) DEFAULT NULL,
  `userId` varchar(45) DEFAULT NULL,
  `userName` varchar(100) DEFAULT NULL,
  `usedOn` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`licenseId`),
  KEY `licenseBatch_idx` (`batchId`),
  CONSTRAINT `licenseBatch` FOREIGN KEY (`batchId`) REFERENCES `licensebatch` (`batchId`) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `licensebatch` (
  `batchId` varchar(45) NOT NULL,
  `agentId` varchar(45) NOT NULL,
  `batchDesc` varchar(100) NOT NULL,
  `issueDate` date NOT NULL,
  `availableTill` date NOT NULL,
  `licenseVolume` int DEFAULT '0',
  PRIMARY KEY (`batchId`),
  KEY `licenseAgent_idx` (`agentId`),
  CONSTRAINT `licenseAgent` FOREIGN KEY (`agentId`) REFERENCES `agent` (`agentId`) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `model` (
  `modelId` varchar(100) NOT NULL,
  `modelName` varchar(100) NOT NULL,
  `modelTemplate` varchar(100) NOT NULL,
  `modelProduct` varchar(100) DEFAULT NULL,
  `modelContent` text NOT NULL,
  PRIMARY KEY (`modelId`),
  UNIQUE KEY `model_name` (`modelName`),
  KEY `model_template_idx` (`modelTemplate`),
  KEY `model_product_idx` (`modelProduct`),
  CONSTRAINT `model_product` FOREIGN KEY (`modelProduct`) REFERENCES `product` (`productShortName`) ON UPDATE CASCADE,
  CONSTRAINT `model_template` FOREIGN KEY (`modelTemplate`) REFERENCES `template` (`templateName`) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `modeltype` (
  `modelTypeName` varchar(100) NOT NULL,
  `modelTypeDescription` varchar(100) NOT NULL,
  PRIMARY KEY (`modelTypeName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `params` (
  `key` varchar(100) NOT NULL,
  `value` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `params_int` (
  `key` varchar(100) NOT NULL,
  `value` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `phplog` (
  `logId` int NOT NULL AUTO_INCREMENT,
  `url` varchar(255) DEFAULT NULL,
  `method` varchar(20) DEFAULT NULL,
  `log` text,
  `logTime` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`logId`)
) ENGINE=InnoDB AUTO_INCREMENT=129 DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `product` (
  `productName` varchar(100) NOT NULL,
  `productDescription` text NOT NULL,
  `ordersPerDay` int DEFAULT '1',
  `minOrderValue` int DEFAULT '1',
  `maxOrderValue` int DEFAULT '999999',
  `productShortName` varchar(100) NOT NULL,
  `productId` int NOT NULL AUTO_INCREMENT,
  PRIMARY KEY (`productShortName`),
  UNIQUE KEY `productShortName_UNIQUE` (`productShortName`),
  KEY `product-name` (`productName`),
  KEY `product-short-name` (`productShortName`),
  KEY `product-id` (`productId`),
  CONSTRAINT `product-name` FOREIGN KEY (`productName`) REFERENCES `baseinfo` (`name`) ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `profileclass` (
  `profileClassCode` varchar(100) NOT NULL,
  `profileName` varchar(100) NOT NULL,
  `profileClassDisplayName` varchar(100) NOT NULL,
  `profileClassMin` int DEFAULT NULL,
  `profileClassMax` int DEFAULT NULL,
  `orgName` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`profileClassCode`),
  KEY `profileClass-profileName` (`profileName`),
  CONSTRAINT `profileclass-profilename` FOREIGN KEY (`profileName`) REFERENCES `baseinfo` (`name`) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `profileimpact` (
  `profileImpactKey` bigint NOT NULL AUTO_INCREMENT,
  `impactClass` varchar(100) NOT NULL,
  `desiredStyle` varchar(100) DEFAULT NULL,
  `skillsClass` varchar(100) NOT NULL,
  `moraleClass` varchar(100) NOT NULL,
  `efficiencyClass` varchar(100) NOT NULL,
  `skillsImpact` decimal(6,2) DEFAULT '0.00',
  `moraleImpact` decimal(6,2) DEFAULT '0.00',
  `efficiencyImpact` decimal(6,2) DEFAULT '0.00',
  `impactMsg` text,
  `skillsAffect` decimal(6,2) DEFAULT '0.00',
  `moraleAffect` decimal(6,2) DEFAULT '0.00',
  `efficiencyAffect` decimal(6,2) DEFAULT '0.00',
  `affectMsg` text,
  `bossMsg` text,
  PRIMARY KEY (`profileImpactKey`),
  KEY `impact-skills` (`skillsClass`),
  KEY `impact-morale` (`moraleClass`),
  KEY `impact-efficiency` (`efficiencyClass`),
  KEY `impact-action` (`impactClass`),
  KEY `desired-style_idx` (`desiredStyle`),
  CONSTRAINT `desired-style` FOREIGN KEY (`desiredStyle`) REFERENCES `baseinfo` (`name`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `impact-class` FOREIGN KEY (`impactClass`) REFERENCES `baseinfo` (`name`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `impact-efficiency` FOREIGN KEY (`efficiencyClass`) REFERENCES `profileclass` (`profileClassCode`) ON UPDATE CASCADE,
  CONSTRAINT `impact-morale` FOREIGN KEY (`moraleClass`) REFERENCES `profileclass` (`profileClassCode`) ON UPDATE CASCADE,
  CONSTRAINT `impact-skills` FOREIGN KEY (`skillsClass`) REFERENCES `profileclass` (`profileClassCode`) ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=7617 DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `profileimpact_original` (
  `profileImpactKey` bigint NOT NULL DEFAULT '0',
  `impactClass` varchar(100) NOT NULL,
  `desiredStyle` varchar(100) DEFAULT NULL,
  `skillsClass` varchar(100) NOT NULL,
  `moraleClass` varchar(100) NOT NULL,
  `efficiencyClass` varchar(100) NOT NULL,
  `skillsImpact` decimal(6,2) DEFAULT '0.00',
  `moraleImpact` decimal(6,2) DEFAULT '0.00',
  `efficiencyImpact` decimal(6,2) DEFAULT '0.00',
  `impactMsg` text,
  `skillsAffect` decimal(6,2) DEFAULT '0.00',
  `moraleAffect` decimal(6,2) DEFAULT '0.00',
  `efficiencyAffect` decimal(6,2) DEFAULT '0.00',
  `affectMsg` text,
  `bossMsg` text
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `profilesens` (
  `profileSensitivityKey` bigint NOT NULL AUTO_INCREMENT,
  `skillsClass` varchar(100) NOT NULL,
  `moraleClass` varchar(100) NOT NULL,
  `efficiencyClass` varchar(100) NOT NULL,
  `skillsSensitivity` decimal(6,2) DEFAULT '0.00',
  `moraleSensitivity` decimal(6,2) DEFAULT '0.00',
  `efficiencySensitivity` decimal(6,2) DEFAULT '0.00',
  PRIMARY KEY (`profileSensitivityKey`),
  UNIQUE KEY `sens-unique` (`skillsClass`,`moraleClass`,`efficiencyClass`),
  KEY `sens-skills` (`skillsClass`),
  KEY `sens-morale` (`moraleClass`),
  KEY `sens-efficiency` (`efficiencyClass`),
  CONSTRAINT `sens-efficiency` FOREIGN KEY (`efficiencyClass`) REFERENCES `profileclass` (`profileClassCode`) ON UPDATE CASCADE,
  CONSTRAINT `sens-morale` FOREIGN KEY (`moraleClass`) REFERENCES `profileclass` (`profileClassCode`) ON UPDATE CASCADE,
  CONSTRAINT `sens-skills` FOREIGN KEY (`skillsClass`) REFERENCES `profileclass` (`profileClassCode`) ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=65 DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `restsession` (
  `sessionId` varchar(100) NOT NULL,
  `userId` varchar(100) NOT NULL,
  `restkey` varchar(100) NOT NULL,
  `createdOn` datetime NOT NULL,
  `usedOn` varchar(100) DEFAULT NULL,
  `deletedOn` datetime DEFAULT NULL,
  PRIMARY KEY (`sessionId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `simulator` (
  `sessionId` varchar(100) NOT NULL,
  `seqNumber` int NOT NULL DEFAULT '0',
  `productName` varchar(100) NOT NULL,
  `productExp` varchar(100) DEFAULT NULL,
  `taskName` varchar(20) NOT NULL,
  `taskExp` varchar(100) DEFAULT NULL,
  `staffId` bigint NOT NULL,
  `staffName` varchar(100) DEFAULT NULL,
  `skillsWeight` decimal(6,2) DEFAULT NULL,
  `moraleWeight` decimal(6,2) DEFAULT NULL,
  `skillsValue` int DEFAULT NULL,
  `skillsAddedValue` int DEFAULT NULL,
  `moraleValue` int DEFAULT NULL,
  `moraleAddedValue` int DEFAULT NULL,
  `efficiencyValue` int DEFAULT NULL,
  `efficiencyAddedValue` int DEFAULT NULL,
  `lastAction` varchar(100) DEFAULT NULL,
  `actionExp` varchar(100) DEFAULT NULL,
  `skillsImpact` int DEFAULT NULL,
  `moraleImpact` int DEFAULT NULL,
  `efficiencyImpact` int DEFAULT NULL,
  `skillsSensitivity` decimal(6,2) DEFAULT NULL,
  `moraleSensitivity` decimal(6,2) DEFAULT NULL,
  `efficiencySensitivity` decimal(6,2) DEFAULT NULL,
  `lastSkillsValue` int DEFAULT NULL,
  `lastMoraleValue` int DEFAULT NULL,
  `lastEfficiencyValue` int DEFAULT NULL,
  `modValue` int DEFAULT '0',
  PRIMARY KEY (`sessionId`,`seqNumber`),
  KEY `simulator-action` (`lastAction`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `skillseffect` (
  `skillsEffectId` bigint NOT NULL AUTO_INCREMENT,
  `minMorale` int DEFAULT NULL,
  `maxMorale` int DEFAULT NULL,
  `skillsEffect` decimal(5,2) DEFAULT NULL,
  `efficiencyEffect` decimal(5,2) DEFAULT NULL,
  PRIMARY KEY (`skillsEffectId`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `staff` (
  `staffId` bigint NOT NULL AUTO_INCREMENT,
  `staffName` varchar(100) NOT NULL,
  `staffPhoto` varchar(255) DEFAULT NULL,
  `skillsProfile` varchar(100) NOT NULL,
  `moraleProfile` varchar(100) NOT NULL,
  `efficiencyProfile` varchar(100) NOT NULL,
  `skillsValue` int DEFAULT '0',
  `moraleValue` int DEFAULT '0',
  `efficiencyValue` int DEFAULT '0',
  `staffDescription` text,
  `staffSelfIntroduction` text,
  `staffInterview` text,
  `yearsWorked` int DEFAULT '0',
  `experience` int DEFAULT '0',
  `newComer` int DEFAULT '0',
  `oldTimer` int DEFAULT '0',
  PRIMARY KEY (`staffId`),
  UNIQUE KEY `staff-unique` (`staffName`),
  KEY `staff-skills` (`skillsProfile`),
  KEY `staff-morale` (`moraleProfile`),
  KEY `staff-efficiency` (`efficiencyProfile`)
) ENGINE=InnoDB AUTO_INCREMENT=31 DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `staffx` (
  `staffId` bigint NOT NULL AUTO_INCREMENT,
  `staffName` varchar(100) NOT NULL,
  `staffPhoto` varchar(255) DEFAULT NULL,
  `skillsProfile` varchar(100) NOT NULL,
  `moraleProfile` varchar(100) NOT NULL,
  `efficiencyProfile` varchar(100) NOT NULL,
  `skillsValue` int DEFAULT '0',
  `moraleValue` int DEFAULT '0',
  `efficiencyValue` int DEFAULT '0',
  `staffDescription` text,
  `staffSelfIntroduction` text,
  PRIMARY KEY (`staffId`),
  UNIQUE KEY `staff-unique` (`staffName`),
  KEY `staff-skills` (`skillsProfile`),
  KEY `staff-morale` (`moraleProfile`),
  KEY `staff-efficiency` (`efficiencyProfile`),
  CONSTRAINT `staff-efficiency` FOREIGN KEY (`efficiencyProfile`) REFERENCES `profileclass` (`profileClassCode`) ON UPDATE CASCADE,
  CONSTRAINT `staff-morale` FOREIGN KEY (`moraleProfile`) REFERENCES `profileclass` (`profileClassCode`) ON UPDATE CASCADE,
  CONSTRAINT `staff-skills` FOREIGN KEY (`skillsProfile`) REFERENCES `profileclass` (`profileClassCode`) ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `style` (
  `styleName` varchar(100) NOT NULL,
  `styleDescription` text,
  PRIMARY KEY (`styleName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `task` (
  `taskName` varchar(100) NOT NULL,
  `taskDescription` varchar(45) DEFAULT NULL,
  `stage` int DEFAULT '0',
  PRIMARY KEY (`taskName`),
  KEY `task-name` (`taskName`),
  CONSTRAINT `task-name` FOREIGN KEY (`taskName`) REFERENCES `baseinfo` (`name`) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `taskefficiency` (
  `taskEfficiencyId` bigint NOT NULL AUTO_INCREMENT,
  `productName` varchar(100) NOT NULL,
  `taskName` varchar(100) NOT NULL,
  `skillsWeight` decimal(6,2) DEFAULT '0.00',
  `moraleWeight` decimal(6,2) DEFAULT '0.00',
  `duration` decimal(6,2) DEFAULT '1.00',
  `taskDisplayName` varchar(100) DEFAULT NULL,
  `passingRate` int DEFAULT '1',
  `target` decimal(5,2) DEFAULT '1.00',
  PRIMARY KEY (`taskEfficiencyId`),
  KEY `efficiency-task` (`taskName`),
  KEY `efficiency-product_idx` (`productName`),
  CONSTRAINT `efficiency-product` FOREIGN KEY (`productName`) REFERENCES `product` (`productShortName`) ON UPDATE CASCADE,
  CONSTRAINT `efficiency-task` FOREIGN KEY (`taskName`) REFERENCES `task` (`taskName`) ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=64 DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `template` (
  `templateName` varchar(100) NOT NULL,
  `templateDescription` text,
  `numberOfStaff` int DEFAULT '10',
  `numberOfDays` int DEFAULT '20',
  `duration` int DEFAULT '20',
  `environmentEvents` int DEFAULT '0',
  `recruits` int DEFAULT '2',
  `report` int DEFAULT '1',
  PRIMARY KEY (`templateName`),
  KEY `template_name_idx` (`templateName`),
  CONSTRAINT `template_name` FOREIGN KEY (`templateName`) REFERENCES `baseinfo` (`name`) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

-- ------------------------------------------------------------------ views

CREATE OR REPLACE VIEW `agentlicense_view` AS select `a`.`agentId` AS `agentId`,`a`.`agentName` AS `agentName`,`a`.`active` AS `active`,`a`.`availableTill` AS `availableTill`,`b`.`batchId` AS `batchId`,`b`.`batchDesc` AS `batchDesc`,sum(`b`.`licenseVolume`) AS `licenseVolume`,(select count(0) from `license` `l` where (`l`.`batchId` = `b`.`batchId`)) AS `licenseUsed` from (`agent` `a` left join `licensebatch` `b` on((`a`.`agentId` = `b`.`agentId`))) group by `a`.`agentId`,`b`.`batchId`;

CREATE OR REPLACE VIEW `batchlicense_view` AS select `agent`.`agentName` AS `agentName`,`licensebatch`.`batchId` AS `batchId`,`licensebatch`.`agentId` AS `agentId`,`licensebatch`.`batchDesc` AS `batchDesc`,`licensebatch`.`issueDate` AS `issueDate`,`licensebatch`.`availableTill` AS `availableTill`,`licensebatch`.`licenseVolume` AS `licenseVolume`,count(`license`.`licenseId`) AS `licenseUsed` from (`agent` join (`licensebatch` left join `license` on((`licensebatch`.`batchId` = `license`.`batchId`)))) where (`agent`.`agentId` = `licensebatch`.`agentId`) group by `licensebatch`.`batchId` order by `licensebatch`.`availableTill`;

CREATE OR REPLACE VIEW `impactprofile_view` AS select `s`.`profileImpactKey` AS `profileImpactKey`,`s`.`impactClass` AS `impactClass`,`s`.`skillsClass` AS `skillsClass`,`s`.`moraleClass` AS `moraleClass`,`s`.`efficiencyClass` AS `efficiencyClass`,`s`.`skillsImpact` AS `skillsImpact`,`s`.`moraleImpact` AS `moraleImpact`,`s`.`efficiencyImpact` AS `efficiencyImpact`,`s`.`impactMsg` AS `impactMsg`,`s`.`skillsAffect` AS `skillsAffect`,`s`.`moraleAffect` AS `moraleAffect`,`s`.`efficiencyAffect` AS `efficiencyAffect`,`s`.`affectMsg` AS `affectMsg`,`sp`.`profileClassDisplayName` AS `skillsProfileName`,`sp`.`profileClassMin` AS `skillsMin`,`sp`.`profileClassMax` AS `skillsMax`,`mp`.`profileClassDisplayName` AS `moraleProfileName`,`mp`.`profileClassMin` AS `moraleMin`,`mp`.`profileClassMax` AS `moraleMax`,`ep`.`profileClassDisplayName` AS `efficiencyProfileName`,`ep`.`profileClassMin` AS `efficiencyMin`,`ep`.`profileClassMax` AS `efficiencyMax`,`ps`.`skillsSensitivity` AS `skillsSensitivity`,`ps`.`moraleSensitivity` AS `moraleSensitivity`,`ps`.`efficiencySensitivity` AS `efficiencySensitivity`,`s`.`desiredStyle` AS `desiredStyle` from ((((`profileimpact` `s` join `profileclass` `sp`) join `profileclass` `mp`) join `profileclass` `ep`) join `profilesens` `ps`) where ((`s`.`skillsClass` = `sp`.`profileClassCode`) and (`s`.`moraleClass` = `mp`.`profileClassCode`) and (`s`.`efficiencyClass` = `ep`.`profileClassCode`) and (`s`.`skillsClass` = `ps`.`skillsClass`) and (`s`.`moraleClass` = `ps`.`moraleClass`) and (`s`.`efficiencyClass` = `ps`.`efficiencyClass`));

CREATE OR REPLACE VIEW `license_view` AS select `l`.`licenseId` AS `licenseId`,`l`.`batchId` AS `batchId`,`l`.`packageId` AS `packageId`,`l`.`companyName` AS `companyName`,`l`.`userId` AS `userId`,`l`.`userName` AS `userName`,`l`.`usedOn` AS `usedOn`,`b`.`batchDesc` AS `batchDesc` from (`license` `l` join `licensebatch` `b`) where (`l`.`batchId` = `b`.`batchId`);

CREATE OR REPLACE VIEW `staffprofile_view` AS select `s`.`staffId` AS `staffId`,`s`.`staffName` AS `staffName`,`s`.`staffPhoto` AS `staffPhoto`,`s`.`skillsProfile` AS `skillsProfile`,`s`.`moraleProfile` AS `moraleProfile`,`s`.`efficiencyProfile` AS `efficiencyProfile`,`s`.`skillsValue` AS `skillsValue`,`s`.`moraleValue` AS `moraleValue`,`s`.`efficiencyValue` AS `efficiencyValue`,`s`.`staffDescription` AS `staffDescription`,`s`.`yearsWorked` AS `yearsWorked`,`s`.`experience` AS `experience`,`s`.`newComer` AS `newComer`,`s`.`oldTimer` AS `oldTimer`,`sp`.`profileClassDisplayName` AS `skillsProfileName`,`sp`.`profileClassMin` AS `skillsMin`,`sp`.`profileClassMax` AS `skillsMax`,`mp`.`profileClassDisplayName` AS `moraleProfileName`,`mp`.`profileClassMin` AS `moraleMin`,`mp`.`profileClassMax` AS `moraleMax`,`ep`.`profileClassDisplayName` AS `efficiencyProfileName`,`ep`.`profileClassMin` AS `efficiencyMin`,`ep`.`profileClassMax` AS `efficiencyMax` from (((`staff` `s` join `profileclass` `sp`) join `profileclass` `mp`) join `profileclass` `ep`) where ((`s`.`skillsProfile` = `sp`.`profileClassCode`) and (`s`.`moraleProfile` = `mp`.`profileClassCode`) and (`s`.`efficiencyProfile` = `ep`.`profileClassCode`));


-- Views are created after every table so their dependencies exist.
SET FOREIGN_KEY_CHECKS = 1;
