# ExecPlan: Open WebUI 原生待办、页面导航与提醒衔接（第二阶段）

## 1. 目标与可观察结果

延续第一阶段工具元数据与原生策略。普通 Android Agent 聊天可以查询、创建、编辑、完成和删除 App 原生待办；确认后写入的内容出现在原生日历。Agent 可以打开白名单校园页面及缓存中可验证的作业详情，并为指定待办设置或取消一次性提醒。提醒通知点击返回原生日历；待办编辑、完成或删除后旧提醒失效。

本计划按 PLANS.md 编写，先于第二阶段源码修改。第一阶段执行记录见 openwebui-campus-tools-phase1-execplan.md。

## 2. 上下文与已有改动保护

- 2026-10-12 已检查 git status --short：第一阶段全部改动仍未提交；另有插件、自启、空教室、constitution、MainActivity、README、测试等大量用户改动。只增量修改本阶段需要的代码块，不 reset/checkout/stash 或全项目格式化。
- 原生待办为 Room UserTodoEntity，SyncRepository/ModuleRepository 已有增删改查，CalendarScreen 使用它们。数据库版本 12，本阶段不迁移。
- NativeAgentToolsPlugin 统一执行 AgentToolExecutor，已有原生确认、前台校验、参数限额及桥实例 requestId 去重。
- BjtuMisApp 使用 Compose 内部路由；MainActivity 的通知入口已有 EXTRA_OPEN_ROUTE。现有 NativeAndroidToolsPlugin 通用提醒与 WebUI IndexedDB tasks 不属于原生待办，不自动迁移。
- 通知已有 POST_NOTIFICATIONS 声明、WorkManager 和 DataStore 依赖；不新增权限、依赖或精确闹钟。

## 3. 边界与设计

- 遵守 constitution I–VI：敏感读取及所有写入/导航仍由原生前台对话框逐次确认，拒绝无副作用。插件原则 VII 的 contract_v1、origin、持久授权和后台 runtime 不变，不复用插件授权。
- 新工具无需工作区：todo.list/create/update/delete、reminder.set/cancel、app.open_page。命令继续使用宿主 requestId，返回真实待办 ID 和明确结果。编辑/删除使用 expectedUpdatedAt 防止覆盖用户期间修改；不存在 ID 不得隐式创建。
- 字段限额与格式由宿主校验：日期 ISO LocalDate，标题/备注有长度限制，列表有数量限制；无网络、无凭据、无任意 URI/Intent。待办笔记只在用户确认的待办查询返回。
- 提醒独立 DataStore 仅保存待办 ID、更新时间版本、触发时间、随机令牌和状态；WorkManager 输入只保存 ID/令牌，不复制标题/备注。唯一工作名按待办 ID 替换，持久记录先写入、调度失败明确标记失败，不假称已安排。
- Worker 重新读取 Room 和提醒记录，验证令牌、版本、未完成、触发时间和通知权限；无效/取消/过期提醒不得发送。WorkManager 为非精确提醒，可能因系统省电延迟；超过限定宽限期失效。完成/编辑/删除在 repository 边界撤销提醒记录并取消工作与已发通知，涵盖原生日历操作。取消失败也先撤销有效令牌。
- 设置提醒时只检查通知权限与频道，不从后台请求系统权限；未启用返回 notification_permission_required 和可操作指引。沿用 App 已有权限请求入口。
- 导航通过宿主内存请求与 Compose 消费确认，不向模型开放原始 route。页面白名单：课表、考试、日历、作业、课程资料；作业详情需缓存中的 homeworkId+courseId。宿主不自动刷新、提交作业或下载资源。导航结果在切页前交回桥，UI 确认消费；回到 Agent 保留现有 WebView。
- 原生日历展示关联提醒的状态和取消入口；新待办进入页面时从 Room 重新加载。后台通知仅读取当前待办并导航到日历。

## 4. 组件与文件

- data/agent/tools：新增 NativeTodoAgentTool、AppNavigationAgentTool 与可 fake gateway；扩充 AgentToolPolicy。
- data/todo：新增纯提醒协调逻辑、DataStore、Android WorkManager scheduler/worker；用于跨进程重验与取消。
- data/repository/Repositories.kt：待办条件更新与提醒清理 hook；di/AppContainer.kt 装配。
- openwebui/NativeAgentToolsPlugin.kt：公开工具映射与确认名称；ui/BjtuMisApp.kt 消费受限导航；CalendarScreen.kt 提醒状态/取消。
- JVM 单元测试、local-first Vitest：CRUD、并发版本冲突、错误输入、确认拒绝、白名单与 ID、提醒替换/取消/失败/失效/权限。
- docs/openwebui-campus-tools.md、android/README.md：契约、通知约束与使用说明。assets/public、pyodide 构建变化和缓存不进入源码变更。

