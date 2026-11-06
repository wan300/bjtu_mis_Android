# ExecPlan: BJTU MIS 双界面 Apple 风格重构

当前用户可见名称已于 2026-11-05 统一为“主题1”（Classic / 原安卓风格）和“主题2”（Apple / 原苹果风格）。本文保留原设计与历史记录中的称呼；本轮检查及修复见 [主题2界面检查](theme2-ui-review.md)。

## 1. Title

为 BJTU MIS 原生 Android 界面新增可即时切换、可回滚的 Apple 风格界面，同时完整保留经典界面和高风险操作确认边界。

## 2. Purpose and user-visible outcome

- 用户场景：用户希望在不丢失当前页面和状态的前提下，在“经典界面”和更清晰、克制、自适应的“Apple 风格界面”之间切换，并继续独立使用现有三套配色。
- 当前问题：原生页面大量依赖重复边框卡片、固定列数和固定高度；首页与服务入口重复；大字体、横屏和宽屏支持不足；同步时间不友好；全局导航缺少连续、可中断反馈；没有减少动态效果和降低透明效果偏好。
- 完成后的可观察行为：
  - 主题页按“界面、配色、辅助效果”分组，可即时切换界面样式，并在保存失败时回退；切换后可通过 Snackbar 撤销。
  - 已有安装第一次读取新增偏好时默认经典界面，首次安装默认 Apple 风格；选择会持久化且启动时不闪烁。
  - Apple 风格在紧凑窗口使用底部导航，在中等和展开窗口使用导航栏；经典模式继续保持竖屏和原有壳层。
  - Apple 风格首页以今日状态、重点提醒、快捷入口和近期内容组织；快捷入口支持 1–8 项的增删、拖拽及无障碍移动操作。
  - Apple 风格服务页可搜索并按自适应网格分组；内置导航、快捷入口和服务入口继续使用经典界面的动漫图标；时间、加载、缓存、刷新、空状态、警告和错误得到一致反馈。
  - 其余原生 Compose 页面、弹窗、面板和播放器控制层通过共享设计令牌与组件获得一致外观和辅助效果；Open WebUI 和第三方网页内容不重做。
- 成功标准：两种界面样式和三套配色可独立组合；现有 route key、repository/provider 边界、本地优先状态及高风险确认流程不变；关键 JVM/UI 测试、lint 和 debug 构建通过，或对环境限制和既有基线失败有明确记录。

## 3. Repository context

- Android 工程根目录：`android/`。
- 应用入口与壳层：
  - `android/app/src/main/java/cn/edu/bjtu/mis/MainActivity.kt`
  - `android/app/src/main/java/cn/edu/bjtu/mis/ui/BjtuMisApp.kt`
  - `android/app/src/main/AndroidManifest.xml`
- 主题与共享组件：
  - `android/app/src/main/java/cn/edu/bjtu/mis/ui/theme/Theme.kt`
  - `android/app/src/main/java/cn/edu/bjtu/mis/ui/theme/AppThemeStore.kt`
  - `android/app/src/main/java/cn/edu/bjtu/mis/ui/components/Shared.kt`
- 首批页面：
  - `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/OverviewScreen.kt`
  - `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/ModuleScreens.kt`
- 其余原生页面位于 `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/` 和 `android/app/src/main/java/cn/edu/bjtu/mis/ui/player/`。
- 数据装配入口：`android/app/src/main/java/cn/edu/bjtu/mis/di/AppContainer.kt`。
- Android JVM 测试：`android/app/src/test/kotlin/cn/edu/bjtu/mis/`。
- Compose 仪器测试将位于 `android/app/src/androidTest/kotlin/cn/edu/bjtu/mis/`。
- Open WebUI 源码位于 `android/open-webui/`；构建输出会同步到 `android/app/src/main/assets/public/`，该目录不直接维护。

## 4. Constraints and non-goals

