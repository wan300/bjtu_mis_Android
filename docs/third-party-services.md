# BJTU MIS Web 插件 Manifest v3 / contract_v1

本文件描述当前唯一可安装、更新和运行的第三方插件契约。Capability 的权威机器可读
来源是
[`plugin-tooling/contracts/capability-contracts.json`](../plugin-tooling/contracts/capability-contracts.json)；
Manifest、marketplace schema、TypeScript 类型、Kotlin descriptor/router/validator 和
[Capability API 参考](generated/plugin-capability-api.md)均由它确定性生成。

旧 Manifest v1/v2 与 P0-A v3（`bjtu-service.json`）不会获得桥或网络，只能在救援入口
导出数据。P0-A 仅能在 publisher subject、plugin ID 和数据 schema 兼容时原位升级为
contract_v1；回滚到 P0-A 也只会恢复救援状态。

## 1. 快速开始

仓库内工具链包含 `@bjtu-mis/plugin-sdk`、`@bjtu-mis/plugin-cli` 和
`create-bjtu-plugin`，本轮不发布到 npm。开发仓库中的构建方式：

```powershell
Set-Location plugin-tooling
npm ci
npm run build
node packages/create-bjtu-plugin/dist/index.js my-plugin --id io.example.demo
Set-Location my-plugin
npm install
npm run dev
```

生成模板是 Vanilla TypeScript + Vite。浏览器开发模式使用 Mock Host；安装 debug APK
并配置 `adb` 后，可在插件目录运行：

```powershell
bjtu dev --android
```

该命令只向 debug 包的显式 receiver 开启当前插件的联调 transport，配置
`adb reverse`，并在退出时撤销。HTTP 与 HMR WebSocket 都经稳定插件 origin 转发到
loopback；release source set 不包含 receiver 或 transport。`bjtu-plugin.dev.json`
只允许保存 Mock/HMR 配置，禁止进入发布包。

## 2. 包结构

```text
bjtu-plugin.json
bjtu-marketplace.json       # 大厅投稿必需；GitHub 直链安装可省略
bjtu-plugin.dev.json        # 仅本地开发；不得打包
dist/
  index.html
  icon.svg
  assets/
```

`entrypoint`、`icon`、migration entrypoint 和截图路径都相对于 `dist/`。发布包只包含
`bjtu-plugin.json`、可选的 `bjtu-marketplace.json` 和 `dist/`。

## 3. 精简 Manifest

`bjtu-plugin.json` 示例：

```json
{
  "schema_version": 3,
  "id": "io.example.demo",
  "name": "Demo",
  "version": "1.0.0",
  "entrypoint": "index.html",
  "icon": "icon.svg",
  "capabilities": {
    "required": ["runtime.lifecycle@1", "network.request@1"],
    "optional": ["storage.blob@1"]
  },
  "origins": {
    "connect": ["https://api.example.com"],
    "media": ["https://cdn.example.com"]
  },
  "data_schema_version": 1
}
```

规则：

- `runtime.lifecycle@1` 必须是 required Capability。
- optional Capability 首次安装默认关闭，用户授权后才可用。
- 空 `origins`、`configuration` 和 `capabilities.optional` 必须省略。
- `origins` 按 `connect`、`media`、`frame`、`navigation` 分离；公开包只接受标准 HTTPS
  origin，不接受路径、通配符、校园域名、私网、回环或链路本地地址。
- `origins.frame` 需要 `remote.frame@1`。远程 frame 永远无原生桥。
- 使用 `storage.kv@2` 或 `storage.blob@1` 时必须声明正整数
  `data_schema_version`；版本增加时必须提供 `migration_entrypoint`。
- `permissions`、`runtime_version`、`min_runtime_version`、`bridge_origins`、内嵌
  marketplace 信息及 P0-A 的平铺 capability/origin 字段全部禁止。

完整结构见
[`third-party-service-manifest.schema.json`](third-party-service-manifest.schema.json)。

## 4. Marketplace 元数据

`bjtu-marketplace.json` 示例：

```json
{
  "description": "A small demonstration plugin.",
  "author": "Example Team",
  "category": "other",
  "tags": ["demo"],
  "license": "MIT",
  "screenshots": [
    { "src": "screenshots/home.webp", "alt": "Home screen" }
  ]
}
```