## 5. 里程碑与步骤

1. [完成] 阅读 PLANS/constitution/既有桥、待办、路由和通知实现，创建计划。
2. [完成] 实现待办 gateway/tools 和 repository 条件更新；fake/repository 测试证明原生数据共用、缺失/冲突无写入。
3. [完成] 提醒持久记录、WorkManager 重验、repository 清理、日历展示；单测覆盖替换/取消/失败/权限、配额、迟到和完成竞态。
4. [完成] 导航白名单、Compose 消费与桥接；单测和 Vitest 验证无需 workspace、命令策略、错误路由和真实 repository 精确缓存。
5. [完成] 完整 JVM/Vitest/assembleDebug 验证、diff 审查、文档和结果已记录；设备验收仍列为后续限制。

## 6. 验证与接受标准

- 定向 JVM 测试先验证新工具/提醒/导航/原有策略，再运行 android 下 .\gradlew.bat test assembleDebug --console=plain；全部 JVM 通过且 APK 编译打包成功。
- android/open-webui 下 npm.cmd run test:frontend -- --run：所有前端测试通过，包括普通聊天工具注册和执行。
- npm run check 的既有 Svelte/TypeScript 诊断单独记录，不声称全项目类型检查通过。
- git diff --check，审查本阶段文件增量，不回退第一阶段及用户改动；构建前保存 pyodide-lock.json 基线，构建后只恢复本次生成变化。
- 设备验收：原生确认取消、后台 gate、创建→日历→编辑/完成→提醒清理、允许/拒绝通知、进程重启、通知点击与 Agent 返回；API 26/35。TODO: confirm 当前 adb 设备可用性；无设备则明确记录未验证，不能把 JVM 等同系统通知验收。

## 7. 风险与回滚

- Room 与 WorkManager/DataStore 无跨库事务：以持久有效令牌和 Room 版本重验阻止旧通知；调度失败返回失败状态，Worker 不凭旧输入发送。提醒不保证精确时间或跨进程恰好一次通知。
- 桥 requestId 仍仅桥实例去重，不承诺跨进程恰好一次创建待办；不引入无期限授权。用户可以通过原生日历编辑/删除本次创建的待办。
- 回滚只撤回本阶段新增工具注册/代码块，先取消本阶段唯一工作前缀并清空提醒 DataStore；保留所有 Room 待办和用户既有数据。无数据库降级或迁移。
- 不触碰第三方插件、生产配置、凭据或既有通用通知；撤回时不得批量撤销用户其他 worktree 改动。

## 8. 进度与决策日志

- 2026-10-12：用户授权第二阶段，计划先于源码实现创建。选择复用 Room 待办并独立存储最小提醒记录，不迁移 WebUI tasks、不扩展通用 Intent。
- 2026-10-12：查明原生 CalendarScreen 的所有待办写入经 ModuleRepository，提醒清理必须落到 repository 而非只在 Agent wrapper；Worker 必须重验当前待办版本。
- 2026-10-12：实现 7 个工具；待办更新版本从秒级改为纳秒级单调时间，避免同秒编辑被当作同一版本。待办 mutation、提醒设置与发送共用宿主 mutex，关闭先清理再写入之间的调度竞态；不改变其他模块同步时间格式。
- 2026-10-12：发现校园日历 CacheOnly 缺失会阻止原生待办呈现，补充本地月历容器并显式显示校园日历/作业/考试缺失状态；不伪造校园日程，不写回缓存。
- 2026-10-12：前端测试通过，13 文件 104 项。定向 JVM 首轮 45 项中 1 项失败：空白必填参数被旧 helper 抛出的 IllegalArgumentException 分类为操作失败；已修正为 invalid_arguments，等待重跑。
- 2026-10-12：Gradle 首次下载受沙箱 Permission denied 阻断；自动审批允许测试所需缓存/下载后编译通过并运行上述定向测试。adb devices -l 无设备，API 26/35 与系统通知 UI 验收尚不可执行。
- 2026-10-12：定向 JVM 重跑 46 项通过；随后增加提醒配额/并发完成两项及真实 repository 导航缓存一项，由完整测试覆盖。资料目录导航按实际页面的 courseId/folderId/全部分类精确匹配缓存，拒绝仅有目录树 ID 但没有列表的目标。
- 2026-10-12：待办编辑/删除和提醒的原生确认展示当前标题、日期、完成状态及完整参数，避免用户只看到模型提供的数字 ID。此摘要只在宿主对话框显示，不额外回传模型。
- 2026-10-12：最终 Vitest 13 文件、104 项通过。完整构建的 debug 76 文件、518 项 JVM 零失败，assembleDebug 完成；release 出现未修改的 CourseSelectionRunnerTest.stopClearsPendingCaptcha 失败，正在核对及重跑，不将其隐藏为全绿。
- 2026-10-12：资产同步完成后恢复构建前保存的 pyodide-lock.json，已确认该文件 git status 干净。只撤回构建脚本自动升级 black 的版本/文件名/hash 三项，未回退任何用户源码。
- 2026-10-12：完整首轮最终 release 518 项中 1 项失败，定位至 CourseSelectionRunnerTest.kt:214 的 stopping 断言；选课源码/测试无本次 diff。其 stop() 完成验证码 waiter 后与后台 finally 复位状态的顺序存在竞态疑点（源码推断），本阶段不扩大到选课重构。正以 test -x buildOpenWebUi -x syncOpenWebUiAssets 重跑全部 JVM；已完成的生产前端构建、资产同步和 APK 不重复执行。
- 2026-10-12：完整 JVM 重跑 BUILD SUCCESSFUL（1m33s），debug/release 最终各 76 文件、518 项，零失败/错误/跳过。首轮 debug 已通过，重跑时 debug 为 UP-TO-DATE；失败的 release 全套重新执行并通过。新增 24 项 JVM（待办 6、导航 4、提醒 10、repository 4），前端新增 1 项注册/回执测试。
- 2026-10-12：assembleDebug 在首轮完成，已核对 app-debug.apk 时间为本次构建（忽略目录，不提交）；完整 Vite/资产管线成功。最终 Vitest 13 文件、104 项全过。npm run check 未执行：已有 Svelte/TypeScript 类型基线诊断，本阶段不声称全项目类型检查通过。
- 2026-10-12：最终 git diff --check 通过，git status 已审查；没有新增依赖/权限/数据库版本变化，没有本次生成文件、APK、凭据或缓存进入待提交源码。第一阶段及原有插件/自启/空教室等用户改动均保留。未修改 MainActivity、Manifest、PLANS 或 constitution 的已有用户代码块。

