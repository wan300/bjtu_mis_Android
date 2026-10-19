# v1.4.3 发布验证记录

发布完成：2026-10-20（Asia/Shanghai）。GitHub 最新正式版为 `v1.4.3` / `versionCode 10`，官网与百度备用下载已同步。

## 来源与最终 CI

- 上一正式版为 `v1.4.2`，源码 `947cf078fe9e163ac25a0de1b7b0e5c6da00ad07`。本次功能来自原 `dev` 的 `8dd040350667eb8898485ee741a393939e5caa8e`，变化见 [更新说明](release-notes-v1.4.3.md)。
- 发布标签 `v1.4.3` 指向 `1d369f2e4db09201ad1b98a27f15371ce55d89b8`；main 与标签以非强制原子推送发布。签名包的运行时代码来自 `eb3a453`，到最终标签只修改设备测试与发布文档，运行时代码未变。
- [最终 CI 39300506242](https://github.com/wan300/bjtu_mis_Android/actions/runs/39300506242) 的五个任务全部成功：API 26、API 35、Android JVM、插件工具链、Node 22 平台与真实 PostgreSQL 集成。
- API 35 完成 33 项设备测试；API 26 日志完成 40 项，其中旧 WebView 不支持的 7 项高级安全传输场景按已有基线跳过。新自启测试验证缺少安全传输时拒绝启动、保留授权并停止前台服务，未降低生产门控。
- 原生桥测试使用 Compose 语义验证日历标题与新增待办入口，并在桥轮询时显式等待 Compose 空闲、推进测试帧时钟；空教室下拉框等待弹出窗口节点后再点击。两次早期 CI 的测试同步失败已由最终矩阵修复验证。

## 正式签名 APK

- Android Studio 于 2026-10-20 01:55 生成原正式签名包：`cn.edu.bjtu.mis`，`v1.4.3` / `10`，minSdk 26、targetSdk 35、`arm64-v8a`。
- 文件：`BJTU-MIS-v1.4.3.apk`；大小：213,933,263 字节，约 204.02 MB。相比 v1.4.2，增量主要来自内嵌前端约 55 MB 的离线 Python 运行库。
- APK SHA-256：`cfcc2e753ab7cffdeae1c6749c6db0ad8800f2ef3faea586bbc7d317e35553f3`。
- `apksigner verify --verbose --print-certs` 通过 v2 签名；证书 SHA-256：`6808eb315b7619f375ed7a58abe24bd53125426203ceaf755d71290a46cf7af8`，与旧正式版一致。新旧权限列表相同，无 debuggable 标记，未发现本机密钥配置文件误入 APK。

## 下载与官网核验

- [GitHub 最新发布](https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.4.3)：Release 417585256 已正式发布并被 `/releases/latest` 返回，时间为 2026-10-20 02:28:48（北京时间）。公开 [APK 下载](https://github.com/wan300/bjtu_mis_Android/releases/download/v1.4.3/BJTU-MIS-v1.4.3.apk) 已完整重新下载，大小和 SHA-256 与本地签名包、GitHub asset digest 三方一致。
- [百度网盘永久分享](https://pan.baidu.com/s/1mjOubaICLAPDsB198h9ZeA?pwd=0721)，提取码 `0721`。客户端上传与永久分享设置已核对；全新匿名 HTTP 会话提取码校验返回成功，公开文件页 HTTP 200，文件名为 `BJTU-MIS-v1.4.3.apk`，大小为 213,933,263 字节。未从百度重新下载全包，因此未声称独立验证百度文件字节摘要。
- [官网](https://bjtu.cc/) 已切换至 `/var/www/bjtu.cc/releases/20261020-023002/web`。60 个静态文件逐一 SHA-256 校验通过；归档排除 `web/platform/`、APK 和本机配置。回环与公开请求读回的 HTML/JS 哈希均与本地一致。
- 首页两处 GitHub 主下载和两处百度备用入口均指向 v1.4.3；内置版本快照、大小与真实发布时间已更新。20 个 HTML/JS 文件更新版本快照或缓存参数，日期统一按 Asia/Shanghai 展示，历史版本保留。
- `node --check web/assets/js/site.js` 与 `git diff --check` 通过；`nginx -t` 成功，公开 `/platform/.env` 仍为 404，插件目录正常。Nginx 配置未变，本次只原子切换静态内容链接。

## 平台部署与服务器恢复

- Fastify `5.12.5`、fast-uri `3.1.8`、find-my-way `9.10.0`；官方 npm 生产依赖审计为 0 项漏洞。平台契约、Schema 与校验器已支持仅可选声明的 `android.app.autoStart@1`。
- 平台使用本地 Node 22 预编译产物，在服务器仅组装生产依赖镜像，避免在生产机运行 TypeScript 编译。镜像内契约自检和 22 项单元测试通过；首次镜像测试因测试相对目录错误失败，使用正确的仓库层级工作目录后全部通过。
- API/worker 均使用镜像 `sha256:8b68e5b5c1de7b8460f5839f37850c0c624558a625594d69faa60afd4791b7cf`，平台 current 为 `/opt/bjtu-plugin-platform/releases/20261020-013623`。通过 `deploy/release-images.yml` 固定镜像，以 `--no-deps --no-build` 只更新 API/worker；未运行数据库迁移、重建数据卷或修改权限、网络和 Nginx 配置。
- PostgreSQL dump、制品目录和 Nginx 已备份于该 release 的私有 `rollback/`；保留旧 API/worker 镜像标签及旧 release。新服务健康检查、公开目录、契约哈希和零重启状态通过。
- 构建期间曾出现 SSH 与 API 超时，用户重启服务器后恢复。旧启动内核日志未发现 OOM、panic 或 lockup，临时 builder 的 OOMKilled 为 false；日志显示 TypeScript 编译停滞、15 秒健康检查延迟到 11 分 39 秒。主机 2 CPU、约 1.6 GiB RAM、2 GiB swap；sysstat 历史目录为空。资源压力是可能原因，缺少历史指标，不能断言已证明 OOM 或唯一根因。改用本地预编译后组装和部署期间 API 正常、swap 为 0。

## 其他验证与范围

- 本地 Android `test :app:assembleDebug :app:compileDebugAndroidTestKotlin` 成功；Debug、Release 单元测试各 518 项通过。完整构建并同步 Open WebUI 资产。
- Open WebUI Vitest：13 文件、104 项通过。Svelte 类型检查存在既有基线，本次未列为通过项。
- 工具链契约生成、类型、模板、包确定性和共享 Schema 一致性通过；平台本地 typecheck、22 项 unit、8 项 e2e 通过，真实数据库验证由最终 CI 完成。
- 本次无连接设备，未执行新版正式签名包的真机覆盖安装，也未重新执行真实校园账号端到端验收；此前 API 31 验收不等同于本次覆盖安装。
- 用户原有 `ThirdPartyServiceInstaller.kt` 行尾字节、根目录 `.idea/` 与 `output/` 保留，不纳入发布提交。APK、密钥、mapping、缓存和本机日志不提交；构建产生的 Pyodide 锁漂移不作为升级提交。
- 官网旧内容回滚目录：`/var/www/bjtu.cc/releases/20260909-120000/web`；平台旧目录：`/opt/bjtu-plugin-platform/releases/20260909-151500`。保留备份与制品卷，不覆盖历史标签或资产，不 force push。
