# BJTU MIS Android

这是 BJTU MIS 的原生 Android 工程。应用不依赖 PC 本地 HTTP 服务，也不需要云端自建后端；它直接访问 BJTU CAS、MIS、AA、VE、Coremail 等系统，并在设备本地保存加密凭据、Cookie 与 Room 数据快照。

智能助手入口已经统一为嵌入式 Open WebUI Agent。旧的独立原生作业 Agent 任务系统、Agent Room 表、原生前台 Agent 服务、模型配置和 API Key 存储已移除。

项目原则以仓库根目录 `.specify/memory/constitution.md` 为权威来源；本 README 只记录 Android 工程事实、命令和关键路径。

## 代码组织

主要模块页面按功能放在 `ui/screens/` 的 `ProfileScreens.kt`、`TimetableScreen.kt`、
`ScoresScreen.kt`、`CalendarScreen.kt`、`HomeworkScreen.kt` 和 `EmptyRoomsScreen.kt` 等文件中。
共享加载组件位于 `ModuleScreenComponents.kt`，日历展示计算位于 `CalendarPresentation.kt`；
日历数据聚合由 `ModuleRepository.calendarDashboard` 负责。就业仓库以 `ModuleLoadStrategy`
作为唯一缓存策略参数。Open WebUI Agent 的必填字段从当前工具 schema 派生。

## 打开工程

1. 用 Android Studio 打开 `android/`。
2. 等待 Gradle 同步完成。
3. 运行 `app` target。

## 命令行

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

正式版本当前为 `v1.4.2`（`versionCode = 9`），更新内容见
[v1.4.2 更新说明](../docs/release-notes-v1.4.2.md)。构建 Release 前，将
`release-signing.properties.example` 复制为本机的 `release-signing.properties`，
并填写 `v1.3.1` 使用的原 keystore、alias 和口令：

```powershell
Copy-Item release-signing.properties.example release-signing.properties
.\gradlew.bat assembleRelease
```

真实属性文件和 keystore 已由仓库忽略规则排除，不得提交。也可以改用
`BJTU_RELEASE_STORE_FILE`、`BJTU_RELEASE_STORE_PASSWORD`、
`BJTU_RELEASE_KEY_ALIAS`、`BJTU_RELEASE_KEY_PASSWORD` 和可选的
`BJTU_RELEASE_STORE_TYPE` 环境变量。缺少完整签名配置时，Release 构建会明确失败，
避免误上传未签名 APK。

也可以直接使用 Android Studio 的 **Build > Generate Signed App Bundle or APK**；
构建脚本会识别向导注入的临时签名参数，不要求额外创建
`release-signing.properties`。选择与旧版相同的 keystore 和 alias，才能保持覆盖升级兼容。

`assembleDebug` 和 `assembleRelease` 会在 `preBuild` 阶段自动构建嵌入的 Open WebUI 前端：

1. 在 `open-webui/` 执行 `npm ci`。
2. 使用 `ENABLE_MOBILE_CLIENT=true` 和 `ENABLE_MOBILE_NATIVE_FEATURES=true` 执行 `npm run build`。
3. 将 `open-webui/build` 同步到 `app/src/main/assets/public`。
4. 删除 sourcemap，并生成空的 `cordova.js` / `cordova_plugins.js` 兼容文件。

前端单独检查：

```powershell
Set-Location open-webui
npm ci
npm run check
npm run test:frontend -- --run
```

### 打包时生成文件缺失

如果 `compileReleaseKotlin` 报 `Internal compiler error`，并在异常链中出现
`NoSuchFileException: .../build/generated/ksp/release/java/.../AppDatabase_Impl.java`，
先确认 Android Studio 和命令行没有同时构建同一个工作区。KSP/Room 生成文件与
Open WebUI 输出会被构建任务重写，并行启动多个构建可能使另一构建读到缺失或不完整的文件。

等待或取消已有构建后，单独重试原打包任务；若仍报相同的生成文件缺失错误，可在
`android/` 下依次执行以下命令，再通过原签名配置或 Android Studio 签名向导打包：

```powershell
.\gradlew.bat --stop
.\gradlew.bat clean
.\gradlew.bat assembleDebug
```

`clean` 会清理构建输出，随后构建会重新生成 Room 实现与 WebView 资产。不要手工维护
`AppDatabase_Impl.java`，也不要通过替换正式签名密钥处理编译错误。

## 第三方插件安全基线

第三方插件只使用 Manifest v3 / contract_v1。权威规则位于仓库根目录
`docs/third-party-services.md` 和 constitution 原则 VII，并覆盖旧 v1/v2 spec、plan、
API 与兼容说明。