大厅投稿必须提供描述、作者、分类和标签；schema 位于
[`bjtu-marketplace.schema.json`](bjtu-marketplace.schema.json)。

## 5. Capability 与 SDK

稳定 Capability 覆盖 lifecycle、configuration、remote frame、外部导航、身份、教务、
邮件读取和只读 `campus.request@1`。beta Capability 包括：

- `network.request@1`
- `storage.kv@2`
- `storage.blob@1`
- `cache.resource@1`
- `academic.userCourses.command@1`
- `academic.homework.submit@1`
- `mail.send@1`
- `android.accessibility.events@1`
- `android.accessibility.nodes@1`
- `android.accessibility.actions@1`
- `android.packages.read@1`
- `android.settings.open@1`
- `android.device.info@1`、`android.network.status@1`、`android.battery.status@1`
- `android.haptics.perform@1`、`android.files.pick@1`、`android.files.save@1`、`android.media.pick@1`
- `android.share.open@1`、`android.notifications.post@1`、`android.location.read@1`
- `android.calendar.read@1`、`android.calendar.write@1`、`android.camera.capture@1`
- `android.audio.record@1`、`android.sensors.read@1`、`android.biometric.verify@1`
- `android.session.keepAlive@1`（runtime 3）
- `android.app.autoStart@1`（runtime 4，仅 `capabilities.optional`）

精确方法、请求/响应 schema、权限、确认、幂等、配额、超时、错误和平台支持范围见
[生成的 API 参考](generated/plugin-capability-api.md)。

插件只使用 SDK 命名空间，不直接访问 transport：

```ts
import { BjtuPluginError, createBjtuPluginSdk } from '@bjtu-mis/plugin-sdk';

const bjtu = createBjtuPluginSdk();
const host = await bjtu.runtime.handshake();
await bjtu.runtime.ready();

const profile = await bjtu.campus.getProfile();
console.log(profile.data, profile.meta.syncedAt, profile.meta.fromCache);

const controller = new AbortController();
const response = await bjtu.network.request(
  { url: 'https://api.example.com/data', method: 'GET' },
  {
    signal: controller.signal,
    onProgress: ({ loaded, total }) => console.log(loaded, total)
  }
);

try {
  await bjtu.storage.kv.set('theme', 'dark');
} catch (error) {
  if (error instanceof BjtuPluginError) console.error(error.code, error.message);
}
```

SDK 公开 `runtime`、`configuration`、`network`、`storage.kv`、`storage.blob`、
`cache`、`navigation`、`campus`、`mail` 和 `android`。常用身份、教务和邮件方法的 `data`
结构由 Registry 生成具体 TypeScript 类型；仅通用 `campus.request@1` 的业务载荷
保持 `unknown`。所有校园读取统一返回：

```ts
{
  data: T,
  meta: {
    syncedAt: string,
    source: 'cache' | 'network' | 'mixed',
    coverage: 'complete' | 'partial' | 'unknown',
    fromCache: boolean
  }
}
```

### Android 系统自动化与原生能力

以下 22 项 beta Capability 只在 Android API 26+ 可用，仍经 publisher+plugin 的稳定
本地 main-frame origin 和 protocol v2 调用。用户必须在首次安装或更新增量审阅中授权；
授权按 publisher+plugin 持久保存，状态订阅可在页面关闭或进程重启后由最多四个后台
WebView runtime 恢复。无障碍相关调用还要求用户在系统设置中另行启用 BJTU MIS 插件
无障碍服务。服务未启用时，节点、事件订阅和动作返回 `capability_unavailable`，错误详情
包含可打开的 `android.settings.ACCESSIBILITY_SETTINGS` action。

```ts
const status = await bjtu.android.accessibility.events.getStatus();
const subscription = await bjtu.android.accessibility.events.subscribe({
  eventTypes: ['viewClicked', 'windowContentChanged'],
  packageNames: ['com.example.app'],
  includeSource: true,
  persistent: true
});

const dispose = bjtu.android.accessibility.events.onReceived((event) => {
  console.log(event.eventType, event.source);
});

const root = await bjtu.android.accessibility.nodes.getRoot({
  maxDepth: 16,
  maxNodes: 1024
});
await bjtu.android.accessibility.actions.performNode({
  idempotencyKey: crypto.randomUUID(),
  nodeId: root.nodeId,
  action: 'click'
});
```

