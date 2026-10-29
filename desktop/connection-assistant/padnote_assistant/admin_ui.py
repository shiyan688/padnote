"""Accessible HTML renderer for the local PadNote management page."""
from __future__ import annotations

import html
import json
import sys
import time
from pathlib import Path
from typing import Any, Callable


def _e(value: Any) -> str:
    return html.escape(str(value if value is not None else ""), quote=True)


def _health(value: str) -> tuple[str, str]:
    return {
        "healthy": ("上次检测通过", "good"), "ready": ("上次检测通过", "good"),
        "not_checked": ("尚未检测", "neutral"), "unsupported": ("暂不支持执行", "warn"),
        "identity_changed": ("身份已变化", "bad"), "unhealthy": ("连接异常", "bad"),
        "error": ("连接异常", "bad"), "degraded": ("部分可用", "warn"),
        "needs_configuration": ("需要配置凭据或本机引擎", "warn"),
        "profile_unavailable": ("配置文件不可用", "bad"),
        "not_running": ("服务未运行", "bad"), "api_not_enabled": ("API 尚未启用", "warn"),
        "authentication_failed": ("凭据无效", "bad"),
        "wrong_service": ("地址指向其他服务", "bad"),
        "capability_missing": ("缺少所需接口", "warn"),
        "wsl_reconnect_required": ("需重新连接 WSL", "warn"),
    }.get(value, (value or "状态未知", "neutral"))


def _health_guidance(value: str) -> str:
    """Give a concrete next step without changing the underlying health state."""
    return {
        "healthy": "接口已通过检测。可以选择此 Agent 并为平板生成配对码。",
        "ready": "接口已通过检测。可以选择此 Agent 并为平板生成配对码。",
        "not_checked": "先运行接口检测，再继续配对。",
        "unsupported": "此 Agent 暂不支持执行。可查看配置说明或选择 Hermes。",
        "identity_changed": "检测到服务身份变化。请重新检测，并为设备重新配对。",
        "unhealthy": "请检查服务是否正在运行，再运行一次接口检测。",
        "error": "请检查服务地址和运行状态，然后重试检测。",
        "degraded": "部分接口可用。查看检测详情，确认所需功能后再配对。",
        "needs_configuration": "完成凭据或本机服务配置后，再运行接口检测。",
        "profile_unavailable": "当前配置不可用。请重新选择或填写服务配置。",
        "not_running": "启动 Agent 服务后，再运行接口检测。",
        "api_not_enabled": "启用本机 API Server 后，再运行接口检测。",
        "authentication_failed": "请检查本机 API 凭据并重新检测。",
        "wrong_service": "地址响应的不是受支持的 Agent 服务，请核对地址。",
        "capability_missing": "当前服务缺少所需接口。查看检测详情或选择其他 Agent。",
        "wsl_reconnect_required": "请进入 WSL 连接向导，重新选择原发行版和 Hermes 配置并连接。不会自动启动 WSL。",
    }.get(value, "运行接口检测以确认连接状态。")


def _instances(service: Any) -> list[dict[str, Any]]:
    return sorted(service.store.list_instances(), key=lambda row: row.get("created_at", 0))


_HERMES_STATUS_TEXT = {
    "ready": ("Hermes 已就绪", "可继续配对平板。"),
    "needs_setup": ("Hermes 需要完成配置", "请分别完成模型供应商登录和 Hermes 本机 API Server 设置。助手不会自动改写 Hermes 配置。"),
    "not_installed": ("常见位置未找到 Hermes", "只检查了已知安装位置和 PATH。若 Hermes 位于 WSL 或自定义目录，可手动填写可访问的本机 API 地址；否则查看官方安装指南。"),
    "ambiguous": ("发现多个 Hermes 配置", "请在“发现已有配置”中选择要使用的配置；如果列表里没有，可手动填写本机 API 地址。"),
    "starting": ("Hermes 正在启动", "请稍候片刻，再检查连接状态。"),
    "error": ("暂时无法检查 Hermes", "请确认 Hermes 已安装并完成配置，然后重试。"),
}