- 不修改 Room schema、provider、repository、网络协议、凭据/Cookie 安全存储或 Open WebUI 主题桥接协议。
- 不重做 Open WebUI、第三方网页内容或桌面小组件内部界面。
- 不引入 SF Symbols、Apple 字体、iOS 控件仿制、实时背景模糊生产依赖、界面音效、遥测或生产日志。
- 不移除或弱化邮件、作业、选课、评教、删除、退出登录和第三方权限等高风险流程的预览与明确确认。
- 不提交 APK/AAB、构建缓存、`node_modules`、Open WebUI 生成资产、本机配置、密钥、真实校园账号或个人数据。
- 经典模式除主题页新增选项和非视觉安全/无障碍修复外保持现有外观、角色图标、导航和流程。
- 保留现有三套配色的持久化值和未知值回退行为；新增 DataStore 键必须可被旧版本安全忽略。
- 用户现有工作不得修改、格式化或回退：
  - `android/open-webui/static/pyodide/pyodide-lock.json`
  - `web/assets/js/site.js`
  - `web/index.html`
  - `docs/plugin-platform-production-deployment-execplan.md`
  - `docs/website-v1.4.0-deployment-execplan.md`
- 实施期间又出现了并行的 Web/部署工作，包括根配置与说明、`deploy/`、`platform/` 到 `web/platform/` 的迁移、`specs/`、更多 `web/` 文件和部署 ExecPlan；这些也全部按用户工作保护，不纳入本次改动。

## 5. Research notes

- `git status --short` 已于 2026-07-28 检查，确认上述五项为实施前已有的无关改动。
- 当前 `AppThemeStore` 仅持久化 `theme` 字符串；`BjtuMisTheme` 只接收配色。
- 当前 `BjtuMisApp` 使用手工 route 状态和固定底栏；四个主 route 为 `overview`、`services`、Open WebUI Agent 和 Profile。
- 当前 Manifest 将 `MainActivity` 固定为竖屏。
- `OverviewScreen` 使用固定六项快捷入口、固定列数和原始 ISO 同步时间；服务项同时具有 Material `ImageVector` 和角色图片资源，可按界面样式切换而不新增素材。
- 当前共享加载组件包含循环位移动画，尚未读取减少动态效果设置。
- 当前 Android 模块无 Compose `androidTest` 依赖和对应测试目录。
- 当前工程已有 DataStore、Material3 和 Compose 动画/手势能力，生产实现不需要新增依赖；仅增加必要的 Android UI 测试依赖。
- 已在 API 31 真机检查主题、首页、服务和课表现状；截图仅用于本地评审，包含真实课程数据，不进入仓库。
- 设备环境确认：API 31 真机可用；API 35 AVD 可用并已实跑；当前机器未安装 API 26 系统镜像。
- Open WebUI 源码未修改；完整 Android 构建已验证其 npm/build/sync 资产流水线可用。

## 6. Proposed design

### 持久化与初始化

- 新增 `AppUiStyle { Classic, Apple }` 和 `AppAppearancePreferences`，后者包含配色、界面样式、减少动态效果覆盖值、降低透明效果覆盖值及初始化状态。
- 将 `AppThemeStore` 扩展为统一偏好流，并保留兼容的配色读取/保存入口。样式键缺失时依据 `PackageInfo.firstInstallTime` 与 `lastUpdateTime` 一次性解析：
  - 首次安装：Apple。
  - 已有安装升级：Classic。
  - 无法读取：Classic。
- 初始化结果持久化后才渲染应用内容，避免经典/Apple 闪烁；写入失败时使用 Classic 安全回退。
- 辅助效果保存为三态覆盖（跟随系统、开启、关闭）。有效值由应用覆盖与可用系统信号合并。
- 新增快捷入口 DataStore 存储。纯函数规范化会过滤未知/重复 route，保持顺序并限制为 1–8 项；空结果回退默认六项。

### 主题、设计令牌和交互

- `BjtuMisTheme` 接收完整外观偏好。经典分支保留现有配色、形状与字体；Apple 分支使用系统字体、尺寸相关行高、分级圆角/间距、48dp 最小触控区、统一状态色和近不透明色调材质。
- 通过 CompositionLocal 暴露界面样式、有效辅助效果、布局宽度、动效参数和触觉策略。
- 正常动效使用可中断弹簧：普通移动 `dampingRatio=1.0`、`stiffness=400`，吸附 `dampingRatio=0.8`、`stiffness=500`。减少动态时取消位移、弹簧和循环，仅保留不超过 120ms 的淡化/颜色反馈。
- 不使用实时模糊；降低透明或高对比度时使用实色表面。
- 仅对样式切换、拖拽吸附、成功、错误和提交使用轻触觉反馈，并尊重系统设置。

### 壳层、窗口和导航

