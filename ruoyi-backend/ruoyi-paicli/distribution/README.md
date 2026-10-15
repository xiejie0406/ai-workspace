# PaiCLI 原生分发与学习资料

Windows 使用 `windows/paicli.ps1`、`windows/runner.ps1`、`windows/eval.ps1`；Linux/macOS 使用各自目录内的同名包装，例如 `sh distribution/linux/paicli --help`。包装从 `JAVA_HOME` 或 PATH 查找 Java，不复制个人机器路径或凭证；目标字节码为 Java 17。Runner通过终端fatjar的真实 `runner` 子命令启动，独立Runner jar是协议库，不伪装为可直接启动的fatjar。Eval使用独立fatjar，薄 `agentbench-runner` classifier只用于受信中继classpath。

Windows 本机网页工作台使用 PowerShell 7.5 以上的 [统一启动入口](windows/start-local.ps1)。传入已登记的工作区和运行目录后，它核对原共享 MySQL / Redis、固定中心和 CLI 包，按实际进程身份复用中心、本人 Runner、网页，等待真实终端连接后报告就绪。普通启动不再输入登录信息或绑定码；首次安装通过 `-InitializeRunner` 和安全输入提供当前已认证账号，绑定一次本人设备。默认数据根为 `%USERPROFILE%\PaiCLI\data`，本人文件、会话目录和派生状态由现有 Runner 规则维护。若第一次准备中断并且短期码已过期，显式本人初始化刷新原待绑定设备的码；日常启动不会创建新设备或绕过撤销。

[Runner 管理入口](windows/local-runner.ps1)支持 `Start / Stop / Status`，参数 `-RuntimeDirectory` 指向同一登记目录。停止通过私有请求等待正常关闭；自动恢复仅对暂时断连有限重试，已撤销、过期或归属改变保留原记录并要求重新确认。网页刷新可以重连存活的终端；Runner 退出后的旧终端不会恢复成原进程，需要显式打开新终端。

版本、五份jar与包装摘要在构建后的 `package-manifest.json` 中记录。本机Windows帮助入口可技术检查；Linux/macOS包装与真实沙箱仍需对应系统运行验证，不能用Windows帮助通过代替。更新先备份明确的旧分发jar，再替换并核对SHA；回退恢复这些备份，中心未确认回执与未知副作用继续人工对账，不重跑。

[学习资料](learning/README.md)保留原文，原Provider、明文配置、数据库和本地执行教程描述是来源项目历史事实。产品入口与受控配置以[联机工程说明](../README.md)为准。学习目录内的 `docs/`、`landing/`、`demo*`、`ppt/`、`poster/`、`img/` 和 `output/` 为本机参考资产，不执行原demo或同步脚本。

[来源摘要](source-assets-manifest.json)逐文件列原路径、目标路径、大小与SHA-256，复制前后检查原文未变。[原工具脚本](../reference-tools/README.md)仅作只读参考。原根没有许可证文件，本轮在已批准迁移范围内保存本机资产；外部发布须另核原代码及教程授权。
