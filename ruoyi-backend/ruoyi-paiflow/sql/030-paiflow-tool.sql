-- HTTP工具唯一中心资源、不可变版本及逐次操作；应用不自动执行本DDL。
CREATE TABLE paiflow_tool (
 id BIGINT NOT NULL AUTO_INCREMENT, space_id BIGINT NOT NULL, owner_user_id BIGINT NOT NULL,
 plugin_id VARCHAR(128) NOT NULL, name VARCHAR(128) NOT NULL, configuration_json MEDIUMTEXT NOT NULL,
 draft_configuration_json MEDIUMTEXT NULL,
 revision BIGINT NOT NULL DEFAULT 0, enabled TINYINT NOT NULL DEFAULT 1, temporary TINYINT NOT NULL DEFAULT 0,
 deleted TINYINT NOT NULL DEFAULT 0, create_by VARCHAR(64) NOT NULL, update_by VARCHAR(64) NOT NULL,
 create_time DATETIME(3) NOT NULL, update_time DATETIME(3) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_paiflow_tool_plugin(plugin_id), KEY ix_paiflow_tool_scope(space_id,deleted),
 CONSTRAINT ck_paiflow_tool_flags CHECK(enabled IN (0,1) AND temporary IN (0,1) AND deleted IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE paiflow_tool_version (
 id BIGINT NOT NULL AUTO_INCREMENT, tool_id BIGINT NOT NULL, space_id BIGINT NOT NULL,
 owner_user_id BIGINT NOT NULL, legacy_version VARCHAR(32) NOT NULL, configuration_json MEDIUMTEXT NOT NULL,
 configuration_hash CHAR(64) NOT NULL, create_time DATETIME(3) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_paiflow_tool_version(tool_id,legacy_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE paiflow_tool_operation (
 id VARCHAR(64) CHARACTER SET ascii NOT NULL, run_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
 lease_id VARCHAR(128) CHARACTER SET ascii NOT NULL, attempt INT NOT NULL, node_id VARCHAR(255) NOT NULL,
 resource_id VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 idempotency_key VARCHAR(4096) NOT NULL, request_key_hash CHAR(64) NOT NULL, request_hash CHAR(64) NOT NULL,
 request_json MEDIUMTEXT NOT NULL, binding_json MEDIUMTEXT NOT NULL, state VARCHAR(32) NOT NULL,
 dispatch_started TINYINT NOT NULL DEFAULT 0, response_json MEDIUMTEXT NULL, failure_kind VARCHAR(32) NULL,
 revision BIGINT NOT NULL DEFAULT 0, create_time DATETIME(3) NOT NULL, update_time DATETIME(3) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_paiflow_tool_request(run_id,lease_id,attempt,request_key_hash),
 KEY ix_paiflow_tool_run(run_id,state), KEY ix_paiflow_tool_resource(run_id,resource_id(191)),
 CONSTRAINT ck_paiflow_tool_operation_state CHECK(state IN ('RUNNING','COMPLETED','FAILED','CANCELLED','EFFECT_UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
