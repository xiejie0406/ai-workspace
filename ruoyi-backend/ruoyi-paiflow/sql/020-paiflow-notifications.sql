-- 本地源码接续增量，未执行；不包含账号、旧通知导入、对外发送或自动初始化。
-- 正式启用前按既有结构备份和变更授权核对；不能把此前25张表称为已包含本增量。
CREATE TABLE paiflow_notification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  type VARCHAR(16) NOT NULL,
  title VARCHAR(200) NOT NULL,
  body MEDIUMTEXT NOT NULL,
  template_code VARCHAR(100) NULL,
  payload_json MEDIUMTEXT NULL,
  meta_json MEDIUMTEXT NULL,
  creator_user_id BIGINT NOT NULL,
  expire_at DATETIME(3) NULL,
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY(id), KEY ix_paiflow_notification_type(type,create_time,id),
  CONSTRAINT ck_paiflow_notification_type CHECK(type IN('PERSONAL','BROADCAST','SYSTEM','PROMOTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paiflow_notification_recipient (
  notification_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  is_read TINYINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  read_at DATETIME(3) NULL,
  received_at DATETIME(3) NOT NULL,
  PRIMARY KEY(notification_id,user_id), KEY ix_paiflow_notification_user(user_id,deleted,is_read,notification_id),
  CONSTRAINT fk_paiflow_notification_recipient FOREIGN KEY(notification_id) REFERENCES paiflow_notification(id),
  CONSTRAINT ck_paiflow_notification_read CHECK(is_read IN(0,1)),
  CONSTRAINT ck_paiflow_notification_deleted CHECK(deleted IN(0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
