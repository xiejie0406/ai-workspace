# 自动布局容器标识边界收口

日期：2026-10-04。归一化新增溢出列原用 `parentId + '__overflow'`，合法100字符父ID会生成110字符ID，且可能与已有容器/叶子ID重名。原输入ID边界与重排算法保持不变，仅受控生成路径局部修复。

`FlexEditing.normalize` 先采集原整树ID。生成溢出容器时按100减后缀长度截取父ID；常规短ID仍取原 `parentId__overflow`；若已存在同名，增加编号并按对应后缀长度限制总长，在同一集合中预留生成ID。因此新ID最多100字符且不与原树或本次其他自动容器冲突。修复前当前源码保存在[原文](生成布局ID边界修复前原文/FlexEditing.java)。

第一次完整私有Maven编译见[日志](生成布局ID边界编译.log)，退出1：当前并发新增 `AipptTextRouteService` 依赖的 `com.ruoyi.system.secret.ManagedSecretService` 不在离线安装的system依赖jar中；6条缺类错误不在FlexEditing。该失败保留，不能写成完整编译通过。主对话后续将用当前common/system/framework源码的私有reactor统筹完整编译。

定向纯编译已执行：`javac --release 17 -encoding UTF-8 -classpath <现有fastjson2-2.0.64.jar;原模块target/classes> -d .runtime/reference-migration-audit-20261004/演示生成布局定向编译 <FlexEditing.java>`，退出0，[日志](生成布局ID边界定向编译.log)无诊断。输出仅在私有目录，没有替换原模块产物。此结果只证明修复文件可按Java17编译，不代表业务测试或整模块联机通过。`git diff --check` 对该文件退出0（仅换行格式提示）。

最终FlexEditing源码SHA256：`6aa0cb477af0cc42f20ff367a6c043ca47bfe450c88bd9a67d6b2fb11ae6e34c`。未运行测试、API、服务、Provider或数据库；未回退其他并发修改。
