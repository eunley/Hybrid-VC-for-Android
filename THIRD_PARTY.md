# 第三方许可与来源核实 / Third-party evidence inventory

核实日期：2026-11-11。范围：当前开源准备目录，以及 svc-local-http（libs/native/hsrc/res 含指向 svcapp 的符号链接）。项目 LICENSE 的 MIT 只适用于有权授权的原创代码，不能覆盖下述第三方代码、图标、权重或二进制。

证据等级：**实物确认**表示已读本地文件/元数据或做内容比对；**来源声明**表示本地归属记录尚未与上游该提交逐文件复核；**未闭环**表示不能据此认定再分发许可。此前许可核实时 GitHub raw/API 的 curl 请求超时或 TLS 失败，未取得可用上游响应；以下不冒称在线核实。具体本地绝对路径、SHA-256 和请求结果见同次 license-audit.md 与审计 evidence。

## 代码、算法和图形

| 组件 | 确认的许可与实际文件证据 | 来源版本/提交与限制 |
| --- | --- | --- |
| ultimate-rvc-mobile | **实物确认**：本地 rvc/urm/LICENSE 是 MIT，Copyright (c) 2025 Ultimate RVC Mobile Team；文件还包含 Additional Terms，说明依赖保留各自许可、声音使用需权利人许可、教育研究用途及守法责任。不能只摘取 MIT 标题忽略后文。 | 本地目录无 .git，无法确认提交。历史 dl.sh 指向 Sharl210/ultimate-rvc-mobile 的 v2.0.0 APK；README 另列 JackismyShephard、hamzahtime1-pixel 历史托管。MIT 文本可确认于此本地副本，无法认证其与当前 Sharl210 远端版本一致。APK 内模型不是凭仓库 MIT 就自动获授权。 |
| SVC 推理/导出算法来源 | **实物确认**：rvc/export_onnx.py、export_role.py 从 so_vits_svc_fork.modules.synthesizers 导入 SynthesizerTrn；svcpkg 的 synthesizers.py 引用 NSFHifiGANGenerator。rvc/svsf/LICENSE 前段 MIT（2023 34j and contributors；2021 Jingyi Li），随后还附 Apache-2.0 全文，不能称为“全部 MIT”。另一个已安装 so-vits-svc-fork 4.2.30 的 METADATA 声明 MIT，licenses/LICENSE 可读。 | 上游项目 https://github.com/voicepaw/so-vits-svc-fork 。4.2.30 是另一本地安装包版本，不能代替历史 svcpkg 导出所用确切版本/提交。Engine、Feats、RoleImporter、OnnxTpl 的模型接口、权重映射与上述 SVC 链有关，不能仅因 Java 重写或 ultimate-rvc 名称便宣称 clean-room/MIT 已清。原始 so-vits-svc、VITS、NSF-HiFiGAN 等逐文件继承、修改界线和提交仍需核实；未下载成功的 so-vits-svc LICENSE 不作结论。 |
| RNNoise 源码 | **实物确认**：native/rnnoise/COPYING 是 BSD-3-Clause 条款；include/src 文件保留 Mozilla、Xiph、Gregor Richards、Octasic、Mark Borgerding 等逐文件声明。kiss_fft 文件的 BSD 条款与各文件版权需一并保留，不能只留一个总标题。assets/licenses/rnnoise/LICENSE 也是保留证据。 | 本地 Git HEAD `70f1d256acd4b34a572f999a05c87bf00b67730d`；开源目录中存在的 36 个受该 Git 跟踪文件与 HEAD 内容完全一致，零差异。生成模型文件另列。权威上游 https://gitlab.xiph.org/xiph/rnnoise ，GitHub xiph/rnnoise 是 convenience copy（本地 README 明示）。 |
| AndroidLiquidGlass Java 改编 | **实物确认**：assets/licenses/AndroidLiquidGlass-LICENSE.txt 含 Apache-2.0 全文；GlassNav.java、GlassShader.java 首部有 Copyright 2025 Kyant、Apache-2.0 及修改说明。 | SOURCE.txt 声明 Kyant0/AndroidLiquidGlass，提交 `65ab177e90e5c1d8c62e70cf7755841982da65f6`，来源 Shaders.kt、LiquidBottomTabs.kt、DampedDragAnimation.kt（路径见 SOURCE.txt）。这是**来源声明**；旧 HANDOFF 说上游无 NOTICE，本次无法拉取 tree，不能独立确认不存在 NOTICE。保留许可证、归属和修改说明，补查该提交 NOTICE。 |
| LSPosed 矢量图标 | **实物确认**：res/drawable/lsp_*.xml 含 LSPosed GPL version 3 or later 原始文件头及 2022 LSPosed Contributors 版权；assets/licenses/LSPosed/LICENSE 保留 GPL 全文。 | SOURCE.txt 声明 LSPosed/LSPosed 提交 `df74d83eb03a44cc6ad268841ac2ada28d077c77`，修改为移除 theme tint。本次未取到该提交源文件作 diff。文件级 GPL-3.0-or-later 证据明确；不能以图标是素材而忽略 GPL，也不能仅凭这些文件断言整个应用必然 GPL。公开发布需解决组合/派生范围和相应源代码义务；本次未替换图标。 |

