# BJTU MIS Android 代理工作指南

本文件给后续 Codex 会话使用。内容只基于当前仓库中可见的 README、Gradle/npm 配置、源码目录和测试结构；不确定的信息必须标为 `TODO: confirm`。

## 0. 项目原则权威

- 项目原则以 `.specify/memory/constitution.md` 为权威来源；本文件提供代理执行细则和仓库事实。
- 如本文件与 constitution 冲突，以 constitution 为准，并同步修正文档。
- 第三方插件安全策略以 constitution 原则 VII、`docs/third-party-services.md` 和 Manifest v3 schema 为准。任何旧 v1/v2 spec、plan、API 或迁移说明仅用于历史追踪和无桥数据救援，不得作为降低当前安全边界的依据。

## 1. 项目概览

BJTU MIS Android 是面向北京交通大学学生的校园学习服务 Android App。应用直接访问 BJTU CAS、MIS、AA、VE、Coremail、知行论坛、就业服务等系统，并在设备本地保存加密凭据、Cookie 与 Room 数据快照。应用内的智能助手入口是嵌入式 Open WebUI Agent。

主要技术栈：

- Android 原生工程：Kotlin、少量 Java、Android Gradle Plugin 8.7.3、Kotlin 2.0.21、KSP。
- UI：Jetpack Compose、Material3、Navigation Compose。
- 数据与后台：Room、DataStore、WorkManager、OkHttp、Jsoup、kotlinx.serialization。
- 原生能力：Capacitor/Cordova、PyTorch Android、Media3、PDFBox Android、Commons Compress。
- 嵌入式前端：`android/open-webui/`，使用 SvelteKit、Svelte 5、TypeScript、Vite、Tailwind、Vitest。
- 网站：`web/` 下的静态前端，以及 `web/platform/` 下的 Node.js 22、TypeScript、Fastify、PostgreSQL 插件平台后端。

重要目录：

- `android/`：Android Studio 应打开的主工程目录。
- `android/app/src/main/java/cn/edu/bjtu/mis/data/`：网络访问、解析、Room、仓库、安全存储、同步、Agent 工具等数据层代码。
- `android/app/src/main/java/cn/edu/bjtu/mis/di/AppContainer.kt`：应用依赖装配入口。
- `android/app/src/main/java/cn/edu/bjtu/mis/model/`：共享模型、序列化数据结构和模块 key。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/`：Compose 应用外壳、页面、组件和主题。
- `android/app/src/main/java/cn/edu/bjtu/mis/openwebui/`：Android 与 Open WebUI 的 Capacitor 插件桥接。
- `android/app/src/main/java/cn/edu/bjtu/mis/widget/`：桌面小组件。
- `android/open-webui/`：嵌入到 Android WebView 的 Open WebUI 前端源码。
- `android/open-webui/src/lib/local-first/`：Open WebUI local-first provider、Agent loop、Android 原生工具注册和作业 handoff 逻辑。
- `android/app/src/test/kotlin/`：Android/JVM 单元测试。
- `android/app/src/test/resources/fixtures/`：解析器与 provider 测试 fixture。
- `web/`：bjtu.cc 静态前端、插件大厅页面和共享 schema。
- `web/platform/`：插件平台 API、worker、SQL migration、Dockerfile 与自动化测试。
- `plugin-tooling/`：Capability Contract Registry、确定性生成器、TypeScript SDK、CLI、Mock Host 与默认 Vite 模板。
- `deploy/`：插件平台 Docker Compose 与 bjtu.cc Nginx 配置。
- `docs/`：README 图片、插件 Manifest v3 权威开发文档、共享 schema 与各专项 ExecPlan。

## 2. Setup and local development

环境要求：

- 使用 Android Studio 打开 `android/`，等待 Gradle Sync 完成后运行 `app` target。
- JDK 17。
- Android SDK：当前 `compileSdk = 35`、`targetSdk = 35`、`minSdk = 26`。
- Node.js 与 npm：用于构建嵌入式 Open WebUI；`android/open-webui/package.json` 要求 Node `>=18.13.0 <=22.x.x`、npm `>=6.0.0`。
- 访问真实校园服务需要可访问 BJTU 相关站点的网络和有效校园账号。

安装与本地命令：

```powershell
Set-Location android
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

