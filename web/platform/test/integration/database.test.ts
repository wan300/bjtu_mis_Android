import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import test from 'node:test';
import { createDatabase } from '../../src/db.js';
import { runMigrations } from '../../src/migrate.js';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (process.env.CI && !databaseUrl) {
  throw new Error('CI integration tests require TEST_DATABASE_URL; refusing to skip database validation.');
}

test('migrations create the plugin catalog tables', { skip: !databaseUrl }, async () => {
  await runMigrations(databaseUrl!);
  const db = createDatabase(databaseUrl!);
  try {
    const result = await db.query<{ name: string }>(
      `SELECT table_name AS name FROM information_schema.tables
       WHERE table_schema='public' AND table_name = ANY($1)`,
      [['plugins', 'plugin_versions', 'validation_jobs', 'reports', 'audit_log']]
    );
    assert.equal(result.rowCount, 5);
    const columns = await db.query<{ column_name: string }>(`
      SELECT column_name FROM information_schema.columns
      WHERE table_schema='public' AND (
        (table_name='plugins' AND column_name IN (
          'github_repository_id','publisher_subject_id','publisher_owner_id',
          'pending_owner_id','publisher_transfer_status'
        )) OR
        (table_name='plugin_versions' AND column_name IN (
          'manifest_schema_version','runtime_version','min_runtime_version',
          'data_schema_version','compatibility_state','verification_level',
          'contract_profile','runtime_floor','capabilities_json','marketplace_json',
          'manifest_file_name'
        )) OR
        (table_name='submissions' AND column_name='contract_profile')
      )
    `);
    assert.equal(columns.rowCount, 17);
    const repositoryIdentityIndex = await db.query(`
      SELECT indexname FROM pg_indexes
      WHERE schemaname='public' AND indexname='plugins_github_repository_id_idx'
    `);
    assert.equal(repositoryIdentityIndex.rowCount, 1);
    const contractIndex = await db.query(`
      SELECT indexname FROM pg_indexes
      WHERE schemaname='public' AND indexname='plugin_versions_contract_v1_catalog_idx'
    `);
    assert.equal(contractIndex.rowCount, 1);
  } finally {
    await db.end();
  }
});

test('database prevents concurrent active submissions for the same repository', { skip: !databaseUrl }, async () => {
  await runMigrations(databaseUrl!);
  const db = createDatabase(databaseUrl!);
  const userId = randomUUID();
  const firstSubmissionId = randomUUID();
  const secondSubmissionId = randomUUID();
  const repositoryName = `race-${randomUUID()}`;
  try {
    await db.query(
      `INSERT INTO users(id,github_id,login,encrypted_oauth_token) VALUES($1,$2,$3,$4)`,
      [userId, `test-${userId}`, 'integration-test', 'encrypted-test-token']
    );
    await db.query(
      `INSERT INTO submissions(id,user_id,source_url,repo_owner,repo_name,status)
       VALUES($1,$2,$3,$4,$5,'queued')`,
      [firstSubmissionId, userId, `https://github.com/alice/${repositoryName}`, 'Alice', repositoryName]
    );
    await assert.rejects(
      db.query(
        `INSERT INTO submissions(id,user_id,source_url,repo_owner,repo_name,status)
         VALUES($1,$2,$3,$4,$5,'queued')`,
        [secondSubmissionId, userId, `https://github.com/alice/${repositoryName}`, 'alice', repositoryName.toUpperCase()]
      ),
      (error: unknown) => (error as { code?: string; constraint?: string }).code === '23505' &&
        (error as { constraint?: string }).constraint === 'submissions_active_repository_idx'
    );
  } finally {
    await db.query('DELETE FROM users WHERE id=$1', [userId]);
    await db.end();
  }
});
