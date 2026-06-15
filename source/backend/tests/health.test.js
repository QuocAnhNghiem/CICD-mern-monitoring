const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const path = require('node:path');
const test = require('node:test');

const PORT = '5055';
const BASE_URL = `http://127.0.0.1:${PORT}`;

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

test('GET /health returns ok status', async (t) => {
  const child = spawn(process.execPath, ['server.js'], {
    cwd: path.join(__dirname, '..'),
    env: {
      ...process.env,
      NODE_ENV: 'test',
      PORT,
      MONGO_URI: 'mongodb://127.0.0.1:27017/mern_test_db'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });

  t.after(() => {
    child.kill('SIGTERM');
  });

  let lastError;
  for (let attempt = 1; attempt <= 20; attempt += 1) {
    try {
      const response = await fetch(`${BASE_URL}/health`);
      const body = await response.json();

      assert.equal(response.status, 200);
      assert.equal(body.status, 'ok');
      assert.equal(body.environment, 'test');
      return;
    } catch (error) {
      lastError = error;
      await wait(250);
    }
  }

  throw lastError;
});
