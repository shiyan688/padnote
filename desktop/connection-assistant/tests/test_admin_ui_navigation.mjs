// Run from connection-assistant with:
// NODE_PATH=<bundled-node_modules> node tests/test_admin_ui_navigation.mjs
// This uses the real rendered admin page and browser DOM; all network requests
// are blocked and the fixture contains only synthetic local Hermes profiles.
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { strict as assert } from 'node:assert';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const project = process.cwd();
const python = process.env.PYTHON || 'python3';
const fixtureSource = String.raw`
import json
from pathlib import Path
from types import SimpleNamespace
from padnote_assistant import admin_ui
import sys

status = sys.argv[1]
if status == "needs_setup":
    admin_ui.sys.platform = "win32"
instances = [{"instance_id":"existing-hermes", "name":"Existing Hermes", "kind":"hermes",
              "created_at":1, "base_url":"http://127.0.0.1:8642", "health":"not_checked",
              "detail":"fixture", "features":{}, "executable":False,
              "credential_fingerprint":""}]
candidates = [
    {"kind":"hermes", "path":"/fixtures/hermes-a/.env", "guidance":"Synthetic first profile"},
    {"kind":"hermes", "path":"/fixtures/hermes-b/.env", "guidance":"Synthetic second profile"},
]
service = SimpleNamespace(
    store=SimpleNamespace(list_instances=lambda: instances, list_pending_pairs=lambda: [], list_connections=lambda: []),
    lan_identity=None, lan_port=None, last_pair_context=None, clock=lambda:100,
    discovery_results=candidates)
admin_ui.hermes_onboarding_status = lambda _service: {"status":status}
html = admin_ui.render_admin_page(service, "csrf-fixture", 12345, 12346, "", lambda _payload:"<svg></svg>")
root = Path("padnote_assistant/static")
print(json.dumps({"html":html, "css":(root/"admin.css").read_text(), "js":(root/"admin.js").read_text()}))
`;

function render(status) {
  const result = spawnSync(python, ['-c', fixtureSource, status], {
    cwd: project, encoding: 'utf8', env: {...process.env, PYTHONDONTWRITEBYTECODE: '1'}
  });
  if (result.status !== 0) throw new Error(`fixture render failed: ${result.stderr}`);
  return JSON.parse(result.stdout);
}

async function setRenderedPage(browser, status, width = 1440, height = 900) {
  const page = await browser.newPage({viewport: {width, height}});
  await page.route('**/*', (route) => route.abort());
  const assets = render(status);
  const html = assets.html
    .replace('<link rel="stylesheet" href="/assets/admin.css">', '')
    .replace('<script src="/assets/admin.js" defer></script>', '');
  await page.setContent(html, {waitUntil: 'domcontentloaded'});
  await page.addStyleTag({content: assets.css});
  await page.addScriptTag({content: assets.js});
  return page;
}

async function assertActionLayout(page) {
  await page.locator('[data-onboarding-actions]').scrollIntoViewIfNeeded();
  const layout = await page.evaluate(() => {
    const root = document.documentElement;
    const actions = document.querySelector('[data-onboarding-actions]');
    if (!actions) return {hasActions:false, horizontalOverflow:root.scrollWidth > innerWidth};
    const parent = actions.getBoundingClientRect();
    const children = [...actions.children].map((node) => {
      const rect = node.getBoundingClientRect();
      return {left:rect.left, right:rect.right, top:rect.top, bottom:rect.bottom, width:rect.width};
    });
    const viewportVisible = parent.left >= -1 && parent.right <= innerWidth + 1
      && parent.top >= -1 && parent.bottom <= innerHeight + 1
      && children.every((rect) => rect.left >= -1 && rect.right <= innerWidth + 1
        && rect.top >= -1 && rect.bottom <= innerHeight + 1);
    return {hasActions:true, horizontalOverflow:root.scrollWidth > innerWidth,
      actionOverflow:actions.scrollWidth > actions.clientWidth + 1,
      withinCard:children.every((rect) => rect.left >= parent.left - 1 && rect.right <= parent.right + 1),
      viewportVisible, actionRect:{left:parent.left,right:parent.right,top:parent.top,bottom:parent.bottom},
      children};
  });
  assert.equal(layout.horizontalOverflow, false, `document overflow: ${JSON.stringify(layout)}`);
  assert.equal(layout.hasActions, true, 'expected onboarding action group');
  assert.equal(layout.actionOverflow, false, `action group overflow: ${JSON.stringify(layout)}`);
  assert.equal(layout.withinCard, true, `action outside status card: ${JSON.stringify(layout)}`);
  assert.equal(layout.viewportVisible, true, `action group is not visible in viewport: ${JSON.stringify(layout)}`);
}

