-- 未执行增量草案。真实MySQL 8迁移须审批并先备份核对旧版本；不回填、不改旧canvas/hash。
-- NULL代表旧版本未保存中心权威资源编译；仅旧纯图可按原hash和空资源运行。
-- 旧含资源版本必须重新编译新版本，不能把NULL/客户端metadata误认作已批准资源。
-- 新版本由Service总写受控对象JSON（纯图为{}）；LONGTEXT无DEFAULT，避免方言表达式假设。
ALTER TABLE paiflow_workflow_version ADD COLUMN resource_snapshots_json LONGTEXT NULL;