- `events` 每插件最多 16 个订阅、每订阅最多 60 events/s。`persistent: true` 会在页面
  关闭或 App 进程重启后恢复；前台插件页面优先接收，后台 WebView 不重复派发。
- `nodes` 每次快照最多 4,096 节点、深度 64，opaque `nodeId` 30 秒失效。密码、
  邮箱、姓名、地址和电话输入值不返回；节点以 `sensitive: true` 标识脱敏。
- `actions` 支持节点动作、全局动作和最多 16 笔画的手势，每分钟最多 120 次。首次或
  增量授权后不再逐次弹窗，但每次仍必须提供 idempotency key；加密回执只保存摘要和
  结果，不保存输入文本、节点文本或手势轨迹。
- `packages` 返回包名、标签、版本、UID、启用/系统状态、安装更新时间、请求/授予权限、
  最多 4,096 个组件和签名 SHA-256；不返回 APK 路径、应用私有数据或图标字节。
- `settings.open` 每分钟最多 30 次，只接受 `android.settings.*` action 和可选包名。
  宿主只构造无 data 或 `package:` data 的隐式 Intent，不接受 extras、显式组件或任意 URI。
- 后台自动化全局最多运行 4 个插件，并显示持续通知及“全部停止”。插件删除、禁用、
  授权撤销、publisher 不匹配、能力声明丢失、更新进入复审或无障碍服务断开时立即停止
  runtime、清除订阅并失效 node handle。
- `network`、`battery` 和 `sensors` 可使用 `persistent: true` 订阅。每插件最多 16 个；
  网络/电池最多 60 events/s，传感器最多 20 Hz。设备和网络信息不包含硬件稳定 ID、
  SSID、BSSID、MAC 或 IP。
- `files`、`media`、`share`、`camera` 和 `biometric` 必须由前台插件页面触发系统 UI；
  后台调用返回 `foreground_required`。文件或媒体只返回隔离加密 Blob handle，永不返回
  raw URI、任意路径或可持续 URI。
- 定位只允许前台一次性读取，最长 60 秒，不请求后台定位；录音只能由可见前台 runtime
  启动，单段最多 10 分钟，停止后保存为隔离加密 Blob。运行时权限、系统选择器和生物识别
  弹窗仍由 Android 控制，持久授权不能绕过它们。
- 日历读取限制为 366 天/200 条；日历写入、文件保存、通知和录音命令使用 idempotency
  key 与加密摘要回执，但不再显示宿主逐次确认。通知每插件每小时最多 60 条；haptics
  单次最多 1 秒且不得循环。

这些能力当前只面向项目既有 GitHub APK 分发。`QUERY_ALL_PACKAGES`、无障碍服务和
special-use 前台服务在任何 Google Play 发布前必须重新完成政策与数据范围审查。

迁移页面只能使用 `createBjtuPluginMigrationSdk()`，其表面仅包含影子 KV 和显式
`commit()`；网络、校园读取和 Command Capability 不可用。

## 6. Protocol v2 与桥边界

SDK 内部 protocol v2 请求为：

```json
{
  "protocolVersion": 2,
  "requestId": "request-id",
  "capability": "identity.profile@1",
  "method": "getProfile",
  "params": {}
}
```

响应使用统一成功/错误 envelope；事件包含 `eventId`、`capability`、`event` 和可选
`requestId`。lifecycle 事件由契约固定为 `resume`、`pause`、`theme`、`resize`、
`network` 与 `back`；`back` listener 返回 `true`（也可返回
`Promise<true>`）才表示已消费。宿主只在没有确认消费时回退 WebView 历史或关闭页面，
不会再并行派发 DOM back 事件。SDK 把宿主错误转换为 `BjtuPluginError`，并通过
`AbortSignal` 发送取消。
底层对象不属于公共 API，宿主也不再注入 `window.BjtuService`。

