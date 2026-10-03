# Java 业务模块统一装配与启动

> 2026-10-03 启动规则已更新：本页旧四模块装配与检查保留原范围，九项目选择及共享数据库／Redis 的日常操作唯一见[多项目并行开发启动指南](多项目并行开发启动指南.md)。本轮未修改 POM、模块开关或运行脚本。

> 文档类型：共享后端集成设计与实施记录  
> 文档状态：Draft  
> 风险等级：L2  
> owner：共享若依后端  
> 批准依据：2026-09-25 用户要求四个项目的 Java 后端由若依统一管理，并要求开始实施  
> 更新时间：2026-10-01

本页讲共享 Java 后端怎样装配业务模块。当前四项目日常使用四个独立实例，操作只维护在[启动指南](多项目并行开发启动指南.md)；下面的默认组合能力和 2026-09-25 技术记录不代表当前四实例或业务验收结果。

## 目标与验收条件

`ruoyi-backend/ruoyi-admin` 是四个项目 Java HTTP API 的唯一 Spring Boot 启动入口。面试、智能选品、智能体桌面端和生产排产分别保留自己的 Maven 模块、业务数据边界和权限规则。

1. 准备好各业务数据库和 schema 后，默认配置在一个 `ruoyi-admin` JVM 中注册四组 Java HTTP API。
2. `interview.enabled`、`fashion.enabled`、`aden.enabled`、`aps.enabled` 缺省为 `true`；任一项设为 `false` 并重启后，该项目的 HTTP 入口、业务 Bean 和进程内后台任务均不注册，其他项目仍可装配。
3. 开启模块不自动执行 Flyway 迁移、真实 Provider 调用、对象存储写入或 APS 求解轮询。这些副作用使用现有独立开关；已有选品图片和交付 Worker 的默认值保持原状。
4. APS API 在若依 JVM 内；同一 Maven 父工程中的 `aps-worker` 保持独立 Java 进程，不被 `ruoyi-admin` 隐式装配。

## 配置与部署边界

| 模块 | 总开关 | 默认启用的前置条件 |
|---|---|---|
| 面试 | `INTERVIEW_ENABLED` | 面试 PostgreSQL 业务库、账号和已迁移 schema；平台账号仍由若依 MySQL 管理 |
| 智能选品 | `FASHION_ENABLED` | 若依 MySQL 中已准备选品表；正式业务写入仍由 Java/MySQL 持有 |
| 智能体桌面端 | `ADEN_ENABLED` | `ADEN_EXPECTED_DATABASE` 与若依 MySQL 的已迁移 Aden schema；只读 schema guard 保留 |
| 生产排产 API | `APS_ENABLED` | 独立 `APS_DB_URL`、`APS_DB_USERNAME`、`APS_SITE_CODE`、数据库及已迁移 APS schema；单独关闭报表时可不设置站点码 |

默认启用是**装配意图**，不代表数据库、账号或 schema 已准备。缺少必要配置应明确失败，不能静默回退到若依主库，也不能把启动失败记成模块就绪。`test` profile 可显式关闭业务模块，避免单元测试连接真实数据库。本地入口 `ruoyi-backend/bin/run-local.ps1` 从根 `.env` 读取上述项及相关数据库变量；不提交真实口令。

Maven POM 保持固定的模块依赖；运行开关由 Spring 配置决定，修改后重启应用，不支持热卸载。APS Worker 仍按[排产引擎设计](../项目/生产排产项目/架构/APS排程引擎需求与技术设计.md)独立运行，以隔离 OR-Tools 原生库和求解资源。

## 实施范围与验证

- 面试、选品、Aden：在各业务模块内完成总开关条件装配和定向测试。
- APS：沿用现有分层条件装配，调整若依默认配置并验证独立数据源缺项明确失败。
- 共享入口：保留现有 POM 依赖和用户未提交差异，更新启动变量、配置说明与必要的 Mapper 扫描边界。
- 最小验证：四模块定向测试、`ruoyi-admin -am` 构建、默认配置和逐项关闭的条件装配核查；实际单 JVM 联机启动须使用隔离且已迁移的 MySQL、PostgreSQL 和 Redis。未经运行的项目或外部链路记录为未验证。

本轮不执行真实业务库迁移、Provider 调用、对外写入、发布或 Git 写操作。实际命令、结果与未满足的环境前置条件在实施结束时记录，不以本页目标代替验证结论。

## 本轮技术验证（2026-09-25）

下表均在本机 Windows、JDK 17、Maven 3.9 的当前工作树执行；定向测试使用无真实业务数据库的 Spring 测试上下文。

| 对象 | 实际命令或步骤 | 结果 | 能证明的范围 |
|---|---|---|---|
| 面试条件装配 | `mvn -pl ruoyi-interview -am "-Dtest=InterviewModuleEnabledTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | Pass；2 项 | 缺省装配和 `interview.enabled=false` 时组件扫描退出 |
| 选品条件装配 | `mvn -pl ruoyi-fashion -am "-Dtest=FashionModuleEnabledTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | Pass；2 项 | 缺省装配和 `fashion.enabled=false` 时组件扫描退出 |
| Aden 条件装配 | `mvn -pl ruoyi-aden -am '-Dtest=AdenModuleSwitchTest,AdenPropertiesTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | Pass；5 项 | 关闭模块时 HTTP、Mapper、任务与 guard 不装配；保留数据库前置校验 |
| APS 数据源 | `mvn -pl aps/aps-infrastructure-mysql -am '-Dtest=ApsDataSourceConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | Pass；4 项 | APS URL/用户名缺失时明确失败，不回退若依主库 |
| 共享编译 | `mvn -pl ruoyi-admin -am -DskipTests compile` | Pass；18 个 reactor 项目 | 四业务模块与若依入口可共同编译；不证明运行就绪 |
| 验证制品 | 聚合 `package` 临时给 Spring Boot 插件配置 `codex-verify` classifier，构建后恢复 POM；检查 JAR 清单 | Pass；生成 `ruoyi-admin-codex-verify.jar`，含四个项目 API 模块 | 一个可执行制品包含四业务 Java 模块；未启动它 |
| 工作区差异 | `git diff --check` | Pass | 新增改动没有 Git 空白错误 |

标准 `mvn -pl ruoyi-admin -am -DskipTests package` 的编译阶段全部通过，但最后重打包失败：两个已存在的 Java 进程正在使用 `ruoyi-admin.jar`，Windows 不允许 Maven 将该文件改名为 `.original`。本轮没有停止这些进程。失败后磁盘上的标准 `ruoyi-admin.jar` 是普通 JAR，不能作为可执行启动制品；`run-local.ps1` 已增加制品格式检查以阻止误报“启动成功”。占用进程结束后需重新执行标准 package 才能恢复标准制品；临时验证 JAR 只留在忽略的 `target/`，POM 未保留临时 classifier。

文档全量检查 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-docs.ps1` 未通过，原因是本轮开始前已有的 Aden `证据/2026-09-25-合成验收/verification-report.html` 不符合中文文件名规则；本轮未移动或改名该文件。四模块共同连接隔离 MySQL、PostgreSQL 和 Redis 的真实单 JVM 启动及逐项关闭验证尚未执行，因此不能把条件测试和制品清单当作联机验收。
