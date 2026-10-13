<!--
Sync Impact Report
Version change: 3.1.0 -> 3.2.0
Modified principles: I, IV, VII — optional-only declaration android.app.autoStart@1 allows visible main-Activity-triggered background loading after explicit grant; no boot startup, new accessibility access, or perpetual survival.
Templates reviewed: .specify/templates/plan-template.md, tasks-template.md, spec-template.md.
Runtime guidance synchronized: AGENTS.md, PLANS.md, docs/third-party-services.md, android/README.md, web/developers.html.
Validation: see runtime/plugin-app-autostart-execplan.md; API 26/35 system validation remains required.
-->

# BJTU MIS Android Constitution

## Core Principles

### I. 用户安全与明确授权优先

BJTU MIS Android MUST 保护校园账号、密码、Cookie、token、邮件内容、验证码、作业附件和个人学习数据。任何会发送邮件、提交作业、选课、删除数据、写入本地工作区、导出文件或改变上游校园系统状态的能力，MUST 保留明确的用户确认、可理解的错误反馈和可追踪的调用边界。Agent MUST NOT 静默发送邮件或自动提交课程平台作业。

仅下列列举的 Android Capability 可以在插件首次 Capability 审阅或更新增量审阅时取得持久授权，并在授权后免除宿主逐次确认：`android.accessibility.events@1`、`android.accessibility.nodes@1`、`android.accessibility.actions@1`、`android.packages.read@1`、`android.settings.open@1`、`android.device.info@1`、`android.network.status@1`、`android.battery.status@1`、`android.haptics.perform@1`、`android.files.pick@1`、`android.files.save@1`、`android.media.pick@1`、`android.share.open@1`、`android.notifications.post@1`、`android.location.read@1`、`android.calendar.read@1`、`android.calendar.write@1`、`android.camera.capture@1`、`android.audio.record@1`、`android.sensors.read@1`、`android.biometric.verify@1` 与 `android.session.keepAlive@1`、`android.app.autoStart@1`。该例外 MUST 同时满足 Manifest 显式声明、稳定 publisher subject + plugin ID 隔离、可随时撤销、进入复审或声明变化时立即停止，以及每项契约规定的最小数据、配额、幂等和持续通知约束。

持久授权不替代 Android runtime permission、系统选择器、生物识别弹窗、前台服务或可见前台 Activity。要求用户直接参与的文件、媒体、分享、相机和生物识别操作 MUST 由前台 runtime 发起；后台调用 MUST 返回 `foreground_required`，不得启动隐蔽 UI。定位 MUST NOT 请求后台定位；录音 MUST NOT 在后台静默开始。文件和媒体结果 MUST 进入 publisher+plugin 隔离的加密资源存储，不得向插件暴露任意路径或可持续 URI。该列举不得扩展为任意 Intent、任意 URI、任意文件系统、通知监听、联系人、短信、电话、MediaProjection、Overlay、VPN、UsageStats、Shell/ADB 或其他通用 Android API。

`android.session.keepAlive@1` MUST 使用宿主生成、publisher subject + plugin ID 隔离的限时租约；首次/增量授权后 acquire/renew 免逐次确认但必须由可见前台 runtime 发起。后台仅查询/释放；恢复不得延长原到期时间或绕过 Android FGS 限制。每插件最多 2 租约、单租约最长 60 分钟、每分钟最多 6 次申请/续租、滚动一小时最多预留 60 租约分钟；配额和幂等摘要 MUST 加密持久化。通知持续可见并提供全部停止，租约不保证进程永不终止，也不授予通用后台任务或凭据访问。

`android.app.autoStart@1` MUST 仅能声明于 Manifest `capabilities.optional`，默认关闭，用户首次或增量授权后才可在打开主应用且主 Activity 可见时由宿主加载该插件的本地入口。它是无 JS 方法的声明型 Capability，不允许声明为 required，不授予开机启动或永久存活，不替代前台服务/通知约束。用户停止、授权撤销、禁用、复审、声明丢失、publisher 不匹配及更新/回滚 MUST 停止相应自启 runtime；重新开启必须由用户明确选择。后台实例全局最多 4 个，稳定本地 origin/main-frame 桥、前台事件优先、既有无障碍访问范围及系统 UI/运行时权限前台 gate 均保持不变。