const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH});
try {
  const manual = await setRenderedPage(browser, 'not_installed');
  assert.equal(await manual.locator('#add-agent').evaluate((node) => node.open), false,
    'manual entry fixture should begin collapsed');
  await manual.locator('[data-open-manual-hermes]').click();
  await manual.waitForFunction(() => {
    const target = document.querySelector('#add-agent-form input[name="name"]');
    const rect = target.getBoundingClientRect();
    const endpoint = document.querySelector('#manual-api-endpoint').getBoundingClientRect();
    return document.activeElement === target && rect.top >= 0 && rect.bottom <= innerHeight
      && endpoint.top >= 0 && endpoint.bottom <= innerHeight;
  });
  const manualState = await manual.evaluate(() => {
    const details = document.querySelector('#add-agent');
    const name = document.querySelector('#add-agent-form input[name="name"]');
    const endpoint = document.querySelector('#manual-api-endpoint');
    const rect = name.getBoundingClientRect();
    const endpointRect = endpoint.getBoundingClientRect();
    return {open: details.open, kind: document.querySelector('#instance-kind').value,
      focusedName: document.activeElement === name, endpointVisible: !!endpoint && !endpoint.closest('[hidden]'),
      focusInViewport: rect.top >= 0 && rect.bottom <= innerHeight,
      endpointInViewport: endpointRect.top >= 0 && endpointRect.bottom <= innerHeight};
  });
  assert.deepEqual(manualState, {open:true, kind:'hermes', focusedName:true, endpointVisible:true,
    focusInViewport:true, endpointInViewport:true});
  await manual.close();

  const ambiguous = await setRenderedPage(browser, 'ambiguous');
  assert.equal(await ambiguous.locator('#discovered-configs').evaluate((node) => node.open), false,
    'discovery fixture should begin collapsed');
  await ambiguous.locator('[data-open-discovery]').focus();
  await ambiguous.keyboard.press('Enter');
  await ambiguous.waitForFunction(() => {
    const target = document.querySelector('#discovered-configs > summary');
    const rect = target.getBoundingClientRect();
    const firstChoice = document.querySelector('#discovered-configs button')?.getBoundingClientRect();
    return document.activeElement === target && rect.top >= 0 && rect.bottom <= innerHeight
      && firstChoice && firstChoice.top >= 0 && firstChoice.bottom <= innerHeight;
  });
  const discoveryState = await ambiguous.evaluate(() => {
    const details = document.querySelector('#discovered-configs');
    const summary = details.querySelector('summary');
    const firstChoice = details.querySelector('button');
    const rect = summary.getBoundingClientRect();
    const choiceRect = firstChoice.getBoundingClientRect();
    return {open:details.open, focusedSummary:document.activeElement === summary,
      firstChoiceVisible:!!firstChoice && choiceRect.top >= 0 && choiceRect.bottom <= innerHeight,
      focusInViewport:rect.top >= 0 && rect.bottom <= innerHeight};
  });
  assert.deepEqual(discoveryState, {open:true, focusedSummary:true, firstChoiceVisible:true, focusInViewport:true});
  await ambiguous.close();

  for (const width of [390, 900]) {
    for (const status of ['not_installed', 'ambiguous', 'needs_setup']) {
      const page = await setRenderedPage(browser, status, width, 640);
      await assertActionLayout(page);
      if (status === 'needs_setup') {
        assert.equal(await page.locator('[data-setup-button]').count(), 1);
        assert.equal(await page.locator('[data-open-manual-hermes]').count(), 1,
          'setup button and manual endpoint fallback should both remain reachable');
      }
      if (status === 'ambiguous') {
        await page.locator('[data-open-discovery]').focus();
        await page.keyboard.press('Enter');
        await page.waitForFunction(() => {
          const target = document.querySelector('#discovered-configs > summary');
          const rect = target.getBoundingClientRect();
          const choice = document.querySelector('#discovered-configs button')?.getBoundingClientRect();
          return document.activeElement === target && rect.top >= 0 && rect.bottom <= innerHeight
            && choice && choice.top >= 0 && choice.bottom <= innerHeight;
        });
      }
      await page.close();
    }
  }
  console.log('PASS: manual Hermes entry and ambiguous profile navigation work in the real DOM; action links/buttons wrap without horizontal overflow at 390x640 and 900x640.');
} finally {
  await browser.close();
}
