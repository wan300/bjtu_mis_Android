# ExecPlan: 当前改动真机验收后提交到 origin/dev

## 目标与范围

用户于 2026-10-13 授权：对当前未提交和未推送的更改进行真机实际操作测试，通过后提交并推送远端 dev。目标为 GitHub origin/dev；Gitee 是另一远端，本次不改变其分支。当前检出 codex/plugin-app-autostart，HEAD 55ee861，origin/dev d279c5c 包含额外一条插件详情/更新修复。必须保留该远端提交和当前全部有效源码改动，禁止 force push。

当前更改包含：插件 optional-only app.autoStart 与持久 runtime/权限策略、插件 README/增量更新 UI、空教室数据与筛选、Open WebUI 第一/二阶段工具元数据/原生策略/校园查询/原生待办/导航/提醒，以及对应文档、契约生成物和测试。旧 .codex/、tmp_media3_ui/ 截图与回滚材料保留在本地，不作为源码提交。

## 初始事实与设备

- 已检查 git status、分支、远端与未推送提交。origin/dev 的 d279c5c 已存在于当前 worktree 的多数文件内容中，需要按内容核对后切换到 dev，禁止丢失任何额外修改。
- ADB 首轮无设备；用户要求重扫后已连接 Huawei ADA_AL00U，Android 12 / API 31。设备序列号仅写入本地忽略目录的执行脚本，不进入提交文档。
- 已确认 debug 构建已有 .debug applicationIdSuffix，真机只有正式包，直接复用 cn.edu.bjtu.mis.debug 独立安装，无需修改 Gradle 或新增 .qa 变体。
- 手机安装有正式 cn.edu.bjtu.mis。不得卸载、清数据或读取凭据/Cookie/邮件正文；安装使用独立测试包和测试数据。若需校园账号或模型配置，必须由用户在手机直接操作，不能要求发送凭据。
- 现有 Android instrumentation 包含 Room 11→12、WebView v3 origin/main-frame/资源/KV/权限测试、会话保活、Compose 基础、空教室筛选和 README 长要求滚动。
- 本机真机为 API 31，API 26/35 的特有系统行为不能宣称通过，继续由现有阻塞 CI 矩阵验收；记录此限制。

## 执行设计与保护

1. 在 runtime/device-qa-20261013/ 保存 HEAD、工作区 diff、修改文件与源码备份（忽略目录，不提交）。不 stash 全目录、不 reset --hard、不清理临时目录。
2. 对 origin/dev 的 10 个文件逐项比较，确认已包含的代码与剩余差异；切换 dev 时由 Git 的覆盖保护拒绝潜在丢失。如拒绝，先在临时副本完成三方合并并保持可恢复，再测试最终合并源码。
3. 若现有 debug 与正式包同 ID，增加仅用于验收的显式 Gradle property，派生 applicationId 后缀 .qa；默认生产/debug 构建行为不变。独立包使用独立 Room、Keystore、DataStore、WorkManager 与 WebView 数据。临时 APK、测试结果与截图留在忽略目录；测试包结束后仅清理本次创建的测试数据/插件/提醒，不卸载正式包。
4. 复用现有 instrumented tests；必要时新增真实 Room/WorkManager/Capacitor/前台确认测试，使用可识别的 synthetic QA 数据。实际 UI 通过观察后的 ADB 操作与 WebView 调试完成；不得绕过产品原生确认或系统权限窗口。
5. 测试不会发送邮件、提交作业、抢课或改变真实校园服务状态。校园查询使用缓存/fixture 和实际页面可达性；真实账户/模型场景缺失时明确记录，不把模拟数据说成上游成功。
6. 插件安全保持 constitution 3.2.0 与 contract_v1：固定 self/main-frame origin、分域请求、加密隔离数据、optional-only 自启与可见 Activity 触发、系统 UI/运行时权限 gate、撤销/更新/删除清理、最多 4 个后台 runtime、持续通知和全部停止。QA 不添加任意 URI/HTTP/凭据桥。

## 验收清单