def hermes_onboarding_status(service: Any) -> dict[str, str]:
    """Expose only the small, non-sensitive status contract used by the UI."""
    try:
        from . import hermes_onboarding
        value = hermes_onboarding.get_hermes_status(service)
    except Exception:
        value = {"status": "error"}
    if not isinstance(value, dict):
        value = {"status": "error"}
    status = value.get("status")
    if not isinstance(status, str) or status not in _HERMES_STATUS_TEXT:
        status = "error"
    result = {"status": status}
    instance_id = value.get("instance_id")
    if isinstance(instance_id, str) and len(instance_id) <= 120:
        result["instance_id"] = instance_id
    message = value.get("message")
    if isinstance(message, str):
        result["message"] = message[:1200]
    phase = value.get("phase")
    if isinstance(phase, str):
        result["phase"] = phase[:80]
    launched = value.get("launched")
    if isinstance(launched, bool):
        result["launched"] = launched
    base_url = value.get("base_url")
    if isinstance(base_url, str):
        result["base_url"] = base_url[:512]
    return result


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
        checked_at = i.get("checked_at")
        now = int(service.clock())
        valid_check = isinstance(checked_at, int) and not isinstance(checked_at, bool) and 0 < checked_at <= now
        check_age = now - checked_at if valid_check else None
        if check_age is None:
            check_label, check_tone = "未知 · 需要重新检测", "neutral"
            check_age_attr = ""
            label, tone = "状态未知 · 需要重新检测", "neutral"
        else:
            check_label = "上次检测：" + str(check_age) + " 秒前" if check_age <= 60 else "上次检测：" + str(check_age // 60) + " 分钟前 · 结果已过期"
            check_tone = "good" if check_age <= 60 else "warn"
            check_age_attr = ' data-check-age="' + str(check_age) + '"'
            if check_age > 60:
                label, tone = "检测结果已过期", "warn"
        kind = {"hermes": "Hermes", "openclaw": "OpenClaw · 仅发现与引导", "builtin_video": "可选 Qwen 视频适配"}.get(i.get("kind"), i.get("kind", "Agent"))
        retired = bool(i.get("retired_at"))
        actions = ''
        check_is_fresh = check_age is not None and check_age <= 60
        health_ready = i.get("health") in {"ready", "healthy"}
        if i.get("kind") in {"hermes", "builtin_video"} and not retired:
            keylabel = "模型与配音 API Key" if i["kind"] == "builtin_video" else "Hermes API Key"
            if i.get("executable"):
                if i["kind"] == "hermes":
                    check_form = '<form method="post" action="/admin/instances/' + iid + '/check" data-agent-check><input type="hidden" name="csrf" value="' + csrf + '"><button data-check-button>重新检测接口</button></form>'
                    pair_link = '<a class="button" href="#pairing" data-pair-agent="' + iid + '">连接平板</a>'
                    if check_is_fresh and health_ready:
                        main_action = pair_link + check_form.replace('<button data-check-button>', '<button class="button-quiet" data-check-button>')
                    else:
                        main_action = check_form + pair_link.replace('class="button"', 'class="button button-quiet"')
                else:
                    main_action = '<a class="button" href="#pairing" data-pair-agent="' + iid + '">连接平板</a>'
            elif not i.get("credential_fingerprint"):
                main_action = '<button type="button" data-open-credentials="' + iid + '">填写凭据</button>'
            else:
                main_action = '<form method="post" action="/admin/instances/' + iid + '/check" data-agent-check><input type="hidden" name="csrf" value="' + csrf + '"><button data-check-button>检测 Agent 接口</button></form>'
            actions = '<div class="agent-primary">' + main_action + '</div><details class="advanced" data-credentials="' + iid + '"><summary>凭据与维护</summary><form method="post" action="/admin/instances/' + iid + '/key"><input type="hidden" name="csrf" value="' + csrf + '"><label>' + keylabel + ' <span class="muted">不会回显</span><input data-credential-input type="password" name="api_key" autocomplete="new-password" required></label><button class="button-quiet">保存凭据</button></form>'
            if i["kind"] == "builtin_video":
                actions += '<form method="post" action="/admin/instances/' + iid + '/credential/revoke"><input type="hidden" name="csrf" value="' + csrf + '"><button class="button-quiet danger">撤销凭据与设备</button></form>'
            actions += '</details>'
        detail = '<details class="agent-details"><summary>检测详情与配置</summary><p class="muted">' + _e(i.get("detail", "")) + '</p>'
        if i.get("kind") == "builtin_video":
            detail += '<p class="meta">模型 ' + _e(i.get("provider_model", "qwen-plus")) + ' · ' + _e(i.get("provider_region", "")) + ' · TTS ' + _e(i.get("tts_model", "")) + ' / ' + _e(i.get("tts_voice", "")) + '</p>'
        detail += '</details>'
        guidance = ("上次检测结果已过期。重新检测接口后，再继续配对。"
                    if check_age is not None and check_age > 60
                    else _health_guidance(i.get("health", "")))
        cards.append('<article class="agent-card"><div class="agent-heading"><div><h3>' + _e(i.get("name")) + '</h3><span class="muted">' + _e(kind) + '</span></div><span class="badge ' + tone + '">' + _e(label) + '</span></div><p class="check-age" data-check-age-label' + check_age_attr + '>' + _e(check_label) + '</p><p class="agent-guidance">' + _e(guidance) + '</p>' + actions + detail + '</article>')
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
        pairing_shortcut = '<a class="pairing-shortcut" href="#pairing">查看当前配对码 <span aria-hidden="true">↓</span></a>'
    elif pair:
        notice = "上一个配对二维码已过期。请重新生成后再扫描。" if expires <= int(service.clock()) else "二维码目标 Agent 当前不可连接。请检查 Agent 后重新生成配对码。"
        pair_block = '<div class="callout warn">' + _e(notice) + '</div>'
        pairing_shortcut = ''
    else:
        pairing_shortcut = ''

    pending, connected, pc, cc = _lists(service, csrf_token)
    message_html = '<div class="notice" role="status">' + _e(message) + '</div>' if message else ''
    onboarding = hermes_onboarding_status(service)
    onboarding_title, fallback_onboarding_text = _HERMES_STATUS_TEXT[onboarding["status"]]
    onboarding_ready = onboarding["status"] == "ready"
    # Keep the prominent instructions localized and stable by known status;
    # retain the backend detail only in a collapsed diagnostic disclosure.
    onboarding_text = fallback_onboarding_text
    raw_onboarding_message = onboarding.get("message")
    onboarding_detail = ''
    if isinstance(raw_onboarding_message, str) and raw_onboarding_message.strip():
        onboarding_detail = '<details class="onboarding-detail"><summary>检查详情</summary><p class="muted">' + _e(raw_onboarding_message) + '</p></details>'
    onboarding_extra = ''
    wsl_onboarding_link = '<a class="text-link" href="#wsl-hermes" data-open-wsl-hermes>在 WSL 中查找已有 Hermes</a>' if sys.platform == "win32" else ''
    if onboarding["status"] == "not_installed":
        onboarding_extra = wsl_onboarding_link + '<a class="text-link" href="#add-agent" data-open-manual-hermes>手动填写可访问的本机 API 地址</a><a class="text-link" href="https://hermes-agent.nousresearch.com/docs/getting-started/installation" target="_blank" rel="noopener noreferrer">查看 Hermes 官方安装指南 ↗</a>'
    elif onboarding["status"] == "ambiguous":
        onboarding_extra = wsl_onboarding_link + '<a class="text-link" href="#discovered-configs" data-open-discovery>查看已发现的 Hermes 配置 ↓</a><a class="text-link" href="#add-agent" data-open-manual-hermes>列表中没有？手动填写本机 API 地址</a>'
    elif onboarding["status"] == "needs_setup" and sys.platform == "win32":
        onboarding_extra = '<form method="post" action="/admin/hermes/setup" data-hermes-setup><input type="hidden" name="csrf" value="' + csrf + '"><button type="submit" class="button-quiet" data-setup-button>打开 Hermes 配置向导</button></form><a class="text-link" href="#add-agent" data-open-manual-hermes>使用其他目录中的 Hermes？手动填写本机 API</a>'
    elif onboarding["status"] == "needs_setup":
        onboarding_extra = '<a class="text-link" href="#add-agent" data-open-manual-hermes>使用其他目录中的 Hermes？手动填写本机 API</a><a class="text-link" href="https://hermes-agent.nousresearch.com/docs/getting-started/installation" target="_blank" rel="noopener noreferrer">查看 Hermes 官方配置指南 ↗</a>'
    onboarding_actions = ('<div class="onboarding-actions" data-onboarding-actions>' + onboarding_extra + '</div>') if onboarding_extra else ''
    hermes_status_html = '<div class="hermes-status-card"><div><span class="eyebrow">HERMES</span><h3>' + _e(onboarding_title) + '</h3><p class="muted">' + _e(onboarding_text) + '</p>' + onboarding_actions + onboarding_detail + '</div><form method="post" action="/admin/hermes/connect" data-hermes-connect><input type="hidden" name="csrf" value="' + csrf + '"><button class="hermes-connect-button" type="submit" data-connect-button>一键检查并连接 Hermes</button><span class="connect-progress" data-connect-progress role="status" aria-live="polite" hidden>正在检查并连接，请稍候…</span></form></div>'
    if onboarding_ready:
        onboarding_heading = "继续完成平板配对"
        onboarding_steps_html = '<div class="onboarding-progress" aria-label="连接进度"><span class="complete"><b>1</b><strong>Agent 已就绪</strong></span><span><b>2</b><strong>生成配对二维码</strong></span><span><b>3</b><strong>扫码并批准</strong></span></div>'
    else:
        onboarding_heading = "从 Agent 到平板，按顺序完成"
        onboarding_steps_html = '<ol class="onboarding-steps"><li><b>检查 Agent</b><span>选择已有配置，或添加 Hermes；运行接口检测确认服务可用。</span></li><li><b>生成配对二维码</b><span>电脑与平板连接同一 Wi-Fi，选择 Agent 和电脑网络地址。</span></li><li><b>扫码并批准</b><span>平板扫描后提交申请；回来核对设备名称与 Agent，再批准。</span></li></ol>'
    lan_advanced = '<details class="advanced"><summary>远程连接（可选：Tailscale）</summary><p>仅在不使用同一网络时考虑。检查现有配置后，可将本机 API 端口发布为 HTTPS；助手不会修改 Tailscale 配置或启用 Funnel。</p><pre>tailscale serve --bg --https=443 http://127.0.0.1:' + str(int(api_port)) + '</pre><form method="post" action="/admin/pair-codes"><input type="hidden" name="csrf" value="' + csrf + '"><label>Agent 实例<select name="instance_id" required>' + opts + '</select></label><label>Tailscale HTTPS 根地址<input name="public_url" placeholder="https://computer.example.ts.net" required></label><button class="button-quiet">生成远程配对码</button></form></details>' if executable else ''
    add_form = '<form id="add-agent-form" method="post" action="/admin/instances"><input type="hidden" name="csrf" value="' + csrf + '"><label>Agent 类型<select id="instance-kind" name="kind"><option value="hermes">Hermes</option><option value="builtin_video">可选 Qwen 视频适配</option></select></label><label>显示名称<input name="name" maxlength="120" required placeholder="例如：我的 Hermes"></label><div data-kind="hermes"><label>Hermes 本机地址<input id="manual-api-endpoint" name="base_url" value="http://127.0.0.1:8642"></label><label>Hermes API Key <span class="muted">可稍后在高级设置中填写</span><input type="password" name="api_key" autocomplete="new-password"></label><p class="muted">填写本机可访问的 API 地址；手动填写仅本次运行有效。发现已有配置可导入。</p></div><div data-kind="builtin_video" hidden><label>服务区域<select name="provider_region"><option value="china">中国站（配音链路已验证）</option><option value="international">国际站（完整配音与生成暂不可用）</option></select></label><label>模型与配音 API Key<input type="password" name="api_key" autocomplete="new-password"></label><details><summary>可选视频适配设置</summary><label>分镜模型<input name="provider_model" value="qwen-plus" maxlength="120"></label><label>JSON 响应模式<select name="response_mode"><option value="json_object">JSON Object</option><option value="json_schema">JSON Schema（需模型支持）</option></select></label><label>配音模型<input name="tts_model" value="qwen3-tts-instruct-flash" maxlength="120"></label><label>配音音色<input name="tts_voice" value="Maia" maxlength="120"></label></details></div><button>添加 Agent</button></form>'
    wsl_section = '''<section id="wsl-hermes" class="section wsl-section" aria-labelledby="wsl-heading">
      <div class="section-title"><div><span class="eyebrow">连接已有服务</span><h2 id="wsl-heading">连接 WSL 中的 Hermes</h2>
      <p>选择发行版和配置；预览后才会启用或启动服务。现有模型供应商设置不会被改写。</p></div></div>
      <div class="wsl-step-grid">
        <article class="wsl-step"><span class="wsl-step-number">1</span><div><h3>选择 WSL 发行版</h3>
          <label for="wsl-distro">发行版</label><select id="wsl-distro" data-wsl-distro><option value="">先扫描发行版</option></select>
          <p class="muted" data-wsl-distro-state>扫描只列出发行版名称和状态。</p>
          <button type="button" class="button-quiet" data-wsl-scan>扫描发行版</button>
          <button type="button" data-wsl-find disabled>查找 Hermes 配置</button>
        </div></article>
        <article class="wsl-step"><span class="wsl-step-number">2</span><div><h3>选择 Hermes 配置</h3>
          <label for="wsl-profile">配置（profile）</label><select id="wsl-profile" data-wsl-profile disabled><option value="">等待查找结果</option></select>
          <p class="muted">多个配置时请明确选择；每套配置的连接凭据彼此独立。</p>
        </div></article>
        <article class="wsl-step"><span class="wsl-step-number">3</span><div><h3>预览并连接</h3>
          <p class="muted">先查看将创建、更新或沿用的 API 设置字段，不显示设置值。</p>
          <button type="button" class="button-quiet" data-wsl-inspect disabled>预览变更</button>
          <div data-wsl-preview class="wsl-preview" hidden></div>
          <button type="button" data-wsl-connect disabled>选择配置后连接</button>
        </div></article>
      </div>
      <div class="wsl-operation" data-wsl-operation role="status" aria-live="polite" hidden>
        <p data-wsl-message></p><div class="wsl-operation-actions">
          <button type="button" class="button-quiet" data-wsl-refresh hidden>刷新操作状态</button>
          <button type="button" class="button-quiet" data-wsl-cancel hidden>取消等待</button>
          <a href="#pairing" class="text-link" data-wsl-pair hidden>前往现有配对页面</a>
        </div>
      </div>
      <p class="wsl-safety-note">取消只取消 PadNote 的等待，不会停止 Hermes。若连接结果暂时未知，请刷新操作状态；不要重复提交连接。</p>
    </section>'''
    if sys.platform != "win32":
        wsl_section = ""
    add_open = " open" if not instances else ""
    return f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><title>PadNote 电脑连接助手</title><link rel="stylesheet" href="/assets/admin.css"><script src="/assets/admin.js" defer></script></head><body><a class="skip-link" href="#main-content">跳到主要内容</a><div class="app-shell"><aside class="sidebar"><a class="brand" href="/"><img src="/assets/brand.png" alt=""><span>PadNote<small>电脑连接助手</small></span></a><nav aria-label="页面导航"><a href="#agents"><span>01</span>Agent</a><a href="#pairing"><span>02</span>配对</a><a href="#requests"><span>03</span>申请</a><a href="#devices"><span>04</span>设备</a></nav><div class="sidebar-foot"><span class="local-mark"></span>仅本机管理 · 端口 {int(admin_port)}</div></aside><main id="main-content" class="main"><header class="topbar"><div><span class="eyebrow">PADNOTE · 电脑助手</span><h1>把纸面工作带到电脑</h1><p>检查 Agent，连接平板，再在电脑上处理连接申请。</p></div><span class="local-pill"><i></i>本机管理</span></header>{message_html}<section id="getting-started" class="section onboarding-section"><div class="section-title"><div><span class="eyebrow">开始连接</span><h2>{_e(onboarding_heading)}</h2></div>{pairing_shortcut}</div><div class="onboarding-grid{' onboarding-ready' if onboarding_ready else ''}">{onboarding_steps_html}{hermes_status_html}</div></section>{wsl_section}<section id="agents" class="section"><div class="section-title"><div><span class="eyebrow">01 · 检查与准备</span><h2>Agent</h2><p>先确认服务状态，再继续配对。</p></div><a href="#add-agent" class="text-link">添加 Agent <span aria-hidden="true">↓</span></a></div><div class="agent-grid">{agent_content}</div><details id="discovered-configs" class="advanced discovery"><summary>发现已有配置</summary><p>只检查已知位置的配置，不会自动安装 Agent 或改写配置。</p>{discovery}<form method="post" action="/admin/discovery/refresh"><input type="hidden" name="csrf" value="{csrf}"><button class="button-quiet">重新扫描</button></form></details><details id="add-agent" class="add-panel"{add_open}><summary><span><span class="eyebrow">添加服务</span><b>添加 Agent</b></span><span class="muted" aria-hidden="true">展开＋</span></summary><p class="add-intro">Hermes 是当前推荐的可执行 Agent。添加后运行接口检测。</p>{add_form}</details></section><section id="pairing" class="section"><div class="section-title"><div><span class="eyebrow">02 · 扫描配对</span><h2>连接平板</h2><p>默认使用同一 Wi-Fi；远程连接选项保留在下方。</p></div></div><div class="pairing-card">{lan_info}{pair_form}{lan_advanced}</div>{pair_block}</section><section id="requests" class="section" data-live-region><div class="section-title"><div><span class="eyebrow">03 · 在电脑上确认</span><h2>连接申请 <span class="count" data-pending-count>{pc}</span></h2><p>核对设备名称和目标 Agent 后再批准。</p></div><span class="live-status" data-live-status aria-live="polite">正在连接更新…</span></div><div id="pending-list" class="stack">{pending}</div></section><section id="devices" class="section" data-live-region><div class="section-title"><div><span class="eyebrow">04 · 已连接</span><h2>设备 <span class="count" data-connection-count>{cc}</span></h2><p>查看已授权设备，必要时可撤销连接。</p></div></div><div id="connection-list" class="stack">{connected}</div><p class="update-time" data-updated-at>等待首次更新</p></section><footer><span>管理页只在本机打开。</span><span>API 服务端口 {int(api_port)}</span></footer></main></div><input type="hidden" data-csrf value="{csrf}"></body></html>'''
