// Regenerates tools.json from the bridge registry, so the fake body never drifts from the real one.
// Usage: node brain/bench/sync-tools.js
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const registry = resolve(here, '../../mod/src/main/java/dev/blocknet/bridge/BridgeTools.java');
const integers = new Set(['count', 'length', 'x', 'y', 'z', 'distance']);

export function parseRegistry(source) {
  return [...source.matchAll(/add\("([^"]+)", "([^"]+)", (true|false)((?:, "[^"]+")*)/g)].map(([, name, description, task, rest]) => {
    const params = [...rest.matchAll(/"([^"]+)"/g)].map(([, param]) => param);
    const properties = {};
    const required = [];
    for (const param of params) {
      const optional = param.endsWith('?');
      const key = optional ? param.slice(0, -1) : param;
      properties[key] = integers.has(key) ? { type: 'integer' } : { type: 'string' };
      if (!optional) required.push(key);
    }
    return { name, description, task: task === 'true',
      params: { type: 'object', properties, required, additionalProperties: false } };
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const tools = parseRegistry(await readFile(registry, 'utf8'));
  await writeFile(resolve(here, 'tools.json'), `${JSON.stringify(tools, null, 2)}\n`);
  console.log(`tools.json: ${tools.length} tools`);
}
