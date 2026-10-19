# v1.4.3 发布验证记录

开始日期：2026-10-19；候选版本：`v1.4.3` / `versionCode 10`。本文持续记录实际结果，未完成项不得视为发布成功。

## 来源与改动

- 上一正式版是 GitHub `v1.4.2`，源码提交 `947cf078fe9e163ac25a0de1b7b0e5c6da00ad07`，APK SHA-256 为 `6d01b66df07b187cc2474bbd89d9981de914e521cff37b1f0797f401fd27e839`。
- 本次从 `dev` 的 `8dd040350667eb8898485ee741a393939e5caa8e` 准备；主要功能差异见 [更新说明](release-notes-v1.4.3.md)。原生业务功能已在此前 API 31 真机验收中记录，本次不将其等同于 API 26/35 或真实校园账号端到端验证。
- 修正设备测试同步：原生桥测试保留生产帧时钟，通过日历标题与新增待办操作验证真实导航，避免依赖小屏首屏外的月视图控件；空教室测试等待下拉窗口节点后再点击；在旧 WebView 缺少安全传输时，验证插件自启拒绝启动并清理前台服务。没有降低生产运行门控。
- 平台 Fastify 升至 `5.12.5`，锁定 `fast-uri 3.1.8`、`find-my-way 9.10.0`，修复官方 npm 审计发现的现有高危依赖告警；未新增依赖种类或数据库迁移。

## 已完成的本地检查

- Android `test :app:assembleDebug :app:compileDebugAndroidTestKotlin` 成功，耗时 19 分 35 秒；Debug、Release 单元测试各 518 项，均无失败或跳过。完整执行 Open WebUI 构建和资产同步，未跳过资产任务。
- Open WebUI Vitest：13 文件、104 项通过。Svelte 类型检查存在既有基线，本次未将其列为通过项。
- 插件工具链：8 项契约生成物一致性、TypeScript 和模板检查通过；Manifest schema 的 docs/web 副本 SHA-256 一致。
- 更新依赖后，平台 typecheck、unit 与 e2e 检查通过；本机 PostgreSQL 集成用例因未设置 TEST_DATABASE_URL 跳过，待真实数据库 CI 验证。npm 官方生产依赖审计为 0 项漏洞。
- Android Studio 的目录锁启动问题已恢复。保留原 IDE 设置和缓存，失效 `.port`/`.pid` 隔离在本机恢复目录；未提交本机配置。

## 发布门槛（待完成）

- 候选 `eb3a4536a8d70370ccf44cfd5694614b4479adb6` 的 CI 39295184033：平台、真实 PostgreSQL、工具链和 JVM 通过；API 26/35 因桥测试加入 Compose 帧时钟后初始化超时失败，API 35 另有下拉框节点未就绪失败。原自启测试的安全传输门控已通过。TODO: confirm：同步修正后的完整设备矩阵结果。
- TODO: confirm：Android Studio 正式签名 APK、新版包名/版本/ABI/摘要与历史证书一致性。历史证书 SHA-256 必须为 `6808eb315b7619f375ed7a58abe24bd53125426203ceaf755d71290a46cf7af8`。
- TODO: confirm：GitHub Release、同一制品的百度网盘永久分享、官网版本与下载链接上线及读回校验。
- TODO: confirm：平台新契约与修复依赖镜像验证、生产 API/worker 切换和健康检查。上次候选构建连接中断，未重启线上服务；官网与平台生产链接仍指向旧 release。

## 保护与回滚

用户原有 `ThirdPartyServiceInstaller.kt` 行尾字节、根目录 `.idea/` 与 `output/` 保留且不纳入本次提交。APK、密钥、mapping、缓存与构建日志不提交。构建自动产生的 Pyodide 锁文件漂移不作为依赖升级提交。

官网初始回滚目录：`/var/www/bjtu.cc/releases/20260909-120000/web`；平台初始回滚目录：`/opt/bjtu-plugin-platform/releases/20260909-151500`。发布前再次核对；保留原镜像、数据库和制品卷，不删除旧 release、不 force push、不覆盖历史资产。
