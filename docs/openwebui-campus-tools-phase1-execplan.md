# ExecPlan: Open WebUI 校园查询与原生工具执行策略（第一阶段）

## 1. 目标与可观察结果

普通 Android Open WebUI 聊天可以查询系统状态、课表、考试、作业和聚合日程，无需作业工作区。查询默认 CacheOnly，用户明确请求 refresh 才联网。工具输出包含来源、同步时间、缓存/过期/截断和部分失败状态；查询失败不能解释为没有事务。

本阶段交付工具元数据、原生执行策略、工作区判断修复和 P0 只读工具。App 待办写入、页面导航、资源导入及其他 P1/P2 工具不在本阶段范围。

2026-10-12 后续：第二阶段待办写入、页面导航和提醒衔接已获授权，执行与回滚记录见 [第二阶段 ExecPlan](openwebui-campus-tools-phase2-execplan.md)。第一阶段完成记录保持不变。

## 2. 仓库上下文与初始状态

- 已检查 git status --short：仓库存在大量用户改动，主要涉及插件、自启、空教室、constitution、README、AGENTS 和 PLANS。本次不撤销、格式化或提交这些改动。
- 工具接口：android/app/src/main/java/cn/edu/bjtu/mis/data/agent/tools/AgentTool.kt；装配：di/AppContainer.kt；桥：openwebui/NativeAgentToolsPlugin.kt。
- 前端：android/open-webui/src/lib/local-first/{agent.ts,native-agent-tools.ts,tools/registry.ts}。
- 业务：ModuleRepository、SessionRepository、SyncRepository；缓存模式 CacheOnly/CacheFirst/NetworkFirst；ModuleEnvelope 含 syncedAt/sourceSystem/coverage。
- 原生策略目前没有统一校验；前端无工作区例外写死 agent_mail_。calendarDashboard 会吞部分异常，新的聚合工具不得直接复用此行为。

## 3. 约束与原则检查

- 遵守 constitution 3.2.0 的 I/II/III/IV/V/VI：原生确认、repository 边界、本地优先、可测试、无新依赖、先计划后实现。
- 第三方插件身份、contract_v1、Capability Registry、bridge origin、校园代理和持久授权均不修改、不复用为 Agent 授权。
- 不新增 Android 权限、后台自动化、Room migration、凭据访问或通用 HTTP 工具。会话状态仅返回最小状态，不返回凭据/Cookie/账号。
- 原生入口基于宿主元数据校验参数、工作区、前台、超时和大小，敏感读取/状态修改由原生可见对话框确认；拒绝和取消不执行。
- 保留已有邮件前端确认，并增加原生兜底（可能出现两次确认，后续可在验证后的单独变更中合并）。现有 native Agent 写入/删除/代码执行也纳入原生确认；拒绝不修改数据。
- 不新增持久授权或持久回执。命令调用按本次桥实例中的 requestId+规范化参数摘要去重，禁止摘要冲突；销毁清理，进程结束不承诺恰好一次。邮件发送不能被模型自动重试。
- 校园数据送往配置的模型服务需要明确提示和原生确认；第一版逐次确认，不做无期限授权。
- 无用户数据进入日志、fixture 或文档。不修改 assets/public 生成内容。

## 4. 设计与文件

- AgentTool 添加策略元数据，覆盖操作类型、数据域、确认、前台、超时、输入/输出上限。既有工具按名字分类；未知工具 fail closed。
- 新 AgentToolExecutor 统一执行校验/确认/限额/超时/幂等；参数验证支持本项目实际使用的 JSON schema 子集，未知类型失败。桥只接受注册的公开名字。
- 新 AcademicAgentTool 和 RepositoryAcademicAgentGateway：system.status、academic.timetable/exams/homework/agenda；Gateway 可 fake 测试。
- 日程分别读取课表、考试、作业、校园日历与原生 todos，保留各模块错误。输出使用明确 DTO，禁止完整原始 envelope/URL/cookie 泄漏。日期窗口默认本周，最大 31 天；条目上限 100；过期阈值明确为 24 小时策略值。
- system.status 返回缓存会话和各模块快照新鲜度，不主动登录或校验网络。
- 原生 listTools 提供元数据；前端按 requiresWorkspace 校验，不按名字推断，并在执行时检查工具确实在原生注册列表中。
- 测试新增在 data/agent/tools 与前端 tools/registry.test.ts。新文档说明工具契约和授权。

## 5. 里程碑与执行步骤

1. [完成] 只读检查与计划；保留初始 git status。
2. [完成] 元数据/策略与 JVM 测试：无授权、后台、错误参数、未知工具、重复请求、输出限额均有可观察拒绝。
3. [完成] 校园工具与 fake gateway/repository 测试：缓存/刷新、日期过滤、空与失败、部分成功、最小字段。
4. [完成] 桥/装配/前端判断和 Vitest：普通聊天能执行无工作区查询，工作区工具继续拒绝，邮件确认保留；13 文件、103 测试通过。
5. [完成] 完整验证与差异审查，记录环境限制及回滚；设备验证保留为明确的后续验收项。

## 6. 验证

