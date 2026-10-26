"""Accessible HTML renderer for the local PadNote management page."""
from __future__ import annotations

import html
import json
import time
from pathlib import Path
from typing import Any, Callable


def _e(value: Any) -> str:
    return html.escape(str(value if value is not None else ""), quote=True)


def _health(value: str) -> tuple[str, str]:
    return {
        "healthy": ("可连接", "good"), "ready": ("可连接", "good"),
        "not_checked": ("尚未检测", "neutral"), "unsupported": ("暂不支持执行", "warn"),
        "identity_changed": ("身份已变化", "bad"), "unhealthy": ("连接异常", "bad"),
        "error": ("连接异常", "bad"), "degraded": ("部分可用", "warn"),
        "needs_configuration": ("需要配置凭据或本机引擎", "warn"),
        "profile_unavailable": ("配置文件不可用", "bad"),
        "not_running": ("服务未运行", "bad"), "api_not_enabled": ("API 尚未启用", "warn"),
        "authentication_failed": ("凭据无效", "bad"),
        "wrong_service": ("地址指向其他服务", "bad"),
        "capability_missing": ("缺少所需接口", "warn"),
    }.get(value, (value or "状态未知", "neutral"))


def _instances(service: Any) -> list[dict[str, Any]]:
    return sorted(service.store.list_instances(), key=lambda row: row.get("created_at", 0))


def _lists(service: Any, csrf: str) -> tuple[str, str, int, int]:
    instances = _instances(service)
    names = {row["instance_id"]: row.get("name", "未知 Agent") for row in instances}
    pending = service.store.list_pending_pairs()
    connections = service.store.list_connections()
    phtml = "".join(
        '<article class="request-card"><div><strong>' + _e(item.get("device_name", "平板设备")) + '</strong>'
        + '<p class="muted">申请连接到 <b>' + _e(names.get(item.get("instance_id"), "未知 Agent")) + '</b></p></div>'
        + '<div class="actions"><form method="post" action="/admin/pair-requests/' + _e(item.get("request_id")) + '/approve"><input type="hidden" name="csrf" value="' + _e(csrf) + '"><button>批准连接</button></form>'
        + '<form method="post" action="/admin/pair-requests/' + _e(item.get("request_id")) + '/deny"><input type="hidden" name="csrf" value="' + _e(csrf) + '"><button class="button-quiet danger">拒绝</button></form></div></article>'
        for item in pending)
    if not pending:
        phtml = '<div class="empty-inline">没有待批准的设备。平板扫码并提交连接申请后，会自动显示在这里。</div>'
    chehtml = "".join(
        '<article class="device-card"><div><strong>' + _e(item.get("device_name", "平板设备")) + '</strong><p class="muted">Agent：' + _e(names.get(item.get("instance_id"), "未知 Agent")) + '</p></div>'
        + ('<span class="badge muted">已撤销</span>' if item.get("revoked_at") else
           '<form method="post" action="/admin/connections/' + _e(item.get("connection_id")) + '/revoke"><input type="hidden" name="csrf" value="' + _e(csrf) + '"><button class="button-quiet danger">撤销设备</button></form>')
        + '</article>' for item in connections)
    if not connections:
        chehtml = '<div class="empty-inline">还没有已连接设备。</div>'
    return phtml, chehtml, len(pending), sum(not bool(item.get("revoked_at")) for item in connections)


def render_live_state(service: Any, csrf_token: str) -> dict[str, Any]:
    pending, connections, pc, cc = _lists(service, csrf_token)
    return {"pending_html": pending, "connections_html": connections,
            "pending_count": pc, "connection_count": cc,
            "updated_at": int(time.time())}


