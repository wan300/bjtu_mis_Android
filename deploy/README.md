# bjtu.cc 部署

## 插件平台

插件目录使用 `deploy/docker-compose.plugins.yml` 部署 PostgreSQL 16、迁移任务、Fastify API 和独立校验 worker。部署前：

1. 将 `web/platform/.env.example` 复制到服务器 `/etc/bjtu-plugin-platform.env`，填写 GitHub OAuth、32 字节 AES-GCM 密钥、管理员 GitHub ID 和数据库密码；不要提交该文件。
2. `/etc/bjtu-plugin-postgres-password` 只包含 PostgreSQL 密码，且必须与 `DATABASE_URL` 中的密码一致。
3. 备份 PostgreSQL、插件 artifact 卷和当前 Nginx 配置。在本地或 CI 构建并验证镜像，通过 `docker save` / `docker load` 传入生产机，使用下述预构建入口部署。不要在这台 1.6 GiB 生产机执行源码编译或 `up --build`。
4. 安装 `deploy/nginx/bjtu.cc.conf` 后先执行 `nginx -t`，确认 `/api/v1/`、`/api/v2/` 与 `/api/v3/` 只反代至 `127.0.0.1:15020`。

contract_v1 发布必须按顺序执行：先部署 additive 数据库 migration、`/api/v3` 与对应 Nginx 路由，再发布 Android contract_v1 runtime。旧 `/api/v2` 只保留 P0-A 目录、详情与制品读取；旧 `/api/v1` 仅保留迁移期兼容。新 worker 只接受 `bjtu-plugin.json`，旧插件必须由作者迁移并通过 `/api/v3` 重新发布。

v1.4.3 平台使用本地预编译产物组装的固定镜像，当前 release 的 `deploy/release-images.yml` 保存发布镜像记录。生产操作使用 `/usr/local/sbin/bjtu-platform`，其源码为本目录的 `platform-production.sh`；它同时加载去除全部 `build` 定义的 `docker-compose.production.yml` 与 `docker-compose.resources.yml`。先检查镜像已在本机，且必须使用不可变 digest，然后仅更新 API/worker：

```sh
image=sha256:8b68e5b5c1de7b8460f5839f37850c0c624558a625594d69faa60afd4791b7cf
bjtu-platform check /opt/bjtu-plugin-platform/current "$image"
bjtu-platform up /opt/bjtu-plugin-platform/current "$image"
```

本次未运行数据库迁移；数据库 dump 和制品备份保留在对应 release 的 `rollback/`，旧镜像以 rollback 标签保留，`deploy/rollback-images.yml` 记录其覆盖配置。回滚应用镜像时使用该配置并保留数据库和制品卷。具体摘要、健康检查、资源问题证据与验证限制见 [v1.4.3 发布验证记录](../docs/release-verification-v1.4.3.md)。

回滚时恢复旧 Nginx 配置并停止插件 Compose 服务；Android 会自动退化到目录缓存与已安装插件。数据库和 artifact 卷应保留以便恢复。

生产站点是仓库 `web/` 下除 `web/platform/` 以外的纯静态文件，由 `my-server` 上的 Nginx 直接提供。`web/platform/` 是后端源码，必须通过 Docker 构建，禁止复制到静态站点 release。Nginx 配置的仓库副本位于 `deploy/nginx/bjtu.cc.conf`，并对 `/platform/` 设置显式 404 作为纵深防护。

服务器布局：

- release 内容目录：`/var/www/bjtu.cc/releases/<timestamp>/web`
- 当前版本：`/var/www/bjtu.cc/current`（必须直接指向某个 release 下的 `web/` 目录）
- Nginx 配置：`/etc/nginx/sites-available/bjtu.cc`
- 启用链接：`/etc/nginx/sites-enabled/bjtu.cc`
- 证书：`/etc/letsencrypt/live/bjtu.cc/`

发布新版本时，应先创建 `/var/www/bjtu.cc/releases/<timestamp>/web/`，再把仓库 `web/` 中的静态内容上传到该目录，但必须排除 `web/platform/`。核对文件数与关键文件 SHA-256，并确认 release 中不存在 `platform/` 后再进行回环验证。随后原子更新 `current` 链接，使其直接指向新 release 的 `web/` 目录。若 Nginx 配置也有变更，再安装仓库中的配置，执行 `nginx -t`，成功后执行 `systemctl reload nginx`；仅静态内容更新不需要重载。

回滚内容版本时，把 `current` 原子指向上一个已验证 release 的 `web/` 目录。只有确认 `127.0.0.1:15000` 已恢复服务后，才可回滚本次架构迁移：恢复 `/etc/nginx/sites-available/bjtu.cc.before-direct-20260717-162630.bak`，然后依次执行 `nginx -t` 和 `systemctl reload nginx`。如果该端口仍未监听，恢复旧配置会使站点再次不可用，不应作为回滚手段。

不要把 `web/platform/`、Android 工程、Open WebUI、密钥、本机配置、APK/AAB、构建缓存或其他生成物上传到静态站点目录。

## 历史监控与可用性告警

轻量监控由 `deploy/monitoring/` 提供，使用 Ubuntu 已有 Python 3、Docker、systemd 和 sysstat，不运行额外的数据库或监控容器，也不开放指标端口。