## 权重、训练数据与生成文件

- **RNNoise 默认数据已纳入源码**：native/rnnoise/src/rnnoise_data.c、rnnoise_data.h 为未跟踪生成文件，不能用“模型不在仓库”概括。model_version 是归档 SHA-256 `0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37`，不是 Git 提交。本地对应 tar.gz 的 SHA-256 精确吻合，两个生成文件也与归档条目字节相同。download_model.sh 指向 `https://media.xiph.org/rnnoise/models/rnnoise_data-<hash>.tar.gz` 并校验 hash。归档还含 little 模型和训练 checkpoint；此次只静态列目录/比对，不反序列化 checkpoint。
- 本地 RNNoise README 声称其模型使用公开数据并列出训练流程；“公开可取得”不是数据统一 BSD 授权。生成 rnnoise_data.c 首部没有独立许可声明，归档未含独立 LICENSE。因此源码 BSD 已确认，具体权重及各训练数据的授权覆盖仍**未闭环**，不得把数据权利一律推定为 BSD。
- HuBERT、RMVPE、mel.bin、角色 enc/dec 和原始 pth：开源目录按现有策略外供，不授予新许可。历史本地 APK 中 HuBERT/RMVPE 与解包文件 hash 一致及下载记录，仅证明取得链的一部分；不能证明上游 checkpoint/导出者/训练集/声音权利授权。SVC 算法来源与角色权重来源必须分开。具体角色和当前生产权重的逐 tensor/逐文件来源闭环仍未完成，不能套用 ultimate-rvc-mobile 或 fork 的代码 MIT。

## 二进制和构建/测试依赖

