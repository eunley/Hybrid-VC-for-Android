# 构建说明

源码已包含 `src/`、`res/` 和 Manifest，可直接按下述步骤准备依赖并构建。

## Termux 构建

在手机 Termux 私有 ext4 工作区构建。安装支持 arm64 Android 的 JDK（建议 21）、clang、Python 3、zip、aapt1、zipalign、apksigner。各工具须在 PATH；aapt2 不能直接代替 aapt1。原生脚本面向 arm64-v8a，并非桌面交叉编译脚本。

依赖自行从有授权的官方来源获取，不随本目录提供：

| 路径/变量 | 内容 |
| --- | --- |
| libs/android-34/android.jar / ANDROID_JAR | Android API 34 平台 |
| libs/r8.jar / R8_JAR | R8 中的 D8 |
| libs/ort/classes.jar / ORT_JAR | 与 JNI 匹配、支持所需 QNN EP 的 ONNX Runtime Java 库 |
| libs/ort/jni/arm64-v8a / ORT_NATIVE_DIR | 同版本 ORT JNI .so |
| libs/qnnsdk/jni/arm64-v8a / QNN_NATIVE_DIR | 同版 QNN HTP、Prepare、System、GPU，加 V69/73/75/79/81/85 的 Skel/Stub（16 个打包库） |

可用 LIBS_DIR 改变默认依赖目录。QNN 固定为完整官方 **QAIRT 2.51.0.260929**，构建号 `v2.51.0.260929181845`；来源、原始 ZIP SHA-256、条目路径及逐库 SHA-256 见 `scripts/qnn-sdk-manifest.json`。同名 Maven `qnn-runtime:2.51.0` 的 AAR 不包含 V85，不能直接替代。

从 [Qualcomm 官方完整 SDK](https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.51.0.260929/v2.51.0.260929.zip) 提取 `lib/aarch64-android/` 的四个后端库和 Stub，以及 `lib/hexagon-v{架构}/unsigned/` 的 Skel，放到上述目录。构建先运行 `python3 scripts/verify-qnn.py "$QNN_NATIVE_DIR"`（默认目录可直接传 `libs/qnnsdk/jni/arm64-v8a`），缺 V85、混版、额外旧 QNN 库或哈希不符均中止；清单另含同版 V68 对作可选校验，但当前构建不打包 V68。禁止直接将已有 libs 全目录纳入仓库。

ORT 1.29 只接受显式 `htp_arch=68/69/73/75/81`；V79/V85 省略该项，由 QNN 自动探测。SoC 判定仍要求包内同时存在对应 Skel 与 Stub。当前设备 SM8850/V81；V85 设备运行未实测，库完整不代表每个芯片/模型都成功。QNN 来源已核实，其余依赖按各自来源准备。

```sh
bash build-termux.sh
```

输出 `.build/hybrid-vc.apk`。脚本在 `.build` 生成 policy.dex，不携带原生产密钥；默认于 `.private/debug.jks` 生成独立 debug key，默认密码仅用于本地调试。自备签名设置 KEYSTORE_PATH、KEYSTORE_ALIAS、KEYSTORE_PASSWORD，可设置 KEY_PASSWORD；密码经环境变量传入签名器，不写入仓库。debug 签名通常不能覆盖生产签名安装。

推理需要自行取得合法的 hubert.onnx、rmvpe.onnx、mel.bin，放入独立目录后设置 MODEL_ASSETS_DIR。源码 BundledModels 校验固定大小与哈希；替代模型须单独验证兼容性。缺少模型可能构建出 APK，但初始化/推理不完整。仓库不包含模型和角色包，不提供自动下载。

完整构建约需较大内存；需要实际编译时请脱离 MCP 进程树运行并自行记录、回收任务。构建会生成本地输出，请勿将其提交。

## 测试及多语言审阅

测试入口和依赖见 [tests/README.md](tests/README.md)。运行 `bash tests/run-wire-tests.sh`。业务和分块上传测试需自行提供 JVM org.json jar，通过 JSON_JAR 指定；分块脚本默认可能产生 513 MiB 测试数据，使用前阅读参数并设置 TEST_WORK。不要把生成物提交。

检查 res/values*/strings.xml 的 key、占位符顺序、复数、转义、RTL、语言回退，并在实际设备验证界面。资源键和格式占位符已检查；各语言仍需人工校对。

API 文档见 assets/local-http-api.txt，客户端见 assets/upload_role.py。启用本机监听、上传或虚拟麦克风前阅读 SECURITY.md。虚拟麦克风涉及高权限和系统兼容性，应仅在授权设备测试。

发布前必须处理 THIRD_PARTY.md 中的项目授权、GPL 图标、模型和 QNN 再分发问题。任何许可证清单都是待核实记录，不是法律审核结论。

Template assets are required: set `TEMPLATE_ASSETS_DIR` to a directory containing `enc.onnx`, `enc.onnx.data`, and `dec.onnx`. They are packaged under `assets/tpl`; missing files in the active tpl directory are restored before UI or HTTP role import. Existing nonempty user templates are preserved. `dec.onnx` has inline weights and needs no `.data` file. Obtain appropriately licensed templates separately; expected sizes and SHA-256 are in `BundledTemplates.java`.
