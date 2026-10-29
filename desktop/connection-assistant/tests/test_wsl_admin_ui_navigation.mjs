// Run from connection-assistant with:
// NODE_PATH=<bundled-node_modules> node tests/test_wsl_admin_ui_navigation.mjs
// Browser HTTP is intercepted locally; fixtures and profiles are synthetic.
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { strict as assert } from 'node:assert';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const project = process.cwd();
const python = process.env.PYTHON || 'python3';
const renderFixture = String.raw`
import json
import sys
from types import SimpleNamespace
from padnote_assistant import admin_ui
from padnote_assistant import lan
admin_ui.sys.platform = "win32"
lan.lan_addresses = lambda: ["192.0.2.10"]
instances = [
 {"instance_id":"old-instance","name":"已有 Agent","kind":"hermes","executable":True,"health":"healthy","checked_at":100,"credential_fingerprint":"synthetic"},
 {"instance_id":"instance-1","name":"WSL Agent 1","kind":"hermes","executable":True,"health":"healthy","checked_at":100,"credential_fingerprint":"synthetic"},
 {"instance_id":"instance-2","name":"WSL Agent 2","kind":"hermes","executable":True,"health":"healthy","checked_at":100,"credential_fingerprint":"synthetic"},
]
service = SimpleNamespace(
 store=SimpleNamespace(list_instances=lambda: instances, list_pending_pairs=lambda: [], list_connections=lambda: []),
 lan_identity=SimpleNamespace(sha256="0"*64), lan_port=8765, last_pair_context=None, clock=lambda:100,
 discovery_results=[])
admin_ui.hermes_onboarding_status = lambda _service: {"status":"not_installed"}
print(json.dumps({"html":admin_ui.render_admin_page(service,"csrf-wsl",8766,8765,"",lambda _payload:"<svg></svg>")},ensure_ascii=False))
`;
const rendered = spawnSync(python, ['-c', renderFixture], {cwd: project, encoding: 'utf8', env:{...process.env,PYTHONDONTWRITEBYTECODE:'1'}});
if (rendered.status !== 0) throw new Error(`render failed: ${rendered.stderr}`);
const html = JSON.parse(rendered.stdout).html;
const reviewDir = path.resolve(project, '../../..', 'ui-review-r1');
await mkdir(reviewDir, {recursive:true});

