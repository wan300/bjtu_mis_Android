# 主题2界面检查记录（2026-11-05）

## 范围与命名

用户可见名称统一为“主题1”（Classic / 原安卓风格）和“主题2”（Apple / 原苹果风格）。设置选项与切换提示共用 `AppUiStyle.displayName`，`classic` / `apple` 存储值、已有选择和默认规则兼容，配色仍独立。

验收依据是 `docs/apple-ui-refactor-execplan.md` 的现有设计：分组圆角表面、既有动漫入口图标、系统操作图标、手机底栏/宽屏侧栏、48dp 操作目标及辅助效果。检查使用源码与隔离 API 35 模拟器上的测试数据。初始 worktree 干净。

遵循 `PLANS.md`，执行计划和完整过程记录保存在本机临时文件 `bjtu-theme2-ui-execplan.md`；本文只维护本轮结果。

## 发现与修复

| 范围 | 发现与处理 |
| --- | --- |
| 设置说明 | 主题2仍写“系统图标”，与现有动漫入口图标不一致，已修正。 |
| 返回与动效 | 作业详情回作业/课表/日历、插件诊断回目录被判成进入方向，已改为返回方向。第三方服务目录顶部返回与系统返回统一到服务标签，并同步选中状态。 |
| 往返状态 | 增加路由级可保存状态容器，保留列表滚动、服务搜索词和作业筛选；不同作业共用详情 route，不复用上一项的滚动位置。 |
| 标题栏 | 主题2固定高度和单行标题不利于大字体，改为可增高、最多两行，保留 48dp 返回触控区。主题1标题参数保持原值。 |
| 导航侧栏 | 短窗口下部分入口可能位于可见区域之外，增加滚动，验证每个入口可达。 |
| 主题卡片 | 320dp / 200% 字体下预览挤占文字区域；大字体时隐藏装饰预览，为名称和说明留出空间，截图确认文字完整。 |
| 文字徽标 | 改为按文字行高计算圆形尺寸，并保留内边距，以适应字号放大。 |
| 辅助效果 | 两个 Switch 缺少用途名称，补齐可访问描述，保留原有效果解析与覆盖规则。 |

### lint 续检修复

