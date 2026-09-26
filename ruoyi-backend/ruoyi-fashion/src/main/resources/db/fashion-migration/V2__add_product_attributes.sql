-- 保留旧记录与全部业务引用；旧程序可忽略新增列。
ALTER TABLE fq_product ADD COLUMN attributes_json JSON NULL COMMENT '服装属性与电商来源白名单';