const browser = await chromium.launch({headless:true, executablePath:process.env.CHROME_PATH});
try {
  const page = await browser.newPage({viewport:{width:390,height:740}});
  const calls = [];
  let profileOperationPoll = 0;
  let connectOperationPoll = 0;
  let connectSequence = 0;
  let selectedProfiles = [];
  let connectBodies = [];
  let profileCancelled = false;
  await page.route('**/*', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path === '/') return route.fulfill({status:200,contentType:'text/html; charset=utf-8',body:html});
    if (path === '/assets/admin.css') {
      const fs = await import('node:fs/promises');
      return route.fulfill({status:200,contentType:'text/css',body:await fs.readFile(`${project}/padnote_assistant/static/admin.css`,'utf8')});
    }
    if (path === '/assets/admin.js') {
      const fs = await import('node:fs/promises');
      return route.fulfill({status:200,contentType:'text/javascript',body:await fs.readFile(`${project}/padnote_assistant/static/admin.js`,'utf8')});
    }
    if (path === '/assets/brand.png') {
      const fs = await import('node:fs/promises');
      return route.fulfill({status:200,contentType:'image/png',body:await fs.readFile(`${project}/padnote_assistant/static/brand.png`)});
    }
    if (!path.startsWith('/admin/wsl/')) return route.abort();
    const headers = request.headers();
    const body = request.postData() || '';
    calls.push({method:request.method(),path,body,csrf:headers['x-padnote-csrf'],origin:headers.origin || ''});
    if (path === '/admin/wsl/distros' && request.method() === 'GET') {
      assert.equal(headers['x-padnote-csrf'],'csrf-wsl');
      return route.fulfill({status:200,json:{distros:[
        {id:'11111111-1111-4111-8111-111111111111',name:'Ubuntu QA',state:'stopped',version:2},
        {id:'22222222-2222-4222-8222-222222222222',name:'Debian QA',state:'running',version:2}
      ]}});
    }
    if (path === '/admin/wsl/profiles' && request.method() === 'POST') {
      assert.match(body,/csrf=csrf-wsl/);
      assert.match(headers.origin,/^http:\/\/padnote\.test$/);
      const params = new URLSearchParams(body);
      if (params.get('distro_id') === '11111111-1111-4111-8111-111111111111')
        return route.fulfill({status:202,json:{operation_id:'profiles-op-1'}});
      if (params.get('distro_id') === '22222222-2222-4222-8222-222222222222')
        return route.fulfill({status:202,json:{operation_id:'profiles-op-2'}});
      return route.fulfill({status:400,json:{message:'unknown fixture distro'}});
    }
    if (path === '/admin/wsl/operations/profiles-op-1' && request.method() === 'GET') {
      profileOperationPoll += 1;
      return route.fulfill({status:200,json:profileOperationPoll === 1
        ? {operation_id:'profiles-op-1',status:'running',phase:'scan',message:'查找中',distro_id:'11111111-1111-4111-8111-111111111111',profiles:[]}
        : {operation_id:'profiles-op-1',status:'succeeded',phase:'complete',message:'找到配置',distro_id:'11111111-1111-4111-8111-111111111111',profiles:[
          {id:'33333333-3333-4333-8333-333333333333',label:'Personal profile',api_state:'api_not_enabled'},
          {id:'44444444-4444-4444-8444-444444444444',label:'Work profile',api_state:'ready'}
        ]}});
    }
    if (path === '/admin/wsl/inspect' && request.method() === 'POST') {
      const params = new URLSearchParams(body);
      selectedProfiles.push(params.get('profile_id'));
      const profile = params.get('profile_id');
      return route.fulfill({status:200,json:{distro_id:params.get('distro_id'),profile_id:profile,
        preview_token:`preview-${profile}`,mode:'profile',api_state:profile === '33333333-3333-4333-8333-333333333333' ? 'api_not_enabled':'ready',
        impact:[{path_label:'所选 profile 配置',fields:['API_SERVER_ENABLED','API_SERVER_HOST'],action:profile === '33333333-3333-4333-8333-333333333333'?'update':'reuse'}],
        requires_enable:profile === '33333333-3333-4333-8333-333333333333',requires_start:false,message:'仅展示字段，不返回设置值。'}});
    }
    if (path === '/admin/wsl/connect' && request.method() === 'POST') {
      connectBodies.push(body);
      connectSequence += 1;
      return route.fulfill({status:202,json:{operation_id:`connect-op-${connectSequence}`}});
    }
    if (/^\/admin\/wsl\/operations\/connect-op-[12]$/.test(path) && request.method() === 'GET') {
      connectOperationPoll += 1;
      const operationId = path.split('/').at(-1);
      const status = connectOperationPoll <= 1 ? 'running':'succeeded';
      return route.fulfill({status:200,json:{operation_id:operationId,status,phase:status === 'running'?'connect':'complete',
        message:status === 'running'?'连接中':'连接成功',distro_id:'11111111-1111-4111-8111-111111111111',result:{instance_id:`instance-${connectSequence}`,name:`WSL Agent ${connectSequence}`}}});
    }
    if (path === '/admin/wsl/operations/profiles-op-2' && request.method() === 'GET') {
      return route.fulfill({status:200,json:{operation_id:'profiles-op-2',status:'succeeded',phase:'complete',message:'找到配置',distro_id:'22222222-2222-4222-8222-222222222222',profiles:[
        {id:'33333333-3333-4333-8333-333333333333',label:'Personal profile',api_state:'api_not_enabled'},
        {id:'44444444-4444-4444-8444-444444444444',label:'Work profile',api_state:'ready'}
      ]}});
    }
    if (path === '/admin/wsl/operations/profiles-op-cancel' && request.method() === 'GET') {
      return route.fulfill({status:200,json:{operation_id:'profiles-op-cancel',status:profileCancelled?'cancelled':'running',phase:'scan',message:'等待',distro_id:'22222222-2222-4222-8222-222222222222',profiles:[]}});
    }
    if (path === '/admin/wsl/operations/profiles-op-cancel/cancel' && request.method() === 'POST') {
      profileCancelled = true;
      return route.fulfill({status:202,json:{operation_id:'profiles-op-cancel',status:'pending'}});
    }
    return route.fulfill({status:404,json:{message:'mock endpoint not found'}});
  });
  await page.goto('http://padnote.test/');
  await page.locator('[data-open-wsl-hermes]').click();
  await page.waitForFunction(() => {
    const heading = document.querySelector('#wsl-hermes h2').getBoundingClientRect();
    const bar = document.querySelector('.sidebar').getBoundingClientRect();
    return heading.top >= bar.bottom - 1 && heading.top < innerHeight;
  });
  const distro = page.locator('[data-wsl-distro]');
  const profile = page.locator('[data-wsl-profile]');
  assert.equal(await page.locator('[data-wsl-connect]').isDisabled(),true);
  await page.locator('[data-wsl-scan]').click();
  assert.equal(await distro.inputValue(),'','scan must not auto-select a distro');
  assert.equal(await page.locator('[data-wsl-find]').isDisabled(),true);
  await distro.selectOption('11111111-1111-4111-8111-111111111111');
  page.once('dialog', dialog => { assert.match(dialog.message(),/启动该发行版并查找/); dialog.dismiss(); });
  await page.locator('[data-wsl-find]').click();
  assert.equal(calls.filter(call => call.path === '/admin/wsl/profiles').length,0,'declining stopped-distro wake must not request profile search');
  page.once('dialog', dialog => dialog.accept());
  await page.locator('[data-wsl-find]').click();
  await page.waitForFunction(() => document.querySelector('[data-wsl-profile]').options.length === 3);
  assert.equal(await profile.inputValue(),'','profile list must not auto-select the first result');
  await profile.selectOption('33333333-3333-4333-8333-333333333333');
  await page.locator('[data-wsl-inspect]').click();
  await page.locator('[data-wsl-preview]').waitFor({state:'visible'});
  const previewText = await page.locator('[data-wsl-preview]').innerText();
  assert.match(previewText,/查看技术字段/);
  await page.locator('[data-wsl-preview] details summary').click();
  assert.match(await page.locator('[data-wsl-preview]').innerText(),/API_SERVER_ENABLED/);
  assert.equal(await page.locator('[data-wsl-connect]').textContent(),'启用并连接');
  assert.doesNotMatch(previewText,/preview-33333333-3333-4333-8333-333333333333/,'preview token must not be rendered');
  assert.match(previewText,/点击下一步即确认/);
  await page.locator('[data-wsl-connect]').click();
  await page.waitForFunction(() => document.querySelector('[data-wsl-pair]').hidden === false);
  assert.equal(connectBodies.length,1);
  assert.match(connectBodies[0],/preview_token=preview-33333333-3333-4333-8333-333333333333/);
  assert.doesNotMatch(connectBodies[0],/API_SERVER_KEY|secret/i);
  assert.equal(await page.evaluate(() => Object.keys(sessionStorage).filter(key => key.includes('wsl')).length),0,
    'completed operations should remove the saved operation id');
  assert.equal(await page.locator('[data-wsl-connect]').textContent(),'选择配置后连接');
  await page.locator('[data-wsl-pair]').click();
  assert.equal(new URL(page.url()).hash,'#pairing');
  assert.equal(await page.locator('#pair-instance').inputValue(),'instance-1',
    'pairing page must select the exact registered instance returned by the operation');

  // A second profile is a distinct explicit identity; no implicit first choice or key reuse.
  await page.locator('[data-wsl-scan]').click();
  await distro.selectOption('22222222-2222-4222-8222-222222222222');
  await page.locator('[data-wsl-find]').click();
  await page.waitForFunction(() => document.querySelector('[data-wsl-profile]').options.length === 3);
  await profile.selectOption('44444444-4444-4444-8444-444444444444');
  await page.locator('[data-wsl-inspect]').click();
  await page.locator('[data-wsl-preview]').waitFor({state:'visible'});
  assert.equal(await page.locator('[data-wsl-connect]').textContent(),'连接此配置');
  await page.locator('[data-wsl-connect]').click();
  await page.waitForFunction(() => document.querySelector('[data-wsl-pair]').hidden === false);
  assert.deepEqual(selectedProfiles,['33333333-3333-4333-8333-333333333333','44444444-4444-4444-8444-444444444444']);
  assert.equal(connectBodies.length,2);
  assert.match(connectBodies[1],/preview_token=preview-44444444-4444-4444-8444-444444444444/);

  // Continue from an operation id after a simulated page revisit, then cancel only waiting.
  await page.evaluate(() => sessionStorage.setItem('padnote.wsl.operation_id','profiles-op-cancel'));
  await page.reload();
  await page.waitForFunction(() => document.querySelector('[data-wsl-cancel]').hidden === false);
  assert.equal(await page.locator('[data-wsl-distro]').inputValue(),'22222222-2222-4222-8222-222222222222',
    'resume must restore the operation distro by identity, not guess a first result');
  await page.locator('[data-wsl-cancel]').click();
  await page.waitForFunction(() => document.querySelector('[data-wsl-message]').textContent.includes('取消等待'));
  assert.equal(calls.some(call => call.path.includes('stop') || call.path.includes('gateway/stop')),false,
    'cancel must never call a Hermes stop endpoint');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),true);
  await page.close();

  for (const [width,height,name] of [[900,760,'wsl-desktop-900x760.png'],[390,800,'wsl-mobile-390x800.png']]) {
    const layoutPage = await browser.newPage({viewport:{width,height}});
    await layoutPage.route('**/*', async route => {
      const path = new URL(route.request().url()).pathname;
      if (path === '/') return route.fulfill({status:200,contentType:'text/html',body:html});
      if (path === '/assets/admin.css') {
        const fs = await import('node:fs/promises');
        return route.fulfill({status:200,contentType:'text/css',body:await fs.readFile(`${project}/padnote_assistant/static/admin.css`,'utf8')});
      }
      if (path === '/assets/admin.js') {
        const fs = await import('node:fs/promises');
        return route.fulfill({status:200,contentType:'text/javascript',body:await fs.readFile(`${project}/padnote_assistant/static/admin.js`,'utf8')});
      }
      if (path === '/assets/brand.png') {
        const fs = await import('node:fs/promises');
        return route.fulfill({status:200,contentType:'image/png',body:await fs.readFile(`${project}/padnote_assistant/static/brand.png`)});
      }
      return route.abort();
    });
    await layoutPage.goto('http://layout.test/');
    await layoutPage.locator('[data-open-wsl-hermes]').click();
    await layoutPage.waitForFunction(() => {
      const heading = document.querySelector('#wsl-hermes h2').getBoundingClientRect();
      const bar = document.querySelector('.sidebar').getBoundingClientRect();
      return heading.top >= bar.bottom - 1 && heading.top < innerHeight;
    });
    const metrics = await layoutPage.evaluate(() => ({doc:document.documentElement.scrollWidth,viewport:innerWidth,
      cards:[...document.querySelectorAll('.wsl-step')].map(card => {const r=card.getBoundingClientRect();return [r.left,r.right];})}));
    assert.ok(metrics.doc <= metrics.viewport,`horizontal document overflow at ${width}px: ${JSON.stringify(metrics)}`);
    assert.ok(metrics.cards.every(([left,right]) => left >= -1 && right <= metrics.viewport + 1),`card overflow at ${width}px: ${JSON.stringify(metrics)}`);
    await layoutPage.locator('#wsl-hermes').screenshot({path:path.join(reviewDir,name)});
    await layoutPage.close();
  }
  console.log('PASS: WSL distro/profile selection, stopped-distro consent, safe preview, explicit connect, separate profiles, resumable polling/cancel wording, and 390/900px layout. All HTTP was mocked.');
} finally { await browser.close(); }
