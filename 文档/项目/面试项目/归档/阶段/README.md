# AI Interview Coach 十八期实践路线（合并深化版）

> 设计引用整理（2026-10-01）：本文保留原历史事实与结论。被删除旧设计的原名、章节和版本属于当时上下文；迁移后的设计链接只用于查阅当前统一设计，不替代当时的验收基线。原文及对应见[统一记录](../../开发记录/2026-10-01-设计文档统一与清理.md)。

> 文档类型：Phase / 跨功能路线审查  
> 文档状态：Draft  
> owner / 责任边界：架构计划 owner 维护建议；产品、架构、质量与用户分别审批其责任范围  
> 创建时间：2026-08-02  
> 更新时间：2026-08-02  
> Roadmap ID：ROADMAP-INTERVIEW-001  
> 风险等级：L3  
> 产出/适用阶段：阶段 3–6 的前瞻审查输入  
> 阶段状态：WaitingForApproval  
> 证据结果：NotRun（本轮仅文档审查）  
> 关联：[PRD](../../产品/产品需求文档.md)、[技术架构](../../架构/面试项目完整设计.md#backend)、[决策登记](../../决策/决策登记表.md)

## 1. 结论

推荐采用 **18 期**。期数本身足够；合并审查发现，早期草案第 05–11 期把确定性状态、Agent、文本 UI、评测和报告拆成连续水平层，用户价值出现偏晚；Outbox/Job、用量预留、隐私删除与生产开关却到第 14–17 期才完整出现，存在依赖倒置。当前唯一版本已按下述顺序重排。

合并版保持 18 期，并执行三项重排：

1. T0 在第 02 期验证 Provider、中文技术 ASR/TTS、结构化输出与 Golden Set，不锁定未经核验的版本或供应商。
2. 把 tenant、同意、用量端口、Outbox/Job、幂等和恢复前移到首次真实业务链路之前。
3. 第 09 期形成文本面试可运行切片，第 13 期形成级联语音闭环；第 14–18 期才进入 T2 的多实例韧性、完整隐私、真实支付、生产治理和上线就绪。

本目录是项目唯一的 Phase 路线入口。任何一期进入实施前，仍须修复上游截断、关闭相应决策、完成原型、批准 Canonical Feature Spec/设计，并在 `docs/features/<FEAT-ID>-<slug>/任务清单.md` 建立唯一任务事实源和执行包。

### 1.1 合并说明

本版已经把早期短版十八期与后续架构深化稿合并：保留早期路线中的 T0/T1/T2、风险原型、身份、题库、练习、文本/语音、评测、学习、计费、隐私、运维和系统验证目标，同时采用深化审查后的依赖顺序、26 项每期决策字段、模块/schema/API/文件 owner 和完成边界。项目不再维护第二套路线路径。

## 2. 输入事实与限制

- PRD 在 `FUNC-VOICE-002` 后存在截断；技术架构的数据模型段存在截断。本文只使用截断后仍明确存在的 `REQ-*`、`BR-*`、`AC-*` 和其他可读内容，不猜写丢失正文。
- 当前主阶段仍是阶段 3 功能规格，状态 `WaitingForApproval`；本目录全部为 `Draft`、规划/未实现。
- 除 `DEC-001` 外，Gate A 尚未批准；本文将推荐项写成“规划假设”和前置决策，不把 `Pending` 写成事实。
- 本项目与 PaiCLI 完全独立，不存在源码、Jar、Maven module、Runtime API、数据、配置、前端、部署或 Git 依赖。
- 本轮未创建源码、依赖、测试、构建、服务、部署、Git 或真实外部调用。

## 3. 推荐十八期

| 期次 | 主题 | 主要 owner | 可观察阶段成果 | 里程碑 |
|---|---|---|---|---|
| [01](第01期-独立工程契约与模块边界地基.md) | 独立工程、契约与模块边界地基 | platform | 后端/前端/契约/迁移的最小壳及依赖规则可检查 | T0 |
| [02](第02期-服务商语音结构化输出与标准集风险验证.md) | Provider、语音、结构化输出与 Golden Set 风险验证 | agent/evaluation/voice | 脱敏样本上的质量、延迟、成本与停止结论可审核 | T0 |
| [03](第03期-身份租户与同意基线.md) | Identity、personal tenant 与同意基线 | identity/governance | 登录、租户隔离、协议同意和隐私入口的纵切可演示 | T0 |
| [04](第04期-版本化题库与内容发布.md) | 版本化题库与内容发布纵切 | catalog | 管理员发布、用户筛选已发布题目、历史版本不覆盖 | T0 |
| [05](第05期-文本单题练习.md) | 文本单题练习纵切 | practice | 用户作答、保存、重练、历史和待加强标记可运行 | T1 |
| [06](第06期-服务商接口与单题证据反馈.md) | Provider SPI 与单题证据反馈纵切 | agent/evaluation | 单题回答得到结构化、有证据、可拒判的反馈 | T1 |
| [07](第07期-权益预检与面试计划.md) | 权益预检与面试计划纵切 | billing/interview | 用户看见预计用量并确认确定性面试计划 | T1 |
| [08](第08期-会话状态事务发件箱任务与恢复地基.md) | 会话状态、Outbox/Job 与恢复地基 | interview/platform | 无 LLM 模拟器下可开始、暂停、恢复、结束且任务可接管 | T1 |
| [09](第09期-文本面试智能体闭环.md) | 文本 Interview Agent 闭环 | interview/agent/frontend | 完成一场可流式、可中断、可恢复的文本面试 | T1 |
| [10](第10期-评测报告与质量门.md) | 评测、报告与质量门纵切 | evaluation | 面试结束后生成版本化证据报告，Golden Set 控制启用 | T1 |
| [11](第11期-学习教练复练与看板.md) | Learning Coach、复练与 Dashboard | learning | 报告弱项进入可编辑训练计划并可复测 | T1 |
| [12](第12期-音频语音识别与转写确认.md) | 音频 Artifact、ASR 与转写确认 | voice/governance | 经同意完成语音输入，修正转写并按 SLA 删除原音 | T1 |
| [13](第13期-级联语音面试闭环.md) | TTS 与级联语音面试闭环 | voice/interview | ASR→文本 Agent→TTS 完整闭环且随时降级文本 | T1 |
| [14](第14期-多实例韧性缓存恢复与功能开关.md) | 多实例韧性、Redis、恢复与 Feature Flag | platform/operations | 故障注入下幂等、重试、续传、限流和关闭能力可观察 | T2 |
| [15](第15期-隐私删除管理员权限与审计.md) | 完整隐私删除、管理员权限与审计 | governance | 导出/删除/撤回/敏感访问 reason code 全链可审核 | T2 |
| [16](第16期-订单支付额度结算与成本账本.md) | 订单、支付、额度结算与成本账本 | billing | 沙箱/批准渠道的支付回调不会重复授予，账本可复算 | T2 |
| [17](第17期-生产运维监控备份与回滚准备.md) | 生产运维、监控、备份与回滚准备 | operations | 环境隔离、告警、备份恢复、迁移与回滚手册可审核 | T2 |
| [18](第18期-系统审查浏览器验收与上线就绪.md) | 系统审查、浏览器 UAT 与上线就绪 | quality/release | AC—EV、UAT、故障/安全/容量证据与 ReleaseReady 判断输入 | T2 |

### 3.1 平台收敛补充期（Phase-19）

| 期次 | 主题 | 主要 owner | 可观察阶段成果 | 里程碑 |
|---|---|---|---|---|
| [19](第19期-若依身份与后端单体收敛.md) | RuoYi 身份与后端单体收敛 | platform/identity/backend | 单一登录态、单后端进程、ruoyi-interview 模块接入、旧身份退役候选 | 5–7 |

> Phase-19 是平台治理补充期，不替代上面的 18 期产品路线；它只处理身份/RBAC 与后端单体收敛，不引入新业务 feature。

## 4. 阶段门与延后范围

- T0 退出：高风险路线有实际或明确 `Blocked/NotRun` 证据；模块/协议/数据/隐私假设可供批准，不代表生产能力。
- T1 退出：文本与级联语音均有端到端纵切，报告与复练成立；真实支付、生产 SLA 和发布不属于 T1。
- T2 退出：支付、隐私、治理、运维与系统级验证满足批准门时，才可能形成 `ReleaseReady`；发布事实仍为 `NotReleased`。
- Realtime/WebRTC、B2B、Kubernetes、微服务、SSO/SCIM、Kafka、独立向量库、视频数字人继续延后，恢复即重新走 L3 分流与批准。

## 5. 文档导航

- [架构审查](技术架构审查与边界深化.md)：现状问题、推荐架构、协议、数据、Agent、可靠性与安全边界。
- [依赖矩阵](依赖覆盖与负责人矩阵.md)：期次依赖、模块 owner、REQ/AC 覆盖和外部系统 owner。
- [文件结构蓝图](../../架构/面试项目完整设计.md#implementation)：Maven module、Java package、主要类、迁移、契约、React 与测试计划树。
- [审查清单](十八期路线审查清单.md)：期数、ID、依赖、覆盖、owner、状态与禁止动作的只读检查。
- [代码落地完整性审查](代码落地完整性与开发就绪审查.md)：判断当前是否达到 DoR、阻断项和最小关闭动作。
- [多窗口并行落地计划](多窗口并行开发计划.md)：波次、文件互斥、共享 owner 和交接规则。
- [多窗口提示词](窗口提示词/README.md)：可直接复制到新窗口的文档与代码任务提示词。
- [完整编码目标控制页](完整编码目标控制.md)：记录当前并行工作流、阻断、波次和最终完成边界。

## 6. 批准建议

建议用户按以下顺序决定：先确认 18 期的边界与顺序；再确认 `技术架构审查与边界深化.md` 中的 15 个架构决定；随后修复两个上游截断并完成阶段 4 原型；最后才把第 01 期转成 Feature `任务清单.md`。本路线本身不能授权开发、测试、构建、真实 Provider、支付、部署或 Git。

---

## R7. Vue 3 多端前端回归路线（R0-R8 / 候选 / 用户未批）

> 创建日期：2026-08-16
> 适用范围：在 18 期路线并行运行；不是 18 期的子集，也不是替代
> 状态：Draft / 用户未批
> 关联决策：`docs/decisions/决策登记表.md` 第 2.3 节 `DEC-070 / DEC-071 / DEC-072 / DEC-073`
> 关联 SPEC：`docs/specs/规格-用户前台-PC与H5.md / 规格-后台管理端-若依Vue3.md / 规格-移动端-uni-app-Vue3.md / 规格-后端统一网关层.md`
> 关联 LICENSE 摘要：`docs/licenses/若依Vue3上游评审报告.md`

本节的存在目的是：把用户 2026-08-16 关于"复用现成后台 / 增加 Vue 3 移动端"的对话固化为**候选阶段**，等用户拍板再纳入 18 期。本阶段不做任何源码动作。

### R7.1 路线总览

| 期 | 范围 | 触动位置 | 关键 Gate |
|---|---|---|---|
| **R0 决策回流** | `决策登记表.md` 增加 `DEC-070~073`；`DEC-022` 改成 "A.1+A.3 并存"；新增 `licenses/若依Vue3上游评审报告.md` | docs/ | 用户对 4 条决策是否 `Accepted` |
| **R1 PRD 收敛 + 4 份 SPEC** | `产品需求文档.md` § 19 v0.3 收敛候选；4 份 SPEC（portal / admin / mobile / bridges） | docs/ | 4 份 SPEC Draft → Approved |
| **R2 后端 bridge module** | 新增 `interview-portal-bridge / interview-admin-bridge / interview-mobile-bridge` 三个 Maven module | backend/ | 单独授权 `mvn compile` 与 `backend/pom.xml` 改动 |
| **R3 admin-web** | `apps/admin-web/` 单独立项 + 借鉴 RuoYi-Vue3 通用页面 + 自研鉴权 | apps/ | 单独授权 `git clone` + `pnpm install` |
| **R4 portal-web** | `apps/portal-web/` Vue 3 + Vite + TS + Pinia 自研 | apps/ | 单独授权 + SPEC-portal-web Approved |
| **R5 mobile** | `apps/mobile/` uni-app Vue 3 + H5 + 微信小程序 | apps/ | 用户级 AppID 单独授权 |
| **R6 启动脚本** | `scripts/` 一键起步 backend + 三端前端 | scripts/ | 单独授权 `mvn spring-boot:run` / `vite dev` |
| **R7 旧栈归档** | 现有 `frontend/` React 19 工程 → `frontend-legacy/` | frontend/ | 用户对 React 替换拍板 |
| **R8 部署/支付/真实链路** | 国内云 + 真实支付 + 真实 LLM/ASR/TTS | ops | 用户级高风险授权 |

### R7.2 与现行 18 期路线的相互关系

- R0 / R1 阶段独立于 18 期路线，可先批准
- R2 阶段为 规格-后端统一网关层.md 的实现合约，可能与 18 期"第 07 期后端核心 / 第 14 期韧性 / 第 17 期运维"重叠；R2 不破坏现有 `interview-application / adapters`
- R3-R7 阶段与 18 期"前端 / 后台 / 移动" 三处独立；R7 结束后 React 19 工程归档
- R8 阶段与 18 期第 17-18 期（运维与上线）严格合并，不分叉

### R7.3 与现有 4 份开发记录的关系

- `docs/development-records/2026-08-02-基础建设-批次01.md`
- `docs/development-records/2026-08-03-核心应用边界收口.md`
- `docs/development-records/2026-08-03-持久化与可靠事件流-批次03.md`
- `docs/development-records/2026-08-03-评测与学习-批次03.md`
- `docs/development-records/2026-08-03-产品文档合并与批次03控制.md`
- `docs/development-records/2026-08-09-服务商适配器批次.md`

R7 不动这些。它们当前描述的是"现有 5 module + React 前端"的实现历史；R7 一旦 Approved，R7 路线才算把它们覆盖。

### R7.4 不可执行动作（R7.0-R7.6 期间）

按 `docs/licenses/若依Vue3上游评审报告.md` 第 5 节 + `产品需求文档.md` § 19.6 + `SPEC-*-web.md` 第 9-11 节同步执行；本节不重复。

### R7.5 进入 R8 的全部前置条件

1. `DEC-070` `DEC-071` `DEC-072` `DEC-073` 至少 3 项 Accepted
2. 4 份 SPEC 状态 = Approved
3. 用户对 `git clone` `pnpm install` `mvn spring-boot:run` `vite dev` `git init` 单独授权
4. 用户对"现有 React 19 工程归档"额外签批
5. 用户对"真实 LLM / ASR / TTS Provider Key 接入"单独授权