Rationale: 本应用直接处理校园身份和学习事务，错误自动化会造成账号、隐私、学业和信任风险。

### II. 架构边界不可绕过

Android UI MUST 通过 repository、container 或既有状态模型获取数据，不得直接访问校园服务。provider 负责上游系统访问和会话协议，parser 负责把 HTML/JSON 转换为 model，repository 负责缓存和业务编排，db 负责 Room entity/DAO/迁移，security 负责凭据和 Cookie 安全存储，sync 负责后台同步和会话保活。Open WebUI 原生能力 MUST 通过既有 Capacitor 插件、handoff 和 `src/lib/local-first/` 工具注册路径接入。

Rationale: 清晰边界让校园服务适配、缓存、离线能力、安全存储和 UI 状态可以分别测试和维护。

### III. 本地优先与离线可恢复

用户已经同步的数据 SHOULD 在校园服务不可用、网络失败或离线时继续可读。新增持久化能力 MUST 使用项目既有 Room、DataStore、加密凭据或安全文件路径，并定义刷新、过期、失败和空状态反馈。同步和会话保活逻辑 MUST 避免破坏已有本地快照，除非用户明确触发删除或重置。

Rationale: 校园服务和网络并不总是稳定，本应用的核心价值包括本地快照和离线访问。

### IV. 外部系统、Agent 工具与插件契约变更必须可测试

parser 变更 MUST 使用 fixture 覆盖成功、缺字段和上游结构变化。provider/repository 变更 SHOULD 使用 MockWebServer、fixture 或 fake/store 模式，不依赖真实校园账号或线上服务。Agent 文件、归档、文档、代码、邮件和结果打包工具变更 MUST 覆盖 workspace 边界、路径穿越、大小限制、条目数量、确认流程和错误反馈。Open WebUI local-first、handoff、工具参数或流式输出变更 MUST 运行对应 Vitest。

第三方插件平台、Manifest、WebView runtime、Capability、存储、更新、网络或校园代理变更 MUST 覆盖契约生成确定性、生成物漂移、v3 schema、v1/v2 与 P0-A 拒绝、稳定发布者主体、main-frame/source-origin 桥接约束、授权与逐次确认、命令幂等、SSRF/重定向/Header/Cookie 边界、配额、迁移与回滚以及校园只读 registry。Android 自动化与通用原生 Capability 变更还 MUST 覆盖权限拒绝、系统服务不可用、节点脱敏与过期、严格遍历预算、事件过滤与限流、前台优先、后台恢复、动作幂等且不触发 confirmer、包字段最小映射、Settings Intent 限制、系统 UI 前台 gate、无 raw URI、资源隔离、位置/录音后台拒绝以及更新/撤销/删除清理。自启能力 MUST 覆盖 optional-only 声明、默认关闭、可见主 Activity 触发、复审/撤销/停止清理、通知/前台服务拒绝及后台实例上限。生成的 TypeScript、Kotlin、Schema、lint 和开发者文档 MUST 由同一 Capability Contract Registry 派生，并由阻塞测试证明一致。

Rationale: 上游页面和工具输入不受本项目控制，测试必须固定风险边界并防止回归。

保活 Capability MUST 覆盖租约归属、到期/续租、并发/配额、幂等重放、进程恢复、时钟变化、前台启动拒绝、通知停止、插件撤销/更新/删除和内置 Agent/抢课租约共存。

### V. 最小依赖与干净提交

项目 MUST 优先使用现有库、标准库和本仓库 helper。新增依赖必须说明属于 Android 原生侧还是 Open WebUI 前端侧，并通过 `android/gradle/libs.versions.toml` 或 npm/lockfile 的正常流程维护。提交中 MUST NOT 包含 secrets、本机配置、APK/AAB、临时构建产物、`node_modules`、`.svelte-kit`、Open WebUI build 输出或无关缓存。为验证数据库迁移而由 Room 导出并经审查的 `android/app/schemas/` 版本历史 MUST 随对应 migration 提交；其他临时 schema 输出不得提交。