- 为 Agent 公共下载及课表 PDF/PNG MediaStore 保存函数补齐 `@RequiresApi(Q)`；调用方原有 `SDK_INT >= Q` 分支和旧系统回退路径保持不变。
- 将播放器和空教室组件的 `modifier` 调整为首个可选参数，调用方使用命名参数，界面和交互逻辑不变。
- 插件通知的小图标由彩色加载图改为纯白矢量 `ic_notification_24`；仅修改图标引用，通知渠道、权限、持续通知和停止操作保持不变。
- 将加载位图迁至 `drawable-nodpi`，8 张保留 PNG 经 Git blob 校验与原文件字节一致；重复首帧使用 drawable 别名复用同一张图片，保留原 `R.drawable` 名称。
- 完整 lint 发现生物识别调用缺少 `USE_BIOMETRIC`，补齐这一普通平台权限。已有 API 28 下限、`requireForeground`、非导出交互 Activity 和系统 `BiometricPrompt` 成功回调保持不变；平台权限性质参见 [Android 官方说明](https://developer.android.com/reference/android/Manifest.permission#USE_BIOMETRIC)。
- 另一阻塞项为现有 `QUERY_ALL_PACKAGES` 的合规诊断。该权限用于 constitution 原则 I 和 contract_v1 已允许的 `android.packages.read@1` 应用枚举，直接替换为 launcher 查询会漏掉无启动入口的应用。保留原权限，并仅在这一声明处使用带理由注释的 `tools:ignore="QueryAllPackagesPermission"`。这是明确审阅的单点 lint 例外，不代表发行渠道审核结论。

## 其他源码检查

- 核对首页、快捷入口、服务入口和 `ModuleRoute` 的内置 route 映射。
- 核对三配色的语义色和主题2圆角、间距、表面令牌。
- 检查课表、日历、成绩、考试、作业、邮箱、课程资源/回放等页面的共享组件、滚动和宽屏布局。
- 未改变 provider、数据库、插件协议、Open WebUI 内容或既有邮件/选课/评教/作业确认流程。lint 续检增加的平台权限及其约束见上文；插件文件其余改动仅为通知小图标引用。

## 验证结果

- 完整 `android/gradlew.bat test`：通过（20m23s）。81 个测试套件，Debug/Release 各 550 项，共 1,100 次执行，0 failures / 0 errors；同时完成 Open WebUI 全量构建和资产同步，存在原有 Svelte/弃用 API 警告。
- 修复布局后 `:app:assembleDebug :app:assembleDebugAndroidTest test -PtargetAbis=x86_64 -x :app:syncOpenWebUiAssets` 再次通过（3m47s）；仅跳过此前已完整通过且未改动的前端资源流水线。
- API 35 最终 UI 回归 **17/17 通过**：主题选择/撤销/关闭提示、主导航、短侧栏可达性、服务搜索往返、三配色、320dp / 200% 字体、长标题、辅助开关语义及原有选课/评教确认。
- 额外在 1600×1200、160dpi 模拟窗口验证 840dp 内容区与侧栏/设置组合，**1/1 通过**。
- UI 断言开发期间将段落可用宽度误当作文字实际宽度，造成短文字溢出误报；已改为检查每行真实绘制边界、垂直尺寸与省略截断，只允许 1 像素舍入。
- 测试包增量构建曾因其他 Gradle 进程持有共享 hash cache 锁而超时，重试已继续，未停止其他任务。
- `lintDebug` 完整续检通过：**0 errors / 133 warnings / 22 information**。与 JVM 测试、Debug APK 和仪器测试 APK 一起执行，`BUILD SUCCESSFUL in 10m 10s`，158 个任务（13 执行、145 up-to-date）。

续检期间发现 AGP 8.7.3 独立测试 lint 模型的主源码目录为空；本机 lint 实现会将空列表回退为模块根目录，测试任务长时间遍历构建文件。K1 重试也复现此问题。最终使用该版本原生支持的合并分析，将主源码和测试放入同一次分析，保留默认 K2 分析器与全部规则：

```powershell
$env:ANDROID_HOME = 'C:/Users/15917/AppData/Local/Android/Sdk'
.\gradlew.bat :app:lintDebug test :app:assembleDebug :app:assembleDebugAndroidTest '-Pandroid.experimental.lint.analysisPerComponent=false' -PtargetAbis=x86_64 -x :app:syncOpenWebUiAssets --console=plain
```

该参数仅用于本次本机验证，未写入项目配置；没有新增 baseline 或全局关闭 lint 规则，唯一局部例外为上文已说明的 `QUERY_ALL_PACKAGES` 声明。前端资源使用此前已通过构建的内容。排查中的默认分析、K1 分析为工具性能问题而中止；首次 K1 调用还因 PowerShell 拆分未加引号的参数而在启动阶段失败，已更正。首次完整合并 lint 为 2 errors / 133 warnings / 22 information；两个阻塞项已按上文处理，最终完整复查通过。

### 剩余 lint 诊断

以下为最终 XML 报告的全部分类，数量合计为 133 warnings 和 22 information。保留诊断以便后续维护，未通过批量抑制获得“零警告”。原始 HTML/XML/TXT 位于本机 `android/app/build/reports/lint-results-debug.*`。

| 类别 | 数量 | 审阅结果与后续处理 |
| --- | ---: | --- |
| AGP / 依赖版本建议 | 27 W | `AndroidGradlePluginVersion` 2、`GradleDependency` 25；依赖升级涉及构建兼容，留作独立维护。 |
| WebView 能力检查与 JavaScript | 15 W | `RequiresFeature` 14、`SetJavaScriptEnabled` 1；插件已有集中式 WebView 能力快照和运行门禁，嵌入前端需要 JavaScript。本轮未修改桥接或安全策略。 |
| 第三方 TLS 实现 | 3 W | `TrustAllX509TrustManager` 均定位到 Bouncy Castle `bcpkix-jdk15to18:1.72` JAR；需结合依赖用途独立审查，不能据此认定应用网络调用已绕过证书校验。 |
| Application 作用域引用 | 4 W | `StaticFieldLeak`；检查到容器使用 `applicationContext`，长期引用属于现有 provider / supervisor 生命周期。 |
| 未使用资源与启动图标 | 29 W | `UnusedResources` 28、`IconLauncherShape` 1；含动态加载的 Cordova 配置与既有占位资源，不盲删；保留当前品牌启动图标。 |
| 文本国际化 | 16 W | `HardcodedText` 10、`SetTextI18n` 6；主要为现有原生控件/小组件，属于后续国际化维护。 |
| 布局建议 | 7 W | `DisableBaselineAlignment` 2、`NestedWeights` 4、`Overdraw` 1；既有 XML 布局优化建议，未发现本轮主题2 Compose 页面因此出现错位。 |
| 存储空间查询 | 3 W | `UsableSpace`；保留现有实现，后续可专项检查空间估算与配额语义。 |
| 调试接收器 | 1 W | `ExportedReceiver`；来自调试测试接收器，仍保留诊断，不扩大发行权限。 |
| SDK / 平台兼容与播放器建议 | 28 W | `ObsoleteSdkInt` 21、`UnusedAttribute` 3、`InlinedApi` 1、`ChromeOsAbiSupport` 1、`InflateParams` 1、`PictureInPictureIssue` 1；涉及旧分支、API 31 小组件预览属性、通知权限常量、ABI 和播放器手动画中画等现有实现，未为消除建议而改变行为。 |
| Compose 状态装箱优化 | 22 I | `AutoboxingStateCreation`；性能建议，未扩大为无关状态重构。 |

最终合并 Manifest 已核对包含生物识别和现有应用枚举权限，原生交互 Activity 仍为 `exported=false`；API 35 安装结果显示这两个平台权限已授予。真实生物识别交互仍需下文设备人工验收。

续检最终构建的 Debug/Release JVM 各 **550 项通过**。通知图标和加载资源修复后，API 35 UI **17/17**、额外宽屏 **1/1** 再次通过；新生成的 10 张主题截图与此前截图文件哈希全部一致。

### 扩展设备回归与限制

补齐 Manifest 并安装最终 APK 后，全量 API 35 仪器测试共执行 **44 项，42 通过、2 失败**（160.141s）。其中 `AppleInterfaceUiTest` 7 项、`Theme2ReviewUiTest` 10 项和第三方 WebView 安全测试 10 项全部通过。失败均来自 `NativeAgentBridgeDeviceTest`：

- `nativeDialogDenialAndReplayProtectTheActualRoomTodo`
- `confirmedNavigationOpensNativeCalendarAndBackRestoresAgent`

两项均在等待可访问性树中的原生确认按钮时发生 15 秒超时；单独重跑该类仍失败，进一步单跑导航用例同样超时。截图观察到模拟器桌面上的 **System UI isn't responding** 弹窗；冷启动隔离模拟器后仍观察到该系统弹窗，随后的测试返回 `Process crashed`，未取得有效重测结果。系统异常与最初超时的因果关系尚未证实，不能将这两项认定为已通过或已知基线问题。本轮没有修改 Agent 测试，也没有放宽生产确认条件或增加超时以绕过失败。

因此，主题2原生 UI 回归通过，全量仪器测试尚未全绿。需在健康的 API 35 设备上复测上述两项，再判断是否还存在 Agent 确认/导航问题。本轮所有设备数据均为离线合成数据，未使用真实校园账号。

可用 `-e themeReviewScreenshots true` 为 `Theme2ReviewUiTest` 生成无个人数据截图；截图仅保存在本机，不纳入 Git。

本轮生成并复核 10 张截图，覆盖三配色、放大文字、搜索返回、导航和辅助效果。`git diff --check` 通过；已剔除前端构建自动更新的 Pyodide lock 差异，未包含 APK、缓存或本机配置。

## 变更文件

- `ui/theme/AppearanceModels.kt`、`ui/AppearanceChange.kt`、`ui/screens/ProfileScreens.kt`：统一名称、说明、可访问语义和自适应选择卡片/徽标。
- `ui/BjtuMisApp.kt`、`ui/NavigationMotion.kt`：可保存路由状态、返回一致性、动效方向及标题/侧栏布局。
- `ui/screens/OverviewScreen.kt`、`ui/screens/HomeworkScreen.kt`：保留搜索和筛选。
- `NavigationMotionTest.kt`、`AppleInterfaceUiTest.kt`、`Theme2ReviewUiTest.kt`：返回方向与界面回归。
- `README.md`、原 Apple UI 设计记录与本文：同步当前名称和检查结果。
- lint 续检另涉及 `ScheduleExportStorage.kt`、`NativeAgentToolsPlugin.kt`、播放器/空教室组件签名、插件通知图标引用，以及加载图片的资源目录和首帧别名。
- `AndroidManifest.xml`：补齐生物识别平台权限，并记录现有应用枚举权限的单点 lint 审阅例外。

## 结论与剩余验收

在本轮已验证的原生组件、导航交互、三配色、窄屏/宽屏及大字体范围内，主题2符合现有设计预期；本轮发现的导航和布局问题已修复。存储、配色独立性、品牌图标和高风险确认边界保持兼容。

- `TODO: confirm`：真实账号下各业务页面的完整数据、附件、第三方网页及播放器画面；本轮源码检查不替代这些内容的逐页设备验收。
- `TODO: confirm`：API 26/31 兼容、TalkBack 人工朗读顺序与连续导航性能；本轮设备验证重点为 API 35 原生界面。
- `TODO: confirm`：有真实已录入生物特征的设备上，人工确认系统生物识别弹窗和取消/成功结果；本轮自动验证不模拟用户的生物识别确认。
- `TODO: confirm`：在无 System UI 异常的 API 35 设备上复测两项 Agent 原生确认/返回测试；当前失败原因未定，不能以主题 UI 通过代替。