- Agent：普通聊天加载无工作区工具；错误参数/未知工具/路径穿越拒绝；原生确认取消和后台拒绝；待办创建→原生日历→更新/完成/删除；版本冲突与重试去重；提醒设置/替换/取消/实际通知/通知点击与重启重验；导航白名单和回到 Agent。
- 空教室：单节/连续节次/星期筛选立即改变结果，跨周保留选择，无时段信息不显示未验证空闲教室。
- 插件：README 与运行要求分别滚动；详情与安装/更新入口、请求隔离和增量授权展示；自启默认关闭、仅 optional 声明、用户授权后可见 Activity 加载、停止/撤销/禁用清理；WebView 与加密存储/迁移安全现有 instrumentation 全过。
- 回归：主页面/Agent/日历/我的切换、旋转/返回、进程恢复、无崩溃；正式应用包与用户数据保留。

## 自动化验证命令

- Android：gradlew test、:app:assembleDebug、:app:assembleDebugAndroidTest；独立 .debug 真机 instrumentation，必要时按类运行定位；复用 2026-10-12 已通过的完整前端构建与资产同步，测试新增源码重新编译。记录全部测试数、失败/跳过与修复。
- Open WebUI：npm.cmd run test:frontend -- --run。npm run check 有既有 Svelte/TypeScript 基线，按实际运行结果报告。
- plugin-tooling：generate:check、typecheck、test、pack:check；共享 schema 字节一致与全部有效模板 Manifest lint。
- web/platform：typecheck、test、test:integration、test:e2e；真实 PostgreSQL 不可用时明确记录 skip，并尝试可用本地隔离服务，不操作生产数据库。
- git diff --check、显式暂存文件审查、secrets/生成物检查。构建可能升级 pyodide-lock.json，预先保存基线，仅恢复本次生成变化。

## 提交与推送门槛

所有本次相关真机操作及自动化测试通过（或确切区分已有基线和环境缺失）后，才创建提交。显式暂存源码/测试/文档/契约生成物，排除 .codex、tmp_media3_ui、runtime、APK、缓存、截图和本机配置。保留 origin/dev 历史；推送前重新读取远端 SHA，若变化则整合并重新验证受影响范围。使用普通 git push origin HEAD:dev，禁止 force。推送后比对远端 SHA 与本地提交，并记录结果。既有 API 26/35 阻塞 CI 的状态与真机 API 31 的结果分别报告。

## 风险与回滚

- 源码合并冲突：用保存的初始文件/diff 精确恢复相应文件；不回滚用户其他修改。
- 真机安装/测试：始终使用独立包，不覆盖/卸载正式包，不清用户应用数据；创建的 QA 待办/插件/提醒均可识别并清理。
- 权限与系统状态：仅在明确测试场景中由可见系统 UI 授权；结束停止测试后台任务和持续通知，恢复测试调整的状态。
- 测试失败：先修复本范围缺陷并重跑受影响测试；已有无关竞态单独记录，不隐瞒失败。
- 发布失败：不 force，不删除远端历史；本地提交保留并核实远端状态后恢复正常推送。若已发布需撤回，以新的 revert 提交回滚，保留审计历史。

## 进度日志

