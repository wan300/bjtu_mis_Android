# CI 校验入口

`plugin-platform-v3.yml` 保留原有 job 名称，PR 使用 `classify_changes.py` 按模块选择检查；push 和手动运行始终执行完整基线。共享契约、生成物、工作流、校验工具、安全规范和未知路径会扩大到所有模块。`git diff --no-renames` 同时考虑重命名前后路径，避免把代码移入文档目录后漏检。

汇总 job 复用原 `tooling` 的显示名 `Contract generation, SDK, CLI and package determinism`，拒绝被选中 job 的失败、取消、缺失或意外跳过。这样浏览器 smoke 拆分后失败仍使原工具链 required check 失败，已有保护上下文保持有效。当前远端分支保护配置是 **TODO: confirm**，本次不自动修改。

## 本地命令

```powershell
python -B -m unittest discover -s tools/ci/tests -v
Set-Location plugin-tooling
npm ci
npm run check:ci
npm run test:browser
```

`check:ci` 构建工具链一次，随后验证类型、模板、单测与确定性打包；浏览器 smoke 独立运行且在 CI 中仍为必需 job。可通过 `BJTU_PLUGIN_TEST_BROWSER` 指定 Chrome/Chromium/Edge 路径。每次 smoke 使用临时独立 profile，20 秒浏览器执行期限内失败会输出有界诊断。

平台在 `web/platform` 运行 `npm run check:ci`，只编译一次；本机未设置 `TEST_DATABASE_URL` 时数据库测试仍显示跳过，**CI 缺失该变量则立即失败**。原有 `typecheck`、`test`、`test:integration`、`test:e2e` 可继续单独使用。`:built` 命令只供本次已完成构建后的步骤使用。

## Android 安全测试实际执行

```powershell
python tools/ci/check_instrumentation.py android/app/build/outputs/androidTest-results/connected
```

检查从当前 WebView、Room migration、会话保活和插件自启测试源码发现测试方法，再核对 JUnit XML。缺报告、缺用例、失败或跳过必需用例均失败；仅 ArrayBuffer 专用测试可因平台能力不足跳过，Base64URL 兼容传输与基础安全桥接测试必须实际通过。

API 26/35 矩阵仍执行全部 instrumentation。测试设备必须安装支持 `DOCUMENT_START_SCRIPT` 与 `WEB_MESSAGE_LISTENER` 的 WebView，API 级别本身不能证明支持。CI 在测试前输出 `dumpsys webviewupdate` 便于定位；若 SDK 镜像自带版本过旧，必须升级测试 provider，不能放宽 XML 门禁。已有本地 `tools/run-webview-beta-instrumentation.ps1` 可使用经审查、与系统兼容的 APK；不从第三方镜像自动下载可执行 APK。远端运行结果以 PR 最新提交的 Actions 报告和 XML 门禁为准，本机验证不替代远端结果。

Android 前端依赖挂在 `merge*Assets`，纯 JVM 测试和仅 Kotlin 编译不触发 npm/Pyodide/Vite；实际 APK 打包和设备测试仍必须构建并同步完整前端。可用 `gradlew test :app:compileDebugAndroidTestKotlin --dry-run` 与 `gradlew assembleDebug --dry-run` 分别核对任务图。

### API 26 的固定测试 provider

Google APIs 26 镜像自带 Chrome 69，会跳过缺少 document-start/message-listener 支持的测试。API 26 CI 因此使用同 API/ABI 的 AOSP default 镜像，并通过 `prepare_webview.py` 安装官方 AOSP WebView 124.0.6367.219（minSdk 26）；API 35 保持 Google APIs 镜像。安全测试和无跳过门禁保持启用。

APK 来源固定为 `android.googlesource.com/platform/external/chromium-webview` 的 `android-15.0.0_r1/prebuilt/x86_64/webview.apk`，SHA-256 为 `b76f0b24f2f3f90dbbc7c23007721428b74eb0b2b969dc8315b5a9dc98469c8f`。下载拒绝重定向并校验摘要，缓存命中仍重新校验。制品与签名输出不进入仓库。

大型 APK 通过官方 Git 仓库 partial clone/sparse checkout 获取，固定 tag 同时校验 commit `479929d2f7c4e3484903132afa864b1f68dd2705` 和 APK 摘要，避免 Gitiles 大型 Base64 请求的 503。Git 传输关闭重定向并有界重试；小型公开证书 HTTPS 获取仅对临时网络错误重试。摘要、来源 commit 和签名错误均停止。API 26 的 userdata 分区为 4 GiB，用于测试 APK 安装/解压，插件配额保持不变。

官方 API 26 default 镜像预装 `com.android.webview`，框架却只登记 Google/Chrome 包名。脚本核验该官方镜像 `framework-res.apk` 的固定 SHA-256（`63dbee0f89fd1ed6a0ff123a495d53c5c9ce47cec8f47ca082065d567ac6f0e9`），然后仅将临时 APK Manifest 的根包名和四个 ContentProvider authority 字符串调整为框架已登记的 `com.google.android.apps.chrome`，这是 [Chromium 文档推荐的调试包名](https://chromium.googlesource.com/chromium/src/+/main/android_webview/docs/build-instructions.md)。引擎代码、native 库、资源、Manifest 权限及 WebViewLibrary 元数据保持原样；不修改框架白名单或系统分区。

临时 APK 使用 SDK zipalign 对齐，再使用同一官方固定 tag 的公开 AOSP development testkey 签名并验证证书；build-tools 36/37 的证书输出格式均支持，仍要求每个证书摘要匹配固定值。测试材料的摘要固定在脚本中，既不读取也不修改 App release/debug 签名配置。无需 ADB root、系统分区修改或签名检查绕过。

安装脚本默认要求恰好一个已连接 emulator，本地可通过 `--serial emulator-NNNN` 选择已连接实例；检查 qemu、API 26、userdebug/eng 与固定框架摘要，使用显式 serial 安装，选择后核对实际包名/版本。物理设备、错误摘要、错误环境、错误签名、选择失败均停止，不能继续执行后伪报通过。

## 契约与换行

生成物使用 `.gitattributes` 固定 LF；生成器只容忍已有 CRLF checkout，空白、内容、缺失文件等漂移继续失败。Registry 在生成前通过 draft 2020-12 schema 和原有语义不变量验证。Ajv 是 `plugin-tooling` 开发依赖，不进入插件或 Android runtime。

beta → stable 是合法元数据演进，不需要修改阶段性状态快照。历史 capability 的 runtime floor、确认、幂等和安全配额测试仍保留。远程 iframe 可以使用 `sandbox=""` 或更少授权的 sandbox；缺少 sandbox、未声明来源、弹窗/顶层导航/下载等危险 token 继续拒绝。