- `sysstat-collect.timer` 每分钟保存 CPU、内存、换页、磁盘和网络历史；每日汇总/清理，保留 14 天，3 天后压缩。Debian cron helper 在 systemd 主机上自行跳过，避免双重采集。
- `bjtu-monitor.timer` 每分钟保存主机 PSI、负载、可用内存、swap、磁盘，以及各运行容器的 cgroup CPU/内存、OOM、PID、重启和健康状态。记录官网、目录和回环 API 健康探测，不记录业务响应正文、用户 IP、Cookie 或环境变量。
- JSONL 历史目录 `/var/log/bjtu-monitor/` 仅 root 可读，保留 14 天，每天最多 16 MiB；达到上限会明确使采集失败并写 journal。状态目录 `/var/lib/bjtu-monitor/`。采集器最多使用 0.1 CPU、96 MiB 内存，低 CPU/IO 优先级、50 秒总运行超时；容器查询失败仍保留主机记录和缺失警告。
- 可用内存低于 160 MiB、根分区达到 85%、容器工作集达到上限 85%、OOM/重启、持续 PSI/负载或探测异常时写入样本警告和 journal。资源警告当前保存在本机；GitHub Issue 通知针对下述外部可用性检查。

查看采集状态及历史：

```sh
systemctl list-timers 'sysstat*' 'bjtu-monitor*'
systemctl status bjtu-monitor.service --no-pager
journalctl -u bjtu-monitor.service --since '1 hour ago' --no-pager
sar -u -r -S -W -q -f /var/log/sysstat/sa$(date +%d)
sar -d -n DEV -f /var/log/sysstat/sa$(date +%d)
tail -n 2 /var/log/bjtu-monitor/metrics-$(date -u +%F).jsonl
```

`.github/workflows/availability.yml` 从 GitHub 托管 runner 约每 5 分钟探测公开官网与数据库支撑的 `/api/v3/plugins?limit=1`，校验 HTTP 200、首页标识和目录契约字段，保留 TLS 校验并拒绝重定向。失败最多尝试 3 次。持续失败时创建一条分配给 `wan300` 的 Issue；后续失败不重复发帖，恢复时留言并关闭。只使用本次工作流的 `GITHUB_TOKEN`，不向服务器复制个人 GitHub token。Issues 及探测摘要为公开内容，不包含服务器资源数据或秘密。

手动运行工作流并选中 `drill`，会在真实服务健康时创建一条明确标为 TEST 的演练 Issue、验证重复失败去重，再以真实健康结果更新并关闭。线上服务不会为演练停止。Issue 创建/分配可验证；邮件、推送等实际呈现取决于用户 GitHub 通知设置。[GitHub 定时任务可能延迟，公开仓库长期无活动也可能被停用](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)，因此这不是有严格时限保证的告警服务；维护时应确认工作流持续有定时运行记录。它不依赖本地电脑或生产机存活。

## 容器预算与回滚

当前共享主机 2 CPU、约 1608 MiB RAM、2047 MiB swap。BJTU 初始资源预算：

| 服务 | RAM 上限 | RAM + swap 总上限 | CPU 上限 | PID 上限 |
| --- | ---: | ---: | ---: | ---: |
| API | 192 MiB | 320 MiB | 0.50 | 128 |
| worker | 256 MiB | 384 MiB | 0.35 | 128 |
| PostgreSQL | 256 MiB | 384 MiB | 0.40 | 128 |

同机其他服务原有的两个 256 MiB 内存上限不调整。BJTU RAM 上限合计 704 MiB，与其他容器上限合计 1216 MiB，剩余约 392 MiB 给宿主机；这是根据当前负载设置的初始预算，并不证明所有业务同时满负载仍有足够容量。应根据历史工作集、PSI、OOM 和延迟调整，避免把峰值正常业务误判成异常。数据库 shared_buffers 等参数保持原值，未运行数据库迁移。

本次在线更新不重建容器，Docker 会保存配置并在容器/宿主机重启后沿用；后续重建必须通过预构建入口，以 Compose 文件保留预算。`check` 验证入口不会部署；`up` 固定使用 `--no-deps --no-build --pull never`，不会启动迁移任务或重建数据库。该约束保护标准部署路径，无法限制 root 另行执行任意 Docker 构建。

安装代码：`/opt/bjtu-ops/releases/20261020-monitoring-v1`；当前入口：`/opt/bjtu-ops/current`。原配置、unit 开关和资源值在 root-only 的 `/var/backups/bjtu-ops/20261020-monitoring-v1/`。安装器与在线限额脚本均在验证失败时自动回滚本次变更，人工回滚可执行：

```sh
ops=/opt/bjtu-ops/releases/20261020-monitoring-v1/deploy/monitoring
backup=/var/backups/bjtu-ops/20261020-monitoring-v1
python3 "$ops/resource_limits.py" rollback --backup-dir "$backup"
python3 "$ops/install.py" rollback --backup-dir "$backup"
```

第一步恢复原来的有效资源分配且保持容器身份；第二步恢复 sysstat/监控配置和开关，保留已采集历史与业务数据。此 Docker 版本在线更新 `--memory 0` 不会清除旧限额，`--memory-swap -1` 也未清除已有 cgroup swap 上限，因此原无限额容器用 8 GiB RAM / 10 GiB RAM+swap、CPU quota -1、PID -1 恢复等效无限额；这些上限高于整台主机可用的 RAM+swap，配置数字不会变回零。该行为已在隔离临时容器验证。不要手改 Docker 内部元数据。若只需撤销某一个限额，应先审查其备份与当前容器身份。

撤销外部告警时，在 GitHub Actions 中停用 `bjtu.cc availability` 工作流，并通过普通提交撤销对应 schedule；只关闭属于本监控的演练/故障 Issue。无需停止官网、修改 Nginx 或恢复数据库备份。