- 取消 Manifest 的静态竖屏限制；运行时经典样式请求竖屏，Apple 样式允许旋转。
- 继续使用现有 route key 和页面状态。紧凑 Apple 壳层使用底栏，600–839dp 使用导航栏，840dp 及以上预留列表/详情双栏。
- Apple 主标签切换使用轻量交叉淡化；详情进入/返回使用方向对称过渡，并通过现有 Activity Compose API 接入预测返回进度。
- 界面选择立即乐观生效；保存失败自动回退并显示错误。成功后 Snackbar 提供一次撤销，撤销只写回上一个样式，不改变 route 和可保存页面状态。

### 页面

- 主题页分成“界面”“配色”“辅助效果”，提供两种样式语义化选择、Apple 预览和辅助效果开关。
- Apple 首页重排为今日状态、重点提醒、快捷入口和近期内容；快捷入口显式编辑，支持拖拽和非拖拽移动/删除。
- Apple 服务页增加搜索和自适应分组；内置导航、快捷入口和服务入口复用经典界面的动漫图标，第三方插件保留自有图标，操作与状态仍使用系统图标。
- 共享组件为其余页面提供 Apple 风格的标题、分组、表面、表单、状态、弹窗/面板和可访问触控区。密集表格在紧凑屏优先摘要/日程视图，宽屏保留完整表格。
- ISO 时间转换为本地友好文本，解析失败保留安全可读值。

### 架构和安全边界

- UI 仍只消费现有 repository/container 状态，不直接访问校园服务。
- Open WebUI/Capacitor 内部内容及协议不变，仅原生外壳应用对应系统栏/导航风格。
- Room、网络、缓存和数据迁移无影响；所有新增持久化都是附加 DataStore 键。
- 高风险操作保持原有最终确认，不在自动化验收中执行真实提交。

## 7. Files and components to change

- `docs/apple-ui-refactor-execplan.md`：持续记录设计、进度、验证和回滚。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/theme/AppThemeStore.kt`：统一外观偏好、默认迁移和独立保存接口。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/theme/Theme.kt`：双样式主题、设计/动效/宽度令牌。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/theme/AppearanceModels.kt`：纯模型、辅助效果解析和默认判定。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/preferences/QuickActionsStore.kt`：快捷入口偏好和纯规范化函数。
- `android/app/src/main/java/cn/edu/bjtu/mis/MainActivity.kt`：初始化门控、方向策略、外观注入。
- `android/app/src/main/AndroidManifest.xml`：移除静态竖屏限制。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/BjtuMisApp.kt`：双壳层、自适应导航、过渡和撤销 Snackbar。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/Shared.kt`：共享 Apple 组件、状态和减少动态处理。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/OverviewScreen.kt`：Apple 首页和快捷入口编辑。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/ModuleScreens.kt`：主题页、密集页面和共享调用点。
- 其余 `ui/screens/` 与 `ui/player/` 中必要文件：共享令牌接入、触控区、表单/弹窗/控制层一致性。
- `android/gradle/libs.versions.toml`、`android/app/build.gradle.kts`：仅增加 Compose 仪器测试依赖和 runner 配置。
- `android/app/src/test/kotlin/...`：默认迁移、兼容回退、辅助效果、快捷入口、时间和路由方向测试。
- `android/app/src/androidTest/kotlin/...`：主题切换/撤销、响应式导航、快捷入口、语义和高风险确认测试。
- 生成文件处理：`assembleDebug` 可能改写 `android/app/src/main/assets/public/`；验证后不得将无关生成差异纳入本次变更。

## 8. Milestones

### Milestone 1: 外观偏好与设计基础

- 目标：完成兼容迁移、统一偏好流、快捷入口存储和双主题令牌。
- 预期可观察行为：应用启动无样式闪烁；新装/升级默认可由纯函数验证；三套配色保持兼容。
- 验证命令：`Set-Location android; .\gradlew.bat test --tests "*Appearance*" --tests "*QuickAction*"`
- 完成状态：completed

### Milestone 2: 双壳层与主题页

- 目标：实现即时切换、撤销、条件方向、自适应导航和过渡。
- 预期可观察行为：当前 route 不变，紧凑/宽屏导航按阈值变化，经典仍保持原壳层。
- 验证命令：`Set-Location android; .\gradlew.bat testDebugUnitTest connectedDebugAndroidTest`
- 完成状态：completed

