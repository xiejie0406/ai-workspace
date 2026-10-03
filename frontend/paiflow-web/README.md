# PaiFlow 原 React 前端

本工程于2026-10-03按用户要求直接引入原PaiFlow前端，保留ReactFlow、Ant Design、Recoil、Zustand、React Router及原页面、组件和样式。

引入时1703个原文件与参考项目逐文件哈希一致，随后仅调整中心地址、WebSocket、登录/验证码/会话退出及必要条件版本协议适配；当前差异另有逐文件记录。实际引入清单、方案和检查事实位于 `文档/项目/PaiFlow工作流项目/`。环境文件、旧部署脚本、node_modules及构建缓存未复制，避免默认连接旧服务或带入真实配置。服务/登录/流式请求尚在接入中，此时不能作为已完成迁移的运行版本。

原脚本包含 `npm run type-check`、`npm run lint`、`npm run build`；`npm run test`是Vite启动，不是单元测试。运行前须按已核实的中心环境配置BASE_URL及相关直接请求，不得用只修改BASE_URL宣称全部联机。

`window.__APP_CONFIG__.BASE_URL`优先于构建环境，默认`/paiflow/legacy`；`WS_BASE_URL`可覆盖WebSocket网关。验证码和退出沿用平台`/captchaImage`、`/logout`，`PLATFORM_BASE_URL`默认由兼容层路径去掉`/paiflow/legacy`后取得。登录仍使用原`/api/login`及`accessToken`字段，服务器调用若依`SysLoginService`；验证码要求由平台决定，不使用旧Casdoor刷新令牌。旧SSO回调引导到平台登录，SSO协议仍待确认。

开发代理只在明确配置`VITE_PAIFLOW_CENTRE_URL`时启用，禁止默认为原8080/7880通道；本轮没有启动服务或写入环境配置。中心源码已实现`/user-info/me`及明确旧UID映射、受限资料和真实协议记录，仍未连接真实业务库或完成真实登录/页面验收。

`utils/workflow-revision.ts`在现有HTTP hook中按会话/空间缓存已读条件，总上限128条，智能体、工作流与个人模型ID分别定位。原详情、自身创建/成功写入可记录版本，智能体/工作流列表和复制空回执不能自动提升旧窗口条件。跟踪响应迟到、身份或条件不一致、会话/空间已切换时，HTTP层拒绝交付，避免旧详情进入原画布后配上较新的条件自动保存。智能体写入/删除透传`If-Match`；绑定工作流同时透传已读取的`X-Workflow-Revision`，不改原JSON/form body，不新开请求去绕过409，也不让画布版本替旧智能体窗口自动解锁。仅识别原始精确API路径，dot段折叠、外部URL或另一baseURL不能获得条件头。跨域部署须由既有配置显式允许条件请求头并暴露`ETag`/`X-Workflow-Revision`响应头；没有修改共享CORS或部署配置。

定向测试：`node --test tests/runtime-websocket.test.cjs tests/platform-auth.test.cjs tests/workflow-revision.test.cjs`。类型基线比较：`node tests/compare-reference-types.cjs`，直接使用同一依赖、配置和参考源文件；无新增诊断不代表类型检查通过。

个人模型仍使用原 `services/model.ts` 和页面。详情读取匹配命名 ETag `"paiflow-model-<id>-<revision>"` 后，原更新请求仅附加真实读过的 If-Match；列表行可给启停/删除按钮提供条件，却不能提升旧编辑表单的 detailRevision。原启停保留 updateById 的更新成功布尔回执（on/off 均在实际中心更新成功后为true）；实际 enabled 状态保留在中心标准详情，验证与本地部署分别保留原字符串/布尔；不以假公钥、空目录或未验证模型宣称可用。迟到读、非法版本头、跨作用域和冲突回执拒绝交付。

沿用原store的默认个人区空ID：缺省、null或空串只用于默认区，具名区响应必须明确返回当前正ID。数组、对象、布尔、非规范ID及具名区缺失ID均拒绝交付，没有改原页面或加入新的初始化流程。2026-10-03该兼容版本67项定向检查和独立78项作用域检查通过，冻结12份输入的最终生产构建通过（2分50秒），构建期间输入哈希未变。原类型基线3712错误、当前3710错误、无新增诊断，完整类型检查仍失败；此前63项和66项构建属于保留的阶段证据，不作为当前源码检查。当前检查入口为 `文档/项目/PaiFlow工作流项目/证据/本地源码检查/默认个人原前端接入检查.json`。真实平台登录、模型授权及供应商请求和页面验收尚未执行。
