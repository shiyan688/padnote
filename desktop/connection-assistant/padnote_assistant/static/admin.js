(() => {
  'use strict';
  const kind = document.getElementById('instance-kind');
  const form = document.getElementById('add-agent-form');
  const openclaw = document.querySelector('[data-openclaw-note]');
  const applyKind = () => {
    if (!kind) return;
    document.querySelectorAll('[data-kind]').forEach((group) => {
      const active = group.dataset.kind === kind.value;
      group.hidden = !active;
      group.querySelectorAll('input,select,textarea').forEach((control) => { control.disabled = !active; });
    });
    if (openclaw) openclaw.hidden = kind.value !== 'openclaw';
  };
  if (kind) { kind.addEventListener('change', applyKind); applyKind(); }

  const addDetails = document.getElementById('add-agent');
  const focusAddAgent = () => {
    if (!addDetails || location.hash !== '#add-agent') return;
    addDetails.open = true;
    window.setTimeout(() => addDetails.querySelector('#add-agent-form input[name="name"]')?.focus(), 50);
  };
  focusAddAgent();
  window.addEventListener('hashchange', focusAddAgent);
  document.querySelectorAll('[data-open-manual-hermes]').forEach((link) => {
    link.addEventListener('click', () => {
      if (kind) { kind.value = 'hermes'; applyKind(); }
      if (addDetails) addDetails.open = true;
      window.setTimeout(() => {
        const name = addDetails?.querySelector('#add-agent-form input[name="name"]');
        name?.scrollIntoView({block: 'center'});
        name?.focus();
      }, 0);
    });
  });
  const discovered = document.getElementById('discovered-configs');
  document.querySelectorAll('[data-open-discovery]').forEach((link) => {
    link.addEventListener('click', () => {
      if (discovered) discovered.open = true;
      window.setTimeout(() => {
        const summary = discovered?.querySelector('summary');
        summary?.scrollIntoView({block: 'center'});
        summary?.focus();
      }, 0);
    });
  });
  document.querySelectorAll('[data-open-wsl-hermes]').forEach((link) => {
    link.addEventListener('click', () => {
      const section = document.getElementById('wsl-hermes');
      const target = section?.querySelector('[data-wsl-scan]');
      window.setTimeout(() => {
        section?.scrollIntoView({block:'start'});
        target?.focus({preventScroll:true});
      }, 0);
    });
  });
  const sectionNav = Array.from(document.querySelectorAll('.sidebar nav a'));
  const navSections = sectionNav.map((link) => document.querySelector(link.getAttribute('href'))).filter(Boolean);
  if ('IntersectionObserver' in window && navSections.length) {
    const setCurrent = (section) => {
      for (const link of sectionNav) {
        if (link.getAttribute('href') === `#${section.id}`) link.setAttribute('aria-current', 'location');
        else link.removeAttribute('aria-current');
      }
    };
    const observer = new IntersectionObserver((entries) => {
      const visible = entries.filter((entry) => entry.isIntersecting)
        .sort((left, right) => right.intersectionRatio - left.intersectionRatio)[0];
      if (visible) setCurrent(visible.target);
    }, {rootMargin: '-18% 0px -68% 0px', threshold: [0, .15, .35]});
    navSections.forEach((section) => observer.observe(section));
  }
  document.querySelectorAll('[data-open-credentials]').forEach((button) => {
    button.addEventListener('click', () => {
      const details = document.querySelector(`[data-credentials="${CSS.escape(button.dataset.openCredentials)}"]`);
      if (!details) return;
      details.open = true;
      window.setTimeout(() => details.querySelector('[data-credential-input]')?.focus(), 50);
    });
  });
  document.querySelectorAll('[data-pair-agent]').forEach((link) => {
    link.addEventListener('click', () => {
      const select = document.getElementById('pair-instance');
      if (select) {
        select.value = link.dataset.pairAgent;
        select.dispatchEvent(new Event('change', {bubbles: true}));
      }
    });
  });

  const pairResult = document.querySelector('.pair-result');
  if (pairResult) {
    const expiry = Number(pairResult.dataset.expires) * 1000;
    const countdown = pairResult.querySelector('[data-countdown]');
    const expiredNote = pairResult.querySelector('[data-pair-expired]');
    const mismatchNote = pairResult.querySelector('[data-pair-mismatch]');
    const qr = pairResult.querySelector('[data-qr-wrap]');
    const technical = pairResult.querySelector('.pair-technical');
    const selector = document.getElementById('pair-instance');
    const hasExpired = () => Date.now() >= expiry;
    const updateTarget = () => {
      const mismatch = Boolean(selector && selector.value !== pairResult.dataset.pairInstance);
      pairResult.classList.toggle('target-mismatch', mismatch);
      if (qr) qr.hidden = mismatch || hasExpired();
      if (technical) {
        technical.hidden = mismatch || hasExpired();
        if (mismatch || hasExpired()) technical.open = false;
      }
      if (mismatchNote) mismatchNote.hidden = !mismatch || hasExpired();
      if (expiredNote) expiredNote.hidden = !hasExpired();
    };
    if (selector) selector.addEventListener('change', updateTarget);
    const tick = () => {
      const left = Math.max(0, Math.ceil((expiry - Date.now()) / 1000));
      if (countdown) countdown.textContent = left ? `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}` : '已过期';
      if (!left) {
        pairResult.classList.add('expired');
        const copy = pairResult.querySelector('[data-copy]');
        if (copy) { copy.disabled = true; copy.textContent = '配对码已过期'; }
      }
      updateTarget();
    };
    tick(); window.setInterval(tick, 1000);
    const copy = pairResult.querySelector('[data-copy]');
    if (copy) copy.addEventListener('click', async () => {
      const text = pairResult.querySelector('#pair-payload')?.value || '';
      try { await navigator.clipboard.writeText(text); copy.textContent = '已复制'; }
      catch (_) { const box = pairResult.querySelector('#pair-payload'); box?.focus(); box?.select(); copy.textContent = '请使用系统复制'; }
    });
  }

  const csrf = document.querySelector('[data-csrf]')?.value || '';
  document.querySelectorAll('[data-hermes-connect]').forEach((connectForm) => {
    connectForm.addEventListener('submit', () => {
      const button = connectForm.querySelector('[data-connect-button]');
      const progress = connectForm.querySelector('[data-connect-progress]');
      if (button) { button.disabled = true; button.textContent = '正在检查并连接…'; }
      if (progress) progress.hidden = false;
    });
  });
  document.querySelectorAll('[data-hermes-setup]').forEach((setupForm) => {
    setupForm.addEventListener('submit', () => {
      const button = setupForm.querySelector('[data-setup-button]');
      if (button) { button.disabled = true; button.textContent = '正在打开…'; }
    });
  });
  document.querySelectorAll('[data-agent-check]').forEach((checkForm) => {
    checkForm.addEventListener('submit', () => {
      const button = checkForm.querySelector('[data-check-button]');
      if (button) { button.disabled = true; button.textContent = '正在检测 Agent 接口…'; }
    });
  });
  const pending = document.getElementById('pending-list');
  const connections = document.getElementById('connection-list');
  const status = document.querySelector('[data-live-status]');
  let active = false;
  const ageLabels = Array.from(document.querySelectorAll('[data-check-age-label]')).map((element) => ({
    element,
    initialAge: Number(element.dataset.checkAge),
    startedAt: performance.now()
  })).filter((item) => Number.isFinite(item.initialAge));
  const refreshCheckAges = () => {
    for (const item of ageLabels) {
      const age = item.initialAge + Math.floor((performance.now() - item.startedAt) / 1000);
      item.element.textContent = age <= 60 ? `上次检测：${age} 秒前` : `上次检测：${Math.floor(age / 60)} 分钟前 · 结果已过期`;
      if (age > 60) {
        item.element.classList.remove('good');
        item.element.classList.add('warn');
        const card = item.element.closest('.agent-card');
        const status = card?.querySelector('.agent-heading .badge');
        if (status) { status.textContent = '检测结果已过期'; status.classList.remove('good'); status.classList.add('warn'); }
        const guidance = card?.querySelector('.agent-guidance');
        if (guidance) guidance.textContent = '上次检测结果已过期。重新检测接口后，再继续配对。';
        const pairLink = card?.querySelector('[data-pair-agent]');
        const checkButton = card?.querySelector('[data-check-button]');
        const checkForm = checkButton?.closest('form');
        if (checkButton) checkButton.classList.remove('button-quiet');
        if (pairLink) pairLink.classList.add('button-quiet');
        if (checkForm && pairLink && pairLink.parentElement === checkForm.parentElement) {
          checkForm.parentElement.insertBefore(checkForm, pairLink);
        }
      }
    }
  };
  refreshCheckAges();
  const fetchLive = async () => {
    if (active || document.visibilityState !== 'visible') return;
    active = true;
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), 5000);
    try {
      const response = await fetch('/admin/live', {headers: {'X-PadNote-CSRF': csrf}, cache: 'no-store', signal: controller.signal});
      if (!response.ok) throw new Error('更新失败');
      const data = await response.json();
      // Preserve a focused approve/revoke control and its surrounding form.
      const focused = document.activeElement;
      const focusPending = pending?.contains(focused);
      const focusConnections = connections?.contains(focused);
      if (!focusPending && pending) pending.innerHTML = data.pending_html;
      if (!focusConnections && connections) connections.innerHTML = data.connections_html;
      const pc = document.querySelector('[data-pending-count]');
      const cc = document.querySelector('[data-connection-count]');
      if (pc) pc.textContent = String(data.pending_count);
      if (cc) cc.textContent = String(data.connection_count);
      const updated = document.querySelector('[data-updated-at]');
      if (updated) updated.textContent = `最近更新：${new Date(data.updated_at * 1000).toLocaleTimeString()}`;
      if (status) status.textContent = '自动更新中';
    } catch (_) {
      if (status) status.textContent = '暂时无法更新，正在重试';
    } finally {
      window.clearTimeout(timer); active = false;
    }
  };
  window.setInterval(fetchLive, 3000);
  window.setInterval(refreshCheckAges, 3000);
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') fetchLive(); });

  const wsl = document.getElementById('wsl-hermes');
  if (wsl) {
    const distroSelect = wsl.querySelector('[data-wsl-distro]');
    const profileSelect = wsl.querySelector('[data-wsl-profile]');
    const scanButton = wsl.querySelector('[data-wsl-scan]');
    const findButton = wsl.querySelector('[data-wsl-find]');
    const inspectButton = wsl.querySelector('[data-wsl-inspect]');
    const connectButton = wsl.querySelector('[data-wsl-connect]');
    const preview = wsl.querySelector('[data-wsl-preview]');
    const operation = wsl.querySelector('[data-wsl-operation]');
    const operationMessage = wsl.querySelector('[data-wsl-message]');
    const refreshButton = wsl.querySelector('[data-wsl-refresh]');
    const cancelButton = wsl.querySelector('[data-wsl-cancel]');
    const returnButton = wsl.querySelector('[data-wsl-return]');
    const recentContainer = wsl.querySelector('[data-wsl-recent]');
    const pairLink = wsl.querySelector('[data-wsl-pair]');
    const distroState = wsl.querySelector('[data-wsl-distro-state]');
    const operationStorageKey = 'padnote.wsl.operation_id';
    const operationKindStorageKey = 'padnote.wsl.operation_kind';
    const operationDistroStorageKey = 'padnote.wsl.operation_distro';
    const recentOperationsStorageKey = 'padnote.wsl.recent_operations';
    const pairTargetStorageKey = 'padnote.wsl.pair_instance_id';
    const validOperationId = (value) => typeof value === 'string' && value.length > 0 && value.length <= 256 && !/[\u0000-\u001f\u007f]/.test(value);
    let selectedDistros = new Map();
    let previewToken = '';
    let operationId = '';
    let operationKind = '';
    let pollTimer = 0;
    let selectionRevision = 0;
    let operationEpoch = 0;
    const maxRecentOperations = 5;
    const recentRefreshStates = new Map();
    const recentCancelStates = new Map();

    const lockSelection = (locked) => {
      scanButton.disabled = locked;
      distroSelect.disabled = locked;
      findButton.disabled = locked || !distroSelect.value;
      profileSelect.disabled = locked || profileSelect.options.length <= 1;
      inspectButton.disabled = locked || !distroSelect.value || !profileSelect.value;
      if (locked) connectButton.disabled = true;
    };

    const showOperation = (message, {refresh = false, cancel = false, pair = false, leave = false} = {}) => {
      operation.hidden = false;
      operationMessage.textContent = message;
      refreshButton.hidden = !refresh;
      cancelButton.hidden = !cancel;
      returnButton.hidden = !leave;
      pairLink.hidden = !pair;
    };
    const readRecentOperations = () => {
      try {
        const parsed = JSON.parse(sessionStorage.getItem(recentOperationsStorageKey) || '[]');
        if (!Array.isArray(parsed)) return [];
        return parsed.filter((item) => item && validOperationId(item.id)
          && ['profiles','connect'].includes(item.kind)
          && (item.distroId === '' || typeof item.distroId === 'string' && item.distroId.length <= 256 && !/[\u0000-\u001f\u007f]/.test(item.distroId))
          && ['pending','running','succeeded','failed','cancelled','unknown'].includes(item.status))
          .slice(0, maxRecentOperations).map((item) => ({id:item.id,kind:item.kind,distroId:item.distroId,status:item.status,label:typeof item.label === 'string' ? item.label.slice(0,120) : ''}));
      } catch (_) { return []; }
    };
    const writeRecentOperations = (items) => {
      sessionStorage.setItem(recentOperationsStorageKey, JSON.stringify(items.slice(0, maxRecentOperations)));
      renderRecentOperations();
    };
    const rememberOperation = (id, kind, distroId, status = 'unknown', label = '') => {
      if (!validOperationId(id) || !['profiles','connect'].includes(kind)) return;
      const current = readRecentOperations().filter((item) => item.id !== id);
      const safeLabel = typeof label === 'string' ? label.replace(/[\u0000-\u001f\u007f]/g,' ').slice(0,120) : '';
      current.unshift({id,kind,distroId:typeof distroId === 'string' ? distroId : '',status,label:safeLabel});
      writeRecentOperations(current);
    };
    const updateRecentStatus = (id, status) => {
      const items = readRecentOperations();
      const item = items.find((entry) => entry.id === id);
      if (item) { item.status = status; writeRecentOperations(items); }
    };
    const renderRecentOperations = () => {
      if (!recentContainer) return;
      const entries = readRecentOperations();
      if (entries.length && operation.hidden) {
        operation.hidden = false;
        operationMessage.textContent = '可稍后只读刷新操作记录。不会自动重试，也不会停止 Hermes。';
      }
      const fragment = document.createDocumentFragment();
      for (const entry of entries) {
        const row = document.createElement('p');
        row.className = 'wsl-recent-operation';
        const state = document.createElement('span');
        const statusText = entry.status === 'unknown' ? '结果未知' : entry.status === 'running' || entry.status === 'pending' ? '后台可能仍在运行' : entry.status === 'succeeded' ? '已完成' : entry.status === 'failed' ? '已失败' : '已取消等待';
        const refreshState = recentRefreshStates.get(entry.id);
        const refreshText = refreshState === 'checking' ? ' · 正在只读查询' : refreshState === 'updated' ? ' · 状态刚更新' : refreshState === 'unavailable' ? ' · 暂时无法查询，可重试' : '';
        const cancelState = recentCancelStates.get(entry.id);
        const cancelText = cancelState === 'confirmed' ? ' · 取消等待请求已确认' : cancelState === 'unconfirmed' ? ' · 取消请求未确认' : '';
        state.textContent = `${entry.label ? `${entry.label} · ` : ''}${entry.kind === 'connect' ? '连接操作' : '查找操作'} · ${statusText}${refreshText}${cancelText}`;
        const button = document.createElement('button');
        button.type = 'button'; button.className = 'button-quiet'; button.textContent = '查看状态';
        button.disabled = refreshState === 'checking';
        button.dataset.wslReopen = entry.id;
        button.addEventListener('click', () => refreshRecentOperation(entry));
        const hint = document.createElement('small');
        hint.className = 'wsl-recent-hint';
        hint.textContent = '只查询原操作，不会重新发送连接请求。';
        row.append(state, hint, button); fragment.append(row);
      }
      recentContainer.replaceChildren(fragment);
      recentContainer.hidden = entries.length === 0;
    };
    const clearPollTimer = () => { window.clearTimeout(pollTimer); pollTimer = 0; };
    const clearActiveOperation = () => {
      clearPollTimer(); operationEpoch += 1;
      operationId = ''; operationKind = '';
      sessionStorage.removeItem(operationStorageKey);
      sessionStorage.removeItem(operationKindStorageKey);
      sessionStorage.removeItem(operationDistroStorageKey);
      lockSelection(false);
    };
    const detachOperation = (message, status) => {
      if (!operationId) return;
      rememberOperation(operationId, operationKind, sessionStorage.getItem(operationDistroStorageKey) || distroSelect.value, status, currentOperationLabel());
      clearActiveOperation();
      clearPreview();
      showOperation(message, {refresh:false, cancel:false, leave:true});
    };
    const clearPreview = () => {
      previewToken = '';
      preview.replaceChildren();
      preview.hidden = true;
      connectButton.textContent = '选择配置后连接';
      connectButton.disabled = true;
    };
    const clearPairTarget = () => {
      pairLink.hidden = true;
      pairLink.dataset.instanceId = '';
      sessionStorage.removeItem(pairTargetStorageKey);
    };
    const currentOperationLabel = () => {
      const distro = selectedDistros.get(sessionStorage.getItem(operationDistroStorageKey) || distroSelect.value);
      const distroName = distro && typeof distro.name === 'string' ? distro.name : '';
      const profileName = operationKind === 'connect' && profileSelect.value ? profileSelect.selectedOptions[0]?.textContent || '' : '';
      return `${distroName}${profileName ? ` · ${profileName}` : ''}`.slice(0,120);
    };
    const makeOption = (label, value = '') => {
      const item = document.createElement('option'); item.textContent = label; item.value = value; return item;
    };
    const csrfHeaders = () => ({'X-PadNote-CSRF': csrf});
    const postForm = async (url, values) => {
      const body = new URLSearchParams({csrf, ...values});
      const response = await fetch(url, {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'}, body, credentials: 'same-origin', cache: 'no-store'});
      let data = {};
      try { data = await response.json(); } catch (_) { /* fail closed below */ }
      if (!response.ok) throw new Error(typeof data.message === 'string' ? data.message : `请求失败（${response.status}）`);
      return {response, data};
    };
    const renderProfiles = (profiles) => {
      selectionRevision += 1;
      profileSelect.replaceChildren(makeOption('请选择一个 Hermes profile'));
      for (const profile of profiles) {
        if (!profile || typeof profile.id !== 'string' || !profile.id || typeof profile.label !== 'string') continue;
        const option = makeOption(profile.label, profile.id);
        option.dataset.apiState = typeof profile.api_state === 'string' ? profile.api_state : 'unknown';
        profileSelect.append(option);
      }
      profileSelect.disabled = profileSelect.options.length <= 1;
      inspectButton.disabled = true;
      clearPreview();
    };
    const loadDistros = async (preferredId = '', isCurrent = () => true) => {
      const response = await fetch('/admin/wsl/distros', {headers: csrfHeaders(), credentials:'same-origin', cache:'no-store'});
      if (!response.ok) throw new Error(`扫描失败（${response.status}）`);
      const data = await response.json();
      if (!isCurrent()) return false;
      if (!Array.isArray(data.distros)) throw new Error('发行版列表格式无效');
      selectedDistros = new Map();
      distroSelect.replaceChildren(makeOption(data.distros.length ? '请选择一个发行版' : '没有发现发行版'));
      for (const distro of data.distros) {
        if (!distro || typeof distro.id !== 'string' || !distro.id || typeof distro.name !== 'string') continue;
        selectedDistros.set(distro.id, distro);
        const state = typeof distro.state === 'string' ? distro.state : 'unknown';
        const version = distro.version === 1 || distro.version === 2 ? ` · WSL ${distro.version}` : '';
        distroSelect.append(makeOption(`${distro.name} · ${state}${version}`, distro.id));
      }
      if (preferredId && selectedDistros.has(preferredId)) {
        distroSelect.value = preferredId;
        findButton.disabled = false;
        const distro = selectedDistros.get(preferredId);
        distroState.textContent = `已恢复所选发行版 ${distro.name}。请继续查看操作状态。`;
        return true;
      }
      distroSelect.value = '';
      findButton.disabled = true;
      distroState.textContent = data.distros.length ? '发行版已列出；请选择后再继续。不会自动选择第一个。' : '没有找到发行版。可重试扫描；这不代表 WSL 未安装。';
      return false;
    };
    const describeProfileState = (value) => ({ready:'API 已就绪', api_not_enabled:'API 尚未启用', not_running:'服务未运行', needs_setup:'需要配置', unknown:'状态未知'}[value] || '状态未知');
    const renderImpact = (data) => {
      const allowedActions = {create:'创建', update:'更新', reuse:'沿用'};
      const fragment = document.createDocumentFragment();
      const heading = document.createElement('h4'); heading.textContent = '变更预览'; fragment.append(heading);
      const summary = document.createElement('p');
      summary.textContent = `所选配置：${profileSelect.selectedOptions[0]?.textContent || '未知'}；${describeProfileState(data.api_state)}。`;
      fragment.append(summary);
      const list = document.createElement('ul');
      const impact = Array.isArray(data.impact) ? data.impact : [];
      for (const item of impact) {
        if (!item || typeof item.path_label !== 'string' || !Array.isArray(item.fields)) continue;
        const safeFields = item.fields.filter((field) => typeof field === 'string' && /^API_SERVER_[A-Z_]{1,40}$/.test(field));
        const row = document.createElement('li');
        row.append(document.createTextNode(`${item.path_label}：${allowedActions[item.action] || '检查'}本机连接设置`));
        if (safeFields.length) {
          const details = document.createElement('details');
          const summary = document.createElement('summary'); summary.textContent = '查看技术字段'; details.append(summary);
          const fields = document.createElement('span'); fields.textContent = safeFields.join('、'); details.append(fields);
          row.append(details);
        }
        list.append(row);
      }
      if (!list.children.length) {
        const row = document.createElement('li'); row.textContent = '无需修改现有设置；将检查并连接所选配置。'; list.append(row);
      }
      fragment.append(list);
      if (data.requires_enable === true || data.requires_start === true) {
        const consent = document.createElement('p');
        const actions = [];
        if (data.requires_enable === true) actions.push('启用本机 API');
        if (data.requires_start === true) actions.push('启动 Hermes');
        consent.textContent = `点击下一步即确认：${actions.join('并')}，随后连接。不会改动模型供应商设置。`;
        fragment.append(consent);
      } else {
        const reuse = document.createElement('p'); reuse.textContent = '将沿用这套配置的现有连接凭据，不会显示或修改凭据值。'; fragment.append(reuse);
      }
      if (typeof data.message === 'string' && data.message) {
        const note = document.createElement('p'); note.textContent = data.message; fragment.append(note);
      }
      preview.replaceChildren(fragment);
      preview.hidden = false;
      const tokenOk = typeof data.preview_token === 'string' && data.preview_token.length > 0 && data.preview_token.length <= 2048;
      previewToken = tokenOk ? data.preview_token : '';
      if (tokenOk) connectButton.textContent = data.requires_enable === true
        ? '启用并连接'
        : data.requires_start === true ? '启动并连接' : '连接此配置';
      connectButton.disabled = !tokenOk;
      if (!tokenOk) showOperation('预览无效或已过期。请重新检查，不会提交连接。');
      else preview.scrollIntoView({block:'nearest'});
    };
    const finishOperation = (status, data) => {
      updateRecentStatus(operationId, status);
      if (status === 'succeeded') {
        if (operationKind !== 'connect' && Array.isArray(data.profiles)) {
          renderProfiles(data.profiles);
          showOperation(data.profiles.length ? `已找到 ${profileSelect.options.length - 1} 个配置。请选择一个继续。` : '没有找到可用的 Hermes 配置。可重新查找或使用现有手动连接。', {refresh:false, cancel:false});
          findButton.disabled = false;
        } else {
          const result = data.result && typeof data.result === 'object' ? data.result : {};
          const instanceId = typeof result.instance_id === 'string' ? result.instance_id : '';
          clearPairTarget();
          pairLink.dataset.instanceId = instanceId;
          pairLink.hidden = !instanceId;
          showOperation(instanceId
            ? 'Hermes 已连接。前往配对页面时会按这次返回的 Agent ID 明确选择；不会自动生成配对码。'
            : 'Hermes 已连接，但没有返回 Agent ID。请到 Agent 列表确认目标后，再自行打开配对页面；不会自动选择或生成配对码。',
          {refresh:false, cancel:false, pair:Boolean(instanceId)});
        }
        clearActiveOperation();
      } else if (status === 'failed') {
        if (operationKind === 'profiles') findButton.disabled = false;
        if (operationKind === 'connect') inspectButton.disabled = !distroSelect.value || !profileSelect.value;
        showOperation(typeof data.message === 'string' && data.message ? data.message : '操作失败。检查所选配置后可重新扫描或预览。', {refresh:true, cancel:false});
        rememberOperation(operationId, operationKind, sessionStorage.getItem(operationDistroStorageKey) || distroSelect.value, 'failed', currentOperationLabel());
        clearActiveOperation();
      } else if (status === 'cancelled') {
        detachOperation('此操作已结束等待；这不会停止 Hermes。可返回选择，或稍后只读查看记录。', 'cancelled');
      } else if (status === 'unknown') {
        detachOperation('操作结果仍未知。Hermes 可能仍在后台运行；可返回选择，稍后只读刷新此操作。不要重复提交连接。', 'unknown');
      }
    };
    const pollOperation = async () => {
      const requestedId = operationId;
      if (!requestedId) return;
      const epoch = operationEpoch;
      try {
        const response = await fetch(`/admin/wsl/operations/${encodeURIComponent(requestedId)}`, {headers: csrfHeaders(), credentials:'same-origin', cache:'no-store'});
        if (!response.ok) throw new Error(`状态查询失败（${response.status}）`);
        const data = await response.json();
        if (epoch !== operationEpoch || requestedId !== operationId) return;
        if (data.operation_id !== requestedId) throw new Error('返回的操作身份不匹配');
        if (typeof data.distro_id === 'string' && !selectedDistros.has(data.distro_id)) {
          await loadDistros(data.distro_id, () => epoch === operationEpoch && requestedId === operationId);
        }
        if (epoch !== operationEpoch || requestedId !== operationId) return;
        const statuses = new Set(['pending','running','succeeded','failed','cancelled','unknown']);
        const statusValue = statuses.has(data.status) ? data.status : 'unknown';
        if (statusValue === 'pending' || statusValue === 'running') {
          const phase = typeof data.phase === 'string' ? data.phase : '处理中';
          const message = typeof data.message === 'string' && data.message ? data.message : phase;
          showOperation(`${message}（离开页面后可回来刷新；取消只取消等待。）`, {refresh:true, cancel:true});
          if (operationKind === 'profiles' && Array.isArray(data.profiles) && data.profiles.length) renderProfiles(data.profiles);
          clearPollTimer();
          pollTimer = window.setTimeout(() => { if (epoch === operationEpoch && operationId === requestedId) pollOperation(); }, 1200);
        } else finishOperation(statusValue, data);
      } catch (error) {
        if (epoch !== operationEpoch || requestedId !== operationId) return;
        showOperation(`${error.message || '暂时无法读取状态'}。可刷新此操作；不会自动重复启动或连接。`, {refresh:true, cancel:Boolean(operationId)});
      }
    };
    const beginPolling = (id, kind) => {
      if (!validOperationId(id)) throw new Error('服务器返回了无效操作编号');
      clearPollTimer(); operationEpoch += 1;
      operationId = id; operationKind = kind;
      sessionStorage.setItem(operationStorageKey, id);
      sessionStorage.setItem(operationKindStorageKey, kind);
      if (distroSelect.value) sessionStorage.setItem(operationDistroStorageKey, distroSelect.value);
      rememberOperation(id, kind, distroSelect.value, 'pending', currentOperationLabel());
      lockSelection(true);
      showOperation('操作已受理，正在等待状态。离开页面不会停止 Hermes。', {refresh:true, cancel:true});
      pollOperation();
    };
    const recentRefreshEpochs = new Map();
    const refreshRecentOperation = async (entry) => {
      const epoch = (recentRefreshEpochs.get(entry.id) || 0) + 1;
      recentRefreshEpochs.set(entry.id, epoch);
      const item = readRecentOperations().find((candidate) => candidate.id === entry.id);
      if (!item) return;
      recentRefreshStates.set(entry.id, 'checking'); renderRecentOperations();
      try {
        const response = await fetch(`/admin/wsl/operations/${encodeURIComponent(entry.id)}`, {headers: csrfHeaders(), credentials:'same-origin', cache:'no-store'});
        if (!response.ok) throw new Error(`状态查询失败（${response.status}）`);
        const data = await response.json();
        if (recentRefreshEpochs.get(entry.id) !== epoch) return;
        if (!data || data.operation_id !== entry.id || !['pending','running','succeeded','failed','cancelled','unknown'].includes(data.status)) throw new Error('返回的操作身份或状态无效');
        const current = readRecentOperations();
        const saved = current.find((candidate) => candidate.id === entry.id);
        if (!saved) return;
        saved.status = data.status;
        recentRefreshStates.set(entry.id, 'updated');
        writeRecentOperations(current);
      } catch (error) {
        if (recentRefreshEpochs.get(entry.id) !== epoch) return;
        const current = readRecentOperations();
        const saved = current.find((candidate) => candidate.id === entry.id);
        if (saved) { recentRefreshStates.set(entry.id, 'unavailable'); writeRecentOperations(current); }
      }
    };
    returnButton.addEventListener('click', () => {
      clearPreview();
      lockSelection(false);
      showOperation('已返回选择。下方保留操作记录，可稍后只读刷新；不会自动重试。', {refresh:false, cancel:false, leave:false});
    });
    renderRecentOperations();

    scanButton.addEventListener('click', async () => {
      clearPairTarget();
      selectionRevision += 1;
      const preferredId = distroSelect.value;
      scanButton.disabled = true;
      distroSelect.disabled = true;
      distroState.textContent = '正在扫描 WSL 发行版…';
      distroSelect.replaceChildren(makeOption('正在扫描…'));
      findButton.disabled = true; profileSelect.disabled = true; inspectButton.disabled = true; clearPreview();
      try {
        await loadDistros(preferredId);
      } catch (error) {
        distroSelect.replaceChildren(makeOption('扫描失败，请重试'));
        distroState.textContent = `${error.message || '扫描失败'}。未连接或修改任何发行版。`;
      } finally { scanButton.disabled = false; distroSelect.disabled = false; }
    });
    distroSelect.addEventListener('change', () => {
      clearPairTarget();
      selectionRevision += 1;
      findButton.disabled = !selectedDistros.has(distroSelect.value);
      profileSelect.replaceChildren(makeOption('先查找配置'));
      profileSelect.disabled = true; inspectButton.disabled = true; clearPreview();
      const distro = selectedDistros.get(distroSelect.value);
      if (distro) distroState.textContent = `已选择 ${distro.name}（${distro.state || '状态未知'}）。`;
    });
    findButton.addEventListener('click', async () => {
      const distro = selectedDistros.get(distroSelect.value);
      if (!distro) return;
      if (String(distro.state || '').toLowerCase() === 'stopped'
          && !window.confirm(`发行版“${distro.name}”当前已停止。确认启动该发行版并查找 Hermes 配置？`)) return;
      const requestedDistroId = distro.id;
      const requestRevision = selectionRevision;
      const requestedDistroName = typeof distro.name === 'string' ? distro.name : '';
      findButton.disabled = true; profileSelect.replaceChildren(makeOption('正在查找…'));
      try {
        const {data} = await postForm('/admin/wsl/profiles', {distro_id:requestedDistroId});
        if (requestRevision !== selectionRevision || requestedDistroId !== distroSelect.value) {
          if (validOperationId(data.operation_id)) rememberOperation(data.operation_id, 'profiles', requestedDistroId, 'pending', requestedDistroName);
          return;
        }
        beginPolling(data.operation_id, 'profiles');
      } catch (error) {
        if (requestRevision !== selectionRevision || requestedDistroId !== distroSelect.value) return;
        findButton.disabled = false;
        profileSelect.replaceChildren(makeOption('查找未完成，可重试'));
        showOperation(`${error.message || '无法开始查找'}。没有自动选择配置。`, {refresh:false, cancel:false});
      }
    });
    profileSelect.addEventListener('change', () => {
      clearPairTarget();
      selectionRevision += 1;
      inspectButton.disabled = !distroSelect.value || !profileSelect.value;
      clearPreview();
    });
    inspectButton.addEventListener('click', async () => {
      const revision = selectionRevision;
      const selectedDistroId = distroSelect.value;
      const selectedProfileId = profileSelect.value;
      clearPreview(); inspectButton.disabled = true;
      try {
        const {data} = await postForm('/admin/wsl/inspect', {distro_id:selectedDistroId, profile_id:selectedProfileId});
        if (revision !== selectionRevision || selectedDistroId !== distroSelect.value || selectedProfileId !== profileSelect.value) return;
        renderImpact(data);
      } catch (error) {
        if (revision !== selectionRevision) return;
        showOperation(`${error.message || '无法预览配置'}。未执行配置更改；请重新检查或返回选择。`);
        inspectButton.disabled = false;
      }
    });
    connectButton.addEventListener('click', async () => {
      if (!previewToken) return;
      const token = previewToken;
      const requestRevision = selectionRevision;
      const requestDistro = distroSelect.value;
      const requestProfile = profileSelect.value;
      const requestProfileLabel = profileSelect.selectedOptions[0]?.textContent || '';
      clearPairTarget();
      clearPreview();
      connectButton.disabled = true;
      lockSelection(true);
      const requestEpoch = ++operationEpoch;
      showOperation('正在提交明确确认的连接操作…', {refresh:false, cancel:false});
      try {
        const {data} = await postForm('/admin/wsl/connect', {preview_token:token});
        if (requestEpoch !== operationEpoch || requestRevision !== selectionRevision
            || requestDistro !== distroSelect.value || requestProfile !== profileSelect.value) {
          if (validOperationId(data.operation_id)) rememberOperation(data.operation_id, 'connect', requestDistro, 'pending', `${selectedDistros.get(requestDistro)?.name || ''} · ${requestProfileLabel}`.slice(0,120));
          return;
        }
        beginPolling(data.operation_id, 'connect');
      } catch (error) {
        if (requestEpoch !== operationEpoch) return;
        lockSelection(false);
        inspectButton.disabled = !distroSelect.value || !profileSelect.value;
        showOperation(`${error.message || '连接结果未知'}。请刷新状态或重新预览；不要重复提交旧预览。`, {refresh:false, cancel:false});
      }
    });
    refreshButton.addEventListener('click', pollOperation);
    pairLink.addEventListener('click', (event) => {
      const instanceId = pairLink.dataset.instanceId || '';
      if (!instanceId) return;
      event.preventDefault();
      sessionStorage.setItem(pairTargetStorageKey, instanceId);
      location.hash = '#pairing';
      location.reload();
    });
    cancelButton.addEventListener('click', async () => {
      if (!operationId) return;
      const requestedId = operationId;
      detachOperation('已退出此页等待，正在确认取消等待请求；后台操作可能继续运行。这不会停止 Hermes。', 'unknown');
      try {
        const {data} = await postForm(`/admin/wsl/operations/${encodeURIComponent(requestedId)}/cancel`, {});
        if (data.operation_id !== requestedId) return;
        recentCancelStates.set(requestedId, 'confirmed');
        renderRecentOperations();
      } catch (error) {
        // A failed cancel request does not reattach the UI wait or imply the operation stopped.
        recentCancelStates.set(requestedId, 'unconfirmed');
        renderRecentOperations();
      }
    });
    const savedOperationId = sessionStorage.getItem(operationStorageKey) || '';
    if (savedOperationId && validOperationId(savedOperationId)) {
      operationId = savedOperationId;
      operationKind = sessionStorage.getItem(operationKindStorageKey) || '';
      const savedDistroId = sessionStorage.getItem(operationDistroStorageKey) || '';
      const bootstrapEpoch = operationEpoch;
      const bootstrapId = savedOperationId;
      const bootstrapOwnsWait = () => bootstrapEpoch === operationEpoch && bootstrapId === operationId;
      showOperation('正在恢复此页会话中的操作状态…', {refresh:true, cancel:true});
      lockSelection(true);
      if (savedDistroId) loadDistros(savedDistroId, bootstrapOwnsWait)
        .then(() => { if (bootstrapOwnsWait()) pollOperation(); })
        .catch(() => { if (bootstrapOwnsWait()) pollOperation(); });
      else if (bootstrapOwnsWait()) pollOperation();
    } else if (savedOperationId) {
      sessionStorage.removeItem(operationStorageKey);
      sessionStorage.removeItem(operationKindStorageKey);
      sessionStorage.removeItem(operationDistroStorageKey);
    }
    renderRecentOperations();
  }
  const desiredPairInstance = sessionStorage.getItem('padnote.wsl.pair_instance_id');
  if (desiredPairInstance) {
    const pairSelect = document.getElementById('pair-instance');
    if (pairSelect) {
      const option = Array.from(pairSelect.options).find((item) => item.value === desiredPairInstance);
      if (option) {
        pairSelect.value = desiredPairInstance;
        pairSelect.dispatchEvent(new Event('change', {bubbles:true}));
      } else {
        pairSelect.selectedIndex = -1;
        const region = document.querySelector('#wsl-hermes [data-wsl-message]');
        if (region) region.textContent = '新 Agent 尚未出现在配对列表中。请刷新页面后重新确认目标；没有生成配对码。';
      }
      sessionStorage.removeItem('padnote.wsl.pair_instance_id');
    } else {
      const region = document.querySelector('#wsl-hermes [data-wsl-message]');
      if (region) region.textContent = '新 Agent 尚未出现在配对列表中。请刷新页面后重新确认目标；没有生成配对码。';
      sessionStorage.removeItem('padnote.wsl.pair_instance_id');
    }
  }
})();