- 新客户端拒绝安装、更新和运行 v1/v2 与 P0-A v3，拒绝 `allowed_origins`；旧数据只通过无桥、
  无网络的救援 WebView 访问。
- sandbox origin 由 plugin ID 与不可变 publisher subject 决定，不随 commit 改变；
  bridge 只注入稳定本地 origin 的 main frame，并要求精确 source-origin 匹配。
- WebView 同时支持 `DOCUMENT_START_SCRIPT` 与 `WEB_MESSAGE_LISTENER` 才允许运行 contract_v1；
  不使用 `onPageFinished` 降级注入。
- `WEB_MESSAGE_ARRAY_BUFFER` 可用时握手提供 ArrayBuffer 与 Base64URL 两种二进制模式并
  首选 ArrayBuffer；缺失时使用 48 KiB、无填充、逐片 ACK 的 Base64URL 兼容模式，不因此
  关闭插件。插件管理中的导入预检和“插件运行环境诊断”页实时显示 WebView provider、版本、
  三项 feature 与协商模式。
- 插件生成的 Blob/Cache 数据逐片校验 SHA-256 并写 app-private 临时文件；网络图片继续由
  原生 `network.request` 资源 handle 直接 `cache.promote`，不进入 JavaScript/Base64。
- connect/media/frame/navigation origin 独立声明；bridge origin 由宿主固定为 self，
  禁止出现在 Manifest；
  远程 frame 无桥、第三方 Cookie、顶层导航、下载、弹窗或多窗口。
- 插件重要数据使用 publisher+plugin 隔离的 AES-GCM KV/Blob/Cache；更新通过影子
  migration、上一版本包和索引快照完成原子切换与回滚。
- 公共 API 只通过 `@bjtu-mis/plugin-sdk` 的 protocol v2 调用版本化 Capability，
  不公开 `window.BjtuService` 或底层 transport。
- 校园访问只允许 MIS/AA/VE 的 `campus.request` 只读 registry。运行时不提供
  `identity.get_credentials`、`identity.credentials.read` 或 `app.http_request`。

## Agent 集成

- `OpenWebUiAgentScreen` 在 Compose 内嵌 `OpenWebUiAgentFragment`，通过 Capacitor WebView 加载打包后的 Open WebUI。
- Fragment 注册 `NativeSsePlugin`、`NativeWebSearchPlugin`、`NativeSecureStorePlugin`、`NativeAndroidToolsPlugin`、`NativeAgentToolsPlugin` 和 `NativeHttpPlugin`。
- 作业列表中的“Agent 协助”按钮会写入 `NativeAgentHomeworkHandoffStore`，然后跳转到 Open WebUI Agent。
- Open WebUI 启动后消费 pending handoff，生成作业分析草稿，并在请求参数中携带 `agent_workspace_id`。
- Android 端自动下载作业附件并导入 Agent workspace。压缩包不在 handoff 阶段自动解压，Agent 需要通过 `agent_archive_extract` 按需解压到 `work/attachments/`。当前支持 `zip`/`jar`、`tar`、`tar.gz`/`tgz`、`tar.bz2`/`tbz2`、`gz` 和 `bz2`，并限制路径穿越、条目数量、单文件大小和总大小。
- Android 普通聊天自动进入 local-first agent loop；系统状态、校园查询和邮件工具不需要 workspace，文件、归档、文档、代码和结果打包工具需要 workspace。前后端均依据原生注册元数据判断。
- 原生入口统一校验参数、工作区、前台、确认、超时和输入/输出限额。校园/邮件读取会提示结果可能发送给配置的模型服务；状态修改必须原生逐次确认。`agent_mail_send` 同时保留既有 Open WebUI 确认弹窗；作业 Agent 不会自动提交课程平台作业。

## 原生工具

`NativeAgentToolsPlugin` 当前向 Open WebUI 暴露以下工具名：

