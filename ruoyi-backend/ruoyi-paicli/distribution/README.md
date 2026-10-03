# PaiCLI 原生分发与学习资料

Windows 使用 `windows/paicli.ps1`、`windows/runner.ps1`、`windows/eval.ps1`；Linux/macOS 使用各自目录内的同名包装，例如 `sh distribution/linux/paicli --help`。包装从 `JAVA_HOME` 或 PATH 查找 Java，不复制个人机器路径或凭证；目标字节码为 Java 17。Runner通过终端fatjar的真实 `runner` 子命令启动，独立Runner jar是协议库，不伪装为可直接启动的fatjar。Eval使用独立fatjar，薄 `agentbench-runner` classifier只用于受信中继classpath。

版本、五份jar与包装摘要在构建后的 `package-manifest.json` 中记录。本机Windows帮助入口可技术检查；Linux/macOS包装与真实沙箱仍需对应系统运行验证，不能用Windows帮助通过代替。更新先备份明确的旧分发jar，再替换并核对SHA；回退恢复这些备份，中心未确认回执与未知副作用继续人工对账，不重跑。

[学习资料](learning/README.md)保留原文，原Provider、明文配置、数据库和本地执行教程描述是来源项目历史事实。产品入口与受控配置以[联机工程说明](../README.md)为准。学习目录内的 `docs/`、`landing/`、`demo*`、`ppt/`、`poster/`、`img/` 和 `output/` 为本机参考资产，不执行原demo或同步脚本。

[来源摘要](source-assets-manifest.json)逐文件列原路径、目标路径、大小与SHA-256，复制前后检查原文未变。[原工具脚本](../reference-tools/README.md)仅作只读参考。原根没有许可证文件，本轮在已批准迁移范围内保存本机资产；外部发布须另核原代码及教程授权。
