# IMP-02 MySQL 与权限底座验证

> 文档类型：阶段实施证据；版本：1.0.0；文档状态：Draft  
> Feature：FEAT-FASHION-001；实施阶段：IMP-02  
> 证据结果：Pass；执行日期：2026-09-13  
> owner：Codex 按实际命令与结果维护  
> 输入：[生产任务清单](../../任务清单.md)、[数据库设计 v2.3](../../../../归档/设计/2026-10-01-设计归一前/架构/数据库设计.md)

## 1. 结论与边界

IMP-02 的 MySQL、公共持久化、RuoYi 权限/字典/配置底座已经实现并通过退出门。迁移精确创建数据库设计 v2.3 的 16 张 `fq_*` 业务表；Flyway 使用独立技术历史表 `fashion_flyway_schema_history`，它不是业务表。设置页只是底座页面，不代表商品、选品、报价或交付流程已经完成。

本次数据库验证只使用临时隔离 MySQL 8.0.46，监听 `127.0.0.1` 高位端口；没有连接、迁移或写入已有项目数据库。两次实例均已正常关闭，临时数据目录已移入 Windows 回收站，可恢复；验证后端口不再监听。没有创建账号/角色，没有写 `sys_role_menu`，没有启用真实 Provider，也没有执行 Git 写操作或发布。

## 2. 实际实现

- `V1__create_fashion_schema.sql` 一次性建立 T01～T16，DDL 静态统计为 16 表、39 外键、110 个命名检查、39 个 JSON 字段、22 个 `DECIMAL` 字段、62 个 `DATETIME(3)` 字段和 16 个 `row_version` 字段。
- Fashion Flyway 固定使用 `classpath:db/fashion-migration`、独立 history、`cleanDisabled=true`、默认不开启；目标库名、RuoYi 标志表、未知/残缺 `fq_*` 表和首次 baseline 均 fail-closed。
- `fashionJdbcTemplate`、`fashionTransactionManager`、`fashionTransactionTemplate` 均显式绑定 `dynamicDataSource`；双事务管理器测试证明存在 `interviewTransactionManager` 时不会误注入。
- `FashionCatalogWriteLock` 统一锁名 `fashion:{database}:catalog-write`，要求活动 Fashion 事务，通过同一事务物理连接获取，并在 commit/rollback 后、连接归还池前释放。
- 平台脚本只复用 `sys_menu`、`sys_dict_type/sys_dict_data`、`sys_config`；六类字典和八个非 Secret 配置键均为封闭白名单，脚本可重复执行。
- 设置 API 有独立读写权限、严格请求字段、类型/范围/字典校验和 before/after/操作者审计；Admin 设置页从 RuoYi 字典接口取仓库标签。
- 客户归属、有效字典值和字段白名单 Guard 已建立；登录主体和操作日志继续复用 RuoYi。

## 3. MySQL 与 Java 证据

| 检查 | 实际命令或场景 | 实际结果 |
| --- | --- | --- |
| 隔离 MySQL 真实迁移 | 以系统属性向 `FashionProvidedMySqlMigrationTest` 提供临时 `jdbc:mysql://localhost:34069/fashion_test` | MySQL 8.0.46；3 tests，0 failure/error；Flyway baseline 0、迁移 V1、重复迁移 0 项，Pass |
| 精确 Schema | 查询 `information_schema` | `fq_*` 恰好 16；39 JSON；所有 `fq_*` DATETIME 精度为 3；至少 15 个 `DECIMAL(16,*)`、40 个唯一索引/主键组合、100 个检查、30 个引用约束；禁建表为 0 |
| RuoYi 升级兼容 | 先导入隔离副本 `ry_20260417.sql`，再 baseline/migrate | 迁移后既有 `sys.account.captchaEnabled=true` 保持不变；Fashion Schema 完整，Pass |
| 失败与恢复 | 无效 `row_version=0`、重复客户编码、锁竞争、事务中强制抛错 | 约束拒绝无效数据；第二连接即时锁超时；回滚后测试客户为 0，锁可再次获取，Pass |
| 平台脚本幂等 | 权限、六字典、八配置脚本各执行两次 | 六类字典、八个配置键均无重复；Fashion `sys_role_menu` 关联为 0，Pass |
| Java 完整回归 | `mvn -f platform-backend/pom.xml -pl ruoyi-fashion -am test` | 51 tests，0 failure/error，6 skipped；45 项本地测试实际执行，Pass |
| MySQL 跳过说明 | 同一完整回归中的 Testcontainers/外部 URL 用例 | Docker 不可用时 3 项容器测试按声明跳过，未提供 URL 时 3 项外部库测试按声明跳过；上述隔离 MySQL 命令已单独实际执行 3/3 |
| 后台完整装配 | `mvn -f platform-backend/pom.xml -pl ruoyi-admin -am -DskipTests package` | 15 个 reactor module 全部 SUCCESS，`ruoyi-admin.jar` 重新打包，Pass |
| Bean 与 YAML | 双事务管理器 Context、`application.yml` 解析测试 | Fashion Bean 绑定 MySQL；`fashion` 与完整 `interview.voice-runtime` 层级互不覆盖，Pass |
| API 安全链 | MockMvc + Spring Security + `@PreAuthorize` | 未登录 GET `/fashion/settings` 为 401；已登录无权限为 403，Pass |
| 资源权限 | `FashionCustomerAccessGuardTest` | 管理员/销售归属/协作者语义与越权拒绝均通过 |