- 工作区文件：`agent_file_list`、`agent_file_read`、`agent_file_write`、`agent_file_delete`
- 归档：`agent_archive_extract`、`agent_archive_create_zip`
- 文档：`agent_document_extract_pdf`、`agent_document_extract_docx`、`agent_document_generate_pdf`、`agent_document_generate_docx`
- 代码：`agent_run_javascript`
- 邮件：`agent_mail_list_folders`、`agent_mail_list_recent`（支持最近天数或显式日期区间）、`agent_mail_read`、`agent_mail_mark_read`、`agent_mail_digest_context`、`agent_mail_search_contacts`、`agent_mail_save_draft`、`agent_mail_send`
- 结果：`agent_package_results`
- 校园只读：`agent_system_status`、`agent_academic_timetable`、`agent_academic_exams`、`agent_academic_homework`、`agent_academic_agenda`。默认仅查缓存，支持原生确认后的 `refresh`；详见 [工具契约](../docs/openwebui-campus-tools.md)。
- 原生待办：`agent_todo_list`、`agent_todo_create`、`agent_todo_update`、`agent_todo_delete`；与原生日历共用 Room，编辑/完成/删除撤销关联提醒，条件写入防止覆盖并发修改。
- 提醒：`agent_reminder_set`、`agent_reminder_cancel`，需要通知许可，使用持久令牌和 WorkManager 非精确调度；原生日历可查看状态/取消，通知点击回到日历。
- 页面：`agent_app_open_page`，仅白名单模块及缓存可验证的作业详情/资料目录。上述工具无需工作区，敏感读取、命令及导航仍逐次原生确认；WebUI tasks 独立保存，不自动迁移。执行与回滚见 [第二阶段 ExecPlan](../docs/openwebui-campus-tools-phase2-execplan.md)。

## 关键路径

- `app/src/main/java/cn/edu/bjtu/mis/`：Android 应用源码。
- `app/src/main/java/cn/edu/bjtu/mis/openwebui/OpenWebUiAgentFragment.kt`：嵌入式 Open WebUI Fragment 与 Capacitor 插件注册。
- `app/src/main/java/cn/edu/bjtu/mis/openwebui/NativeAgentToolsPlugin.kt`：Open WebUI 到 Android 原生 Agent 工具的桥接。
- `app/src/main/java/cn/edu/bjtu/mis/openwebui/NativeAgentHomeworkHandoffStore.kt`：作业上下文的一次性 handoff。
- `app/src/main/java/cn/edu/bjtu/mis/data/agent/tools/`：工作区、文件、归档、文档、代码、邮件和结果打包工具。
- `app/src/main/assets/bjtu_captcha_crnn.pt`：验证码识别模型。
- `open-webui/`：嵌入到 Android WebView 的 Open WebUI 前端源码。
- `open-webui/src/lib/local-first/`：local-first provider、Agent loop、Android 原生工具注册和 homework handoff 逻辑。
- `app/src/test/resources/fixtures/`：解析器与 Provider 单元测试 fixture。
- `docs/android-local-agent-design.md`：当前 Agent 集成边界说明。

## 数据迁移

- Room 数据库当前版本为 12。
- `MIGRATION_11_12` 将插件记录区分为 `legacy_v1_v2`、`legacy_v3_p0a` 与
  `contract_v1`，新增 granted capabilities、contract profile、派生 runtime floor
  和独立 marketplace 元数据；旧 runtime 被停用但不会删除插件包或数据。
- `MIGRATION_10_11` 增加插件 publisher subject、data schema、兼容状态、验证级别、上一版本元数据和删除 tombstone；旧插件记录迁移为 `legacy_disabled`，不会自动删除旧包或数据。
- `MIGRATION_6_7` 仍负责删除旧原生 Agent 的 `agent_tasks`、`agent_steps`、`agent_artifacts` 和 `agent_messages` 表。
- 应用启动时会调用 `clearLegacyNativeAgentConfiguration`，清理旧 Agent API Key、DataStore 设置和 Android Keystore alias。

## 本地文件

`local.properties`、`release-signing.properties`、keystore、Gradle 缓存、APK/AAB、release 输出、`open-webui/node_modules/`、`open-webui/build/`、`.svelte-kit/` 和 `app/src/main/assets/public/` 都是本机、秘密或构建生成内容，不应提交。`app/schemas/` 是例外：其中用于迁移验证且经审查的 Room schema 版本历史应随对应 migration 提交。

第三方插件可声明 `android.session.keepAlive@1`（runtime 3），首次/增量授权后在前台申请/续租 MIS 会话保活，后台查询/释放。单租约最多 60 分钟，支持通知停止和撤销清理；详细协议见 [`docs/third-party-services.md`](../docs/third-party-services.md#插件-mis-会话保活runtime-3)。

`android.app.autoStart@1`（runtime 4）只能作为可选声明，默认关闭。用户明确授予后，打开主应用且主 Activity 可见时宿主可加载插件本地入口，后台实例全局最多四个，持续通知可停止；不会开机启动、永久保活或扩大无障碍访问。详见 [`docs/third-party-services.md`](../docs/third-party-services.md#打开主应用时可选自启runtime-4)。