### Milestone 3: 首页、服务与共享组件

- 目标：完成 Apple 首页/服务页、快捷入口编辑、友好时间和全局状态组件。
- 预期可观察行为：搜索、重排、增删、无障碍移动、可重试状态和大字体布局可用。
- 验证命令：`Set-Location android; .\gradlew.bat test connectedDebugAndroidTest lintDebug`
- 完成状态：completed

### Milestone 4: 全部原生页面族与播放器

- 目标：通过共享令牌和必要的定点改造覆盖所有原生 Compose 页面、弹窗、面板和播放器控制层。
- 预期可观察行为：两套界面在全原生流程中视觉与交互一致；不可逆操作仍显示明确确认。
- 验证命令：`Set-Location android; .\gradlew.bat test connectedDebugAndroidTest lintDebug assembleDebug`
- 完成状态：completed

### Milestone 5: 设备、可访问性和性能验收

- 目标：验证 API 26/31/35、紧凑/横屏/840dp、200% 字体、三配色和辅助效果组合。
- 预期可观察行为：关键文本不裁切、操作顺序明确、交互期间不锁输入；Apple 卡顿比例不高于经典并以低于 5% 为目标。
- 验证命令：ADB UI 检查、截图、`dumpsys gfxinfo cn.edu.bjtu.mis`。
- 完成状态：partial（API 31/35 自动化已通过；API 26、200% 字体人工遍历、TalkBack 人工顺序检查和核心导航 `gfxinfo` 对照仍需具备对应环境/安全测试账号后补测）

## 9. Step-by-step implementation plan

1. 记录 worktree 基线，保护五项用户已有改动，并创建本 ExecPlan。
2. 新增外观模型、默认判定、辅助效果合并和快捷入口规范化纯函数及 JVM 测试。
3. 扩展 `AppThemeStore`，新增快捷入口 store，并在装配层提供依赖。
4. 扩展 `BjtuMisTheme` 与 CompositionLocal，保持 Classic 分支逐像素兼容。
5. 修改启动门控、方向策略和应用壳层，加入 Apple 响应式导航、路由过渡、Snackbar 与撤销。
6. 重构主题页，再重构 Apple 首页和服务页；经典分支保留旧布局。
7. 通过共享组件和定点修改覆盖其余原生页面族、弹窗、面板及播放器控制层。
8. 添加 Compose UI 测试依赖和关键验收测试，确认高风险最终确认仍存在。
9. 运行单元测试、仪器测试、lint 和 debug 构建；修复本次引入的问题，记录环境/基线限制。
10. 在可用设备上验证旋转、宽度、大字体、TalkBack 语义和 `gfxinfo`，不执行真实高风险提交。
11. 检查 `git status --short`、`git diff --check` 和改动清单，排除生成资产、个人数据及无关文件；更新本计划的里程碑、验证和完成清单。

## 10. Validation plan

- Android JVM 测试：`Set-Location android; .\gradlew.bat test`
  - 接受标准：本次新增默认迁移、未知值、三配色、辅助效果、快捷入口、时间和路由方向测试通过；既有测试无回归。
- Compose UI 测试：`Set-Location android; .\gradlew.bat connectedDebugAndroidTest`
  - 接受标准：主题选择/撤销、紧凑/宽屏导航、快捷入口编辑、关键语义和高风险确认测试通过；无设备时明确记录而不伪报。
- Android lint：`Set-Location android; .\gradlew.bat lintDebug`
  - 接受标准：无本次新增 fatal/error；既有基线单独列明。
- Android 打包：`Set-Location android; .\gradlew.bat assembleDebug`
  - 接受标准：debug APK 构建成功，Open WebUI 资产同步成功且生成差异不进入本次变更。
- 手动验证：
  - 设备/模拟器：API 26、API 31 现有真机、API 35（以环境实际可用性为准）。
  - 窗口：紧凑竖屏、手机横屏、600dp 中等、840dp 展开。
  - 辅助：200% 字体、TalkBack 顺序、三配色、两套界面、减少动态/降低透明组合。
  - 风险流程：仅用 fake/fixture 或停在最终确认前，不发送邮件、不提交作业/评教/选课。
  - 性能：暖机后对核心导航和拖拽运行 `dumpsys gfxinfo`，Apple 风格不高于 Classic 基线，目标卡顿比例低于 5%。

