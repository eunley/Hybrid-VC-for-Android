# Security / 安全

This draft has no configured private vulnerability reporting channel. Before public release, the maintainer must provide one. Do not put tokens, audio recordings, personal voice models, device identifiers or signing material in public issues. Until a channel exists, keep sensitive reports private with the project owner through an already trusted contact.

本稿尚未设置私密漏洞通道，发布前由维护者补充。不要在公开 issue 提交密钥、录音、个人声音模型或设备资料。

Treat HTTP access as access to sensitive voice processing, upload storage and device resources. Review actual bind address, authentication/token handling, upload quotas, archive traversal protections and cancellation behavior in the final source. Restrict to trusted local clients and explicitly authorized listeners; use no public tunnel by default. A local API is not automatically a safe multi-user service.

Virtual microphone/system helpers may require elevated device permissions and use hidden APIs. Test only on authorized devices; review each privileged action and restore system state after tests. Untrusted role archives and models must be treated as hostile input. Avoid logs containing request authorization headers or raw audio. Review retention and removal of uploaded files.

The preparation scan only flags filenames and is heuristic, not evidence that secrets or vulnerabilities are absent. Re-run scanning after the final src/res snapshot. Rotate any actual exposed production credential through its owner; do not copy it into reports.

HTTP authentication: an empty configured token disables checks. A nonempty token requires Authorization: Bearer <token> on every route, before body processing. Tokens reside in application-private preferences and should not be copied into logs or issues. HTTP remains unencrypted.
