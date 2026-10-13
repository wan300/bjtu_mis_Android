# Tasks: [FEATURE NAME]

**Input**: spec and plan for [FEATURE NAME]  
**Constitution Version**: 3.2.0

## Task Rules

- Each task MUST name the file, component or behavior it changes.
- Tasks that touch parser, provider, repository, Agent tools or Open WebUI local-first behavior MUST include matching tests or fixtures.
- Tasks that touch credentials, Cookie, mail, coursework actions, files, archives or generated outputs MUST include explicit safety checks.
- Tasks that touch third-party plugins MUST preserve Manifest v3 / contract_v1 compatibility, registry-generated Capability contracts, stable publisher identity, host-fixed self/main-frame bridge isolation, split origins, transactional storage and the read-only campus registry; optional-only declarations must be rejected from required capabilities.
- Tasks that touch Android automation MUST include service-unavailable, redaction/expiry, runtime quota, foreground-priority/background-restore, idempotency-without-confirmer, restricted package/settings, revocation and API 26/35 instrumentation coverage.
- High-risk changes MUST reference the active ExecPlan and include rollback verification tasks.

## Phase 1: Setup and Scope

- [ ] T001 Check `git status --short` and record unrelated user changes.
- [ ] T002 Confirm the spec, non-goals and unresolved `TODO: confirm` items.
- [ ] T003 Identify existing project patterns, helpers and tests to reuse.

## Phase 2: Tests and Safety First

- [ ] T004 Add or update parser fixtures, provider fakes, repository tests, Agent tool boundary tests or Vitest coverage required by the plan.
- [ ] T005 Add safety coverage for user confirmation, workspace boundaries, path traversal, file size limits, privacy-sensitive logs or failure feedback where relevant.
- [ ] T006 For plugin work, add v3 schema/legacy rejection, publisher identity, WebView bridge/origin, storage migration/rollback and campus-registry coverage where relevant.

## Phase 3: Implementation

- [ ] T007 Implement scoped Android, Open WebUI, data, sync or bridge changes using existing architecture boundaries.
- [ ] T008 Update persistence, cache, migration or local-first behavior only as described in the plan.
- [ ] T009 Update user-facing states for loading, empty, error, offline and permission failures where relevant.

## Phase 4: Documentation and Validation

- [ ] T010 Update README, AGENTS, PLANS or feature docs affected by behavior, commands or boundaries.
- [ ] T011 Run Android validation selected by scope: `Set-Location android; .\gradlew.bat test` or `Set-Location android; .\gradlew.bat assembleDebug`.
- [ ] T012 Run Open WebUI validation selected by scope: `Set-Location android\open-webui; npm run test:frontend -- --run` or `npm run check`.
- [ ] T013 For plugin work, run platform typecheck/unit/integration/e2e, shared Manifest lint and applicable Android WebView instrumentation.
- [ ] T014 Run `git diff --check`, inspect `git diff`, and confirm `git status --short` contains only intended files.

## Completion Notes

- Validation results:
- Known baseline failures:
- Remaining TODOs:
- Rollback notes:


插件会话保活遵循 constitution 3.2.0：`android.session.keepAlive@1` 采用按 publisher+plugin 隔离的加密限时租约，acquire/renew 必须前台发起，后台可查询/释放；到期、更新/回滚、撤销、删除、登出、用户停止和系统限制均清理，恢复不能延长时限或绕过 FGS 限制。执行与验证见 `docs/plugin-session-keepalive-execplan.md`。