- 2026-10-13：完成初始状态、远端 dev 与真机 API 31 检查；先创建本计划，尚未提交或推送。
- 2026-10-13：备份有效源码并推进 Git 基线到 origin/dev 后切换 dev，全部初始文件 SHA256 未变；没有丢失本地或远端改动。
- 2026-10-13：前端 104 项、工具链 40 项、平台单测 22 项通过；PostgreSQL integration 的 2 项因无数据库跳过，Docker Desktop engine 尚未运行，继续尝试本地隔离环境。
- 2026-10-13：首次未限定模块的 assembleDebugAndroidTest 误触发 Capacitor 依赖自身的测试 APK，其旧 Kotlin 依赖重复类导致失败；改为 :app:assembleDebugAndroidTest，只编译本项目测试，不修改 vendor 或升级依赖。
- 2026-10-13：新增 NativeCampusToolsDeviceTest，使用独立 .debug 数据库验证真实 Room/WorkManager/通知。明确标记 QA_SYNTHETIC_OFFLINE 的校园 fixture 和空 cookie 数组仅用于本地缓存界面，不包含凭据、不认证校园服务、不代表真实校园数据成功。正式包完全不动。
- 2026-10-13：API 31 真机首轮 instrumentation 30 项中 29 项通过；新增真实通知测试检测到 debug 包通知被系统关闭而明确失败。将通过可见系统设置启用测试包通知后重跑，不使用 adb grant 绕过用户系统界面。Room/WebView/空教室/README 安全与 UI 场景已通过。
- 2026-10-13：将既有插件阻塞工作流的 push 分支补充 dev，使推送后运行 API 26/35 和 PostgreSQL CI；不削减任何现有检查。
- 2026-10-13：重跑设备测试两次自动执行审批均超时，默认沙箱无法启动/连接 ADB；已按工具要求申请用户明确确认。超时不代表测试失败或安全拒绝，当前尚未提交/推送。
- 2026-10-13：通知设备测试为 API 33+ CI 提供仅测试进程可用的 POST_NOTIFICATIONS runtime grant，不进入生产代码；API 31 真机的 OEM 通知开关仍通过可见系统设置开启。旧本地 .codex 与 tmp_media3_ui 材料增加精确忽略规则，文件保留、不提交。
- 2026-10-13：用户再次明确授权继续沙箱外真机测试。新增 NativeAgentBridgeDeviceTest，直接启动生产 MainActivity/Capacitor WebView，实际点击原生“取消/允许本次”、核对 Room 写入与相同 requestId 去重、确认打开原生日历并返回 Agent；不使用假 confirmer 替代产品对话框。
- 2026-10-13：新增 PluginAutoStartDeviceTest，使用独立 debug 的合成本地插件验证 optional 默认关闭、显式授权后真实 WebView/前台服务及持续通知启动、撤销后停止和清理。插件无外网 origins、无校园请求、无真实用户数据。
- 2026-10-13：原生桥待办确认/去重与插件自启首先通过；导航补测超时经可见节点诊断确认页面已正确显示“学年日历/月视图”，原因是 Compose 虚拟节点不支持该设备的 findAccessibilityNodeInfosByText 搜索。改为遍历实际可见 accessibility 树匹配页面标识，无生产代码改动。
- 2026-10-13：最终 test :app:assembleDebug :app:assembleDebugAndroidTest -x buildOpenWebUi -x syncOpenWebUiAssets 构建成功，JVM debug/release 复用已通过的各 518 项结果。新测试 APK 的真机全套最终 OK (33 tests)，无失败/跳过，耗时 34.027 秒。设备为 Huawei Android 12/API 31；校园数据为 QA_SYNTHETIC_OFFLINE，不代表真实账号联网查询或真实模型端到端成功。
- 2026-10-13：前端 13 文件 104 项、工具链 40 项及 generate/typecheck/pack、平台单测 22 项/e2e 8 项/typecheck、共享 schema 字节检查和 4 组 Manifest lint 均通过。PostgreSQL integration 2 项因无 TEST_DATABASE_URL 跳过，Docker Desktop engine 启动不可用；API 26/35 与真实 PostgreSQL 交由 dev 阻塞 CI，不宣称本机通过。npm run check 未执行，既有 Svelte/TypeScript 类型基线仍保留。
- 2026-10-13：已卸载本次新安装的 cn.edu.bjtu.mis.debug.test 和 cn.edu.bjtu.mis.debug，清理本次合成数据、测试插件/提醒和系统通知设置；pm list packages 确认正式 cn.edu.bjtu.mis 仍在。没有卸载/清除/读取正式应用数据。源码备份、日志、APK 和截图均留在本地忽略目录。
- 2026-10-13：推送前只读核对 origin/dev 仍为 d279c5c，无远端新增提交；开始最终显式暂存和秘密/生成物审查。
- 2026-10-13：显式清单共 92 个文件完成暂存，cached diff --check 通过，未发现私钥/token 或本机配置、APK、缓存、前端产物。原始源码备份逐文件 SHA256 比较仅工作流与验收计划文档有本次预期更新，其余既有改动保持原样。ThirdPartyServiceInstaller.kt 的规范化 Git blob 与 HEAD 完全一致，未产生源码提交差异。

## 完成清单

- [x] 源码备份和 origin/dev 整合已完成。
- [x] 真机实际操作与 instrumentation 已执行并记录。
- [x] JVM / 前端 / 工具链 / 平台验证及环境限制已记录。
- [x] 提交范围、生成物、secrets 与 diff 已审查。
- [ ] 测试通过后已提交和普通推送 origin/dev，并核对远端 SHA。