原生桥只注入由不可变 publisher subject 与 plugin ID 决定的稳定本地 HTTPS origin，
且仅接受该 origin 的 main-frame 消息。`bridge_origins` 是宿主固定为 self 的不变量，
不是作者声明。远程 frame、popup 和导航目标永远无法获得桥。

二进制写入必须先完成 `runtime.lifecycle@1#handshake`。宿主返回
`binaryTransports` 与可选 `preferredBinaryTransport`：支持
`WEB_MESSAGE_ARRAY_BUFFER` 的 WebView 同时提供 `arraybuffer` 和
`base64url-chunks-v1` 并首选前者；缺少 ArrayBuffer feature 但仍具备核心安全桥能力时，
只提供 Base64URL 兼容模式。只有缺少 `DOCUMENT_START_SCRIPT` 或
`WEB_MESSAGE_LISTENER` 才彻底拒绝运行。

私有二进制声明固定为 `{ transport, size, chunks, sha256 }`。ArrayBuffer 使用
256 KiB 原始分片；兼容模式使用 RFC 4648 URL-safe、无 `=` 填充的 48 KiB 原始
分片，并等待逐片 ACK 后再发送下一片。宿主逐片严格解码、更新 SHA-256 并写入
app-private 临时文件，不在内存聚合完整编码或完整二进制。Blob/Cache 上传继续受
item/plugin/global 配额、60 秒 deadline、最多四并发、64 MiB 磁盘安全余量和取消/
异常/关闭/下次启动清理约束。旧 SDK 的非二进制调用仍可使用；缺少 transport 或
SHA-256 的旧 Blob/Cache 写入会被拒绝并提示升级到 SDK 0.2.0。

网络图片及其他下载响应继续走
`network.request resource → native resource handle → cache.promote`，不得绕经
JavaScript/Base64。兼容分片仅用于插件生成的 Blob、元数据备份和少量必须由 JS
上传的数据。

每个生成的 capability deadline 同时由 SDK 与 Android 宿主执行。插件可在
`InvokeOptions.timeoutMs` 中缩短 deadline，但不能超过契约上限；超出 deadline
返回 `request_timeout`，上游网络栈自身超时仍返回 `network_timeout`。

## 7. 网络、存储与资源

`network.request@1` 使用无 Cookie、无宿主认证器的独立 OkHttp 客户端。它支持
GET、HEAD、POST、PUT、PATCH、DELETE，以及 JSON、文本、FormData 和 Blob handle
请求体。初始请求、每次 DNS 解析和每次重定向都会重新执行 SSRF 检查；`Host`、
`Cookie`、`Content-Length` 等传输层 header 被拒绝。

- 默认超时 15 秒，上限 60 秒；最多 5 次手动重定向。
- 每插件并发 4，每 origin 并发 2。
- JSON/文本内联响应上限 1 MiB；更大或二进制响应返回资源 handle。
- 浏览器 `fetch` 可继续访问 Manifest 已声明的 connect origin，但不会获得宿主会话。

`storage.kv@2` 的限制为每插件 10 MiB、单值 256 KiB、最多 1024 keys。它提供
batch、revision/CAS、声明式原子 transaction、watch，以及通过 Blob handle 完成的
导入/导出。旧 KV 文件可由同 publisher+plugin 的受控升级直接读取。

`contract_v1` WebView 关闭 DOM Storage，并在 document-start 阶段以不可重新配置的
guard 禁用 `localStorage`、`sessionStorage`、IndexedDB、Cache Storage、Cookie、
Worker/Service Worker 和浏览器文件存储；CSP 同时设置 `worker-src 'none'`。guard
无法完整安装时不会暴露 protocol v2 桥。插件必须使用 SDK 的 KV/Blob/Cache；只有
无桥、无网络的 legacy 救援页保留 DOM Storage 读取能力，以便用户导出旧数据。CI
在 API 26/35 运行真实 JavaScript probe，避免只断言 WebSettings 配置值。

Blob 不可变且内容寻址；Cache 可淘汰并使用 LRU，pin 只阻止自动淘汰。两者按
publisher+plugin 隔离，使用 AES-GCM 分块加密和原子索引：