def render_admin_page(service: Any, csrf_token: str, admin_port: int, api_port: int,
                      message: str, qr_renderer: Callable[[str], str]) -> str:
    csrf = _e(csrf_token)
    instances = _instances(service)
    candidates = getattr(service, "discovery_results", [])
    executable = [i for i in instances if i.get("executable") and not i.get("retired_at") and i.get("kind") in {"hermes", "builtin_video"}]
    instance_by_id = {i["instance_id"]: i for i in instances}
    candidate_rows = []
    for item in candidates:
        import_form = ""
        if item.get("kind") == "hermes" and Path(item.get("path", "")).name == ".env":
            import_form = '<form method="post" action="/admin/discovery/import"><input type="hidden" name="csrf" value="' + csrf + '"><input type="hidden" name="profile_path" value="' + _e(item.get("path")) + '"><button class="button-quiet">使用此配置</button></form>'
        candidate_rows.append('<li><b>' + _e(item.get("kind")) + '</b><code>' + _e(item.get("path")) + '</code><span>' + _e(item.get("guidance")) + '</span>' + import_form + '</li>')
    if candidate_rows:
        discovery = '<ul class="discovery-list">' + ''.join(candidate_rows) + '</ul>'
    else:
        discovery = '<p class="muted">尚未发现已知位置的候选配置。你也可以直接填写 Hermes 本机 URL。</p>'

    pair_context = getattr(service, "last_pair_context", None)
    pair = pair_context.get("payload") if isinstance(pair_context, dict) else None
    pair_instance_id = pair_context.get("instance_id") if isinstance(pair_context, dict) else None
    cards = []
    for i in instances:
        iid = _e(i.get("instance_id")); label, tone = _health(i.get("health", ""))
        kind = {"hermes": "Hermes", "openclaw": "OpenClaw · 仅发现与引导", "builtin_video": "可选 Qwen 视频适配"}.get(i.get("kind"), i.get("kind", "Agent"))
        retired = bool(i.get("retired_at"))
        actions = ''
        if i.get("kind") in {"hermes", "builtin_video"} and not retired:
            keylabel = "模型与配音 API Key" if i["kind"] == "builtin_video" else "Hermes API Key"
            if i.get("executable"):
                main_action = '<a class="button" href="#pairing" data-pair-agent="' + iid + '">连接平板</a>'
            elif not i.get("credential_fingerprint"):
                main_action = '<button type="button" data-open-credentials="' + iid + '">填写凭据</button>'
            else:
                main_action = '<form method="post" action="/admin/instances/' + iid + '/check"><input type="hidden" name="csrf" value="' + csrf + '"><button>检测连接</button></form>'
            actions = '<div class="agent-primary">' + main_action + '</div><details class="advanced" data-credentials="' + iid + '"><summary>凭据与维护</summary><form method="post" action="/admin/instances/' + iid + '/key"><input type="hidden" name="csrf" value="' + csrf + '"><label>' + keylabel + ' <span class="muted">不会回显</span><input data-credential-input type="password" name="api_key" autocomplete="new-password" required></label><button class="button-quiet">保存凭据</button></form>'
            if i["kind"] == "builtin_video":
                actions += '<form method="post" action="/admin/instances/' + iid + '/credential/revoke"><input type="hidden" name="csrf" value="' + csrf + '"><button class="button-quiet danger">撤销凭据与设备</button></form>'
            actions += '</details>'
        detail = '<p class="muted">' + _e(i.get("detail", "")) + '</p>'
        if i.get("kind") == "builtin_video":
            detail += '<p class="meta">模型 ' + _e(i.get("provider_model", "qwen-plus")) + ' · ' + _e(i.get("provider_region", "")) + ' · TTS ' + _e(i.get("tts_model", "")) + ' / ' + _e(i.get("tts_voice", "")) + '</p>'
        cards.append('<article class="agent-card"><div class="agent-heading"><div><h3>' + _e(i.get("name")) + '</h3><span class="muted">' + _e(kind) + '</span></div><span class="badge ' + tone + '">' + _e(label) + '</span></div>' + detail + actions + '</article>')
    agent_content = ''.join(cards) or '<div class="empty-state"><div class="empty-icon">＋</div><h3>先添加一个 Agent</h3><p>添加 Hermes 地址与凭据后，检测连接，再为平板生成配对码。</p><a class="button" href="#add-agent">添加 Agent</a></div>'

    selected_pair_id = pair_instance_id if pair_instance_id in {i["instance_id"] for i in executable} else (executable[0]["instance_id"] if executable else None)
    opts = ''.join('<option value="' + _e(i["instance_id"]) + '"' + (' selected' if i["instance_id"] == selected_pair_id else '') + '>' + _e(i["name"]) + '</option>' for i in executable)
    lan_enabled = service.lan_identity is not None and service.lan_port is not None
    pairing_enabled = bool(executable and lan_enabled)
    addresses = []
    if lan_enabled:
        from . import lan
        addresses = lan.lan_addresses()
    address_opts = ''.join('<option value="' + _e(a) + '">' + _e(a) + '</option>' for a in addresses)
    if service.lan_identity is None or service.lan_port is None:
        lan_info = '<div class="callout warn"><b>局域网连接未开启</b><p>使用同网配对前，请通过启动参数 <code>--lan</code> 开启局域网服务，然后重新打开此页面。</p></div>'
    elif not executable:
        lan_info = '<div class="callout warn"><b>还没有可连接的 Agent</b><p>先添加 Hermes 并完成连接检测，之后即可生成配对码。</p></div>'
    elif not addresses:
        lan_info = '<div class="callout warn"><b>找不到电脑的局域网地址</b><p>请确认电脑已连接 Wi-Fi 或网线，再重新打开此页面。</p></div>'
    else:
        lan_info = '<div class="callout"><b>电脑和平板连接到同一个 Wi-Fi 或局域网。</b><p>连接使用 TLS 加密。平板只信任本机证书，指纹 <code>' + _e(service.lan_identity.sha256[:16]) + '…</code></p></div>'
    pair_form = ''
    if pairing_enabled and addresses and executable:
        pair_form = '<form class="pair-form" method="post" action="/admin/lan-pair-codes"><input type="hidden" name="csrf" value="' + csrf + '"><label>选择 Agent<select id="pair-instance" name="instance_id" required>' + opts + '</select></label><label>电脑网络地址<select name="address" required>' + address_opts + '</select></label><button>生成平板配对二维码</button></form>'
    expires = pair_context.get("expires_at", 0) if isinstance(pair_context, dict) else 0
    pair_block = ''
    pair_target_is_ready = isinstance(pair, dict) and pair_instance_id in {i["instance_id"] for i in executable}
    if pair and expires > int(service.clock()) and pair_target_is_ready:
        payload_text = json.dumps(pair, ensure_ascii=False, separators=(",", ":"))
        selected = instance_by_id.get(pair_instance_id, {})
        pair_block = '<section class="pair-result" data-expires="' + str(int(expires)) + '" data-pair-instance="' + _e(pair_instance_id) + '"><div class="pair-qr-column"><span class="eyebrow">配对二维码</span><h3>' + _e(selected.get("name", "Agent")) + '</h3><div class="qr-wrap" data-qr-wrap>' + qr_renderer(payload_text) + '</div><p class="muted">二维码有效期 <b data-countdown></b></p><p class="callout warn pair-expired" data-pair-expired hidden>二维码已过期。请重新生成后再扫描。</p><p class="callout warn pair-mismatch" data-pair-mismatch hidden>所选 Agent 与当前二维码不同。请点击“生成配对二维码”后再扫描。</p></div><div class="pair-copy"><p class="pair-instructions">1. 使用平板上的 PadNote 扫描二维码。<br>2. 提交连接申请。<br>3. 返回电脑批准申请。</p><details class="pair-technical"><summary>备用配对内容</summary><textarea id="pair-payload" readonly rows="4">' + _e(payload_text) + '</textarea><button type="button" class="button-quiet" data-copy>复制备用内容</button></details></div></section>'
    elif pair:
        notice = "上一个配对二维码已过期。请重新生成后再扫描。" if expires <= int(service.clock()) else "二维码目标 Agent 当前不可连接。请检查 Agent 后重新生成配对码。"
        pair_block = '<div class="callout warn">' + _e(notice) + '</div>'

    pending, connected, pc, cc = _lists(service, csrf_token)
    message_html = '<div class="notice" role="status">' + _e(message) + '</div>' if message else ''
    lan_advanced = '<details class="advanced"><summary>远程连接（可选：Tailscale）</summary><p>仅在不使用同一网络时考虑。检查现有配置后，可将本机 API 端口发布为 HTTPS；助手不会修改 Tailscale 配置或启用 Funnel。</p><pre>tailscale serve --bg --https=443 http://127.0.0.1:' + str(int(api_port)) + '</pre><form method="post" action="/admin/pair-codes"><input type="hidden" name="csrf" value="' + csrf + '"><label>Agent 实例<select name="instance_id" required>' + opts + '</select></label><label>Tailscale HTTPS 根地址<input name="public_url" placeholder="https://computer.example.ts.net" required></label><button class="button-quiet">生成远程配对码</button></form></details>' if executable else ''
    add_form = '<form id="add-agent-form" method="post" action="/admin/instances"><input type="hidden" name="csrf" value="' + csrf + '"><label>Agent 类型<select id="instance-kind" name="kind"><option value="hermes">Hermes</option><option value="builtin_video">可选 Qwen 视频适配</option></select></label><label>显示名称<input name="name" maxlength="120" required placeholder="例如：我的 Hermes"></label><div data-kind="hermes"><label>Hermes 本机地址<input name="base_url" value="http://127.0.0.1:8642"></label><label>Hermes API Key <span class="muted">可稍后在高级设置中填写</span><input type="password" name="api_key" autocomplete="new-password"></label><p class="muted">手动填写仅本次运行有效；发现已有配置可导入。</p></div><div data-kind="builtin_video" hidden><label>服务区域<select name="provider_region"><option value="china">中国站（配音链路已验证）</option><option value="international">国际站（完整配音与生成暂不可用）</option></select></label><label>模型与配音 API Key<input type="password" name="api_key" autocomplete="new-password"></label><details><summary>可选视频适配设置</summary><label>分镜模型<input name="provider_model" value="qwen-plus" maxlength="120"></label><label>JSON 响应模式<select name="response_mode"><option value="json_object">JSON Object</option><option value="json_schema">JSON Schema（需模型支持）</option></select></label><label>配音模型<input name="tts_model" value="qwen3-tts-instruct-flash" maxlength="120"></label><label>配音音色<input name="tts_voice" value="Maia" maxlength="120"></label></details></div><button>添加 Agent</button></form>'
    add_open = " open" if not instances else ""
    return f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><title>PadNote 电脑连接助手</title><link rel="stylesheet" href="/assets/admin.css"><script src="/assets/admin.js" defer></script></head><body><div class="app-shell"><aside class="sidebar"><a class="brand" href="/"><img src="/assets/brand.png" alt=""><span>PadNote<small>电脑连接助手</small></span></a><nav aria-label="页面导航"><a href="#agents">Agent 管理</a><a href="#pairing">连接平板</a><a href="#requests">连接申请</a><a href="#devices">已连接设备</a></nav><div class="sidebar-foot">仅本机管理 · 端口 {int(admin_port)}</div></aside><main class="main"><header class="topbar"><div><span class="eyebrow">设备管理</span><h1>让平板连接到你的 Agent</h1><p>添加并检测 Agent，然后用同一网络完成配对。</p></div><span class="local-pill"><i></i>本机管理</span></header>{message_html}<section id="agents" class="section"><div class="section-title"><div><span class="eyebrow">01 · AGENT</span><h2>Agent 管理</h2></div><a href="#add-agent" class="text-link">添加 Agent ↓</a></div><div class="agent-grid">{agent_content}</div><details class="advanced discovery"><summary>发现已有配置</summary><p>仅检查已知位置的配置，不会自动启动或安装 Agent。</p>{discovery}<form method="post" action="/admin/discovery/refresh"><input type="hidden" name="csrf" value="{csrf}"><button class="button-quiet">重新扫描</button></form></details><details id="add-agent" class="add-panel"{add_open}><summary><span><span class="eyebrow">添加服务</span><b>添加 Agent</b></span><span class="muted">展开添加表单＋</span></summary><p class="add-intro">Hermes 是推荐的可执行 Agent。添加后请检测连接。</p>{add_form}</details></section><section id="pairing" class="section"><div class="section-title"><div><span class="eyebrow">02 · PAIR</span><h2>连接平板</h2><p>最简单的方法：电脑与平板使用同一 Wi-Fi。</p></div></div><div class="pairing-card">{lan_info}{pair_form}{lan_advanced}</div>{pair_block}</section><section id="requests" class="section" data-live-region><div class="section-title"><div><span class="eyebrow">03 · APPROVAL</span><h2>连接申请 <span class="count" data-pending-count>{pc}</span></h2><p>确认设备名称和目标 Agent 后再批准。</p></div><span class="live-status" data-live-status aria-live="polite">正在连接更新…</span></div><div id="pending-list" class="stack">{pending}</div></section><section id="devices" class="section" data-live-region><div class="section-title"><div><span class="eyebrow">04 · DEVICES</span><h2>已连接设备 <span class="count" data-connection-count>{cc}</span></h2><p>可随时撤销设备授权。</p></div></div><div id="connection-list" class="stack">{connected}</div><p class="update-time" data-updated-at>等待首次更新</p></section><footer>管理页只在本机打开。当前 API 服务端口：{int(api_port)}。</footer></main></div><input type="hidden" data-csrf value="{csrf}"></body></html>'''
