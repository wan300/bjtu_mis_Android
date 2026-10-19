# bjtu.cc 部署

## 插件平台

插件目录使用 `deploy/docker-compose.plugins.yml` 部署 PostgreSQL 16、迁移任务、Fastify API 和独立校验 worker。部署前：

1. 将 `web/platform/.env.example` 复制到服务器 `/etc/bjtu-plugin-platform.env`，填写 GitHub OAuth、32 字节 AES-GCM 密钥、管理员 GitHub ID 和数据库密码；不要提交该文件。
2. `/etc/bjtu-plugin-postgres-password` 只包含 PostgreSQL 密码，且必须与 `DATABASE_URL` 中的密码一致。
3. 备份 PostgreSQL、插件 artifact 卷和当前 Nginx 配置，再运行 `docker compose -f deploy/docker-compose.plugins.yml up -d --build`。
4. 安装 `deploy/nginx/bjtu.cc.conf` 后先执行 `nginx -t`，确认 `/api/v1/`、`/api/v2/` 与 `/api/v3/` 只反代至 `127.0.0.1:15020`。

contract_v1 发布必须按顺序执行：先部署 additive 数据库 migration、`/api/v3` 与对应 Nginx 路由，再发布 Android contract_v1 runtime。旧 `/api/v2` 只保留 P0-A 目录、详情与制品读取；旧 `/api/v1` 仅保留迁移期兼容。新 worker 只接受 `bjtu-plugin.json`，旧插件必须由作者迁移并通过 `/api/v3` 重新发布。

v1.4.3 平台使用本地预编译产物组装的固定镜像，当前 release 的 `deploy/release-images.yml` 保存镜像覆盖配置。维护该部署时同时传入覆盖文件，避免在生产机重新编译；仅更新 API/worker 的命令为：

```sh
docker compose -p bjtu-plugin-platform \
  -f /opt/bjtu-plugin-platform/current/deploy/docker-compose.plugins.yml \
  -f /opt/bjtu-plugin-platform/current/deploy/release-images.yml \
  up -d --no-deps --no-build api worker
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