| 组件 | 实物证据和许可结论 | 尚缺的证据/发布处理 |
| --- | --- | --- |
| ONNX Runtime Java/JNI | libs/ort/classes.jar 实际有 `META-INF/LICENSE-1DS`，内容为 Apache-2.0；它只是 bundled component notice，不能当成 ORT 核心许可。当前 ort/jni/arm64-v8a/libonnxruntime.so 含 `1.29.0` 字符串；qnn/ 下另一个 ORT 含 `1.21.1`。aar-metadata.properties 仅含 AAR 格式/min SDK 信息，不含 ORT 发布版本。 | 本次未取得与实际 1.29.0 二进制相匹配的 ORT LICENSE/ThirdPartyNotices、发布坐标或源提交，故不再用“通常 MIT”充当该二进制已核实结论。相关上游 microsoft/onnxruntime；应逐个 AAR/JAR/SO 匹配 hash、正式发布及完整 notices。开源目录不捆绑 libs；当前 build 使用 ort 路径，备份/qnn 目录名不能证明实际链接版本。 |
| Qualcomm QNN / QAIRT | **实物及来源确认（2026-11-14）**：完整官方 QAIRT 2.51.0.260929 ZIP，SHA-256 `9b4ca798902f02a2543857f5de4d233830c044d19701b92fc66a12fdc292211f`；12,460 条目 CRC 已校验。HTP/Prepare/GPU、V68/69/73/75/79/81/85 Stub/Skel 构建号 `v2.51.0.260929181845`，System 按同 ZIP 条目 CRC/SHA-256 确认。来源及逐库校验见 scripts/qnn-sdk-manifest.json。SDK 内 LICENSE.pdf 标题为 AI Stack License，QNN_NOTICE.txt/NOTICE.txt 本地已保留。 | LICENSE §1(iv) 描述以 object code 嵌入应用的分发许可，禁止单独分发；仍须遵守完整协议和 notices。这是实物协议摘录而非法务结论。公开源码目录仅含脚本及哈希清单，不捆绑 SDK/.so；本地依赖目录保留协议原件。 |
| R8/D8 | **实物确认**：libs/r8.jar 中根 `LICENSE` 明说 non-library code 为 BSD-3-Clause，Copyright 2016 R8 project authors；随后列 Guava/Gson/fastutil/Kotlin 等 Apache-2.0 以及 ASM 的 BSD 等捆绑许可。实际 D8 --version：8.5.35，build `2c176ec131a9edb97721fb8578b7fbb462376632`。 | 不应写 Apache-2.0 单一许可，也不能只保留 BSD 而丢捆绑全文。上游 https://r8.googlesource.com/r8 。这是构建工具，开源目录不捆绑 jar；未来随包分发时保留 jar 中完整 LICENSE 并核对正式构建来源。 |
| JVM org.json | **实物确认**：生产测试脚本所用 tmp/rk/json.jar 的 pom.properties 为 `org.json:json:20240303`；MANIFEST Bundle-Version 20240303.0.0；内嵌 POM 声明 **Public Domain**，链接 stleary/JSON-java/LICENSE。 | 此 jar 未发现单独 LICENSE 条目，本次无法下载 20240303 标签 LICENSE，因此“该制品声明 Public Domain”已确认，标签全文/正式发布 hash 未独立核实。不能把老版 JSON 的 good-not-evil 条款或 Android org.json 的许可直接套到这个 JVM jar，也不能把 POM 声明写成已读上游全文。开源测试 JSON_JAR 外供，实际可选其他制品，需按所选文件重审。 |
| Android API 34 / tools | libs/android-34/source.properties 及 data/NOTICE.txt、skins/NOTICE.txt、templates/NOTICE.txt 存在，NOTICE 含多个组件版权/许可。 | 外供 Android SDK/API jar 与工具；SDK 使用条款、平台源码和工具自身许可分别适用。未逐个核定 AAPT/apksigner/clang 的制品来源与再分发条件，不宣称整套工具获一个许可证覆盖。 |

## 发布前仍需闭环

1. 固定 Ultimate RVC Mobile、历史 SVC 导出依赖及各改编的准确来源提交，逐文件识别继承代码，补齐保留声明；不能因当前项目选择 MIT 而覆盖它们。
2. 解决 LSPosed 图标的 GPL 义务及组合发布范围；确认 AndroidLiquidGlass 指定提交是否存在 NOTICE。
3. 为实际 ORT/QNN 制品取齐官方来源、许可/第三方声明、SDK 协议与 redistributables 清单。版本字符串与目录名不能替代这些材料。
4. 单独证明 RNNoise 内嵌权重、HuBERT/RMVPE/角色模型及数据/声音授权。没有完整证据的项目保持未确认，不默认允许再分发。

本清单是可复核的证据记录，不是“所有组件已获再分发许可”的声明。此清单记录许可核实时的证据；源码公开不代表已核实外部模型与二进制的再分发权限。

## 雨滴来源记录纠正（2026-11-11）
用户确认当前雨滴为项目自行实现，第三方雨滴光学移植因效果不佳已撤销。历史 svc-rain-port/rollback/report.md 记录：2026-11-06 RainOverlay 从移植前备份精确恢复，RainOverlay/GlassShader/RainPhysics/BackdropRain 与移植前四文件 cmp 一致，并删除该次引入的 raindrop-fx LICENSE/NOTICE。因此不能用“下载过该项目”或此前遗留的 rain-renderer-MIT.txt 证明当前包含其代码；从随包第三方组件表及许可目录移除这条误归属。此结论依据回退记录及用户对最终代码来源的确认，不等同于对完整代码的逐行上游相似性审计。液态玻璃改编另有文件头和实际使用，不受该雨滴移植回退影响，继续保留 AndroidLiquidGlass Apache-2.0 声明。
