# 解析运行依赖：Bouncy Castle 类链接审查证据

> 类型：局部依赖审查证据；日期：2026-10-03；owner：实施接入审查 Agent。  
> 范围：不可变 Worker 批次 `parser-worker-7829acb91d3b47c99d16957f78afbdf9` 的 240 个依赖，以及该批次报告中的许可声明解析缺口。  
> 本文件保存审查依据，不维护新的实施状态；项目实施与验证事实仍见[完整迁移设计](../完整迁移设计.md)。

审查确认原批次存在具体的 Bouncy Castle 类链接缺口：`bcpkix-jdk18on:1.85` 的确定长度 CMS 认证数据分支需要原 `bcprov-jdk18on:1.81` 中不存在的类型。普通文档格式解析成功不能覆盖这个分支。结论来自发布 POM、JAR 字节码、完整运行库类名集合与官方源码，未根据版本号差异直接判失败。

## 审查对象与许可解析说明

原始对象及完整依赖清单见[原批次依赖 JSON](2026-10-03-解析运行依赖-parser-worker-7829acb91d3b47c99d16957f78afbdf9.json)。对应本地只读目录为 `ruoyi-backend/ruoyi-paismart/target/parser-worker-7829acb91d3b47c99d16957f78afbdf9/lib`，发布 POM 来自本地 Maven 缓存 `C:/Users/admin/.m2/repository`。

原扫描的九个 `missingLicenseDeclarations` 分两种情况：

| 对象 | 发布 POM 和 JAR 中的事实 | 解释 |
| --- | --- | --- |
| `xmlbeans:5.3.0` | 无 XML 命名空间的 `<project>`，实际存在 `<licenses>`；JAR 有 `META-INF/LICENSE.txt`、`META-INF/NOTICE.txt` | Apache 2.0 声明存在，原工具仅按 Maven 命名空间读取造成漏报 |
| `bcprov-jdk18on:1.81` | 无命名空间 POM，存在 Bouncy Castle Licence 声明；JAR 的许可为 `org/bouncycastle/LICENSE.class` 常量文本 | 原文件名扫描未读取这个载体，不表示发布物无许可 |
| `bcjmail`、`bcpkix`、`bcutil` 的 `jdk18on:1.85` | 无命名空间 POM，均有 Bouncy Castle Licence 声明；JAR 均有 `META-INF/LICENSE.md` | 原 POM 解析漏报；许可正文可直接读取 |
| `jdom2:2.0.6.1` | 无命名空间 POM，实际存在许可声明；JAR 有 `META-INF/LICENSE.txt` | 保留其自有许可原文和 POM 描述，不把它改标为 MIT 或 Apache 2.0 |
| `ruoyi-framework`、`ruoyi-system`、`ruoyi-common:3.9.2` | 模块 POM 无独立 `<licenses>`，对应 JAR 无独立许可文件 | 使用所属若依工程的 MIT 源码证据，不能声称模块发布 POM 已声明 |

若依工程[现有 LICENSE](../../../../ruoyi-backend/LICENSE)与[保留上游 LICENSE](../../../../ruoyi-backend/_reference/ruoyi-original/LICENSE)均为 MIT、`Copyright (c) 2018 RuoYi`，两份文件 SHA256 相同：`46973d260eabeaf43df2478bf00dacf862911988eba72387596fcafbc4888cab`。扫描结果修正不代替发布时保留各依赖实际许可原文的要求。

## 官方要求与发布依赖