- Blob：每插件 256 MiB，单项 64 MiB。
- Cache：每插件 512 MiB，全局 1 GiB，单项 250 MiB。
- 写入还必须保留设备安全剩余空间。

资源通过稳定 origin 的 `/__bjtu/resources/<handle>` 提供 GET、HEAD 和 Range，支持
离线读取。零字节 Blob/Cache 是合法资源（对其发起 Range 返回 416）。网络返回的临时
Cache handle 可通过 `cache.promote(handle, key)` 绑定为业务 key，也可通过
`cache.deleteHandle(handle)` 显式释放，避免只能等待 LRU。插件更新保留上一版本包、
KV 快照和 Blob 影子索引；失败时原子恢复。

## 8. Command Capability

所有会改变校园或宿主状态的调用都是 Command Capability。除上文严格列举并在首次或
增量 Capability 审阅时持久授权的 Android Command Capability（无障碍动作、文件保存、
通知、日历写入和录音）外，每次调用都必须经过用户确认并携带 idempotency key。这些
Android 命令免逐次确认，但仍要求 idempotency key、摘要回执和运行时配额。宿主按
“key + 请求摘要”处理：

- 相同 key 和摘要返回原回执，不重复执行。
- 相同 key、不同摘要返回 `idempotency_conflict`。
- 加密回执不保存请求正文，保留 7 天，每插件最多 1024 条。

邮件发送和作业提交不会静默执行。

## 9. 错误

统一错误至少包括：

`permission_denied`、`capability_unavailable`、`foreground_required`、`invalid_request`、
`origin_denied`、`network_timeout`、`request_timeout`、`http_error`、`quota_exceeded`、
`resource_too_large`、`migration_failed`、`user_cancelled` 和
`idempotency_conflict`。

## 10. 校验、打包与发布

```powershell
bjtu lint --source .
bjtu lint --marketplace .
bjtu test .
bjtu inspect .
bjtu doctor .
bjtu pack .
```

`bjtu test` 会启动本机 Chrome/Chromium/Edge 的 headless 实例，在受控 protocol v2
Mock Host 中实际加载发布入口，并捕获脚本错误与未处理 Promise rejection。
`bjtu pack` 生成确定性 ZIP，拒绝 `bjtu-plugin.dev.json`，并与平台一致地执行
25 MiB 压缩包、50 MiB 解压内容、1000 文件及 1 MiB 图标限制。迁移 P0-A 源仓库可先
运行 `bjtu migrate`，再人工复核 Capability、origin、数据 migration 和 marketplace
信息；旧插件不会被平台自动重发。

新目录、投稿、更新解析和制品使用 `/api/v3`。平台保存 `contractProfile`、派生
`runtimeFloor`、Capability 声明和独立 marketplace 元数据。`/api/v2` 只保留旧
P0-A 目录、详情与制品读取，所有写入口冻结。

仓库内完整验证：

```powershell
Set-Location plugin-tooling
npm run generate:check
npm run typecheck
npm test
npm run pack:check

Set-Location ..\web\platform
npm run typecheck
npm test
npm run test:integration
npm run test:e2e
```

## GitHub README 预览

Android 插件大厅和 GitHub 直链导入会在安装预检完成后提供 README 预览。预览请求固定到
预检使用的 `owner/repo@commit`，单次 README 不超过 1 MiB，并使用无 Cookie、无认证、禁止
重定向的只读客户端；成功内容只保存在本次应用进程的有界内存缓存中，不写入 Room 或文件。

README Markdown 在宿主侧清洗后以独立浮窗 WebView 展示。该 WebView 禁用 JavaScript、DOM
存储、文件访问和混合内容，不注入插件桥，也不加载插件资源。仅允许 HTTPS GitHub/GitHub
User Content/GitHub Assets 图片，其他资源会被移除；README 链接只在用户点击时交给系统浏览器。
README 缺失或读取失败不会阻止用户继续确认或取消安装。


## 打开主应用时可选自启（runtime 4）

`android.app.autoStart@1` 是仅声明型能力，没有 SDK/JS 方法。插件作者只能将其加入 Manifest 的 `capabilities.optional`；放入 `required` 会被 schema、lint、平台与 Android 宿主拒绝。例如：