完整回归出现 Mockito 动态 agent 的未来兼容 warning；当前测试仍为成功。容器测试的 Docker 不可用日志不代表 MySQL 未验证，因为同一套核心断言已经针对独立的本机临时实例实际执行。

## 4. Admin 证据

| 检查 | 实际结果 |
| --- | --- |
| `npm --prefix admin-web run typecheck` | `vue-tsc` 退出码 0，Pass |
| `npm --prefix admin-web run test:unit` | 3 files / 22 tests，Pass |
| `npm --prefix admin-web run build:prod` | Vite 生产构建成功，2561 modules，Pass |

本阶段未启动完整 Java 服务和真实 RuoYi 登录会话，因此未把设置页浏览器端到端业务流写成通过；服务端真实安全链和前端 API/类型/构建已分别取证。

## 5. 依赖与许可证

`mvn ... dependency:tree` 的实际解析结果：Flyway Core/MySQL `11.14.1`、MySQL Connector/J `9.7.0`、Testcontainers JUnit/MySQL `2.0.5`、Spring Security Test `7.0.5`。本机 Maven POM 元数据记录的许可证分别为 Apache-2.0、GPL-2.0 with Universal FOSS Exception、MIT、Apache-2.0。

GitHub Advisory Database 的定向复核显示，Connector/J 已知高风险 `GHSA-m6vm-37g8-gqvh` 影响 `<8.2.0`，当前 9.7.0 不在范围；Spring Security `CVE-2026-22747` 影响 7.0.0～7.0.4，当前 7.0.5 是修复版本。搜索没有发现上述锁定版本的精确 High/Critical 命中，但这不是完整 SBOM 扫描；后续依赖升级仍应复跑自动审计。

## 6. 失败、修正与残余限制

- 第一条临时 MySQL 编排命令因包含动态递归清理被安全策略拒绝，未执行；随后改用明确工作区路径和可恢复回收站清理。
- 第一次实例启动因 `basedir` 含空格未正确引用而失败；修正参数后启动。首次 root 连接又因 `skip-name-resolve` 与 `root@localhost` 不匹配而失败；只停止该临时监听进程并去掉该选项后成功。
- 一次 Maven URL 的 `&` 被 Windows 命令包装器拆分，未进入测试；改用无查询串 URL 后成功。
- Java 17 首次编译测试因测试代码使用较新 Matcher API 失败，已改为 Java 17 兼容循环并通过。
- `application.yml` 初次补丁误切开 Interview 配置层级，自审时发现并修正；现有解析测试覆盖该回归。
- 新增 HTTP 安全测试初次因测试夹具缺少 401 authentication entry point 得到 403；补齐与生产语义一致的入口后，401/403 场景均通过。
- 一次定向测试命令的 Surefire 带点参数被 `mvn.cmd` 拆分为无效生命周期，未执行测试；改跑完整套件并成功。
- 生产数据库迁移仍保持默认关闭；非隔离真实库首次启用必须重新确认目标库、备份/恢复和 baseline 决定。本阶段 Pass 不授权该动作。

