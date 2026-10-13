# Implementation Plan: [FEATURE NAME]

**Branch**: `[###-feature-name]`  
**Spec**: [link-to-spec]  
**Constitution Version**: 3.2.0

**Created**: [DATE]

## Summary

- Goal:
- User-visible result:
- Non-goals:

## Technical Context

- Android areas:
- Open WebUI areas:
- Data, Room, DataStore, or file impacts:
- External systems:
- Third-party plugin, Manifest, WebView, origin, bridge, storage, or campus-proxy impacts:
- Existing patterns to reuse:
- Unknowns: use `TODO: confirm` for unresolved facts.

## Constitution Check

Complete this check before implementation starts. Any FAIL must be resolved or documented as a blocker.

- Architecture boundaries: PASS/FAIL - UI, provider, parser, repository, db, security, sync and Open WebUI bridge responsibilities stay separated.
- Security and authorization: PASS/FAIL - credentials, Cookie, mail, coursework actions, files and Agent tools keep explicit user confirmation and safe boundaries.
- Local-first recovery: PASS/FAIL - cached data, offline behavior, refresh, failure and empty states are defined where relevant.
- Testing strategy: PASS/FAIL - parser fixtures, MockWebServer/fakes, Android JVM tests, Vitest, or Agent tool boundary tests match the change risk.
- Dependencies and artifacts: PASS/FAIL - no unnecessary dependency or generated/local/secret artifact will be committed.
- Rollback and ExecPlan: PASS/FAIL - high-risk changes have rollback steps and a maintained ExecPlan.
- Plugin zero-trust baseline: PASS/FAIL/N/A - plugin work uses Manifest v3 / contract_v1, registry-generated versioned capabilities, stable publisher identity, host-fixed self/main-frame bridge, split origins, transactional storage and the read-only campus registry; legacy documents are not treated as authority.
- Android persistent capability exception: PASS/FAIL/N/A - only the constitution-listed Android capabilities use persistent authorization; system UI/permission foreground gates, redaction, resource isolation, idempotency, quotas, foreground priority, notification, revocation and API 26/35 validation are planned; declaration-only optional startup remains default-off and main-Activity-triggered.

## Design

- Data flow:
- UI behavior:
- Error and empty states:
- Background work or sync behavior:
- Open WebUI/Capacitor behavior:
- Third-party plugin runtime and trust-boundary behavior:
- Room/cache/migration behavior:

## Files and Components

- Source changes:
- Test changes:
- Fixture changes:
- Documentation changes:
- Generated files to ignore or exclude:

## Implementation Steps

1. Check `git status --short` and record unrelated user changes.
2. Implement the smallest scoped source changes that satisfy the spec.
3. Add or update tests, fixtures and safety checks required by the Constitution Check.
4. Update docs or templates affected by behavior, commands or boundaries.
5. Run validation commands selected for the changed areas.
6. Inspect `git diff --check`, `git diff` and `git status --short`.

## Validation

- Android JVM tests:
- Android build or asset packaging:
- Open WebUI frontend tests:
- Open WebUI type check:
- Plugin platform typecheck/unit/integration/e2e:
- Manifest schema/lint and Android WebView instrumentation:
- Manual verification:
- Known baseline failures:

## Rollback

- Code rollback:
- Data or migration rollback:
- Config or permission rollback:
- User-visible recovery:


插件会话保活遵循 constitution 3.2.0：`android.session.keepAlive@1` 采用按 publisher+plugin 隔离的加密限时租约，acquire/renew 必须前台发起，后台可查询/释放；到期、更新/回滚、撤销、删除、登出、用户停止和系统限制均清理，恢复不能延长时限或绕过 FGS 限制。执行与验证见 `docs/plugin-session-keepalive-execplan.md`。
