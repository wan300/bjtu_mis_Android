import { readFileSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';

const schema = JSON.parse(readFileSync(
  new URL('../contracts/capability-contracts.schema.json', import.meta.url), 'utf8'
));
const ajv = new Ajv2020({ allErrors: true, strict: true, allowUnionTypes: true });
const validate = ajv.compile(schema);

export function validateRegistrySchema(registry) {
  if (!validate(registry)) {
    throw new Error(`Invalid capability registry: ${ajv.errorsText(validate.errors, { separator: '; ' })}`);
  }
}

// Accept checkout newline conversion, but never trim or hide other content drift.
export function matchesGeneratedContent(current, expected) {
  return typeof current === 'string' && current.replaceAll('\r\n', '\n') === expected;
}