```powershell
Set-Location android\open-webui
npm ci
npm run check
npm run test:frontend -- --run
```

```powershell
Set-Location web\platform
npm ci
npm run typecheck
npm test
npm run test:integration
npm run test:e2e
```

构建说明：

- `assembleDebug` 和 `assembleRelease` 会在 Android `preBuild` 阶段自动构建 `android/open-webui`，并同步到 `android/app/src/main/assets/public`。
- Open WebUI 构建会使用 `ENABLE_MOBILE_CLIENT=true` 和 `ENABLE_MOBILE_NATIVE_FEATURES=true`。
- `android/local.properties` 是本机 Android SDK 配置，不应提交。
- 插件平台生产编排位于 `deploy/docker-compose.plugins.yml`，Dockerfile 位于 `web/platform/Dockerfile`。
- Manifest v3 阻塞 CI 位于 `.github/workflows/plugin-platform-v3.yml`；devcontainer：TODO: confirm，当前未发现 `.devcontainer`。

## 3. Build, test, lint, and typecheck

Android：

- `Set-Location android`
- `.\gradlew.bat test`：运行 Android/JVM 单元测试；已验证当前可通过。
- `.\gradlew.bat assembleDebug`：构建 debug APK，并触发 Open WebUI 前端构建与资产同步；适合验证打包、资源和 WebView 资产路径。

Open WebUI：

- `Set-Location android\open-webui`
- `npm ci`：按 `package-lock.json` 安装依赖。
- `npm run test:frontend -- --run`：运行 Vitest 前端测试；已验证当前 9 个测试文件、81 个测试通过。
- `npm run check`：运行 `svelte-kit sync && svelte-check --tsconfig ./tsconfig.json`。当前命令可执行但存在已知基线失败，主要是 Open WebUI 中既有 TypeScript/Svelte 类型诊断。
- `npm run lint:frontend` 会执行 `eslint . --fix`，会修改文件；不要把它当作只读检查随意运行。
- `npm run format` 会执行 Prettier 写入；仅在明确需要格式化前端文件时使用。
- `npm run lint` 包含 `lint:backend` 的 `pylint backend/`，但当前仓库用途和后端目录状态需核对：TODO: confirm。

插件平台：

- `Set-Location web\platform`
- `npm run typecheck`：检查 TypeScript 类型。
- `npm test`：运行平台单元测试。
- `npm run test:integration`：设置 `TEST_DATABASE_URL` 时执行真实 PostgreSQL migration 与约束测试；未设置时相关用例会跳过。
- `npm run test:e2e`：运行 API health e2e 测试。

插件工具链：

- `Set-Location plugin-tooling`
- `npm ci`
- `npm run generate:check`：确认 schema、TypeScript、Kotlin 与文档生成物没有漂移。
- `npm run typecheck`
- `npm test`
- `npm run pack:check`：验证 npm 包和插件 ZIP 可确定性构建。

## 4. Coding conventions

Android/Kotlin：

- 遵循 `android/gradle.properties` 中的 `kotlin.code.style=official`。
- 新增 Android 依赖优先加入 `android/gradle/libs.versions.toml`，再在 Gradle 模块中引用。
- 共享 JSON 配置使用 `cn.edu.bjtu.mis.data.AppJson`，其配置包括 `ignoreUnknownKeys = true`、`explicitNulls = false`、`JsonNamingStrategy.SnakeCase`、`encodeDefaults = true`。
- 插件平台 `/api/v3` 使用 camelCase；目录客户端必须使用专用 JSON 编解码器，不得复用带 SnakeCase naming strategy 的 `AppJson`。`/api/v2` 仅保留 P0-A 只读目录兼容。
- 与外部系统交互的 suspend 函数多位于 provider/repository 层，网络错误通常以 `IOException` 或业务异常抛出，并在 UI 层用 `runCatching` 转为加载/错误状态。
- UI 延续 Compose 模式，优先复用 `MaterialTheme`、现有共享组件、`LoadState`、`ProgressiveModuleState`、`remember`、`LaunchedEffect`、`rememberCoroutineScope`。
- 模块 key 放在 `ModuleKeys`，不要在多个位置重复硬编码路由或模块字符串。
- Parser 尽量保持纯函数风格，输入 HTML/JSON，输出 model，便于 fixture 单元测试。

