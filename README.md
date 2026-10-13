<h1 align="center">BJTU MIS Android</h1>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT License" /></a>
</p>

<p align="center">
  <img src="docs/img/readmeimg.png" alt="BJTU MIS Android 应用展示" width="720" />
</p>

BJTU MIS Android 是面向北京交通大学学生的校园学习服务 App。它把 MIS、教学服务、校园邮箱和作业辅助能力整合到手机端，帮助同学们更快查看课程与学习信息，并在离线场景下继续访问已同步的数据。



## 主要功能

- 学业信息：个人信息、培养方案、课表、成绩、考试安排、校历、作业、选课、空教室、课程资源与课程回放。
- 空教室筛选：选择教学楼、周次和星期/日期，设置开始与结束节次，直接查看整个时段均空闲的教室及座位信息，也可切换回整周视图。特殊状态或数据缺失的教室不计入空闲结果。
- 校园邮箱：查看 Coremail 文件夹、邮件列表、邮件详情、联系人、草稿和发送流程。
- 本地同步：支持登录凭据安全保存、Cookie 持久化、Room 本地快照、后台同步、会话保活和作业提醒。
- 离线查看：已同步的常用信息可以在没有网络或校园服务不稳定时继续查看。
- 内置智能助手：在应用内打开 Open WebUI Agent，围绕作业要求、附件资料和用户补充说明生成分析、步骤或答案草稿。
- 附件处理：作业附件会导入助手工作区，支持按需读取、解压和整理常见文档、压缩包与输出文件。
- 安全插件平台：从大厅安装 SDK-first、Capability 驱动的 Manifest v3 / contract_v1 Web 插件，使用稳定发布者身份、宿主固定桥、分离 origin、事务型本地数据和只读校园代理。

## 使用前准备

- 一台 Android 设备或 Android 模拟器。
- 可正常访问北京交通大学相关校园服务的网络环境。
- 你的校园账号及对应密码。
- 如需使用邮箱能力，请确认账号已开通并可访问 Coremail。

## 快速开始

1. 安装并打开 BJTU MIS Android。
2. 在登录页输入校园账号信息。
3. 首次进入后等待应用同步常用数据。
4. 在首页选择课表、成绩、考试、作业、邮箱等模块。
5. 需要处理作业时，打开作业卡片并进入内置 Agent，根据提示补充要求或说明。

## 常用场景

### 查看课程与考试

进入课表或考试模块即可查看当前已同步的信息。若学校服务临时不可用，应用会优先展示本地已有快照。

### 跟进作业

在作业模块中查看截止时间、作业要求和附件状态。作业附件会被导入到 Agent 工作区，助手可以基于这些资料生成整理结果或答题草稿。

### 使用校园邮箱

在邮箱模块中查看邮件文件夹、邮件列表和详情。发送邮件前会经过用户确认，不会在未确认的情况下自动发出。

### 离线访问

应用会保留已同步的课程、作业、邮箱等数据快照。离线时可以继续查看已有内容，重新联网后再同步更新。

## 隐私与安全

- 登录凭据和关键会话信息保存在本地安全存储中。
- 应用只在需要访问校园服务、同步数据或执行用户操作时使用账号信息。
- 内置 Agent 主要用于生成分析、步骤、草稿或输出文件，不会自动提交课程平台作业。
- 邮件发送需要用户在应用内确认后才会执行。
- 第三方插件只安装、更新和运行 Manifest v3 / contract_v1；v1/v2 与 P0-A v3 只能进入无桥、无网络的只读救援入口。
- 插件原生桥只对稳定本地 origin 的 main frame 开放，远程 frame 永远无桥；运行时不提供明文凭据读取或通用原生 HTTP。
- 公网来源按 connect、media、frame、navigation 分离；校园会话只能通过 MIS/AA/VE 的只读 `campus.request` registry 使用。
- 插件通过自动生成的 TypeScript SDK 使用版本化 Capability；重要数据使用 publisher+plugin 隔离、AES-GCM 加密、受配额约束且可迁移回滚的 KV/Blob/Cache。完整规则见 [第三方插件 contract_v1 安全基线](docs/third-party-services.md)。

## 问题排查

- 登录失败：确认账号密码正确，并检查当前网络是否可以访问校园服务。
- 数据没有更新：下拉刷新或重新进入模块，必要时切换到稳定网络后再次同步。
- 附件未准备完成：等待导入状态完成后再提交给 Agent 处理。
- 邮箱不可用：确认 Coremail 账号状态正常，并检查校园邮箱服务是否可访问。
- 应用异常退出：重新打开应用后再次进入对应模块；如问题持续出现，请保留复现步骤和设备信息。

## 源码构建

如果你希望从源码自行构建应用，请使用 Android Studio 打开 `android/` 目录，并准备以下环境：

- JDK 17
- Android SDK，当前项目使用 `compileSdk = 35`、`minSdk = 26`
- Node.js 与 npm，用于构建嵌入式 Open WebUI 前端；`android/open-webui/package.json` 当前要求 Node `>=18.13.0 <=22.x.x`

命令行构建：

```powershell
Set-Location android
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

前端单独检查：

```powershell
Set-Location android\open-webui
npm ci
npm run check
npm run test:frontend -- --run
```

插件契约、SDK 与 CLI：

```powershell
Set-Location plugin-tooling
npm ci
npm run generate:check
npm run typecheck
npm test
npm run pack:check
```

## 开源许可

本项目的原创代码和文档采用 [MIT License](LICENSE) 开源。你可以在保留版权与许可声明的前提下使用、复制、修改、合并、发布、分发、再许可或销售这些内容。

仓库中包含的第三方及衍生组件保留各自版权，并继续遵循对应目录、文件头或上游项目声明的许可证；根目录 MIT 许可证不会重新授权这些组件。特别包括：

- `android/open-webui/`：遵循该目录中的 `LICENSE`、`LICENSE_NOTICE` 与 `LICENSE_HISTORY`。
- `android/capacitor-android/`：遵循该目录中的 `LICENSE`。

## 说明

- Android 工程位于 `android/`。
- `web/` 统一保存网站静态前端与 `web/platform/` 插件平台后端；生产部署布局、发布步骤和回滚前置条件见 `deploy/README.md`。
- 项目原则以 `.specify/memory/constitution.md` 为权威来源；插件安全策略以 constitution 原则 VII 和 `docs/third-party-services.md` 为准，并覆盖旧 v1/v2 文档。`AGENTS.md` 和 `PLANS.md` 提供代理执行细则与 ExecPlan 模板。
- 本地 SDK 配置文件 `android/local.properties` 不应提交。
- Open WebUI 构建产物、Android 构建产物、APK/AAB 和本地缓存均不应提交；仅 `android/app/schemas/` 中用于迁移验证且经审查的 Room schema 历史应随 migration 提交。
