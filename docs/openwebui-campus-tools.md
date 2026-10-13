# Open WebUI 校园工具（第一、二阶段）

Android 普通聊天自动加载可用的原生工具，模型须支持 OpenAI-compatible function calling。工具通过 NativeAgentTools → AgentToolExecutor → repository 调用；第三方插件的授权与桥保持独立。

## 工具

| 名称 | 数据 | 工作区 | 原生确认 |
| --- | --- | --- | --- |
| agent_system_status | 缓存会话状态、四个校园模块的快照时间和过期标记；不校验线上会话 | 否 | 否 |
| agent_academic_timetable | 课表和自定义课程的周期候选 | 否 | 每次 |
| agent_academic_exams | 考试名称、安排、显式日期和状态 | 否 | 每次 |
| agent_academic_homework | 作业标识、课程、标题、截止时间、状态、附件元数据 | 否 | 每次 |
| agent_academic_agenda | 课表、考试、作业、校园月历、App 待办，分模块报告结果 | 否 | 每次 |
| agent_todo_list | Room 原生待办及关联提醒状态 | 否 | 每次 |
| agent_todo_create / agent_todo_update / agent_todo_delete | 创建、编辑/完成、删除原生待办 | 否 | 每次 |
| agent_reminder_set / agent_reminder_cancel | 设置/替换、取消指定待办的一次性提醒 | 否 | 每次 |
| agent_app_open_page | 打开白名单原生校园页面和缓存中的作业详情/资源目录 | 否 | 每次 |

四个 academic 工具均接受可选 `startDate`、`endDate`（YYYY-MM-DD，含首尾）、`week`（教学周 1–60）、`refresh`（默认 false）、`limit`（默认 50，最多 100）。默认查询 Asia/Shanghai 本周周一至周日；只指定 startDate 时查询其后共七天。日期窗口最多 31 天。

```json
{"startDate":"2026-10-12","endDate":"2026-10-18","refresh":false,"limit":50}
```

`week` 仅筛选课表教学周；不以学期编码推测校历起点。课表按日期窗口覆盖的星期筛选，但保留周期信息，不生成确定上课日期。尚未核实校历/临时调课时标记 unresolvedDateCount/partial，不能承诺无课程冲突。考试/作业无可解析日期时保留条目并标记未知，不将其丢弃为空。

## 缓存与输出

`refresh=false` 使用 CacheOnly，不发起登录或校园请求。无缓存返回 `cache_missing`，不返回伪空列表。`refresh=true` 经原生授权后调用既有 NetworkFirst repository；若同步时间未变化，保守标记 fromCache/refreshFailed，不将回退缓存描述成刚从校园取得的数据。

查询输出包含 `ok`、`data`（模块结果）、`meta`（窗口、时区、partial）。各模块分别包含 ok/items/meta 或 ok/error/message；meta 含 sourceSystem、syncedAt（可用时）、fromCache、stale、partial、truncated、matchedCount、unresolvedDateCount、refreshFailed、fieldsTruncated、errors。超过 24 小时、缺失/无效或未来同步时间都视为过期；本地待办不适用校园过期策略。

所有模块共用 limit，按课表→考试→作业→月历→待办分配；即使额度耗尽仍查询并报告后续模块的 matchedCount/truncated，不能把 items=[] 解释成无事务。月历跨月失败保留其他月份并报告 errors。所有模块失败时顶层 ok=false。输出不包含凭据、Cookie、原始快照、附件 URL、作业正文或邮件内容。

## 原生执行策略

listTools 返回 requiresWorkspace 与 policy：operation、dataScope、requiresConfirmation、requiresForeground、requiresIdempotencyKey、timeoutMs、maxInputBytes、maxOutputBytes。原生执行入口是权威，前端字段或工具名不能授予权限。未知策略失败关闭；参数按 schema 子集校验，不做字符串/布尔/数值强制转换。

校园查询/邮件读取和现有原生状态修改工具均使用可取消的原生确认；离开前台关闭待确认对话框，执行前再次检查前台。读取提示结果可能发送给配置的模型服务；状态修改展示完整参数。文件写入/删除、归档/文档生成、代码执行、邮件已读/草稿/发送和结果打包均逐次确认。邮件发送继续保留原有 WebUI 确认，第一阶段可能出现两次确认。

原生工具仅由本地 Open WebUI 页面调用。工作区 ID 限制为单个安全标识；文件工具继续使用 WorkspaceManager 的路径和大小边界。输入最多 256 KiB、输出（含 stdout/stderr/artifact 元数据）最多 1 MiB，执行默认 60 秒；校园聚合每模块 10 秒。后台不得弹出授权窗口。