Open WebUI：

- 前端格式配置在 `android/open-webui/.prettierrc`：tabs、single quotes、trailingComma none、printWidth 100、LF。
- 新增前端依赖应通过 npm 更新 `package-lock.json`，不要手改 lockfile 片段。
- Android 原生能力应通过已有 Capacitor 桥和 `src/lib/local-first/` 模式接入，不要绕过既有 handoff/tool 注册路径。

依赖规则：

- 不要为小问题引入新依赖；优先使用现有库、标准库和本项目已有 helper。
- 添加依赖前确认它属于 Android 原生侧还是 Open WebUI 前端侧，并更新对应的版本/lock 文件。
- 不要把生成产物或本机缓存作为依赖提交。

## 5. Architecture and boundaries

主要边界：

- `provider` 负责访问 BJTU 相关外部系统、处理会话和上游协议细节。
- `parser` 负责把上游 HTML/JSON 转换为内部 model；修改解析逻辑时应更新 fixture 测试。
- `repository` 负责缓存、Room 快照、本地文件、业务聚合和 provider 调用编排。
- `db` 负责 Room entity、DAO、数据库版本和迁移。
- `security` 负责 Android Keystore/AES-GCM 凭据与 Cookie 存储。
- `sync` 负责后台同步和会话保活。
- `ui` 只通过 repository/container 获取数据，不应直接访问校园服务。
- `openwebui` 包下的 Android 插件负责 WebView/Capacitor 与原生工具桥接。
- `android/open-webui/src/lib/local-first/` 负责前端侧本地优先 Agent loop、工具注册和 Android 参数传递。
- `data/thirdparty` 与 `ThirdPartyServiceScreens.kt` 共同实现 Manifest v3 / contract_v1 插件 runtime；只允许稳定本地 origin 的 main frame 获得桥，远程 frame 永远无桥。
- `AndroidSystemCapabilities.kt`、`AndroidNativeCapabilities.kt`、`PluginAutomationStore.kt` 与 `PluginAutomationSupervisor.kt` 实现 constitution 列举的持久 Android Capability；后台 WebView 仍使用相同稳定本地 origin/main-frame 桥，最多 4 个且前台页面优先接收事件。系统 UI、运行时权限、前台定位和录音 gate 不能由后台 runtime 绕过。
- 插件公网访问按 connect/media/frame/navigation origin 分离；校园会话访问只允许走 MIS/AA/VE 的只读 `campus.request` registry。
- 插件重要数据使用按 publisher subject + plugin ID 隔离的加密 `app.storage`，更新必须保持影子迁移、上一版本包/KV 快照和失败原子回滚。

谨慎区域：

- Room 数据库当前版本为 12；`MIGRATION_11_12` 将插件区分为 `legacy_v1_v2`、`legacy_v3_p0a` 与 `contract_v1`，新增 granted capabilities、contract profile、runtime floor 和独立 marketplace 元数据。旧 runtime 仅保留救援数据。
- `android/app/src/main/assets/bjtu_captcha_crnn.pt` 是验证码识别模型，避免无依据替换。
- `NativeAgentToolsPlugin`、`WorkspaceManager`、归档/文件工具涉及本地文件安全边界，改动必须覆盖路径穿越、大小限制、条目数量等测试。
- 邮件发送、作业提交、选课、会话保活是高风险用户操作，必须保持用户确认和明确错误反馈。
- `android/open-webui/` 是嵌入式前端源码，`android/app/src/main/assets/public/` 是构建产物，不要直接维护后者。

## 6. Testing expectations

