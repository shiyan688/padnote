import assert from 'node:assert/strict';
import test from 'node:test';
import {windowsProcessIdentityFromJson} from '../scripts/task-state.js';

test('Windows process identity binds boot and creation time without claiming a process group', () => {
  const identity = windowsProcessIdentityFromJson(
    '{"boot_id":"639210000000000000","start_time":"639210123456789012"}\r\n', 4242);
  assert.deepEqual(identity, {
    platform: 'win32', boot_id: '639210000000000000',
    start_time: '639210123456789012', process_control_id: 4242,
  });
  assert.equal((identity as Record<string, unknown> | undefined)?.process_group_id, undefined);
});

test('Windows identity parser rejects malformed or absent process creation data', () => {
  assert.equal(windowsProcessIdentityFromJson('{"boot_id":"bad","start_time":"2"}', 42), undefined);
  assert.equal(windowsProcessIdentityFromJson('{}', 42), undefined);
});
