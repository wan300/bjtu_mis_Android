# ExecPlan: 插件结构化导入、限时后台回调和网络兼容

## 目的与上下文

用户要求核查并开放第三方插件接口，提交面向 `dev` 的 PR。初始 worktree 干净，HEAD 与 origin/dev 均为 851a7b2。Registry 是唯一契约来源，Android provider 与 SDK 需同步。用户已明确登录态获取由应用自行实现；宿主仅提供结构化导入。

## 设计与边界

- 先修订 constitution 3.2.0 的范围限制，再实现受限例外。保留 contract_v1、publisher+plugin 隔离、稳定本地 main-frame 桥、SSRF、校园凭据隔离和撤销清理。
- `android.credentials.import@1` 接受包名及应用自行获取的数据；仅前台、逐次明确确认、有持续通知，仅返回白名单结构，不读任何其他应用或宿主私有数据，不缓存敏感回执。
- `android.background.task@1` 使用原生调度而非 JS timer；加密持久化、幂等、每插件最多 4 项、间隔至少 1 秒、单次最长 60 分钟，前台 schedule、后台 cancel/status。同插件事件前台优先；系统停止、通知拒绝、撤销、更新、回滚、登出、重启均失效；恢复不能延长到期时间，不承诺精确定时或永久存活。
- `network.request@1` 公开已有 FormUrlEncoded provider；不增加任意 header 绕过。
- `android.network.status@1` 增加显式请求且逐次确认的地址查询；公网 IP 通过插件指定并已声明的 HTTPS endpoint，复用隔离网络 provider，禁止宿主默认外联。
- 用户进一步明确要求通用接口规则，不绑定具体插件。提供 network.request@2 独立授权的显式 include、精确 origin 内存 Cookie 会话。OAID/MAC 当前没有可交付的通用来源：MAC 受 OS 特权约束，OAID 缺少官方 SDK/证书，不宣称空壳支持；通配 origin 非当前阻断项，维持精确声明。
- 无 Room、依赖或新增系统权限；增加使用既有 dataSync 权限的内部 FGS，构建产物不提交。

## 实施与里程碑

1. 审计并修订原则、同步 AGENTS/README/文档。完成标准：审阅者能看到每个请求的现状及支持范围。
2. 修改 Registry、运行生成器，接入 SDK 和 Android provider/生命周期。完成标准：公开 API 可调用，旧权限不会自动泄漏 IP/凭据。
3. 添加契约、幂等/持久恢复/清理、网络编码及敏感数据测试。运行 tooling generate:check/typecheck/test/pack:check、Manifest lint、platform typecheck/unit/integration/e2e、Android test 和 compileDebugAndroidTestKotlin。
4. 检查 diff，提交并推送独立分支，创建 base=dev PR。设备/API 26/35 或 PostgreSQL 缺失时记录并交阻塞 CI。

## 失败处理与回滚

启动通知/FGS 失败不得保留活动任务；持久化损坏或 boot/时钟校验失败时关闭任务。恢复只在可见 Activity 或已运行 FGS 中进行。撤销先停止派发再清理持久记录。回滚本 PR；升级/回滚 hooks 删除新增任务，旧客户端忽略不认识的 capability，不改变已有 KV/Room 数据。任何验证失败均记录原因，不标记为通过。

## 进度

- 2026-11-05：审计发现 FormUrlEncoded 底层存在但公开契约缺失；其余接口未开放。用户确认登录态由应用自行获取。已创建分支 codex/plugin-capability-access。Git 本机配置代理端口不可用，单命令禁用失效代理后成功 fetch dev。

## 待完成

- [x] 原则和文档同步
- [x] Registry/生成物/SDK/Android 实现
- [x] 回归验证及环境限制记录
- [x] 独立分支与 diff 审查；下一步提交、推送并创建 base=dev PR，发布结果以 GitHub 返回为准

- 2026-11-05：constitution 升级 3.3.0，已同步模板、README、AGENTS、PLANS、开发者页面。Registry/8 个派生产物、SDK、Android provider 和撤销 hooks 已实现。新增 automation 原子写 barrier，写入中断后旧活动快照不可恢复。
- 2026-11-05：tooling generate:check/typecheck/43 tests/pack:check 通过；4 个共享 Manifest lint 通过。平台 typecheck、22 unit、8 e2e 通过；integration 未配置 TEST_DATABASE_URL，数据库测试跳过，交现有 PostgreSQL CI。
- 2026-11-05：本机有 API 32 模拟器；已添加原生任务 foreground/background 派发、cancel/replay 和 revoke 通知清理设备测试。API 26/35 继续由已有阻塞矩阵验证。Android 完整 build/test 正在进行，嵌入式前端构建通过并有既有 Svelte 警告。

- 兼容决策：宿主 runtime 提升为 5；新接口以及扩展 network.request@1 / android.network.status@1 的 capability floor 为 5，防止旧宿主将新增方法或 bodyType 当作已支持。已安装旧包仍可运行；新发布包由目录派生 floor 阻止不兼容更新。

## 最终验证与故障记录

- Android `test`：Debug 与 Release 各 548 tests，0 failures/errors/skips；`:app:compileDebugAndroidTestKotlin` 和 `assembleDebug -PtargetAbis=x86_64` 通过。
- API 32 设备：Gradle connected runner 遭遇 No connected devices / Connection reset / Connection refused（环境传输故障，非测试通过）。改用同一 APK 的 `adb -s emulator-5556 shell am instrument -w -r -e class cn.edu.bjtu.mis.data.agent.PluginBackgroundTaskDeviceTest cn.edu.bjtu.mis.debug.test/androidx.test.runner.AndroidJUnitRunner`，结果 `OK (1 test)`。覆盖导入确认/通知、native tick 前台优先、后台取消、重放不恢复、重新 schedule 和撤销通知清理。
- 本机 API 35 AVD 配置的 system image 缺失，未能启动；API 26/35 交现有阻塞 instrumentation CI。无 TEST_DATABASE_URL，2 个真实 PostgreSQL 集成测试跳过，交现有 CI。
- tooling generate:check、typecheck、43 tests、pack:check、4 个 Manifest lint 与 schema 字节一致性通过；platform typecheck、22 unit、8 e2e 通过。
- 修复过的验证失败（均非既有基线）：表单测试对空格编码的预期应为 OkHttp 的 %20；runtime 5 调整期间一次编译输入不同步，重新构建后握手通过；logout 参数增量编译不同步，重跑后通过；加密失败测试需要保留第二项订阅才能触发写入而非直接删除，已修正 fixture；设备 observer 原先早于真实后台 WebView 绑定，被较新 runtime 优先规则选走，已在 foreground tick 后重新绑定观测 sink 并通过。
- 补齐 SessionManager 的宿主登出 hook，停止任务/清空隔离 Cookie；即使清理 hook 抛错，原有凭据清除仍执行，JVM 测试覆盖该路径。
- 生产行为与测试保持一致；未提供跨应用抓取、硬件 ID 空壳或宽泛 origin 通配。新后台 FGS 使用既有 dataSync 权限；无 Room、依赖或新增系统权限。只恢复本次构建改写的 pyodide lock，不提交 APK、模型或前端生成资产。
