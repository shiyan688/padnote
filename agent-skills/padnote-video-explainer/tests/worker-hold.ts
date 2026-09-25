import {acquireExecutionLease, transitionTaskState} from '../scripts/task-state.js';

const taskRoot = process.argv[2];
if (!taskRoot) throw new Error('task root is required');
const lease = await acquireExecutionLease(taskRoot);
await transitionTaskState(taskRoot, 'test_worker_started', current => {
  if (!current) throw new Error('task state missing');
  return {
    ...current,
    status: 'running',
    phase: 'test_hold',
    attempt: {
      attempt_id: 'test-hold',
      lease_token: lease.token,
      pid: process.pid,
      started_at: new Date().toISOString(),
      phase: 'test_hold',
      external_effect_possible: true,
    },
  };
});
console.log('READY');
setInterval(() => {}, 60_000);
