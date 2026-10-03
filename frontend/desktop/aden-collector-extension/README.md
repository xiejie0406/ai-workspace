# Aden 详情页采集扩展（开发通道）

MV3 原生模块，无运行时依赖。用户自行浏览商品，从工具栏打开侧栏，在侧栏逐区提取、展开核对，再统一确认保存。0.3.1 增加“授权京东商品页”：点击后由 Chrome 请求 `https://item.jd.com/*` 可选权限，拒绝时明确提示重新授权；仍保留工具栏 activeTab 临时权限。只解析该域名的数字 SKU 详情路径，不自动导航、搜索、滚动或切规格。

七区为商品信息、规格参数、商品介绍、详情内容、主图、轮播图、详情图，默认全选且至少保留一项。每区独立显示未提取、提取中、已提取、待重提取或未识别/未加载；调整和排除区域只废弃对应区，页面/SKU/规格或权限变化废弃全部预览。参数保留有序原文、重名、单位、未知及排除来源。尚未提取或过期区不能保存；已确认缺失允许形成部分结果。预览不写业务数据库。

参数行排除绑定当前文档、商品/规格指纹及已核对的有序原文来源快照。页面变化清空旧排除；插行、重排或原文变化拒绝应用旧排除，须重新提取并核对。有效参数展示字段及区块均按未排除行的原文顺序以“；”连接，排除行仅保留来源审计。自选范围每区最多 20 处、合计最多 100 处；超限明确拒绝并保留原选择，不截断规则。

开发 ID：`mnejmmlalapfhnanlnckfdhmfpbahidm`；Native Host：`com.aden.collector.dev`。发布前必须单独确定商店 ID、签名与兼容矩阵。0.3.1 依据实际京东商品页的公开 DOM 补充新版标题、所选规格、主图、轮播图及明确“商品详情”区域；条件价格保留原文，页面未加载内容仍记缺失，不从评价、地址或推荐补齐。DOM 只读核查、合成回归和真实侧栏保存验收分别记录，当前真实侧栏复验待重载授权。

## 本地开发与验证

- `npm test`：复用相邻 aden-desktop 已安装 jsdom，测试不连接京东。
- 日常体验加载本机安装版 `resources/collector/extension`，Chrome 中重新加载后核对 manifest 版本；源码目录只用于授权的技术测试。Host 随 Aden 安装，连接当前登录账号的后台业务范围；不要求用户选择工作空间。只同步桌面 out 不会更新扩展或 Host。
- 2026-10-02 本机 Chrome Host 注册已归一到 `Aden Local Test/resources/collector/com.aden.collector.dev.json`；旧注册原值和组件原文已保留。旧、新 Host 独立合成管道均通过，不将旧路径差异误称协议不兼容。安装文件同步成功不等于 Chrome 已重载：侧栏需出现“授权京东商品页”并核对版本 0.3.1；若仍为旧版，在 Chrome 加载上述已安装组件目录。
- 图片域名由 `config.js` 精确名单与 manifest 可选权限共同约束。未知域名记失败，核对来源后通过新扩展版本增加；不允许用户输入任意下载域名。
- `node scripts/prepare-test.mjs <独立输出目录> http://127.0.0.1:19876` 生成仅用于隔离浏览器的合成构建。正式目录没有 localhost 权限。测试 fixture 在 `fixtures/detail.html`，须通过本地服务提供。
- 每次采集采用 UUID 幂等标识，等待内容/每片/最终服务端 ACK。断连后“查询上次保存结果”回读服务端，不把缓存当成功。Worker 恢复将旧运行标志重置为待确认。“重试未保存图片”先核对服务端、工作空间及原 document/SKU，只重新读取失败资源；原文档已经变化时要求重新采集。
- 离线暂存使用扩展自己的 IndexedDB，记录关联 principalId、workspaceId、captureId、documentId。捕获内容和已下载但尚未得到完整上传 ACK 的图片字节最多保留 24 小时，所有记录合计最多 100 MiB；达到上限停止新增，不驱逐未过期记录。浏览器关闭后可在期限内恢复，恢复前必须连接同一账号与工作空间，并由服务端确认捕获；不重新请求源站。已保存图片得到 ACK 后删除本地字节，完整提交后删除暂存记录。
- 过期记录在每次读写、Worker 启动及每小时 alarm 清理；浏览器完全退出时不能执行清理，重新启动后先清理，过期内容不可读取或恢复。明确退出/撤权错误会清理暂存；切换账号或工作空间会清理旧记录。桌面无法主动推送退出事件时，在下一次认证或请求拒绝时清理，未经重新认证不显示持久内容。
- 暂存不包含后端 Token、京东 Cookie 或 URL 签名查询参数。浏览器/系统自身清理站点存储可能提前移除暂存；暂存不是已保存业务数据，UI 不据此报告已入库。资源还未下载完成的字节无法恢复，应回到原页重采。

## 桥协议 v1

请求 `{protocolVersion:1,messageId,type,payload,nonce?,sequence?,expiresAt?}`，响应 `{protocolVersion:1,messageId,ok,data?,error?:{code,message}}`。hello 免证明；返回 `nonce/expiresAt/proofRequired:true`。其余请求使用精确连接 nonce、有效期及规范递增十进制 string 序号；断开或会话变化后重新配对，旧无证明消息明确要求升级。Host 仅校验形态，桌面桥裁决有效期、次序和有界重复 ACK。

| 类型 | payload |
| --- | --- |
| hello | `{extensionId}`，配对由 Aden 确认并绑定登录会话 |
| capture.lookup | `{sku}`，首次直接采集，重复先选择新增快照/查看已有 |
| capture.prepare | captureId、source、fields、blocks、images、documentId、parserVersion、completeness；新版另带 extraction 分区身份/版本、来源、参数原文、范围及确认缺失 |
| asset.chunk | captureId、imageId、generation、offset、totalSize、sha256、mimeType、dataBase64 |
| capture.commit | captureId、generation、failures |
| capture.status | captureId |
| library.open | `{}` 打开商品清单；`{view:'task',captureId,taskId?}` 打开采集任务或其详情 |

传输为 UInt32LE 长度 + UTF-8 JSON，总包不超过 256 KiB，原始资源片不超过 128 KiB。Host 保持当前用户命名管道长连接；Electron 及后端最终裁决权限，扩展与 Host 不保留后端 Token 或京东 Cookie。

图片逐个下载，credentials omit，禁止重定向，30 秒超时，大小/签名/可解码尺寸校验后分片；图片不受支持、未授权、失败和停止均作为缺失清单提交。
