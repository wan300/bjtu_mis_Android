# v1.4.4 发布验证记录

发布完成：2026-10-20（Asia/Shanghai）。GitHub 最新正式版为 `v1.4.4` / `versionCode 11`，官网与百度备用下载已同步。

## 来源与验证

- 正式标签 `v1.4.4` 指向 `665c6a91c45c2024fe46a92f7a96a54324f9104b`，与 Android Studio 签名包的运行时代码一致。main 由 508e29c 非强制快进到该源码，标签与 main 原子推送；后续官网和本文提交不改变 Android 代码或标签。
- 兼容新的 SSO 表单、认证地址和校方 SM2 加密协议，修复匿名会话误判、失效验证码继续使用以及最终会话未就绪却返回登录成功的问题。变更位于 provider/parser/security 和 Compose 登录页，保留现有加密凭据与 Cookie 存储。详情见 [更新说明](release-notes-v1.4.4.md)。
- 最终本地 `gradlew.bat test assembleDebug -x npmCiOpenWebUi -x buildOpenWebUi -x syncOpenWebUiAssets --offline --console=plain` 成功，Debug、Release 各 532 项单测通过。首次完整检查的一项无关选课异步清理断言失败，最终复跑通过，未修改该模块。
- [源码 CI 39374226593](https://github.com/wan300/bjtu_mis_Android/actions/runs/39374226593) 第三次运行最终五项全部成功：Android JVM、API 26、API 35、插件工具链与 Node 22 平台/真实 PostgreSQL 集成。API 35 完成 33 项设备测试，0 skipped、0 failed。
- 前两次该矩阵分别出现 Chrome sandbox 环境失败、通知状态仍为 delivering 的断言及空教室菜单等待超时。没有更改代码、关闭沙箱、跳过用例或放宽断言，重跑后全部通过。
- 同提交的 [main 独立矩阵 39376008852](https://github.com/wan300/bjtu_mis_Android/actions/runs/39376008852) 的 JVM/工具链/平台通过，但 API 26 出现 Compose SnapshotStateObserver 跨线程异常，API 35 两项原生桥测试等待超时。最终发布依据上述同提交的完整成功矩阵；TODO: confirm：另行定位现有设备测试的偶发同步问题，不能将本次通过描述为已修复这些问题。
- Android 12 / API 31 HUAWEI 真机使用用户授权账号登录成功并进入首页。测试凭据未写入源码、测试 fixture 或提交。正式打包完成时设备已断开，未对正式签名包执行真机覆盖安装。
- 网证/企业微信原生跳转未接入：公开网页只提供扫码参数，没有本 App 对应的移动端 SDK、授权 schema 和回调契约。README 与更新说明明确这一限制。
- 检索生产引用确认数学 OCR/模型仅被旧 CAS 登录装配使用，但新版仍可能要求图片验证码，旧 CAS 兼容也仍需模型，因此移除模块/权重的条件尚不成立，本次保留。没有新增依赖、权限或数据库迁移；Open WebUI 源码未改，未重复其 Vitest 或已知存在基线诊断的 Svelte 类型检查。

## 正式签名 APK

- Android Studio `assembleRelease` 于 2026-10-20 13:23 完成，耗时 7 分 58 秒。内嵌前端全量构建和资产同步完成，Android R8 优化完成。
- 包名 `cn.edu.bjtu.mis`，`v1.4.4` / versionCode `11`，minSdk 26、targetSdk 35、`arm64-v8a`。
- 文件 `BJTU-MIS-v1.4.4.apk`，213,966,131 字节，约 204.05 MB。
- APK SHA-256：`349202ff4e6c3ec0fdef5d9180582d8054f41bc0b54cdc4d445d60691f0cd6a7`。
- `apksigner verify --verbose --print-certs` 验证 v2 签名成功，原正式证书 SHA-256：`6808eb315b7619f375ed7a58abe24bd53125426203ceaf755d71290a46cf7af8`。与 v1.4.3 权限列表相同，无 debuggable 和本机密钥配置条目。
- GitHub asset 658375813 的 size/digest 与本地一致。首次直连上传中断，确认草稿无残留资产后经可用代理重试成功，未更改 Git 全局代理。

## 下载与官网

- [GitHub 最新发布](https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.4.4)：Release 418030677 已于 2026-10-20 13:53:44（北京时间）正式发布，`/releases/latest` 返回 v1.4.4。
- 公开 [APK 下载](https://github.com/wan300/bjtu_mis_Android/releases/download/v1.4.4/BJTU-MIS-v1.4.4.apk) 已完整重新下载，大小与 SHA-256 均与本地签名包、GitHub asset digest 一致。
- 用户手动上传已核验的 APK，返回 [百度网盘永久分享](https://pan.baidu.com/s/171pb3tF9ow_kzpm60XfHcg?pwd=0721)，提取码 `0721`。全新匿名 HTTP 会话校验成功，公开文件页 HTTP 200，文件名、213,966,131 字节大小均正确，页面明确显示“过期时间：永久有效”（expiredType 0）。未从百度重新下载全包，不声称独立验证百度字节摘要。
- [官网](https://bjtu.cc/) 已切换至 `/var/www/bjtu.cc/releases/20261020-135358/web`。60 个静态文件逐一 SHA-256 校验通过；归档排除 `web/platform/`、APK 和本机配置。回环、服务器公开请求与本机独立公开请求读回的 HTML/JS 哈希均与本地一致。
- 首页两处 GitHub 主下载、两处百度备用入口均指向 v1.4.4；内置版本快照、实际发布时间、204.05 MB 大小和更新摘要已更新，保留历史版本。19 个 HTML 的脚本缓存参数同步为 `2026102002`，另更新 `site.js`，共 20 个网站文件。
- `node --check web/assets/js/site.js` 与 `git diff --check` 通过；`nginx -t` 成功，公开 `/platform/.env` 仍为 404，插件目录正常。本次只切换静态内容链接，未修改 Nginx 配置、插件平台、数据库或服务权限。
- 官网内容回滚目录：`/var/www/bjtu.cc/releases/20261020-023002/web`。新旧目录均保留；部署脚本在切换后的验证失败时自动恢复旧链接。未覆盖或删除历史发布、标签或网盘文件。

## 工作区保护

- 用户既有 `NativeCampusToolsDeviceTest.kt` 等待修复、`ThirdPartyServiceInstaller.kt` 行尾状态、根 `.idea/` 和 `output/` 保留，未纳入本任务提交。
- 构建产生的 Pyodide lock 漂移已恢复为 HEAD 内容，blob hash 一致；APK、mapping、签名配置、密钥和本机日志不进入 Git。
