# ExecPlan: Deploy plugin platform v1.4.2 and synchronized monitoring

## Scope

Deploy the repository `HEAD` plugin platform and static developer documentation to the existing
`my-server` release layout. Verify `/api/v3` responses, generated contract inputs, service health,
and the GitHub plugin-platform workflow checks. Keep the current release as the rollback target;
do not alter database or artifact volumes.

## Steps

1. Record the active release and service state.
2. Stage a timestamped release containing the current repository sources and build the API/worker image.
3. Verify schema and capability contract files inside `/repo`, run migration and health checks, then atomically switch `current`.
4. Verify public API, developer docs, automation/keep-alive documentation, and service logs.

## Rollback

If build, migration, startup, or health checks fail, point `current` back to the recorded release and
rebuild Compose services with existing volumes. Do not delete releases or run `down -v`.

## Status

- 2026-09-09: Remote active release is 20260813-105705; latest repository platform changes are not deployed.
- 2026-09-09: Repository HEAD was staged successfully, but remote Docker image build could not pull
  `node:22-bookworm-slim` (registry transfer stalled). Staging was removed; production symlink and
  services were left unchanged. Local platform unit tests passed (21/21).
- 2026-09-09: Deployed release `20260909-151500`; migration exited successfully, API and worker
  restarted, localhost health and public `/api/v3/plugins` returned 200.