```json
"capabilities": {
  "required": ["runtime.lifecycle@1"],
  "optional": ["android.app.autoStart@1"]
}
```

可选授权默认关闭，用户在首次/增量授权或插件管理页明确开启后，宿主才会在用户打开主应用、主 Activity 可见时加载插件的稳定本地入口。关闭插件页面后已授权的后台能力可继续运行，受持续通知、全部停止、全局最多四个后台 runtime 与前台事件优先约束。停止、撤销、禁用、复审、声明移除、发布者不匹配或更新/回滚会停止对应自启；重新开启须用户明确选择。这不是设备开机启动或永久保活，不扩大无障碍节点、动作、脱敏和限流权限，也不绕过 Android 系统权限或系统 UI 的前台要求。

## 插件 MIS 会话保活（runtime 3）

`android.session.keepAlive@1` 为 beta 持久授权能力。Manifest 使用
`"capabilities": { "required": ["runtime.lifecycle@1", "android.session.keepAlive@1"] }`；
也可声明 optional，但默认不授权，只有用户显式授权后才能使用。旧 runtime 不支持此能力。

首次/增量审阅后，无需每次弹窗。acquire/renew 要求前台插件 runtime 且应用 Activity 可见，
并要求通知开启；后台可查询/释放。不会自动申请通知权限、打开系统设置、申请电池白名单或绕过 FGS 限制。
保活只维护宿主 MIS 会话，不向插件返回凭据，也不保证 JavaScript 持续执行或进程永不终止。

```ts
const receipt = await bjtu.android.session.keepAlive.acquire({
  idempotencyKey: crypto.randomUUID(),
  requestedDurationMs: 15 * 60 * 1000
});
const leaseId = receipt.result.lease!.leaseId;
const status = await bjtu.android.session.keepAlive.getStatus();
// 续租从当前时间计算，但不能超过创建时的 maxExpiresAtMs。
await bjtu.android.session.keepAlive.renew({
  idempotencyKey: crypto.randomUUID(), leaseId, requestedDurationMs: 20 * 60 * 1000
});
await bjtu.android.session.keepAlive.release({
  idempotencyKey: crypto.randomUUID(), leaseId
});
const dispose = bjtu.android.session.keepAlive.onEnded(({ leaseId, reason }) => {
  // reason: released / expired / revoked / clock_changed / user_stopped /
  // max_runtime / session_unavailable / service_unavailable
});
```

acquire 只创建租约，renew 显式续租；返回 Command 回执，不能将回执视为服务已经启动。
getStatus 的 active/serviceState 表示当前状态：stopped、pending、running 或 degraded。
重复 key+摘要只返回原回执，租约已停止时不会再次启动；不同摘要返回 idempotency_conflict。
结束事件只发送到同发布者同插件的优先前台 runtime，无监听器时不保证补发，重开页面应查询状态。

限制：每插件最多 2 租约，单次 1–60 分钟，创建起硬上限 60 分钟，每分钟最多 6 次申请/续租。
滚动一小时预留最多 60 租约分钟；并行租约分别计费，提前释放不退预留额度，续租仅计增加的部分。
租约只释放本插件资源；服务仍被 Agent、抢课或其他插件持有时继续运行。
通知“停止保活”清空所有来源；插件后台通知“全部停止”仅清理插件任务。

未过期租约、预算和幂等摘要由宿主加密保存。进程重建后重新校验发布者、授权和版本，
等应用前台满足系统要求后恢复，不延长原到期时间。设备重启/时钟异常清理租约。
禁用、撤销、复审、更新/回滚、删除和退出登录清理租约；租约不能借更新快照复活。
文件损坏或未完成的写入安全关闭该能力，需要恢复本机状态，不影响内置 Agent/抢课。
持续通知被关闭或系统拒绝启动时返回 notifications_unavailable/foreground_service_denied；
过期或不属于自己的租约续租返回 lease_not_found。接口不接受任意 URL、token、purpose 文本或 Cookie。

执行计划与验证状态见 [保活 ExecPlan](plugin-session-keepalive-execplan.md)。
