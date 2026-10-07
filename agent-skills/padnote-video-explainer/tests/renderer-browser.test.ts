import assert from 'node:assert/strict';
import test from 'node:test';
import {
  renderBrowserExecutableOptions,
  renderBrowserOptions,
  storyboardBrowserArgs,
} from '../scripts/renderer-browser.js';

test('Windows rendering selects the bundled headless shell without disabling its sandbox', () => {
  assert.deepEqual(renderBrowserExecutableOptions('win32'), {headless: 'shell'});
  assert.deepEqual(renderBrowserOptions('win32'), {
    headless: 'shell',
    args: ['--disable-dev-shm-usage', '--disable-background-networking'],
  });
});

test('non-Windows rendering retains Chrome and its current launch flags', () => {
  assert.equal(renderBrowserExecutableOptions('linux'), undefined);
  assert.deepEqual(renderBrowserOptions('linux'), {
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage', '--disable-background-networking'],
  });
  assert.deepEqual(renderBrowserOptions('darwin'), renderBrowserOptions('linux'));
});

test('Windows storyboard uses shell defaults without single-process or sandbox-disabling flags', () => {
  assert.deepEqual(storyboardBrowserArgs('win32'), ['--disable-dev-shm-usage', '--disable-background-networking']);
});

test('non-Windows storyboard retains its existing process and sandbox flags', () => {
  assert.deepEqual(storyboardBrowserArgs('linux'), [
    '--no-sandbox', '--disable-setuid-sandbox', '--no-zygote', '--single-process',
    '--disable-dev-shm-usage', '--disable-background-networking',
  ]);
  assert.deepEqual(storyboardBrowserArgs('darwin'), storyboardBrowserArgs('linux'));
});