Rationale: 依赖和生成物会扩大维护面、供应链风险和审查成本。

### VI. 高风险变更必须先有 ExecPlan

Room 迁移、权限或网络安全配置、凭据/Cookie 存储、生产配置、构建或依赖升级、Open WebUI 与 Android 桥接、第三方插件身份/runtime/origin/存储/校园代理、Agent 工具安全边界、数据删除和架构边界变化 MUST 先编写可执行、可恢复、持续更新的 ExecPlan。ExecPlan MUST 明确目标、范围、验证命令、失败处理和回滚策略，再进入实现。

Rationale: 高风险变更需要可交接的上下文和回滚路径，不能只依赖聊天记录或临时判断。

### VII. 插件平台契约化安全基线不得降级

Manifest v3 / contract_v1 是第三方插件唯一有效的运行与兼容基线。新客户端 MUST 拒绝安装、更新和运行 Manifest v1/v2 以及 P0-A v3，MUST 拒绝 `allowed_origins`、`permissions`、`runtime_version`、`min_runtime_version`、`bridge_origins` 和内嵌 marketplace 元数据，且 MUST NOT 恢复 `identity.get_credentials`、`identity.credentials.read` 或通用原生 `app.http_request`。旧包和数据只能保留在无桥、无网络的救援环境中，除非用户明确删除；P0-A v3 仅可在 publisher subject、plugin ID 与数据版本全部匹配时原位升级为 contract_v1。

原生桥 MUST 仅注入由 plugin ID 与不可变 publisher subject 决定的稳定本地 origin，且消息必须来自 main frame 并精确匹配 source origin。桥接 origin 是宿主固定为 self 的不变量，插件 Manifest 不得声明或扩展它；远程 frame 永远无桥。远程来源 MUST 按 connect、media、frame、navigation 分离。校园会话只能通过宿主登记的只读 `campus.request` registry 使用，Cookie、认证头和明文凭据不得暴露给插件。

插件公开 API MUST 通过 SDK 的命名空间和 protocol v2 调用 Capability；底层 transport 不得作为公共 API 暴露。Capability ID、版本、方法、请求/响应 Schema、权限、逐次确认、配额、超时、错误、平台支持范围和稳定性 MUST 以单一 JSON Contract Registry 为权威来源。optional Capability 默认关闭；除原则 I 严格列举的 Android 持久授权例外外，所有改变校园或宿主状态的 Command Capability MUST 逐次确认并使用请求摘要绑定的 idempotency key。列举的 Android Command Capability 虽免宿主逐次确认，仍 MUST 使用请求摘要绑定的 idempotency key、加密摘要回执、运行时配额、最小数据集和 Android 必需的系统 UI；不得记录输入文本、节点文本、手势细节、文件内容、位置坐标或音频。

Android 自动化和通用原生桥 MUST 继续只存在于稳定本地 origin 的 main frame。`android.app.autoStart@1` 不提供插件主动启动方法，只有用户打开主应用且主 Activity 可见时宿主才可加载已授权插件；后台须持续通知和全部停止，不可由 worker、无障碍服务唤醒或开机广播隐式触发。无障碍事件每插件最多 16 个订阅、每订阅最多 60 events/s；节点快照最多 4,096 节点、深度 64，opaque node handle 最多存活 30 秒；网络、电池和传感器持久订阅每插件最多 16 个，传感器最多 20 Hz；全局最多 4 个后台插件 runtime，前台页面优先接收且不得重复派发。`android.packages.read@1` 不得返回 APK 路径、私有数据或图标字节；`android.settings.open@1` 只允许 `android.settings.*` action 和无 data 或 `package:` data，禁止 extras、显式组件、任意 URI 和任意 Intent。设备和网络信息不得包含稳定硬件 ID、SSID、BSSID、MAC 或 IP；文件和媒体不得泄漏 raw URI；位置不支持后台追踪。插件删除、禁用、授权撤销、publisher 不匹配、声明丢失或 `needsReview` MUST 立即停止 runtime、清除订阅、失效 node handle，并取消录音或待处理系统 UI。插件保活租约 MUST 同时清理；版本更新/回滚亦不得恢复旧租约。停止、登出、认证失效、系统超时和设备重启 MUST 使租约失效，清理失败不得重新激活。

