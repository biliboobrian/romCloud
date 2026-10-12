import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-ping-test-${process.pid}`);
process.env.API_KEY = 'cle-des-applications';
const { default: express } = await import('express');
const { api } = await import('../src/api.js');

test('ping : répond sans clé d\'API, jamais mis en cache', async () => {
  const app = express();
  app.use('/api', api);
  const server = app.listen(0);
  try {
    const { port } = server.address();
    const res = await fetch(`http://127.0.0.1:${port}/api/ping`);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('cache-control'), 'no-store');
    assert.equal((await res.json()).ok, true);
    // Les autres routes demandent toujours la clé.
    assert.equal((await fetch(`http://127.0.0.1:${port}/api/systems`)).status, 401);
  } finally {
    server.close();
  }
});
