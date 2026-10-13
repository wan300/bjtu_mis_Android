# ExecPlan 模板

`ExecPlan` 是复杂工作的自包含、持续更新的执行计划。它必须让新的 Codex 会话或工程师在没有聊天上下文的情况下继续工作，并聚焦于交付可运行、已验证的行为，而不是只列代码编辑清单。

项目原则以 `.specify/memory/constitution.md` 为权威来源；本文件只定义 ExecPlan 写作和维护规范。如两者冲突，以 constitution 为准，并同步修正本文件。

适用场景：复杂功能、风险重构、Room 数据迁移、构建或依赖升级、Open WebUI 与 Android 桥接变化、第三方插件身份/runtime/origin/存储/校园代理、权限/安全敏感改动、架构边界变化，或需求仍有歧义的任务。

使用规则：

- `PLANS.md` 只维护 ExecPlan 的写作规范和模板，不长期保存某次任务的具体计划、执行日志或完成记录；具体任务计划应保存在当前会话、临时工作文件或用户明确指定的位置。
- 计划必须随发现、决策、失败验证和进度变化持续更新。
- 所有歧义必须在计划里明确解决；不能解决的写成 `TODO: confirm` 并说明阻塞影响。
- 使用苏格拉底式提问来澄清目标、约束和设计；不要假设任何未明确说明的事实。
- 每个里程碑都必须包含预期可观察行为和验证命令。
- constitution 第 VI 条列出的高风险变更必须先有 ExecPlan，并在计划中写明验证和回滚；第三方插件变更还必须逐项通过原则 VII 的 Manifest v3 / contract_v1 零信任基线。
- 插件计划 MUST 以 `docs/third-party-services.md` 和 Manifest v3 schema 为规范输入；带 superseded/legacy 标记的旧 spec、plan 或 API 只能提供历史背景，不得授权恢复 v1/v2、聚合 origin、远程桥、明文凭据读取或通用原生 HTTP。
- Android 自动化或通用原生计划 MUST 逐项记录首次/增量授权、Android runtime permission、系统 UI 前台 gate、后台 runtime 归属、持续通知、撤销/复审/删除清理、节点脱敏与过期、资源隔离、运行时配额、API 26/35 验证和版本回滚；不得超出 constitution 严格列举的 Capability，也不得扩展为通用 Android API。
- 默认验证命令按改动范围选择：Android 改动跑 `Set-Location android; .\gradlew.bat test`；构建/资源/打包相关跑 `Set-Location android; .\gradlew.bat assembleDebug`；Open WebUI 改动跑 `Set-Location android\open-webui; npm run test:frontend -- --run`；类型检查可跑 `npm run check`，但当前 Open WebUI 存在既有类型诊断基线失败，需记录。
- 插件平台改动还需运行 `web/platform` typecheck/unit/integration/e2e、共享 Manifest lint 和适用的 Android WebView instrumentation；本机环境无法执行的阻塞项必须交由 CI 并记录。
- 不要把执行计划当作一次性草稿；实现过程中要维护它。

## ExecPlan: <标题>

### 1. Title

一句话标题，包含目标模块或用户可见能力。

示例：`ExecPlan: <模块或能力> 的 <目标行为>`

### 2. Purpose and user-visible outcome

说明为什么要做，以及用户完成后能看到什么变化。

- 用户场景：
- 当前问题：
- 完成后的可观察行为：
- 成功标准：

### 3. Repository context

写清楚接手者必须知道的仓库事实。引用实际路径，但不要依赖聊天记录。

- Android 工程根目录：`android/`。
- 相关 Android 代码：
- 相关 Open WebUI 代码：
- 相关测试：
- 相关 fixture：
- 已知生成文件或不可提交文件：

### 4. Constraints and non-goals

列出必须遵守的边界和明确不做的事。

- 不修改：
- 不引入：
- 不提交：
- 安全/隐私限制：
- 插件 Manifest v3 / contract_v1 / Capability Registry / publisher identity / origin / bridge / storage / campus proxy 基线：
- 兼容性要求：
- 非目标：

### 5. Research notes

记录已经检查过的事实、命令和结论。发现新事实时追加，不覆盖重要历史。

- `git status --short`：
- README/文档依据：
- Gradle/npm 配置依据：
- 源码结构依据：
- 测试结构依据：
- 未确认事项：

### 6. Proposed design