### 实际变更清单

- 新增 data/agent/tools/NativeTodoAgentTool.kt、AppNavigationAgentTool.kt，修改 AgentToolPolicy.kt、AppContainer.kt、NativeAgentToolsPlugin.kt，装配 7 个无工作区原生工具和确认摘要。
- 新增 data/todo/TodoReminders.kt、AndroidTodoReminders.kt、data/repository/TodoMutationException.kt；修改 Repositories.kt、CalendarDashboard.kt，支持条件写入、共享锁、提醒清理和离线待办呈现。
- 修改 BjtuMisApp.kt、CalendarScreen.kt、CourseResourcesScreen.kt、HomeworkScreen.kt，消费类型化导航、首屏缓存加载、提醒状态及取消入口；通知使用独立 PendingIntent action/request code，避免其他通知覆盖跳转目标。
- 新增 NativeTodoAgentToolTest.kt、AppNavigationAgentToolTest.kt、TodoRemindersTest.kt；RepositoryLoadStrategyTest.kt 仅增量增加 4 项测试及 imports，保留第一阶段及用户原有测试。
- 修改前端 tools/registry.ts 的 WebUI task 描述并扩充 registry.test.ts；不迁移 IndexedDB tasks。
- 更新 android/README.md、openwebui-campus-tools.md、第一阶段计划的后续引用与本计划。

### 设备与剩余不确定项

- 2026-10-13：Huawei Android 12/API 31 真机已通过实际 Capacitor 原生确认取消/允许、待办写入/同请求去重、原生日历导航及返回 Agent、Room 条件写入冲突、真实 WorkManager 通知投递与完成后清理；全套 33 项 instrumentation 通过。校园缓存采用明确标记的离线合成数据，独立 .debug 测试包结束后已清理。详见 docs/device-qa-dev-push-execplan.md。
- TODO: confirm：API 26/35 特有权限与系统行为由 dev CI 验证；系统重启/强停恢复、通知点击以及真实校园账号/真实模型的完整交互尚未在本轮实测，不将 API 31 已通过场景扩大为这些保证。
- TODO: confirm：既有选课 stopping 断言偶发竞态的独立修复；本阶段未改选课模块，不影响新增工具相关测试全部通过的结论。
- 提醒是非精确系统调度；进程在持久认领与实际通知之间终止可能漏发，明确不承诺精确时间或跨进程恰好一次。

## 9. 完成清单

- [x] 计划包含目标、范围、边界、验证和回滚。
- [x] 实现及必要测试完成，第一阶段和用户改动保留。
- [x] JVM/Vitest/打包结果与设备限制已记录。
- [x] 文档、diff/status 和生成物检查完成。
