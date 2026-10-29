// Run from connection-assistant with the bundled Node/Playwright and Chrome.
// All backend requests are intercepted; fixtures are synthetic and no Hermes is contacted.
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
from types import SimpleNamespace
from padnote_assistant import admin_ui, lan
admin_ui.sys.platform = "win32"
lan.lan_addresses = lambda: ["192.0.2.8"]
instances = [
 {"instance_id":"old-instance","name":"Old synthetic Agent","kind":"hermes","executable":True,"health":"healthy","checked_at":100,"credential_fingerprint":"synthetic"},
 {"instance_id":"new-instance","name":"New synthetic Agent","kind":"hermes","executable":True,"health":"healthy","checked_at":100,"credential_fingerprint":"synthetic"},
]
service = SimpleNamespace(store=SimpleNamespace(list_instances=lambda: instances, list_pending_pairs=lambda: [], list_connections=lambda: []),
 lan_identity=SimpleNamespace(sha256="0"*64), lan_port=8765, last_pair_context=None, clock=lambda:100, discovery_results=[])
admin_ui.hermes_onboarding_status = lambda _service: {"status":"not_installed"}
print(json.dumps({"html":admin_ui.render_admin_page(service,"csrf-beta31",8766,8765,"",lambda _payload:"<svg></svg>")},ensure_ascii=False))
`;
const rendered = spawnSync(python, ['-c', renderFixture], {cwd:project,encoding:'utf8',env:{...process.env,PYTHONDONTWRITEBYTECODE:'1'}});
if (rendered.status !== 0) throw new Error(`render failed: ${rendered.stderr}`);
const html = JSON.parse(rendered.stdout).html;
const browser = await chromium.launch({headless:true, executablePath:process.env.CHROME_PATH});
const reviewDir = process.env.PADNOTE_UI_REVIEW_DIR ? path.resolve(process.env.PADNOTE_UI_REVIEW_DIR) : path.resolve(project, '../../..', 'ui-review-r1');
await mkdir(reviewDir,{recursive:true});

const setSession = async (page, values) => {
  await page.addInitScript((items) => {
    if (sessionStorage.getItem('padnote.test.seeded')) return;
    for (const [key,value] of Object.entries(items)) sessionStorage.setItem(key,value);
    sessionStorage.setItem('padnote.test.seeded','1');
  }, values);
};
const routeAssets = async (route, requestPath) => {
  const fs = await import('node:fs/promises');
  if (requestPath === '/') return route.fulfill({status:200,contentType:'text/html; charset=utf-8',body:html});
  if (requestPath === '/assets/admin.css') return route.fulfill({status:200,contentType:'text/css',body:await fs.readFile(`${project}/padnote_assistant/static/admin.css`,'utf8')});
  if (requestPath === '/assets/admin.js') return route.fulfill({status:200,contentType:'text/javascript',body:await fs.readFile(`${project}/padnote_assistant/static/admin.js`,'utf8')});
  if (requestPath === '/assets/brand.png') return route.fulfill({status:200,contentType:'image/png',body:await fs.readFile(`${project}/padnote_assistant/static/brand.png`)});
  return false;
};

try {
  // Terminal unknown detaches, preserves the operation ID, and only explicit refresh reads it.
  {
    const page = await browser.newPage();
    let unknownReads = 0;
    let connectPosts = 0;
    await setSession(page, {
      'padnote.wsl.operation_id':'unknown-op',
      'padnote.wsl.operation_kind':'connect',
      'padnote.wsl.operation_distro':'11111111-1111-4111-8111-111111111111'
    });
    await page.route('**/*', async (route) => {
      const path = new URL(route.request().url()).pathname;
      const asset = await routeAssets(route,path); if (asset !== false) return asset;
      if (path === '/admin/wsl/distros') return route.fulfill({status:200,json:{distros:[
        {id:'11111111-1111-4111-8111-111111111111',name:'Ubuntu A',state:'running',version:2},
        {id:'22222222-2222-4222-8222-222222222222',name:'Debian B',state:'running',version:2}
      ]}});
      if (path === '/admin/wsl/operations/unknown-op') {
        unknownReads += 1;
        if (unknownReads === 3) return route.fulfill({status:503,json:{message:'synthetic read failure'}});
        return route.fulfill({status:200,json:{operation_id:'unknown-op',status:'unknown',phase:'connect',message:'状态无法确认',distro_id:'11111111-1111-4111-8111-111111111111'}});
      }
      if (path === '/admin/wsl/connect') { connectPosts += 1; return route.fulfill({status:202,json:{operation_id:'should-not-post'}}); }
      return route.fulfill({status:404,json:{message:'synthetic fixture endpoint missing'}});
    });
    await page.goto('http://recovery.test/');
    await page.waitForFunction(() => document.querySelector('[data-wsl-return]').hidden === false);
    assert.equal(await page.locator('[data-wsl-distro]').isDisabled(),false,'unknown must unlock distro selection');
    assert.equal(await page.evaluate(() => sessionStorage.getItem('padnote.wsl.operation_id')),null,'detached unknown is not an active lock');
    assert.match(await page.locator('[data-wsl-message]').innerText(),/未知/);
    assert.doesNotMatch(await page.locator('[data-wsl-message]').innerText(),/失败|已取消/);
    await page.setViewportSize({width:900,height:760});
    await page.locator('#wsl-hermes').screenshot({path:path.join(reviewDir,'wsl-unknown-recovery-900x760.png')});
    await page.locator('[data-wsl-operation]').screenshot({path:path.join(reviewDir,'wsl-unknown-operation-900.png')});
    await page.setViewportSize({width:390,height:800});
    await page.locator('#wsl-hermes').screenshot({path:path.join(reviewDir,'wsl-unknown-recovery-390x800.png')});
    await page.locator('[data-wsl-operation]').screenshot({path:path.join(reviewDir,'wsl-unknown-operation-390.png')});
    await page.locator('[data-wsl-return]').click();
    await page.locator('[data-wsl-distro]').selectOption('22222222-2222-4222-8222-222222222222');
    const beforeRefresh = unknownReads;
    assert.equal(await page.locator('[data-wsl-reopen="unknown-op"]').innerText(),'查看状态');
    assert.match(await page.locator('[data-wsl-reopen="unknown-op"]').locator('xpath=..').innerText(),/只查询原操作，不会重新发送连接请求/);
    const readOnlyResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/admin/wsl/operations/unknown-op');
    await page.locator('[data-wsl-reopen="unknown-op"]').click();
    await readOnlyResponse;
    assert.equal(unknownReads,beforeRefresh + 1,'refresh should make exactly one read-only request');
    assert.equal(await page.locator('[data-wsl-distro]').inputValue(),'22222222-2222-4222-8222-222222222222','old result cannot replace the new selection');
    assert.equal(await page.locator('[data-wsl-distro]').isDisabled(),false);
    assert.equal(connectPosts,0,'unknown must never auto-resubmit connect');
    await page.reload();
    await page.waitForTimeout(80);
    assert.equal(unknownReads,beforeRefresh + 1,'reload of detached history must not poll automatically');
    assert.equal(await page.locator('[data-wsl-distro]').isDisabled(),false,'reload must not re-lock a detached operation');
    assert.equal(connectPosts,0);
    const failedRefresh = page.waitForResponse(response => new URL(response.url()).pathname === '/admin/wsl/operations/unknown-op' && response.status() === 503);
    await page.locator('[data-wsl-reopen="unknown-op"]').click();
    await failedRefresh;
    const recentRow = page.locator('[data-wsl-reopen="unknown-op"]').locator('xpath=..');
    assert.match(await recentRow.innerText(),/暂时无法查询，可重试/);
    assert.match(await recentRow.innerText(),/结果未知/);
    assert.doesNotMatch(await recentRow.innerText(),/已失败/);
    await page.close();
  }

  // Cancel-wait detaches immediately. A late old success cannot pair over a newer operation.
  {
    const page = await browser.newPage();
    let lateReadStarted = false;
    let lateReadCount = 0;
    let newProfilePolls = 0;
    let connectSequence = 0;
    let newConnectPolls = 0;
    let cancelPosts = 0;
    let distroFetches = 0;
    let resolveStaleDistroFetch;
    let profilePosts = 0;
    let resolveStaleProfilePost;
    let resolveCancelPost;
    await setSession(page, {
      'padnote.wsl.operation_id':'late-op',
      'padnote.wsl.operation_kind':'connect',
      'padnote.wsl.operation_distro':'11111111-1111-4111-8111-111111111111'
    });
    await page.route('**/*', async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      const asset = await routeAssets(route,path); if (asset !== false) return asset;
      if (path === '/admin/wsl/distros') {
        distroFetches += 1;
        if (distroFetches === 2) return await new Promise(resolve => {
          resolveStaleDistroFetch = () => { route.fulfill({status:200,json:{distros:[
            {id:'11111111-1111-4111-8111-111111111111',name:'Ubuntu A',state:'running',version:2},
            {id:'22222222-2222-4222-8222-222222222222',name:'Debian B',state:'running',version:2},
            {id:'33333333-3333-4333-8333-333333333333',name:'Stale distro',state:'running',version:2}
          ]}}).then(resolve); };
        });
        return route.fulfill({status:200,json:{distros:[
          {id:'11111111-1111-4111-8111-111111111111',name:'Ubuntu A',state:'running',version:2},
          {id:'22222222-2222-4222-8222-222222222222',name:'Debian B',state:'running',version:2}
        ]}});
      }
      if (path === '/admin/wsl/operations/late-op') {
        lateReadCount += 1; lateReadStarted = true;
        return route.fulfill({status:200,json:{operation_id:'late-op',status:'succeeded',phase:'complete',message:'旧 Agent 已连接',distro_id:'33333333-3333-4333-8333-333333333333',result:{instance_id:'old-instance'}}});
      }
      if (path === '/admin/wsl/operations/late-op/cancel' && request.method() === 'POST') {
        cancelPosts += 1;
        return await new Promise(resolve => { resolveCancelPost = () => resolve(route.fulfill({status:202,json:{operation_id:'late-op',status:'running',message:'cancel wait accepted'}})); });
      }
      if (path === '/admin/wsl/profiles' && request.method() === 'POST') {
        profilePosts += 1;
        if (profilePosts === 1) return await new Promise(resolve => { resolveStaleProfilePost = () => resolve(route.fulfill({status:202,json:{operation_id:'stale-profiles'}})); });
        return route.fulfill({status:202,json:{operation_id:'profiles-new'}});
      }
      if (path === '/admin/wsl/operations/profiles-new') { newProfilePolls += 1; return route.fulfill({status:200,json:{operation_id:'profiles-new',status:'succeeded',phase:'complete',message:'found',distro_id:'22222222-2222-4222-8222-222222222222',profiles:[{id:'33333333-3333-4333-8333-333333333333',label:'New profile',api_state:'ready'}]}}); }
      if (path === '/admin/wsl/inspect' && request.method() === 'POST') return route.fulfill({status:200,json:{distro_id:'22222222-2222-4222-8222-222222222222',profile_id:'33333333-3333-4333-8333-333333333333',preview_token:'synthetic-preview',mode:'profile',api_state:'ready',impact:[],requires_enable:false,requires_start:false,message:'ready'}});
      if (path === '/admin/wsl/connect' && request.method() === 'POST') { connectSequence += 1; return route.fulfill({status:202,json:{operation_id:'connect-new'}}); }
      if (path === '/admin/wsl/operations/connect-new') { newConnectPolls += 1; return route.fulfill({status:200,json:{operation_id:'connect-new',status:'succeeded',phase:'complete',message:'connected',distro_id:'22222222-2222-4222-8222-222222222222',result:{instance_id:'new-instance',name:'New Agent'}}}); }
      return route.fulfill({status:404,json:{message:'synthetic fixture endpoint missing'}});
    });
    await page.goto('http://recovery.test/');
    await page.waitForFunction(() => document.querySelector('[data-wsl-cancel]').hidden === false);
    for (let attempt = 0; attempt < 50 && !lateReadStarted; attempt += 1) await page.waitForTimeout(20);
    assert.equal(lateReadStarted,true);
    await page.locator('[data-wsl-cancel]').click();
    await page.waitForFunction(() => document.querySelector('[data-wsl-distro]').disabled === false);
    assert.equal(cancelPosts,1);
    assert.equal(await page.evaluate(() => sessionStorage.getItem('padnote.wsl.operation_id')),null);
    assert.match(await page.locator('[data-wsl-message]').innerText(),/后台操作可能继续/);
    assert.equal(await page.locator('[data-wsl-reopen="late-op"]').count(),1,'cancel must preserve the operation ID for explicit later inspection');
    assert.equal(await page.locator('[data-wsl-cancel]').isHidden(),true);
    await page.locator('[data-wsl-distro]').selectOption('22222222-2222-4222-8222-222222222222');
    assert.equal(typeof resolveStaleDistroFetch,'function','old poll should be waiting on its distro-list request');
    resolveStaleDistroFetch();
    await page.waitForTimeout(50);
    assert.equal(await page.locator('[data-wsl-distro]').inputValue(),'22222222-2222-4222-8222-222222222222','stale distro-list response must not replace the new selection');
    await page.locator('[data-wsl-find]').click();
    for (let attempt = 0; attempt < 50 && !resolveStaleProfilePost; attempt += 1) await page.waitForTimeout(20);
    assert.equal(typeof resolveStaleProfilePost,'function','profile request should be pending');
    await page.locator('[data-wsl-distro]').selectOption('11111111-1111-4111-8111-111111111111');
    resolveStaleProfilePost();
    await page.waitForTimeout(50);
    assert.equal(await page.locator('[data-wsl-distro]').inputValue(),'11111111-1111-4111-8111-111111111111','late profile POST cannot change the newer distro selection');
    assert.equal(await page.evaluate(() => sessionStorage.getItem('padnote.wsl.operation_id')),null,'stale accepted search must not take over the active slot');
    await page.locator('[data-wsl-distro]').selectOption('22222222-2222-4222-8222-222222222222');
    await page.locator('[data-wsl-find]').click();
    await page.waitForFunction(() => document.querySelector('[data-wsl-profile]').options.length === 2);
    await page.locator('[data-wsl-profile]').selectOption('33333333-3333-4333-8333-333333333333');
    await page.locator('[data-wsl-inspect]').click();
    await page.locator('[data-wsl-preview]').waitFor({state:'visible'});
    await page.locator('[data-wsl-connect]').click();
    await page.waitForFunction(() => document.querySelector('[data-wsl-pair]').hidden === false);
    assert.equal(newProfilePolls,1); assert.equal(newConnectPolls,1,'new operation should reach its successful terminal state');
    assert.equal(await page.locator('[data-wsl-pair]').getAttribute('data-instance-id'),'new-instance');
    assert.equal(typeof resolveCancelPost,'function','cancel request should still be pending after the UI detached');
    const recentGet = page.waitForResponse(response => new URL(response.url()).pathname === '/admin/wsl/operations/late-op');
    await page.locator('[data-wsl-reopen="late-op"]').click();
    await recentGet;
    const lateRow = page.locator('[data-wsl-reopen="late-op"]').locator('xpath=..');
    assert.match(await lateRow.innerText(),/已完成/,'explicit GET should publish the latest operation state');
    resolveCancelPost();
    await page.waitForTimeout(50);
    assert.match(await lateRow.innerText(),/已完成/,'late cancel POST must not downgrade newer GET state');
    assert.doesNotMatch(await lateRow.innerText(),/后台可能仍在运行/);
    assert.equal(await page.locator('[data-wsl-pair]').getAttribute('data-instance-id'),'new-instance','late cancel response cannot overwrite the newer pairing target');
    await page.waitForTimeout(80);
    assert.equal(await page.locator('[data-wsl-pair]').getAttribute('data-instance-id'),'new-instance','late old success cannot replace the current pairing target');
    assert.equal(await page.locator('[data-wsl-distro]').inputValue(),'22222222-2222-4222-8222-222222222222','late response cannot replace the newer selection');
    assert.equal(lateReadCount,2,'one active poll plus one explicit read-only refresh; no automatic follow-up poll');
    await page.locator('[data-wsl-pair]').click();
    await page.waitForFunction(() => document.querySelector('#pair-instance')?.value === 'new-instance');
    assert.equal(await page.locator('#pair-instance').inputValue(),'new-instance','pair target must be the explicitly selected latest Agent');
    await page.close();
  }

  // A startup restore must not apply its delayed distro list after the user cancels and starts a fresh scan.
  {
    const page = await browser.newPage();
    let distroCalls = 0;
    let resolveBootstrapDistros;
    let stalePolls = 0;
    await setSession(page, {
      'padnote.wsl.operation_id':'bootstrap-op',
      'padnote.wsl.operation_kind':'connect',
      'padnote.wsl.operation_distro':'11111111-1111-4111-8111-111111111111'
    });
    await page.route('**/*', async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      const asset = await routeAssets(route,path); if (asset !== false) return asset;
      if (path === '/admin/wsl/distros') {
        distroCalls += 1;
        if (distroCalls === 1) return await new Promise(resolve => { resolveBootstrapDistros = () => route.fulfill({status:200,json:{distros:[
          {id:'11111111-1111-4111-8111-111111111111',name:'Old A',state:'running',version:2}
        ]}}).then(resolve); });
        return route.fulfill({status:200,json:{distros:[
          {id:'11111111-1111-4111-8111-111111111111',name:'New A',state:'running',version:2},
          {id:'22222222-2222-4222-8222-222222222222',name:'New B',state:'running',version:2}
        ]}});
      }
      if (path === '/admin/wsl/operations/bootstrap-op') { stalePolls += 1; return route.fulfill({status:200,json:{operation_id:'bootstrap-op',status:'running',phase:'connect',message:'old restore',distro_id:'11111111-1111-4111-8111-111111111111'}}); }
      if (path === '/admin/wsl/operations/bootstrap-op/cancel' && request.method() === 'POST') return route.fulfill({status:202,json:{operation_id:'bootstrap-op',status:'pending'}});
      return route.fulfill({status:404,json:{message:'synthetic fixture endpoint missing'}});
    });
    await page.goto('http://recovery.test/');
    await page.waitForFunction(() => document.querySelector('[data-wsl-cancel]').hidden === false);
    for (let attempt = 0; attempt < 50 && !resolveBootstrapDistros; attempt += 1) await page.waitForTimeout(20);
    assert.equal(typeof resolveBootstrapDistros,'function','startup restore should be waiting on its distro fetch');
    await page.locator('[data-wsl-cancel]').click();
    await page.waitForFunction(() => !document.querySelector('[data-wsl-distro]').disabled);
    await page.locator('[data-wsl-scan]').click();
    await page.waitForFunction(() => document.querySelector('[data-wsl-distro]').options.length === 3);
    await page.locator('[data-wsl-distro]').selectOption('22222222-2222-4222-8222-222222222222');
    resolveBootstrapDistros();
    await page.waitForTimeout(50);
    assert.equal(await page.locator('[data-wsl-distro]').inputValue(),'22222222-2222-4222-8222-222222222222','late bootstrap list must not replace a newer selection');
    assert.equal(stalePolls,0,'a detached bootstrap must not poll after its restore request returns');
    await page.close();
  }
  console.log('PASS: unknown detach/read-only recovery, cancel-wait detach, reload without auto-lock/repost, stale response isolation, and exact latest pairing target. All HTTP was mocked.');
} finally { await browser.close(); }