## 11. Progress log

- 2026-07-28：检查 constitution、`PLANS.md` 与 worktree；确认五项用户已有无关改动并创建本计划。
- 2026-07-28：完成 `AppUiStyle`、统一外观偏好、首次安装/升级默认解析、辅助效果覆盖合并、快捷入口存储及对应纯函数测试。
- 2026-07-28：完成双壳层、紧凑底栏/宽屏导航栏、主标签交叉淡化、详情对称过渡、预测返回进度、运行时方向策略及样式切换撤销。
- 2026-07-28：完成主题页、Apple 首页、服务搜索与自适应分组、快捷入口编辑/拖拽/无障碍移动、友好时间和共享状态/表面改造。
- 2026-07-28：完成登录、邮件、选课、评教和播放器控制层定点改造；邮件、选课与评教最终确认保留并增强，自动化未执行真实提交。
- 2026-07-28：为 debug 变体增加 `.debug` applicationId 后缀，使仪器测试可与设备上的正式签名应用并存，不覆盖正式版数据。
- 2026-07-28：首次 API 31 仪器测试发现导航项缺少稳定 TalkBack 语义；补齐导航项内容描述后通过。最终增加抢课/换课确认用例，API 31 与 API 35 均为 6/6 通过。
- 2026-07-28：最终完整 `.\gradlew.bat test` 通过；完整 `.\gradlew.bat assembleDebug` 通过，生成 `app-debug.apk`（248,097,512 bytes），Open WebUI 资产同步后 `android/app/src/main/assets/public/` 无 Git 差异。
- 2026-07-28：根级 `.\gradlew.bat connectedDebugAndroidTest` 中 App 的 5/5 测试通过，随后 Capacitor 库自身的 AndroidTest 变体因 Kotlin stdlib 1.8.22 与 jdk7/jdk8 1.6.21 重复类失败；单独的 `:app:connectedDebugAndroidTest` 在 API 31/35 均通过。
- 2026-07-28：根级 `.\gradlew.bat lintDebug` 完整运行；本次引入的 `produceState`、Media3 opt-in 和 Compose modifier 顺序问题已修复。最终仅剩 2 个未改动文件的既有 `NewApi` 错误：`NativeAgentToolsPlugin.kt:434` 与 `ScheduleExportStorage.kt:95`；结果为 2 errors / 88 warnings。
- 2026-07-28：API 35 AVD 以无窗口、只读、不保存快照方式运行并在测试后关闭；API 26 镜像缺失。测试进程结束后无可用 debug 进程，未借用已登录正式版自动点击，故核心导航/拖拽 `gfxinfo` 对照未采集。
- 2026-07-28：最终审计补充拖拽跟手与 `0.8/500` 吸附回弹、减少动态分支、第三方服务读取重试，以及“读取失败不持久化删除第三方快捷入口”的保护；增量后重新完成 JVM、APK、API 31/35 仪器测试与 lint。
- 2026-07-28：最终 `git diff --check` 通过；Open WebUI 生成资产无差异；用户并行的 Web、部署与平台迁移文件未被修改或回退。
- 2026-08-05：按后续界面要求，让 Apple 风格的底部导航、宽屏导航栏、首页快捷入口、快捷入口编辑器和内置服务网格继续使用经典界面的动漫图标；第三方插件图标以及操作/状态图标保持不变。`:app:compileDebugKotlin`、`:app:testDebugUnitTest` 与 `assembleDebug` 通过。

## 12. Decision log

- Decision：界面样式和配色正交持久化。
  - Context：用户需要三配色与新旧界面自由组合。
  - Chosen approach：保留原 `theme` 值，新增独立 `ui_style` 和辅助效果键。
  - Consequences：旧版本可忽略新增键，无 Room 迁移。
- Decision：首次安装与已有安装通过安装/更新时间一次性解析。
  - Context：既有用户不得在升级后突然改变界面，首次安装希望默认 Apple。
  - Chosen approach：`firstInstallTime == lastUpdateTime` 为首次安装，否则为升级；异常回退 Classic。
  - Consequences：测试必须覆盖相等、更新、异常和持久化竞争。
