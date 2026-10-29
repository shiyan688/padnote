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
})();