描述要实现的设计，而不是只列文件。说明数据流、接口边界、错误处理和用户反馈。

- Android 数据流：
- UI 行为：
- Open WebUI/Capacitor 桥接：
- 第三方插件 runtime 与信任边界：
- Room/缓存/迁移影响：
- 错误和空状态：
- 与既有模式保持一致的点：

### 7. Files and components to change

列出预计改动的文件或组件。文件清单应足够具体，但不要扩大范围。

- `path/to/file`：改动目的。
- `path/to/test`：新增或更新验证。
- `path/to/fixture`：新增或更新测试样本。
- 生成文件处理：说明是否会产生，是否应忽略或提交。

### 8. Milestones

每个里程碑都要包含可观察行为和验证命令。

#### Milestone 1: <名称>

- 目标：
- 预期可观察行为：
- 验证命令：
- 完成状态：pending

#### Milestone 2: <名称>

- 目标：
- 预期可观察行为：
- 验证命令：
- 完成状态：pending

### 9. Step-by-step implementation plan

写成可以逐步执行的清单。每一步都应说明改什么、为什么改、如何确认。

1. 检查当前 worktree，记录用户已有改动，不回退无关文件。
2. 修改数据模型或解析逻辑，并保持与 `AppJson`、`ModuleEnvelope`、现有异常模式一致。
3. 修改 repository/provider 编排，确保 UI 不直接访问外部服务。
4. 修改 Compose UI 或 Open WebUI local-first 逻辑，复用现有状态和组件模式。
5. 添加或更新单元测试与 fixture。
6. 运行验证命令，记录通过、失败或已知基线问题。
7. 检查 `git diff`，确认没有生成文件、密钥、本机配置或无关源码改动进入本次变更。

根据具体任务替换以上步骤；不要保留不适用的步骤。

### 10. Validation plan

按风险列出必须运行的验证，并写出接受标准。

- Android 单元测试：`Set-Location android; .\gradlew.bat test`
  - 接受标准：
- Android 打包验证：`Set-Location android; .\gradlew.bat assembleDebug`
  - 接受标准：
- Open WebUI 前端测试：`Set-Location android\open-webui; npm run test:frontend -- --run`
  - 接受标准：
- Open WebUI 类型检查：`Set-Location android\open-webui; npm run check`
  - 接受标准或已知失败说明：
- 插件平台验证：`Set-Location web\platform; npm run typecheck; npm test; npm run test:integration; npm run test:e2e`
  - 接受标准：
- Manifest/WebView 安全验证：共享 lint、schema 一致性、Android JVM 与 API 26/35 instrumentation
  - 接受标准或外部环境说明：
- Android 自动化与通用原生验证：服务未启用、脱敏/过期、事件过滤/限流、持久恢复、前台优先、动作幂等无逐次 confirmer、系统 UI 前台 gate、无 raw URI、资源隔离、运行时权限和完整清理
  - 接受标准或外部环境说明：
- Android 自动化验证：服务未启用、脱敏/过期、事件过滤/限流、持久恢复、前台优先、动作幂等无逐次 confirmer、包字段、Settings Intent 和完整清理
  - 接受标准或外部环境说明：
- 手动验证：
  - 设备/模拟器：
  - 网络/账号要求：
  - 用户可见路径：

### 11. Progress log

持续追加，使用绝对时间或清晰顺序，不删除有用历史。

- YYYY-MM-DD HH:mm：创建计划，记录初始目标和约束。
- YYYY-MM-DD HH:mm：完成 <步骤>，观察到 <结果>。
- YYYY-MM-DD HH:mm：验证 <命令>，结果 <通过/失败>，失败原因 <原因>。

### 12. Decision log

记录会影响实现或维护的决策。

- Decision：
  - Context：
  - Options considered：
  - Chosen approach：
  - Consequences：

### 13. Risks and rollback plan

说明主要风险、检测方式和回滚策略。

- 风险：
- 影响：
- 如何发现：
- 缓解措施：
- 回滚步骤：
- 数据迁移回滚：TODO: confirm，如果任务涉及 Room 迁移必须补全。

### 14. Completion checklist

完成前逐项确认。