命令需要宿主桥层 requestId（不进入模型参数）。客户端默认生成 UUID，显式重试须复用 requestId。原生以工具、工作区、规范化参数摘要绑定请求 ID，并在本次 WebView 会话内最多保留 64 条回执；冲突拒绝，超过限额不驱逐旧回执。执行超时/异常后同 ID 返回 execution_uncertain，不再次执行。此保证不跨进程；邮件发送等不应通过生成新 ID 自动重试，必须由用户核对状态后重新确认。

## 范围与验证

未新增权限、持久授权、通用后台自动化、数据库迁移或作业提交。原生待办与 WebUI IndexedDB tasks 使用不同 ID，不自动迁移。第三方插件能力和授权保持独立。

## 原生待办与提醒

创建例子：`agent_todo_create({"title":"复习示例课程","date":"2026-10-13","note":"用户确认的安排"})`。date 必须明确为 YYYY-MM-DD；标题最多 200 字、备注最多 2000 字。结果 `todo` 含 Room 数字 `id` 和 `updatedAt`，原生日历直接读取同一份数据；校园缓存缺失时仍显示本地待办并明确报告校园数据不可用。

`agent_todo_list` 可选 startDate/endDate/includeDone/limit，默认仅未完成、50 项，上限 100；返回 total/truncated/notificationsEnabled，以及每项提醒的 state/remindAt。读取备注前仍提示可能向配置的模型服务分享。工具不暴露提醒令牌。

编辑或完成：`agent_todo_update({"id":7,"expectedUpdatedAt":"查询返回的 updatedAt","done":true})`。只修改提供的 title/date/note/done；空 note 清空备注。编辑和删除必须使用最近查询/写入返回的 updatedAt；冲突返回 todo_conflict，缺失 ID 返回 todo_not_found，不隐式创建。删除：`agent_todo_delete({"id":7,"expectedUpdatedAt":"最近的 updatedAt"})`。

提醒：`agent_reminder_set({"id":7,"expectedUpdatedAt":"最近的 updatedAt","remindAt":"2026-10-13T09:00:00+08:00"})`。时间必须带时区并位于未来 365 天内。仅未完成待办可以设置，最多保留 256 条提醒记录。同一待办替换唯一 WorkManager 工作，返回 exact=false；这是系统可延迟的一次性提醒，迟到超过 1 小时失效。系统通知权限或频道关闭返回 notification_permission_required，用户在系统设置允许通知后重新设置；工具不从后台弹出系统权限窗口。

`agent_reminder_cancel({"id":7})` 撤销持久令牌、工作与已发通知，保留待办。原生日历也展示提醒时间/状态和取消入口。原生日历或 Agent 编辑、完成、删除待办都会撤销旧提醒；重新打开已完成待办不会恢复提醒。状态包括 scheduled、delivered、blocked、expired、failed；pending/delivering 表示安排/发送中断，需要用户核实后重新设置。

提醒记录只保存 ID、版本、时间、令牌及状态，WorkManager 输入只有 ID/令牌。Worker 在发送前重读 Room、持久令牌、状态和权限；撤销、完成、删除、旧版本、旧令牌及过期工作不能发送。通知不包含备注，锁屏公开版本只显示“待办提醒”，点击打开原生日历。发送前持久认领防止重复发送，因此进程恰好在认领后终止可能漏发；不承诺跨进程恰好一次或精确闹钟。

## 页面导航

`agent_app_open_page({"page":"calendar"})` 的 page 白名单为 timetable、exams、calendar、homework、course_resources、homework_detail。详情需要 `courseId`（正整数字符串）和 `homeworkId`（数字），并与本地缓存的作业精确匹配；资源目录可传 courseId+folderId（默认根目录 "0"），必须已有该课程、该目录的“全部”分类列表缓存，不能仅凭目录树中的 ID 假称有可展示的列表。普通模块不接受其他 ID，拒绝 URI、路径、Intent、任意 route。

工具返回 status=queued 后，原生桥将一次性类型化请求交给 Compose。页面首屏 CacheOnly，不因导航自动同步、下载或提交；用户在页面上仍可主动刷新。作业详情返回 Agent，既有 WebView 会话保持。queued 只表示宿主接收请求，退出前台/进程结束可能阻止页面切换。

验证命令见 [第一阶段 ExecPlan](openwebui-campus-tools-phase1-execplan.md) 与 [第二阶段 ExecPlan](openwebui-campus-tools-phase2-execplan.md)。真机授权 UI、前后台切换、通知重启恢复与 API 26/35：TODO: confirm，必须在可用设备上验证。