插件公网访问 MUST 使用无 Cookie、无宿主认证器的隔离客户端，并在初始请求、每次 DNS 解析和每次重定向重新阻止校园、私网、回环和链路本地目标。插件持久化、更新和删除 MUST 使用 publisher+plugin 隔离、加密且受配额约束的数据空间，并保持迁移、快照、回滚、增量授权和失败清理可恢复。任何文档、旧 feature spec、兼容代码或迁移期 API 都不得被用来削弱这些约束；需要改变本基线时 MUST 先修订 constitution、维护新的安全 ExecPlan 并通过对应阻塞验证。

Rationale: 插件代码不受宿主控制，稳定身份、最小桥接、分域联网、事务数据和只读校园代理必须作为统一零信任边界，而不能由旧兼容策略逐项回退。

## Development Workflow

- 开始修改前 MUST 检查 `git status --short`，并保护用户已有改动。
- 需求或实现存在不确定信息时 MUST 标为 `TODO: confirm`，不得把猜测写成事实。
- 修改范围 MUST 贴近任务，不顺手重构无关模块。
- 验证命令 MUST 按改动范围选择：Android 业务或解析改动运行 `Set-Location android; .\gradlew.bat test`；打包、资源或 WebView 资产相关改动运行 `Set-Location android; .\gradlew.bat assembleDebug`；Open WebUI local-first 或前端逻辑改动运行 `Set-Location android\open-webui; npm run test:frontend -- --run`。
- 插件平台或 Manifest 变更 MUST 运行 `web/platform` 的 typecheck、unit、integration、e2e，运行共享 Manifest lint，并编译或执行适用的 Android WebView instrumentation；因环境缺失而未执行的阻塞验证 MUST 交由 CI 且在汇报中明确记录。
- 修改插件相关规范时 MUST 检索 v1/v2、`allowed_origins`、明文凭据桥和通用 HTTP 桥等旧契约；历史文档只能保留带有明确 superseded 标记的非规范说明。
- `npm run lint:frontend` 和 `npm run format` 会写入文件，只能在明确需要格式化或自动修复前端文件时运行。
- 最终汇报 MUST 说明文件变更、主要规则、TODO/不确定项和验证结果；未运行的验证必须说明原因。

## Governance

本 constitution 是项目原则的权威来源。`AGENTS.md`、`PLANS.md`、README 和 Spec Kit 模板可以提供执行细则；如出现冲突，以本文件为准，并同步修正文档。

插件安全策略的规范优先级依次为：

1. 本 constitution，尤其是原则 VII；
2. `plugin-tooling/contracts/capability-contracts.json`、`docs/third-party-services.md` 与生成且字节一致的 Manifest/marketplace schema；
3. 当前有效的 `docs/plugin-runtime-contract-p0-p1-execplan.md`、实现和阻塞测试；
4. 带有 superseded/legacy 标记的旧 spec、plan、API 和迁移说明，仅可用于历史追踪或数据救援。

低优先级材料与高优先级规则冲突时，冲突部分自动失效。旧文档不得授权重新启用 v1/v2 运行时、聚合 origin、远程桥、明文凭据读取或通用原生 HTTP。

修订流程：

1. 提交 constitution 修改时 MUST 更新顶部 Sync Impact Report。
2. 原则或治理规则变化时 MUST 同步检查 `.specify/templates/`、`AGENTS.md`、`PLANS.md` 和 README 中的相关引用。
3. 破坏性治理调整、原则删除或原则重新定义升级 MAJOR；新增原则或实质扩展升级 MINOR；措辞澄清和非语义修正升级 PATCH。
4. 任何 feature spec、implementation plan 或任务拆分 MUST 通过模板中的 Constitution Check；未通过项必须先调整设计或写入明确的 `TODO: confirm` 阻塞说明。

**Version**: 3.2.0 | **Ratified**: 2026-06-11 | **Last Amended**: 2026-10-02
