# Hybrid VC for Android 1.0.1 (build 32)

## 中文

- 修复角色导入缺少模板：UI 和 HTTP 导入前从 APK 恢复缺失模板，保留已有非空模板；修复 FP16 转换依赖较新 Android API 导致的兼容性错误。
- 新增默认关闭、可拖动的实时双波形辅助条。MIC 和 OUT 使用共同采集时间轴，显示转换积压及 RTF；播放游标采用经过身份校验的 helper 所报告的真实系统 AudioTrack 进度，未知或过期时隐藏。修复 PCM16 分片字节对齐。
- 更新为完整官方 QAIRT 2.51.0.260929，打包 V69/73/75/79/81/85 的 Skel/Stub 配对库，构建前校验 SHA-256；新增 SoC/HTP 映射和 ORT 1.29 参数兼容，HTTP 配置响应加入 SoC 诊断。
- 首次启动语言跟随系统，保留已有的显式语言选择。
- 文档：增加按 SoC/HTP 架构整理的“可能支持 NPU”设备表，并说明这是兼容性提示而非逐机型验证。

验证范围：APK 签名和对齐检查通过；库及 helper 已与构建产物核对。已有 JVM 回归覆盖播放协议 17,365 项、probe 21 项和时间轴 40 项；FP16 对照及模板恢复测试已通过。HTP/GPU 推理证据来自 SM8850/V81 上的微型模型。尚未验证 V85 真机、最终 APK 的音频端到端效果或全部旧 EPContext 缓存兼容性。游标代表系统 AudioTrack 消费，不代表接收 App 已读取；缺库时部分缓存路径仍可能先尝试 QNN，失败后回退 CPU。

源码不包含外供模型、模板、依赖库、签名私钥或用户声音包。构建所需外部资产及第三方说明见 BUILDING.md / THIRD_PARTY.md。APK 内 HTTP 帮助仍保留旧的“模板须预先存在”描述，当前源码文档和实现已支持恢复。

## English

- Restore missing role-import templates from the APK before UI or HTTP import while preserving existing nonempty templates. Replace newer Android FP16 conversion APIs with compatible IEEE 754 conversions.
- Add an optional, default-off, draggable dual waveform strip with a shared capture timeline and RTF. The playback cursor follows authenticated helper reports of the real system AudioTrack playback head and hides when unknown or stale. Fix PCM16 alignment across fragmented reads.
- Update to the complete official QAIRT 2.51.0.260929 runtime, including paired V69/73/75/79/81/85 Skel/Stub libraries and build-time SHA-256 validation. Add SoC/HTP mapping, ORT 1.29 option compatibility, and SoC diagnostics in the HTTP configuration response.
- Follow the system language on first launch and preserve explicit saved language choices.

Validation scope: APK signature/alignment and packaged library/helper checks pass. Existing JVM results cover 17,365 playback protocol assertions, 21 probe checks, 40 timeline checks, FP16 reference comparisons and template restoration. HTP/GPU inference evidence is a tiny model on SM8850/V81. V85 hardware, final-APK end-to-end audio, and all old EPContext caches remain unverified. Playback means system AudioTrack consumption, not a receiving app reading audio. Some cached-session paths may attempt QNN before falling back to CPU when libraries are missing.

The source excludes external models, templates, dependency binaries, signing private keys and user voice packages. See BUILDING.en.md and THIRD_PARTY.md for external assets and notices. The APK's bundled HTTP help retains the old prerequisite-template wording; the source documentation and implementation describe restoration.
