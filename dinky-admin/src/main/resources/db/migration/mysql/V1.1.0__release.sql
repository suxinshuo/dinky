
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;


update dinky_sys_menu
set `path`='/registration/alert/rule',
    `component`='./RegCenter/Alert/AlertRule',
    `perms`='registration:alert:rule',
    `parent_id`=12
where `id` = 116;

update dinky_sys_menu
set `path`='/registration/alert/rule/add',
    `perms`='registration:alert:rule:add'
where `id` = 117;

update dinky_sys_menu
set `path`='/registration/alert/rule/delete',
    `perms`='registration:alert:rule:delete'
where `id` = 118;

update dinky_sys_menu
set `path`='/registration/alert/rule/edit',
    `perms`='registration:alert:rule:edit'
where `id` = 119;



-- Increase class_name column's length from 50 to 100.
ALTER TABLE dinky_udf_manage CHANGE COLUMN class_name class_name VARCHAR(100) null DEFAULT null COMMENT 'Complete class name';

ALTER TABLE dinky_task
    add  COLUMN `first_level_owner` int DEFAULT NULL comment 'primary responsible person id';

ALTER TABLE dinky_task
    add  COLUMN `second_level_owners` varchar(128) DEFAULT NULL comment 'list of secondary responsible persons ids';


update dinky_task set first_level_owner = creator;

ALTER TABLE dinky_alert_template MODIFY COLUMN `name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'template name';


ALTER TABLE dinky_history CHANGE COLUMN `statement` `statement` mediumtext DEFAULT NULL COMMENT 'statement set';

ALTER TABLE dinky_task CHANGE COLUMN `statement` `statement` mediumtext DEFAULT NULL COMMENT 'sql statement';

ALTER TABLE dinky_task_version CHANGE COLUMN `statement` `statement` mediumtext DEFAULT NULL COMMENT 'flink sql statement';

SET FOREIGN_KEY_CHECKS = 1;