官方 `r1rv85` 标签的 [README 原文](https://github.com/bcgit/bc-java/blob/r1rv85/README.md)第 28 行说明 companion artifacts 使用相同版本，第 31 行说明 provider 是其他模块的共同依赖。该文件示例仍写 `1.84`，不能把示例版本当作 `1.85` 的精确依赖；本次精确版本以发布 POM 为依据。对应[只读原文地址](https://raw.githubusercontent.com/bcgit/bc-java/r1rv85/README.md)固定到该标签。

本地发布 POM 的实际链为：

```text
bcjmail-jdk18on:1.85 -> bcpkix-jdk18on:1.85
bcpkix-jdk18on:1.85 -> bcutil-jdk18on:1.85
bcutil-jdk18on:1.85 -> bcprov-jdk18on:1.85
```

`bcutil-jdk18on-1.85.pom` 第 35、36 行分别为 `bcprov-jdk18on` 和版本 `1.85`。其余 POM 亦使用普通固定版本依赖，没有允许 `1.81` 的区间。

| 原批次 JAR | JAR SHA256 | POM SHA256 |
| --- | --- | --- |
| `bcprov-jdk18on-1.81.jar` | `249f396412b0c0ce67f25c8197da757b241b8be3ec4199386c00704a2457459b` | `203990122be0fdd34bf12d0b788c0655161386a8e858ba43f06e3709c2cc74cc` |
| `bcjmail-jdk18on-1.85.jar` | `05e7f20cfeea3b8b896109c37a620784a3e73dc17c12c5b0fa2d570acee5811e` | `c1f464537055a476c06fa8aeb35ca4f48125812f6cc92e331a93c2c37e5d62af` |
| `bcpkix-jdk18on-1.85.jar` | `c9f82b2d4e99c4bbdfccf684e52cc06ea06a0b567bfd0d08f9c5a3f417055996` | `9b052b0cb85cb7d8bb0956afcc74f31c814f1c67e03498e1ca74f8fe8611d957` |
| `bcutil-jdk18on-1.85.jar` | `590f55ed5d68529239898a4a5c4f730b6e37f45d1cfa3fbe51f8485abe32c42d` | `9f1c79a12a7c03d78860e05750dcc44e855e20205c7af7cffbf29a69448d85ea` |

三个 `1.85` companion JAR 的 Manifest 均声明 `Bundle-Version: 1.85`，provider 包导入包含 `org.bouncycastle.asn1;version="[1.85,1.86)"`；provider JAR 的 `Bundle-Version` 与导出包版本均为 `1.81`。这是发布兼容范围证据。普通 Java classpath 不执行 OSGi 范围检查，不能仅凭 Manifest 声称应用启动失败；下面的缺类是另一个独立证据。

## 实际缺类与触发路径

逐一读取原批次 240 个 JAR 的类名，再比对三个 `1.85` companion JAR 的类文件常量池引用，发现以下 16 个引用类型在整个批次中都不存在：

```text
org/bouncycastle/asn1/DLGenerator
org/bouncycastle/asn1/DLOctetStringGenerator
org/bouncycastle/asn1/DLSequenceGenerator
org/bouncycastle/asn1/iana/IANAObjectIdentifiers
org/bouncycastle/asn1/pkcs/SecretBag
org/bouncycastle/asn1/plants/MTCObjectIdentifiers
org/bouncycastle/asn1/x509/CertDiscoveryMethod
org/bouncycastle/asn1/x509/MTCCertificationAuthority
org/bouncycastle/asn1/x509/RelatedCertificate
org/bouncycastle/asn1/x509/RelatedCertificateDescriptor
org/bouncycastle/asn1/x509/TBSCertificateLogEntry
org/bouncycastle/asn1/x509/qualified/QcType
org/bouncycastle/crypto/params/MLDSAKeyParameters
org/bouncycastle/crypto/params/MLDSAParameters
org/bouncycastle/jcajce/provider/util/SecurityExceptions
org/bouncycastle/util/AggregateRuntimeException
```

其中前四个用于常见 CMS 分支。对原 `bcpkix:1.85` 执行只读 `javap -c -p org.bouncycastle.cms.CMSUtils`，`getMacOutputLength` 的实际字节码包含：

```text
15:  getstatic org/bouncycastle/asn1/iana/IANAObjectIdentifiers.hmacSHA1
120: getstatic org/bouncycastle/asn1/iana/IANAObjectIdentifiers.hmacMD5
```

官方 [CMSUtils.java](https://github.com/bcgit/bc-java/blob/r1rv85/pkix/src/main/java/org/bouncycastle/cms/CMSUtils.java)第 406–422 行表明，即使选择 HMAC-SHA256，方法也先执行 SHA1 的 OID 比较并访问 `IANAObjectIdentifiers`，之后才到 SHA256 分支。对原 provider JAR 执行 `javap org.bouncycastle.asn1.iana.IANAObjectIdentifiers` 返回找不到类。原批次中另外 239 个 JAR 也没有该类，所以它不能由其他运行库补齐。

官方 [CMSAuthenticatedDataStreamGenerator.java](https://github.com/bcgit/bc-java/blob/r1rv85/pkix/src/main/java/org/bouncycastle/cms/CMSAuthenticatedDataStreamGenerator.java)的 `openDL` 第 297 行调用上述 MAC 长度计算，第 365–404 行使用三个缺失的 DL 类型。使用 `setEncoding(ASN1Encoding.DL)`、明确正文长度与 HMAC-SHA256 即可触发。这证明存在实际二进制兼容缺陷，并不表示全部解析格式或全部加密算法均失败。

## 验证入口与限制

新增 [RuntimeDependencyLinkageTest](../../../../ruoyi-backend/ruoyi-paismart/src/test/java/com/ruoyi/paismart/infrastructure/RuntimeDependencyLinkageTest.java)只有一个离线用例：通过公开 BC API 生成确定长度 CMS/HMAC-SHA256 数据，包装 MAC 密钥后解析接收者正文，核对保存 MAC 与接收者计算结果，并用 JDK HMAC-SHA256 独立计算预期值。它覆盖上述缺失类型的实际操作路径，不通过反射重演内部长度判断。

本审查执行了本地文件读取、哈希、类文件静态检查、`javap` 和官方源码核对；没有运行 Maven、真实加密业务、服务或外部账号操作。测试源码已交给根任务统一运行；本说明不预记新用例通过，也不沿用旧依赖图的测试结果来证明对齐后的运行图。对齐 provider 后的模块测试、构建、不可变依赖批次与 CLI 复验事实由完整迁移设计承接。

## Admin 装配中的 COS 旧 provider 核对

Admin 构建后的进一步静态检查发现 `bcprov-jdk15on:1.67` 与 `bcprov-jdk18on:1.85` 同时进入 fat JAR。来源树见[Admin 加密库来源树](2026-10-03-完整核对-Admin加密库来源树.log)：旧库由 `ruoyi-aippt -> com.qcloud:cos_api:5.6.227` 引入，新库由 `ruoyi-paismart` 引入。不同 artifactId 不参与同一坐标的 Maven 版本冲突消解。独立 Worker 批次没有该 `jdk15on` 依赖，此缺口属于 Admin 最终装配。

只读枚举两个 provider JAR 中 `org/bouncycastle/` 基础路径下的类文件，不把 `META-INF/versions/` 多版本副本重复计数，结果如下：

| 比较对象 | 类文件数 |
| --- | ---: |
| 旧 `jdk15on:1.67` | 3,603 |
| 新 `jdk18on:1.85` | 5,789 |
| 两者同名类交集 | 3,071 |
| 仅旧库存在 | 532 |
| 仅新库存在 | 2,718 |

交集包含 `org/bouncycastle/jce/provider/BouncyCastleProvider.class`；大量重复来自 JCE symmetric/digest/asymmetric、ASN.1、crypto/params、crypto/engines、math/ec 等包。因此不能将两库看作相互隔离的实现。具体运行后果依赖类加载顺序，本审查没有把重复类统计等同于所有 SDK 调用必然失败。

对 `cos_api-5.6.227.jar` 所有类文件的 UTF8 常量池进行扫描，唯一含 `bouncycastle` 的类为 `com/qcloud/cos/internal/crypto/CryptoRuntime.class`，其唯一引用内容是字符串 `org.bouncycastle.jce.provider.BouncyCastleProvider`。没有发现 SDK 对其他 BC 类型、方法或字段的编译链接。实际 `javap` 显示它通过 `Class.forName`、无参实例化得到 `java.security.Provider` 并向 JCE 注册。这个入口在新 provider 中仍然存在；BC 的其余操作均通过 JCE API 及算法名称调用。

对应[官方 v5.6.227 CryptoRuntime 源码](https://github.com/tencentyun/cos-java-sdk-v5/blob/v5.6.227/src/main/java/com/qcloud/cos/internal/crypto/CryptoRuntime.java)第 30–43 行与本地字节码一致。其能力探测是 AES-GCM 和 RSA-OAEP。[该版 AesGcm 源码](https://github.com/tencentyun/cos-java-sdk-v5/blob/v5.6.227/src/main/java/com/qcloud/cos/internal/crypto/AesGcm.java)第 30–46 行确定使用 `AES/GCM/NoPadding`、256 位 key、12 字节 IV、128 位 tag，并指定 `BC`。实际[ContentCryptoScheme](https://github.com/tencentyun/cos-java-sdk-v5/blob/v5.6.227/src/main/java/com/qcloud/cos/internal/crypto/ContentCryptoScheme.java)第 173–186 行通过 JCE 初始化 cipher；[CipherLiteInputStream](https://github.com/tencentyun/cos-java-sdk-v5/blob/v5.6.227/src/main/java/com/qcloud/cos/internal/crypto/CipherLiteInputStream.java)第 197–227 行在完整读取时执行 `doFinal`，GCM 标签失败转换为 `SecurityException`。

这些静态事实支持仅在 Admin 的 `ruoyi-aippt` dependency 排除旧 `bcprov-jdk15on`，由已经对齐的 `jdk18on:1.85` 统一提供 provider。此处理不需要修改 aippt 源码、COS SDK 或调用真实云服务。静态兼容依据不能替代 SDK 流的实际运行，因此补充[CosRuntimeCompatibilityTest](../../../../ruoyi-backend/ruoyi-admin/src/test/java/com/qcloud/cos/internal/crypto/CosRuntimeCompatibilityTest.java)的一个离线用例：

1. 使用 SDK 自身装载 BC，精确核对版本 `1.85`，重新探测 AES-GCM 与 RSA-OAEP；已有不同版本明确失败，已有 provider 不被替换或移除。
2. 使用固定公开 key/IV 和大于 1,024 字节的样本文字，经 SDK 自身 cipher 与 512 字节缓冲流完成加密、解密，核对完整正文及 16 字节标签长度。
3. 修改标签末字节，重新读取完整 SDK 解密流，必须拒绝并抛出 `SecurityException`。
4. 使用 SDK 自身 `COSKeyWrapScheme` 选择的 RSA-OAEP 算法，对临时 RSA-2048 密钥进行内存 AES key 包装和解包，核对原始 key 字节。

本附录记录源码和静态审查的实际完成范围。Admin 用例由根任务定向执行，运行结果、最终 fat JAR 去重与交付产物仍记录到完整迁移设计，不在此另维护状态。