- Decision：Apple 风格建立在 Material3/Compose、经典动漫图标和现有依赖上。
  - Context：不应引入 Apple 私有资产或高成本实时模糊，同时需要保持两套界面的品牌识别一致。
  - Chosen approach：内置入口复用经典动漫图标，操作和状态使用系统图标，并保留 Material 语义、色调表面和 Compose 原生动画/手势。
  - Consequences：视觉遵循 Apple 原则但不仿制 iOS 控件。
- Decision：Classic 与 Apple 共用业务页面状态和 route key。
  - Context：即时切换必须保持当前页面和可保存状态。
  - Chosen approach：样式只切换壳层/呈现分支，不重建业务 repository 或导航标识。
  - Consequences：共享组件需要明确 Classic 兼容分支。
- Decision：不修改 Open WebUI 内容。
  - Context：用户范围只要求原生外壳颜色整合，协议不可变。
  - Chosen approach：只调整原生容器、系统栏和导航。
  - Consequences：无需运行前端测试，除非构建资产管线被触发后发现异常。
- Decision：debug 包使用独立 applicationId 后缀。
  - Context：设备上已有不同签名的正式版，直接安装 debug 会因签名不匹配失败且可能危及本地数据。
  - Chosen approach：仅 debug 变体追加 `.debug` 和 `-debug` 版本名后缀。
  - Consequences：正式包 ID、升级路径和用户数据不变；测试包可并存。
- Decision：不顺手修复两处既有 API 29 lint 错误。
  - Context：错误位于未改动的 Agent 文件下载与课表导出路径，其中 Agent 工具属于项目明确标注的安全敏感边界。
  - Chosen approach：修复所有本次新增 lint 问题，保留并精确记录两项基线。
  - Consequences：`lintDebug` 仍以非零状态退出，但本次改动没有新增 error；后续应以独立小变更补充 `@RequiresApi(29)` 或等价安全处理。

## 13. Risks and rollback plan

- 风险：新增偏好初始化导致启动阻塞或闪烁。
  - 发现：冷启动设备测试和超时/异常单元测试。
  - 缓解：初始化仅做本地 DataStore/PackageInfo 读取，异常立即回退 Classic。
  - 回滚：恢复旧主题读取入口并忽略新增键。
- 风险：运行时方向切换重建 Activity 丢失状态。
  - 发现：主题页切换、横竖屏和详情 route 测试。
  - 缓解：route 和关键 UI 状态使用 `rememberSaveable`；样式切换先持久化再执行方向策略。
  - 回滚：主题页切回 Classic；代码层恢复 Manifest 竖屏并移除 Apple 宽屏分支。
- 风险：共享组件改造使 Classic 外观回归。
  - 发现：真机对照截图、Classic UI 测试和语义快照。
  - 缓解：共享组件按 `AppUiStyle` 显式分支，Classic 参数保持现值。
  - 回滚：保留 Classic 分支，单独移除 Apple 组件实现。
- 风险：快捷入口持久化出现未知/重复/空列表。
  - 发现：纯函数测试和 DataStore 异常测试。
  - 缓解：读取和写入都规范化，空结果回退默认六项。
  - 回滚：忽略快捷入口键并使用固定默认项。
- 风险：全页面视觉覆盖范围大，可能遗漏弹窗或控制层。
  - 发现：源码清单审计、设备逐页检查和 Compose 语义测试。
  - 缓解：优先在共享组件/主题令牌集中覆盖，再对特殊页面定点修正。
  - 回滚：用户可即时切回 Classic；代码回滚只移除 Apple 渲染、偏好和测试。
- 数据迁移回滚：无 Room 迁移。DataStore 只新增附加键，旧版安全忽略；回滚版本无需清理数据。

## 14. Completion checklist

- [x] 计划包含目标、上下文、约束、设计、文件、里程碑、步骤、验证、风险和回滚。
- [x] 已标记当前尚无法确认的模拟器和预测返回事项。
- [x] 实现只触碰计划范围内文件。
- [x] 已记录并保护实施前 worktree 改动。
- [x] 已添加必要 JVM 和 Compose UI 测试。
- [x] 已运行适当验证命令并记录结果。
- [x] 已检查 `git status --short`、`git diff --check` 和生成文件。
- [x] 没有提交 secrets、本机配置、构建产物、APK/AAB、`node_modules`、Open WebUI build、Room schema 或缓存。
- [x] 最终汇报将包含变更文件、主要行为、验证结果、已知失败和剩余 TODO。