- android: .\gradlew.bat test（先跑新增 targeted tests，再跑完整 test）。
- android/open-webui: npm.cmd run test:frontend -- --run。
- android: .\gradlew.bat assembleDebug（验证 Capacitor 桥与前端资产管线，不提交构建产物）。
- npm.cmd run check：已知基线有类型错误，若运行则区分新增和基线诊断。
- git diff --check、任务文件 diff、最终 status；不运行会写全项目的 lint/format。
- 真机 TODO: confirm：本机是否有可用 API 26/35 设备；验证原生对话框取消、后台切换、离线、会话失效、工具结果回到模型。缺少设备时明确报告未验证，不声称真机通过。

## 7. 风险与回滚

- 新原生确认会改变既有工具交互次数；测试保证拒绝无副作用。撤回仅本次工具策略/桥接改动，不撤回用户改动。
- CacheOnly 未命中必须给出 cache_missing 和 refresh 指引，不能返回伪空数据。网络失败保留原快照。
- 模型上下文数据最小化，禁止传入原始 SessionStatus、快照 payload、邮件 token 或资源预览 cookie。
- 无数据库迁移和持久授权；回滚移除新工具注册、恢复本次修改的代码块，保留用户已有文件及全部数据。过程生成物由构建管理，不纳入变更。

## 8. 决策与进度日志

- 2026-10-12：用户授权开始第一阶段；计划先于源码修改创建。本阶段将原生 todos 作为聚合日程只读数据源，待办迁移/写入及后台提醒留到第二阶段。
- TODO: confirm：模型供应商粒度持久授权、跨进程命令回执属于后续设计，本阶段不实现且不依赖。
- 2026-10-12：实现五个无工作区工具；系统状态仅为宿主缓存会话状态，不声称分别验证每个校园服务。日程按模块返回，月历跨月部分失败保留其他月份。课表复用教学周/星期解析 helper，返回周期候选，不假设学期起始日期。
- 2026-10-12：增加本地顶层 origin 校验、命令摘要幂等、原生可取消确认、前台复查、schema/深度/节点/字节/超时限额。code.run_js 的 input 明确允许自由 JSON 对象，避免严格校验破坏既有输入契约。
- 2026-10-12：初轮 targeted JVM 15 项通过；随后增加 origin、配额、规范化摘要和 repository 最小字段/无网络/自定义课表测试，等待完整 test 结果。
- 2026-10-12：普通 Vitest 首次因沙箱无法写入 node_modules/.vite/_svelte_metadata.json 报 EPERM。使用忽略目录中的临时独立 cache 配置后通过；自动审批允许缓存访问后，标准 npm.cmd run test:frontend -- --run 也通过（13 文件、103 测试）。临时配置将在完成时删除。
- 2026-10-12：完整 Gradle 首次被下载网络的 Permission denied 阻断，自动审批后重跑 test assembleDebug，进行最终验证。原生/前端构建按单个 Gradle invocation 串行执行。
- 2026-10-12：adb devices -l 无设备，真机确认 UI/API 26/35 尚未验证。构建脚本自动升级 pyodide-lock.json 中 black 版本，此文件初始无用户改动，构建完成后只撤回该生成变化。
- 2026-10-12：最终 .\gradlew.bat test assembleDebug --console=plain 通过，debug/release 各 73 个测试文件、494 项 JVM 测试，均零失败/错误/跳过；新增 20 项测试（工具策略 11、校园查询 7、repository 集成 2）包含在内。debug APK 打包通过。现有插件页面的 deprecated WebView API 与 Gradle 10 兼容性警告未作无关修改。
- 2026-10-12：标准前端测试通过（13 文件、103 项）。npm run check 未执行：项目已有 Svelte/TypeScript 类型诊断基线，本次以 Vitest 与完整 Vite/Android 打包验证为准，未声称全项目类型检查通过。
- 2026-10-12：已删除忽略目录中的临时 Vitest 配置；Pyodide 锁文件恢复为初始干净版本，git diff 无此文件变更。只回退本次构建产生的该项变化，无用户源码回退。

### 实际变更清单

- 新增 AgentToolPolicy.kt（元数据、原生 executor、验证及摘要回执）、AcademicAgentTool.kt（校园 gateway 与五个工具）。
- 修改 AgentTool.kt、CodeTool.kt、AppContainer.kt、NativeAgentToolsPlugin.kt（接入策略、原生确认、本地顶层 origin 校验和工具装配）。
- 修改前端 agent.ts、native-agent-tools.ts、tools/registry.ts（普通 Android 聊天启用工具、元数据、请求 ID、按注册信息判断 workspace）。
- 新增 AgentToolPolicyTest.kt、AcademicAgentToolTest.kt、native-agent-tools.test.ts、tools/registry.test.ts；修改 agent.test.ts。
- RepositoryLoadStrategyTest.kt 原有用户测试全部保留，只增加 imports 和两个 repository 集成测试。
- android/README.md 保留原有用户修改并更新 Agent 工具说明；新增本 ExecPlan 和 docs/openwebui-campus-tools.md。
- 无新增依赖/权限/数据库迁移/第三方插件变更；未提交 APK、缓存或 assets/public。

## 9. 完成清单

- [x] 计划包含目标、范围、安全边界、验证和回滚。
- [x] 实现与必要测试完成。
- [x] JVM / Vitest / assembleDebug 的结果及限制已记录。
- [x] 差异审查确认保护已有改动，无 secrets、依赖或构建产物进入本次源码变更。

后续验收：有设备时验证原生确认取消/离开前台、离线与失效会话、以及 API 26/35 行为。本阶段未实施持久模型供应商授权、待办迁移/写入或跨进程恰好一次执行。