测试位置：

- Android/JVM 单元测试：`android/app/src/test/kotlin/cn/edu/bjtu/mis/`。
- 测试 fixture：`android/app/src/test/resources/fixtures/`。
- Open WebUI 前端测试：`android/open-webui/src/**/*.test.ts`。
- 插件平台测试：`web/platform/test/`。

添加或更新测试：

- 修改 parser 时，添加或调整 `fixtures/` 中的 HTML/JSON 样本，并覆盖成功、缺字段和上游结构变化。
- 修改 provider/repository 时，优先使用 MockWebServer、fixture 或已有 fake/store 模式，避免依赖真实校园服务。
- 修改 Agent 工具、workspace、归档、文档或邮件工具时，补充对应 `data/agent` 或 `data/agent/tools` 单元测试。
- 修改 Open WebUI local-first、handoff、工具参数或流式输出时，运行前端 Vitest。
- 修改第三方插件 Manifest、publisher、WebView、KV、迁移、回滚、清理或校园代理时，补充对应 JVM 测试，并编译或执行 `androidTest` 中的 Room 11→12 和 WebView 安全场景。
- 修改 constitution 列举的 `android.*` 持久 Capability 时，必须覆盖服务/权限未启用、节点脱敏/过期、过滤与限流、持久恢复、前台优先、免逐次确认的动作幂等、系统 UI 前台 gate、无 raw URI、资源隔离、包字段最小化、Settings Intent 限制和撤销/更新/删除清理；真实服务场景在 API 26/35 instrumentation 验证。
- 修改 Capability 契约时，先改 `plugin-tooling/contracts/capability-contracts.json`，运行生成器并提交全部生成物；不得直接手改生成的 SDK、Kotlin descriptor/schema 或 API 文档。
- 数据库迁移必须同时维护 `android/app/schemas/` 与迁移测试；第三方插件迁移当前覆盖 Room 11→12。

完成前应按改动范围验证：

- Android 业务或解析改动：`Set-Location android; .\gradlew.bat test`。
- Android 打包、资源、WebView 资产相关改动：`Set-Location android; .\gradlew.bat assembleDebug`。
- Open WebUI local-first 或前端逻辑改动：`Set-Location android\open-webui; npm run test:frontend -- --run`。
- Open WebUI 类型检查：可运行 `npm run check`，但当前基线失败需在汇报中说明。
- 插件平台改动：`Set-Location web\platform; npm run typecheck; npm test; npm run test:integration; npm run test:e2e`。
- 插件契约或工具链改动：`Set-Location plugin-tooling; npm run generate:check; npm run typecheck; npm test; npm run pack:check`。
- 插件 Manifest 改动：运行 `tools/third-party-service-lint.cjs` 覆盖模板和示例，并确认 `docs/` 与 `web/assets/schemas/` 下的 v3 schema 字节一致。
- 插件 WebView 安全改动：至少运行 `.\gradlew.bat :app:compileDebugAndroidTestKotlin`；有设备时执行 API 26/35 instrumentation，缺少设备时必须记录并交由阻塞 CI。

## 7. Security and safety rules

