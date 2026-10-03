# AI Interview Coach 完整产品 Feature 控制页

> 2026-09-09 后续语音入口：[FEAT-INTERVIEW-002 商业语音首版邀测](../FEAT-INTERVIEW-002-商业邀测/README.md)。该独立资料包维护新增语音范围、0.2.0 契约与 S1 候选任务；不重启本页历史任务、不改变本页主阶段，也不扩大当前文本训练 PRD 的 P0。

> 文档类型：Feature 控制页  
> 文档状态：Draft  
> owner / 责任边界：产品 owner 维护产品规格；架构/任务/质量 owner 维护对应下游；用户形成批准和验收结论  
> 创建时间：2026-08-02  
> 更新时间：2026-08-29  
> Feature ID：FEAT-INTERVIEW-001  
> 风险等级：L3  
> 当前阶段：3 功能规格  
> 阶段状态：WaitingForApproval  
> 目标版本：RuoYi V2（题库 → 文本面试 → Web 语音）

> **V2 收敛说明**：本控制页原先描述的 18 期完整产品路线已不再是本轮实施范围。当前题库任务入口是[`FEAT-QBANK-001-公共题库`](../FEAT-QBANK-001-公共题库)，若依身份与后端边界见[`若依身份与后端单体收敛设计`](../../架构/面试项目完整设计.md#data)；旧 V2 收敛计划已进入[`历史归档`](../../架构/面试项目完整设计.md#catalog)。支付、学习计划、报告扩展、移动 App 和多 Provider 均延期，不得以本页旧任务状态宣称已实现。

## 1. 目标与非目标

- 目标：在若依统一 JWT/RBAC 下完成面试题库、最小文本面试会话和 Web 语音面试闭环；题库与语音是本轮 P0。
- 非目标：Realtime/WebRTC、B2B、SSO/SCIM、Kubernetes、微服务、Kafka、独立向量库、视频数字人、声音克隆、真实面试隐蔽代答。

## 2. 产物与版本

| 产物 | 唯一路径 | 文档状态 | 阶段/状态 | 备注 |
|---|---|---|---|---|
| 调研 | [`面试教练与题库调研.md`](../../产品/面试教练与题库调研.md) | Draft | 2 / Completed | 仍有用户证据缺口 |
| Canonical Spec | [`产品需求文档.md`](../../产品/产品需求文档.md) | Draft | 3 / WaitingForApproval | `FUNC-VOICE-002` 后截断 |
| 历史产品恢复候选 | [`产品全量审计与权威规格恢复候选.md`](../../归档/评审/产品全量审计与权威规格恢复候选.md) | Archived / 原状态 Draft | 历史阶段 3 | 仅供追溯，不再是当前产品入口 |
| 原型实现 | [`../../../prototype/html-v1/README.md`](../../../../../prototype/html-v1/README.md) | Draft | 4 / NotStarted | 前瞻 Mock 原型，非生产实现 |
| 历史原型资料包 | [`FEAT-INTERVIEW-001-面试教练原型/`](../../归档/原型/FEAT-INTERVIEW-001-面试教练原型) | Archived / 原状态 Draft | 历史阶段 4 | 旧全量产品 Mock 审计，仅供追溯 |
| 历史架构深化 | [`技术架构审查与边界深化.md`](../../归档/阶段/技术架构审查与边界深化.md) | Archived / 原状态 Draft | 历史阶段 5 | 旧 Phase 输入，不作为当前任务门禁 |
| 历史任务与执行包 | [`旧版任务清单.md`](../../归档/功能/FEAT-INTERVIEW-001-AI面试教练/旧版任务清单.md) | Archived / Superseded | 历史阶段 6 | 旧 18 期候选拆分，不再是执行任务源 |
| 验证 | `验证记录.md` | 未创建 | 8 / NotStarted | 实施并获验证授权后创建 |
| 验收 | `验收记录.md` | 未创建 | 9 / NotStarted | 用户形成结论 |
| 上线就绪 | `上线就绪.md` | 未创建 | 10 / NotStarted | NotAssessed / NotReleased |

## 3. 追溯入口

- FIND/REQ/BR/AC：Canonical PRD；截断恢复候选仅供评审。
- DES：当前唯一设计见[面试项目完整设计](../../架构/面试项目完整设计.md)；旧设计来源在恢复备份及统一记录中追溯。
- TASK：旧全产品任务拆分已归档；当前任务以仍在维护的各 Feature `任务清单.md` 为准。
- EV/UAT：尚未形成；原型 Mock 检查不能替代生产 EV/UAT。

## 4. 当前控制信息

- 阻断：V2 Feature Spec/技术设计仍为 Draft；构建、测试、启动、Provider、浏览器 UAT 和删除尚未获得独立授权。
- 当前已获授权：按 V2 执行包进行本地源码和文档修改；用户目标明确要求开始实现。
- 仍需独立决定：测试代码、构建/测试/启动、真实 Provider/语音/支付、部署、Git。
- 下一门：题库与语音代码只读审查完成，随后分别请求验证授权；验收完成后再请求旧项目精确删除清单确认。
- 退回条件：产品范围/权限/数据/AC 变化回阶段 3；公共契约不成立回阶段 5。

### 4.1 工作流状态

| 工作流 | owner | 当前阶段 | 状态 | 上游 | 下一门 |
|---|---|---|---|---|---|
| PRODUCT-RECOVERY | 主会话 | 3/5 | Superseded | 旧 PRD/18 期路线 | 以 RuoYi V2 收敛方案为准 |
| PROTOTYPE-PACK | 主会话 | 4 | Skipped | 本轮 Web 优先，复用现有页面 | 关键交互由代码与 UAT 验证 |
| ARCH-CONTRACTS | 主会话 | 5 | InProgress | RuoYi V2 收敛方案 | 题库/语音契约审查 |
| MASTER-TASKS | 主会话 | 6 | InProgress | V2 Feature 任务包 | 验证执行包授权 |
| IMPLEMENTATION | 主会话 + 子 Agent | 7 | InProgress | 题库/语音分片 | 阻断问题关闭后进入阶段 8 |

### 4.2 独立授权矩阵

| 动作 | 范围 | 授权状态 | 依据 | 限制 |
|---|---|---|---|---|
| 文档修改 | 本项目规划/Feature 资料 | Approved | 用户要求完善并全程推进 | 不伪造批准/证据 |
| 生产代码 | 本项目内按现行 Feature 任务清单和互斥窗口推进的本地源码/配置 | Approved | 用户明确要求多个子 Agent 持续推进，最终目标为编码全部完成 | 不把候选行为发布为承诺；公共契约变化须单一 owner；外部动作不包含 |
| 测试代码 | 待 tasks 列明 | NotRequested | 无 | 不创建 |
| 构建/测试/启动 | 待精确命令 | NotRequested | 无 | 不执行 |
| 真实外部系统/费用 | Provider/语音/支付/云 | NotRequested | 无 | 不调用 |
| Git 各动作 | add/commit/push/PR/tag | NotRequested | 无 | 不执行 |

## 5. 上线与发布事实

- 上线就绪结论：NotAssessed
- 发布事实：NotReleased

## 6. 变更记录

| 日期 | 变化 | 原因 | 状态影响 |
|---|---|---|---|
| 2026-08-02 | 建立完整产品控制页与候选主任务源 | 用户要求启用多个子 Agent 并完成全部编码 | 主阶段仍为 3；未进入实现 |
| 2026-08-02 | 启动 Foundation 局部实现工作流 | 用户继续要求以全部编码完成为目标 | 主阶段仍为 3；源码实现与运行证据分离，详见开发记录 |