- [ ] 计划已包含目标、上下文、约束、设计、文件、里程碑、步骤、验证、风险和回滚。
- [ ] 所有歧义已解决，未解决项标为 `TODO: confirm` 并说明影响。
- [ ] 实现只触碰计划范围内文件。
- [ ] 已保护用户现有 worktree 改动，没有回退或覆盖无关源码。
- [ ] 已添加或更新必要测试和 fixture。
- [ ] 已运行适当验证命令，并记录结果。
- [ ] 已检查 `git status --short` 和 `git diff`。
- [ ] 没有提交 secrets、本机配置、构建产物、APK/AAB、`node_modules`、Open WebUI build、未批准的临时 schema 或缓存；如有 Room migration，已审查并提交 `android/app/schemas/` 对应版本历史。
- [ ] 最终汇报包含变更文件、主要行为、验证结果、已知失败和剩余 TODO。

## ExecPlan: OpenWebUI Local Agent tool argument compatibility

### 1. Purpose and user-visible outcome

Fix local-first OpenWebUI Agent tool calls that fail because models or OpenAI-compatible providers send argument objects, snake_case aliases, or near-miss field names instead of the current camelCase Android tool schema. Users should see fewer failed Agent workspace actions such as listing files, reading files, extracting archives, extracting documents, running JavaScript, and packaging results.

### 2. Constraints and non-goals

- Keep existing Android native tool names and camelCase schemas compatible.
- Do not rewrite the local-first tool loop or adopt OpenWebUI Default tool mode in this change.
- Do not add production logging of tool arguments.
- Do not change mail send confirmation, workspace path validation, write-directory limits, or homework submission safety.

### 3. Proposed design

- Normalize provider tool arguments in `android/open-webui/src/lib/local-first/agent.ts` before execution, accepting either JSON strings or plain JSON objects.
- Add Agent-tool alias normalization for common model output variants such as `archive_path`, `target_dir`, `output_path`, `content_markdown`, `timeout_seconds`, `timeout_ms`, `final_answer`, `filename`, and `file`.
- Return structured tool argument errors with the tool name, missing fields when inferable, and an example arguments object.
- Improve homework handoff prompt examples in `NativeAgentToolsPlugin.kt` so models see valid JSON arguments instead of only prose-style field hints.
- Clarify local file database tool descriptions in `registry.ts` so they are distinct from Agent workspace file tools.

### 4. Validation plan

- Run `Set-Location android\open-webui; npm run test:frontend -- --run`.
- Run `Set-Location android; .\gradlew.bat test`.
- Inspect `git diff` and `git status --short` to ensure no secrets, local config, APK/AAB, build output, `node_modules`, unreviewed temporary schema, or Open WebUI generated assets were included.

### 5. Rollback

Revert only the changes to this ExecPlan section, local-first Agent argument normalization/tests, native homework prompt text, and affected Android Agent parsing tests. No database migration or persisted data rollback is required.

### 6. Progress log

- Implemented local-first Agent argument parsing for JSON strings and plain objects.
- Implemented Agent tool argument alias normalization for native tool calls and inline XML fallback.
- Implemented structured missing-argument errors with `tool`, `missing`, and `expected_arguments_example`.
- Updated homework handoff examples and local-first file database tool descriptions.
- Added Open WebUI Vitest coverage and an Android JVM test for existing camelCase argument compatibility.
- Validation: `npm.cmd run test:frontend -- --run` passed, 9 files and 84 tests.
- Validation: Android Gradle target test and full `test` could not complete in this environment. Gradle wrapper initially hit network sandbox restrictions when downloading Gradle 9.0.0; after escalation, the target test still timed out without output. Gradle daemons were stopped after the timeout.
- Diff check: `git diff --check` passed with only CRLF conversion warnings.

### 7. Completion status

- Scope complete for the compatibility fix.
- No production logging added.
- No native tool names or public camelCase schemas removed.
- No mail send, homework submit, workspace path, or write-boundary safety rules changed.


插件会话保活遵循 constitution 3.2.0：`android.session.keepAlive@1` 采用按 publisher+plugin 隔离的加密限时租约，acquire/renew 必须前台发起，后台可查询/释放；到期、更新/回滚、撤销、删除、登出、用户停止和系统限制均清理，恢复不能延长时限或绕过 FGS 限制。执行与验证见 `docs/plugin-session-keepalive-execplan.md`。`android.app.autoStart@1` 仅可放在 `capabilities.optional`，默认关闭，由可见主 Activity 触发打开主应用后的本地入口加载；不支持开机启动或永久保活。