- 不要提交 `.env`、`.env.*`、`android/local.properties`、`android/key.properties`、`android/keystore.properties`、`*.jks`、`*.keystore`。
- 不要在代码、测试 fixture、日志或文档中写入真实校园账号、密码、Cookie、token、邮箱内容、验证码图片或个人信息。
- 登录凭据和 Cookie 存储应继续使用现有安全存储路径，尊重 Android Keystore 与 AES/GCM 设计。
- 邮件发送必须保留用户确认流程；`agent_mail_send` 不应静默发送。
- Agent 不得自动提交课程平台作业。
- 文件、归档和结果打包工具必须限制 workspace 边界，防止路径穿越和超大文件写入。
- 新客户端不得安装、更新或运行 Manifest v1/v2 或 P0-A v3，不得接受 `allowed_origins`；legacy 插件只能进入无桥、无网络救援入口。
- `bridge_origins` 是宿主固定为 self 的不变量，禁止出现在 Manifest；桥接消息必须来自稳定本地 origin 的 main frame 并精确匹配 source origin。不得恢复 `identity.get_credentials`、`identity.credentials.read`、`window.BjtuService` 或 `app.http_request`。
- 公网 origin 必须按 connect/media/frame/navigation 最小声明；公共制品只允许 HTTPS，开发者模式仅允许 localhost HTTP，普通 origin 不得指向校园、私网、回环或链路本地地址。
- 插件更新必须保留增量授权、影子 KV migration、上一版本包和数据快照；删除清理失败必须保留 tombstone 并重试。
- `campus.request` 只能调用 registry 中 MIS/AA/VE 的 `GET`/`HEAD` 只读路径，且不得向插件暴露 Cookie、认证头或明文凭据。
- 仅 constitution 原则 I 严格列出的 Android Capability 可在首次/增量审阅后持久授权。事件最多 16 订阅/插件与 60 events/s/订阅；节点最多 4,096、深度 64、handle 30 秒；通用持久订阅遵循契约频率；动作必须幂等且不得记录敏感数据；后台 runtime 全局最多 4 个并提供持续通知与全部停止。系统 UI、运行时权限、后台定位和静默录音不得由持久授权绕过。
- `android.packages.read@1` 只返回契约字段，不返回 APK 路径、私有数据或图标；`android.settings.open@1` 只允许 `android.settings.*` 与可选 `package:` data，不得扩展为通用 Intent。
- 涉及生产配置、网络安全配置、权限、前台服务、数据迁移或删除操作时，先写执行计划并明确回滚。

## 8. Pull request / change expectations

- 保持变更范围小，按用户请求处理，不顺手重构无关模块。
- 开始修改前查看 `git status`；不要回退用户已有改动。
- 修改文档时同步更新 README 或本文件中受影响的命令和边界说明。
- 修改构建、依赖、数据库、权限、Open WebUI 资产管线时，在汇报中说明风险和验证结果。
- 提交前检查是否误包含生成文件、本机配置、密钥、APK/AAB、缓存或大型构建产物。
- CI/PR 模板和分支策略：TODO: confirm。

## 9. Definition of done

- `git status --short` 已检查，明确区分本次改动和用户已有改动。
- 只修改与任务相关的文件；涉及 Android 功能实现的任务允许修改必要的 Android 源码、测试和文档。
- 运行了与改动范围匹配的验证命令，或清楚记录未运行原因。
- 对失败命令记录失败类型、是否为已知基线问题，以及对本次改动的影响。
- 没有提交 secrets、本机配置、构建产物或生成缓存。
- 文档中的不确定信息使用 `TODO: confirm`，没有把猜测写成事实。
- 插件相关变更已证明符合 constitution 原则 VII 和 contract_v1；旧 spec/plan 中的冲突策略没有重新进入代码、文档、模板或测试。
- 最终汇报包含文件变更、主要规则、TODO/不确定项和验证命令结果。

## 10. When to use `PLANS.md`

复杂功能、风险重构、数据迁移、架构边界变化、构建/依赖升级、权限或安全敏感改动，以及需求不清的任务，应先根据 `PLANS.md` 写一个可执行、可恢复、持续更新的 `ExecPlan`。constitution 第 VI 条列出的高风险变更必须先有 ExecPlan；插件相关计划还必须通过原则 VII 的零信任基线检查。计划应先解决目标、范围、验证和回滚，再开始修改代码。


插件会话保活遵循 constitution 3.2.0：`android.session.keepAlive@1` 采用按 publisher+plugin 隔离的加密限时租约，acquire/renew 必须前台发起，后台可查询/释放；到期、更新/回滚、撤销、删除、登出、用户停止和系统限制均清理，恢复不能延长时限或绕过 FGS 限制。执行与验证见 `docs/plugin-session-keepalive-execplan.md`。`android.app.autoStart@1` 仅可放在 `capabilities.optional`，默认关闭，由可见主 Activity 触发打开主应用后的本地入口加载；不支持开机启动或永久保活。
