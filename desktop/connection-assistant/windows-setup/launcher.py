"""Native Windows connection assistant launcher with persistent diagnostics."""
from __future__ import annotations

import ctypes
from ctypes import wintypes
import os
import platform
from pathlib import Path
import queue
import secrets
import subprocess
import sys
import threading
import time
import webbrowser

if os.name != "nt":
    raise SystemExit("The PadNote graphical launcher runs on Windows.")

from launcher_support import (MAX_DIAGNOSTIC_CHARS, Lifecycle, explain_exit, load_saved_logs,
                              parse_exit_code, parse_startup_error, prioritize_current_log, read_log_tail, rotate_log,
                              sanitize_diagnostics, onboarding_layout)
from padnote_assistant.hermes_onboarding_launcher import (connect as connect_hermes,
    open_setup as open_hermes_setup, read_status as read_hermes_status,
    should_probe_hermes, phase_label as hermes_phase_label,
    message_label as hermes_message_label)
from padnote_assistant.hermes_onboarding_launcher import (download_pinned_installer,
    hermes_installer_cache_dir, launch_verified_installer,
    has_official_hermes_cli, has_existing_hermes_home)

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
comdlg32 = ctypes.WinDLL("comdlg32", use_last_error=True)
gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)
shell32 = ctypes.WinDLL("shell32", use_last_error=True)
WNDPROC = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)

class WNDCLASSW(ctypes.Structure):
    _fields_ = [("style", wintypes.UINT), ("lpfnWndProc", WNDPROC), ("cbClsExtra", ctypes.c_int),
                ("cbWndExtra", ctypes.c_int), ("hInstance", wintypes.HINSTANCE), ("hIcon", wintypes.HICON),
                ("hCursor", wintypes.HANDLE), ("hbrBackground", wintypes.HANDLE),
                ("lpszMenuName", wintypes.LPCWSTR), ("lpszClassName", wintypes.LPCWSTR)]

class OPENFILENAMEW(ctypes.Structure):
    _fields_ = [("lStructSize", wintypes.DWORD), ("hwndOwner", wintypes.HWND), ("hInstance", wintypes.HINSTANCE),
                ("lpstrFilter", wintypes.LPCWSTR), ("lpstrCustomFilter", wintypes.LPWSTR),
                ("nMaxCustFilter", wintypes.DWORD), ("nFilterIndex", wintypes.DWORD),
                ("lpstrFile", wintypes.LPWSTR), ("nMaxFile", wintypes.DWORD), ("lpstrFileTitle", wintypes.LPWSTR),
                ("nMaxFileTitle", wintypes.DWORD), ("lpstrInitialDir", wintypes.LPCWSTR),
                ("lpstrTitle", wintypes.LPCWSTR), ("Flags", wintypes.DWORD), ("nFileOffset", wintypes.WORD),
                ("nFileExtension", wintypes.WORD), ("lpstrDefExt", wintypes.LPCWSTR), ("lCustData", wintypes.LPARAM),
                ("lpfnHook", ctypes.c_void_p), ("lpTemplateName", wintypes.LPCWSTR), ("pvReserved", ctypes.c_void_p),
                ("dwReserved", wintypes.DWORD), ("FlagsEx", wintypes.DWORD)]

class MINMAXINFO(ctypes.Structure):
    _fields_ = [("ptReserved", wintypes.POINT), ("ptMaxSize", wintypes.POINT), ("ptMaxPosition", wintypes.POINT),
                ("ptMinTrackSize", wintypes.POINT), ("ptMaxTrackSize", wintypes.POINT)]

class DRAWITEMSTRUCT(ctypes.Structure):
    _fields_ = [("CtlType", wintypes.UINT), ("CtlID", wintypes.UINT), ("itemID", wintypes.UINT),
                ("itemAction", wintypes.UINT), ("itemState", wintypes.UINT), ("hwndItem", wintypes.HWND),
                ("hDC", wintypes.HDC), ("rcItem", wintypes.RECT), ("itemData", ctypes.c_size_t)]

WM_CREATE, WM_DESTROY, WM_COMMAND, WM_TIMER = 1, 2, 0x111, 0x113
WM_CLOSE, WM_SIZE, WM_DPICHANGED, WM_GETMINMAXINFO = 0x10, 5, 0x2E0, 0x24
WM_CTLCOLORSTATIC, WM_CTLCOLOREDIT, WM_DRAWITEM = 0x138, 0x133, 0x2B
WS_OVERLAPPEDWINDOW, WS_CHILD, WS_VISIBLE, WS_TABSTOP = 0x00CF0000, 0x40000000, 0x10000000, 0x00010000
BS_OWNERDRAW = 0x0000000B
ES_MULTILINE, ES_READONLY, ES_AUTOVSCROLL, WS_VSCROLL = 4, 0x800, 0x40, 0x00200000
DT_SINGLELINE, DT_VCENTER, DT_CENTER = 0x20, 0x04, 0x01
BUTTON_START, BUTTON_OPEN, BUTTON_STOP, BUTTON_FIREWALL = 101, 102, 103, 104
BUTTON_COPY, BUTTON_SAVE, BUTTON_FOLDER, BUTTON_DETAILS = 105, 106, 107, 108
BUTTON_HERMES_CONNECT, BUTTON_HERMES_INSTALL = 109, 110
BUTTON_HERMES_CANCEL = 111
WINDOW_CLASS, MUTEX_NAME = "PadNoteConnectionAssistantWindow", "Local\\PadNoteConnectionAssistantLauncher"
LOG_MARKER = "PadNote management UI: http://127.0.0.1:8766"

# Explicit signatures keep 64-bit HWND/HANDLE values intact on Windows x64.
user32.CreateWindowExW.restype = wintypes.HWND
user32.CreateWindowExW.argtypes = [wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD,
    ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.HWND, wintypes.HMENU,
    wintypes.HINSTANCE, wintypes.LPVOID]
user32.RegisterClassW.restype = wintypes.ATOM
user32.RegisterClassW.argtypes = [ctypes.POINTER(WNDCLASSW)]
user32.FindWindowW.restype = wintypes.HWND
user32.FindWindowW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR]
user32.DestroyWindow.restype = wintypes.BOOL
user32.DestroyWindow.argtypes = [wintypes.HWND]
user32.DefWindowProcW.restype = ctypes.c_ssize_t
user32.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.SetWindowTextW.restype = wintypes.BOOL
user32.SetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPCWSTR]
user32.GetClientRect.restype = wintypes.BOOL
user32.GetClientRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
user32.SetWindowPos.restype = wintypes.BOOL
user32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.UINT]
user32.SendMessageW.restype = ctypes.c_ssize_t
user32.SendMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.SetPropW.restype = wintypes.BOOL
user32.SetPropW.argtypes = [wintypes.HWND, wintypes.LPCWSTR, wintypes.HANDLE]
user32.RemovePropW.restype = wintypes.HANDLE
user32.RemovePropW.argtypes = [wintypes.HWND, wintypes.LPCWSTR]
user32.LoadImageW.restype = wintypes.HANDLE
user32.LoadImageW.argtypes = [wintypes.HINSTANCE, wintypes.LPCWSTR, wintypes.UINT, ctypes.c_int, ctypes.c_int, wintypes.UINT]
user32.LoadCursorW.restype = wintypes.HANDLE
user32.LoadCursorW.argtypes = [wintypes.HINSTANCE, wintypes.LPCWSTR]
user32.GetDlgCtrlID.restype = ctypes.c_int
user32.GetDlgCtrlID.argtypes = [wintypes.HWND]
user32.EnableWindow.restype = wintypes.BOOL
user32.EnableWindow.argtypes = [wintypes.HWND, wintypes.BOOL]
user32.InvalidateRect.restype = wintypes.BOOL
user32.InvalidateRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT), wintypes.BOOL]
user32.ShowWindow.restype = wintypes.BOOL
user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
user32.SetForegroundWindow.restype = wintypes.BOOL
user32.SetForegroundWindow.argtypes = [wintypes.HWND]
user32.UpdateWindow.restype = wintypes.BOOL
user32.UpdateWindow.argtypes = [wintypes.HWND]
user32.GetMessageW.restype = ctypes.c_int
user32.GetMessageW.argtypes = [ctypes.POINTER(wintypes.MSG), wintypes.HWND, wintypes.UINT, wintypes.UINT]
user32.TranslateMessage.argtypes = [ctypes.POINTER(wintypes.MSG)]
user32.DispatchMessageW.argtypes = [ctypes.POINTER(wintypes.MSG)]
user32.IsDialogMessageW.restype = wintypes.BOOL
user32.IsDialogMessageW.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.MSG)]
user32.PostQuitMessage.argtypes = [ctypes.c_int]
user32.SetTimer.restype = ctypes.c_size_t
user32.SetTimer.argtypes = [wintypes.HWND, ctypes.c_size_t, wintypes.UINT, wintypes.LPVOID]
user32.KillTimer.argtypes = [wintypes.HWND, ctypes.c_size_t]
user32.SetProcessDpiAwarenessContext.restype = wintypes.BOOL
user32.SetProcessDpiAwarenessContext.argtypes = [ctypes.c_void_p]
user32.GetSystemMetrics.restype = ctypes.c_int
user32.GetSystemMetrics.argtypes = [ctypes.c_int]
user32.SystemParametersInfoW.restype = wintypes.BOOL
user32.SystemParametersInfoW.argtypes = [wintypes.UINT, wintypes.UINT, wintypes.LPVOID, wintypes.UINT]
try:
    user32.GetDpiForSystem.restype = wintypes.UINT
    user32.GetDpiForWindow.restype = wintypes.UINT
    user32.GetDpiForWindow.argtypes = [wintypes.HWND]
    get_dpi_for_system = user32.GetDpiForSystem
    get_dpi_for_window = user32.GetDpiForWindow
except AttributeError:
    get_dpi_for_system = get_dpi_for_window = None
user32.OpenClipboard.restype = wintypes.BOOL
user32.OpenClipboard.argtypes = [wintypes.HWND]
user32.EmptyClipboard.restype = wintypes.BOOL
user32.SetClipboardData.restype = wintypes.HANDLE
user32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
user32.CloseClipboard.restype = wintypes.BOOL
user32.MessageBoxW.restype = ctypes.c_int
user32.MessageBoxW.argtypes = [wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.UINT]
user32.SetFocus.restype = wintypes.HWND
user32.SetFocus.argtypes = [wintypes.HWND]
kernel32.CreateMutexW.restype = wintypes.HANDLE
kernel32.CreateMutexW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR]
kernel32.CreateEventW.restype = wintypes.HANDLE
kernel32.CreateEventW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR]
kernel32.SetEvent.restype = wintypes.BOOL
kernel32.SetEvent.argtypes = [wintypes.HANDLE]
kernel32.CloseHandle.restype = wintypes.BOOL
kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
kernel32.GetModuleHandleW.restype = wintypes.HINSTANCE
kernel32.GetModuleHandleW.argtypes = [wintypes.LPCWSTR]
kernel32.GlobalAlloc.restype = wintypes.HANDLE
kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
kernel32.GlobalLock.restype = wintypes.LPVOID
kernel32.GlobalLock.argtypes = [wintypes.HANDLE]
kernel32.GlobalUnlock.restype = wintypes.BOOL
kernel32.GlobalUnlock.argtypes = [wintypes.HANDLE]
kernel32.GlobalFree.restype = wintypes.HANDLE
kernel32.GlobalFree.argtypes = [wintypes.HANDLE]
gdi32.CreateFontW.restype = wintypes.HANDLE
gdi32.CreateFontW.argtypes = [ctypes.c_int] * 5 + [wintypes.DWORD] * 8 + [wintypes.LPCWSTR]
gdi32.CreateSolidBrush.restype = wintypes.HBRUSH
gdi32.CreateSolidBrush.argtypes = [wintypes.COLORREF]
gdi32.DeleteObject.restype = wintypes.BOOL
gdi32.DeleteObject.argtypes = [wintypes.HANDLE]
gdi32.SelectObject.restype = wintypes.HANDLE
gdi32.SelectObject.argtypes = [wintypes.HDC, wintypes.HANDLE]
gdi32.SetTextColor.restype = wintypes.COLORREF
gdi32.SetTextColor.argtypes = [wintypes.HDC, wintypes.COLORREF]
gdi32.SetBkColor.restype = wintypes.COLORREF
gdi32.SetBkColor.argtypes = [wintypes.HDC, wintypes.COLORREF]
gdi32.SetBkMode.argtypes = [wintypes.HDC, ctypes.c_int]
user32.FillRect.restype = ctypes.c_int
user32.FillRect.argtypes = [wintypes.HDC, ctypes.POINTER(wintypes.RECT), wintypes.HBRUSH]
user32.DrawTextW.argtypes = [wintypes.HDC, wintypes.LPCWSTR, ctypes.c_int, ctypes.POINTER(wintypes.RECT), wintypes.UINT]
user32.DrawFocusRect.argtypes = [wintypes.HDC, ctypes.POINTER(wintypes.RECT)]
comdlg32.GetSaveFileNameW.restype = wintypes.BOOL
comdlg32.GetSaveFileNameW.argtypes = [ctypes.POINTER(OPENFILENAMEW)]
shell32.ShellExecuteW.restype = ctypes.c_ssize_t
shell32.ShellExecuteW.argtypes = [wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR,
                                  wintypes.LPCWSTR, wintypes.LPCWSTR, ctypes.c_int]

# COLORREF uses 0x00BBGGRR.
def rgb(r, g, b): return r | (g << 8) | (b << 16)
IVORY, NAVY, BLUE, PALE_BLUE, WHITE = rgb(248, 246, 240), rgb(24, 43, 69), rgb(44, 105, 176), rgb(232, 241, 250), rgb(255, 255, 255)

main_hwnd = status_hwnd = details_hwnd = path_hwnd = None
child = shutdown_event = mutex = None
shutdown_name = None
lifecycle = Lifecycle()
stop_worker = None
worker_result = queue.Queue()
hermes_result = queue.Queue()
hermes_worker = None
hermes_busy = False
hermes_pending = False
hermes_pending_setup = False
hermes_cancel_event = None
hermes_status = "尚未检查"
hermes_instance = ""
hermes_details = {}
hermes_info_hwnd = None
tutorial_heading_hwnd = tutorial_body_hwnd = diagnostics_heading_hwnd = None
installer_process = None
installer_script_path = ""
log_stream = None
dpi = 96
start_time = 0.0
last_error = ""
startup_error = None
session_offset = 0
session_log = ""
previous_log = ""
last_diagnostic_text = None
controls = []
control_roles = {}
control_fonts = {}
control_font_sizes = {}
font_handles = []
brushes = {}
closing = False
window_width = 820
window_height = 730


def scale(n): return (abs(n) * dpi + 48) // 96 * (-1 if n < 0 else 1)

def data_dir(): return Path.home() / ".local" / "share" / "padnote-connection-assistant"

def control(parent, text, x, y, w, h, ident=None, style=0, role="body", font_size=15):
    cls = "BUTTON" if ident else ("EDIT" if style & ES_READONLY else "STATIC")
    if ident: style |= BS_OWNERDRAW
    tab = WS_TABSTOP if ident or cls == "EDIT" else 0
    hwnd = user32.CreateWindowExW(0, cls, text, WS_CHILD | WS_VISIBLE | tab | style,
        scale(x), scale(y), scale(w), scale(h), parent, ident, kernel32.GetModuleHandleW(None), None)
    face = "Microsoft YaHei UI" if any(ord(ch) > 127 for ch in text) or role == "header" else "Segoe UI"
    weight = 700 if role in ("title", "header") else 500
    height = scale(-font_size)
    font = gdi32.CreateFontW(height, 0, 0, 0, weight, 0, 0, 0, 1, 0, 0, 5, 0, face)
    if font:
        font_handles.append(font)
        control_fonts[hwnd] = font
        user32.SendMessageW(hwnd, 0x30, font, 1)
    controls.append((hwnd, x, y, w, h))
    control_roles[hwnd] = role
    control_font_sizes[hwnd] = (font_size, weight, face)
    return hwnd

def find_control(ident):
    for hwnd, *_ in controls:
        if user32.GetDlgCtrlID(hwnd) == ident: return hwnd
    return None

def status_text(text):
    if status_hwnd: user32.SetWindowTextW(status_hwnd, text)
    refresh_diagnostics()

def diagnostics_text():
    text = ["PadNote 连接助手诊断", "版本：0.18.0-beta.20", "构建：2026-10-28-beta20-r1",
            f"Windows：{platform.release()}（build {platform.version()}）",
            f"Python：{platform.python_version()}", f"状态：{current_status()}",
            f"Hermes：{hermes_status}", f"Hermes 实例：{hermes_instance or '未选择'}",
            f"Hermes 阶段：{hermes_details.get('phase', '')}",
            f"Hermes 阶段说明：{hermes_phase_label(str(hermes_details.get('phase', '')))}",
            f"Hermes 中文提示：{hermes_message_label(hermes_status, str(hermes_details.get('phase', '')), str(hermes_details.get('message', '')))}",
            f"Hermes 消息：{hermes_details.get('message', '')}",
            f"Hermes 已启动：{hermes_details.get('launched', False)}",
            f"Hermes 地址：{hermes_details.get('base_url', '')}",
            f"安装终端：{('运行中' if installer_process and installer_process.poll() is None else '未运行')}",
            f"安装脚本缓存：{installer_script_path or '无'}", f"日志目录：{data_dir()}"]
    if last_error: text.append("原因与建议：" + last_error)
    metadata = "\n".join(text)
    log_text = prioritize_current_log(session_log, previous_log,
                                      limit=max(0, MAX_DIAGNOSTIC_CHARS - len(metadata) - 1))
    return sanitize_diagnostics(metadata + "\n" + log_text)

def current_status():
    if lifecycle.stopping: return "正在停止"
    if lifecycle.process is None: return "已停止或启动失败"
    return "已就绪" if lifecycle.ready else "正在启动"

def refresh_diagnostics(force=False):
    global last_diagnostic_text
    if not details_hwnd: return
    content = diagnostics_text()
    if force or content != last_diagnostic_text:
        user32.SetWindowTextW(details_hwnd, content.replace("\r\n", "\n").replace("\n", "\r\n"))
        last_diagnostic_text = content

def update_buttons():
    start_button = find_control(BUTTON_START)
    open_button = find_control(BUTTON_OPEN)
    if start_button:
        user32.SetWindowTextW(start_button, "重试启动" if last_error else "启动助手")
        control_roles[start_button] = "primary" if lifecycle.can_start else "secondary"
    user32.EnableWindow(start_button, lifecycle.can_start)
    if open_button:
        control_roles[open_button] = "primary" if lifecycle.can_open else "secondary"
    user32.EnableWindow(open_button, lifecycle.can_open)
    user32.EnableWindow(find_control(BUTTON_STOP), lifecycle.can_stop)
    hermes_actions_enabled = (lifecycle.ready or lifecycle.can_start) and not lifecycle.stopping and not hermes_busy and installer_process is None and not closing
    user32.EnableWindow(find_control(BUTTON_HERMES_CONNECT), hermes_actions_enabled)
    user32.EnableWindow(find_control(BUTTON_HERMES_INSTALL), hermes_actions_enabled)
    user32.EnableWindow(find_control(BUTTON_HERMES_CANCEL), hermes_busy)
    for button in (start_button, open_button, find_control(BUTTON_STOP), find_control(BUTTON_HERMES_CONNECT), find_control(BUTTON_HERMES_INSTALL), find_control(BUTTON_HERMES_CANCEL), find_control(BUTTON_COPY),
                   find_control(BUTTON_SAVE), find_control(BUTTON_FOLDER), find_control(BUTTON_FIREWALL)):
        if button:
            user32.InvalidateRect(button, None, True)

def close_child_handles():
    global shutdown_event, shutdown_name, log_stream
    if shutdown_event:
        kernel32.CloseHandle(shutdown_event)
        shutdown_event = None
    shutdown_name = None
    if log_stream:
        try: log_stream.close()
        except OSError: pass
        log_stream = None

def start_service():
    global child, shutdown_event, shutdown_name, log_stream, start_time, last_error, startup_error, previous_log, session_offset, session_log
    if not lifecycle.can_start: return
    last_error = ""
    startup_error = None
    session_log = ""
    root = Path(__file__).resolve().parent
    data = data_dir()
    try:
        warning = rotate_log(data / "launcher.log")
        data.mkdir(parents=True, exist_ok=True)
        # If rotation failed append without deleting the only previous evidence.
        log_stream = (data / "launcher.log").open("a" if warning else "w", encoding="utf-8", buffering=1)
        if warning: log_stream.write(warning + "\n")
        try:
            if warning and (data / "launcher.log").is_file():
                previous_log = read_log_tail(data / "launcher.log")[0]
            else:
                previous_log = read_log_tail(data / "launcher.log.previous")[0] if (data / "launcher.log.previous").is_file() else ""
        except OSError as exc:
            previous_log = f"Previous launcher log is unavailable: {type(exc).__name__}: {exc}"
        # Use the output stream's byte position as this attempt's start marker.
        log_stream.flush()
        session_offset = (data / "launcher.log").stat().st_size
        executable = Path(sys.executable).with_name("pythonw.exe")
        shutdown_name = "Local\\PadNoteConnectionAssistantShutdown-" + secrets.token_hex(16)
        shutdown_event = kernel32.CreateEventW(None, True, False, shutdown_name)
        if not shutdown_event: raise OSError(ctypes.get_last_error(), "Could not create private shutdown event")
        env = os.environ.copy(); env["PYTHONDONTWRITEBYTECODE"] = "1"
        child = subprocess.Popen([str(executable), str(root / "start.py"), "--lan", "--no-browser",
            "--state-dir", str(data), "--shutdown-event", shutdown_name], cwd=str(root), env=env,
            creationflags=subprocess.CREATE_NO_WINDOW, stdin=subprocess.DEVNULL,
            stdout=log_stream, stderr=subprocess.STDOUT, close_fds=True)
        lifecycle.started(child)
        start_time = time.monotonic()
        status_text("正在启动连接助手。窗口会保持打开；就绪后可打开本机管理页。")
        update_buttons(); refresh_diagnostics(force=True)
    except Exception as exc:
        close_child_handles(); child = None
        try:
            previous_path = data / "launcher.log.previous"
            if previous_path.is_file(): previous_log = read_log_tail(previous_path)[0]
        except OSError:
            pass
        last_error = f"{type(exc).__name__}: {exc}。请检查日志目录权限或修复安装后重试。"
        status_text("连接助手无法启动。请查看下方诊断并重试。")
        update_buttons(); refresh_diagnostics(force=True)

def stop_async():
    global stop_worker
    process = lifecycle.process
    if process is None or not lifecycle.begin_stop(process): return
    if shutdown_event: kernel32.SetEvent(shutdown_event)
    status_text("正在请求助手正常关闭……")
    update_buttons()
    def worker(target):
        try:
            target.wait(timeout=8)
            worker_result.put((target, "exited", target.returncode))
            return
        except subprocess.TimeoutExpired:
            pass
        try:
            target.kill()  # Only the Popen child created by this launcher.
            target.wait(timeout=5)
            worker_result.put((target, "killed", target.returncode))
        except Exception as exc:
            worker_result.put((target, "alive", f"{type(exc).__name__}: {exc}"))
    stop_worker = threading.Thread(target=worker, args=(process,), daemon=True)
    stop_worker.start()

def finish_stop():
    global child, stop_worker, last_error
    try: process, outcome, detail = worker_result.get_nowait()
    except queue.Empty: return
    if process is not lifecycle.process:
        return
    if outcome == "alive" and process.poll() is None:
        lifecycle.stop_result(process, exited=False)
        last_error = f"助手仍在运行，停止失败：{detail}。可以再次点击停止。"
        status_text("Windows 尚未确认助手退出。助手仍在运行；可再次尝试停止。")
        stop_worker = None
        update_buttons(); refresh_diagnostics(force=True)
        return
    lifecycle.stop_result(process, exited=True)
    child = None
    close_child_handles()
    stop_worker = None
    if closing:
        return
    status_text("助手已停止。下次需要配对时可重新启动。" if outcome == "exited" else "正常关闭超时，已结束本启动器拥有的助手进程。")
    update_buttons(); refresh_diagnostics(force=True)

def poll_session_log():
    global session_offset, session_log, startup_error, last_error
    path = data_dir() / "launcher.log"
    try:
        with path.open("rb") as stream:
            size = path.stat().st_size
            if size < session_offset: session_offset = 0
            stream.seek(session_offset)
            raw = stream.read(1_000_000)
            session_offset += len(raw)
        if raw:
            session_log = (session_log + raw.decode("utf-8", "replace"))[-1_000_000:]
            startup_error = parse_startup_error(session_log, ready=lifecycle.ready)
            if startup_error:
                title, action = explain_exit(-1, was_ready=False, startup_error=startup_error)
                last_error = f"{title} {action}"
                status_text(title)
            else:
                refresh_diagnostics()
    except OSError as exc:
        if not last_error:
            # Keep the UI useful if the filesystem became unavailable after spawn.
            set_error = f"Could not read launcher log: {type(exc).__name__}: {exc}. Use Save diagnostics or check folder permissions."
            last_error = set_error
            refresh_diagnostics()

def update_status():
    global child, last_error, session_log, session_offset, startup_error, hermes_pending, hermes_pending_setup
    finish_stop()
    finish_hermes_action()
    poll_hermes_installer()
    process = lifecycle.process
    if process is None:
        if hermes_pending and lifecycle.can_start and not closing:
            start_service()
            if lifecycle.process is None:
                hermes_pending = False
                hermes_pending_setup = False
        return
    if lifecycle.stopping: return
    poll_session_log()
    code = process.poll()
    if code is not None:
        was_ready = lifecycle.ready
        if lifecycle.exited(process):
            try:
                exit_record = f"PADNOTE_LAUNCHER_EXIT_CODE {code}\n"
                with (data_dir() / "launcher.log").open("a", encoding="utf-8") as output:
                    output.write(exit_record)
                session_log = (session_log + exit_record)[-1_000_000:]
                session_offset += len(exit_record.encode("utf-8"))
            except OSError:
                pass
            child = None; close_child_handles()
            title, action = explain_exit(code, was_ready=was_ready, startup_error=startup_error)
            if startup_error:
                title = f"{title}（退出代码 {code}）"
            last_error = action
            status_text(title + " 查看下方诊断。")
            update_buttons(); refresh_diagnostics(force=True)
        return
    if not lifecycle.ready and LOG_MARKER in session_log and lifecycle.mark_ready(process):
        last_error = ""
        startup_error = None
        status_text("已就绪。本机管理页已启动；请让平板连接同一 Wi-Fi 并扫描配对二维码。")
        update_buttons(); refresh_diagnostics(force=True)
        if hermes_pending:
            pending_setup = hermes_pending_setup
            hermes_pending = False
            hermes_pending_setup = False
            start_hermes_action(setup=pending_setup)
    elif not lifecycle.ready and time.monotonic() - start_time > 90 and not last_error:
        status_text("启动已超过 90 秒，仍在等待管理页就绪。进程会继续运行；请查看诊断。")
        last_error = "启动尚未报告管理页就绪。慢速电脑可能仍在启动，请检查诊断后再决定是否重试。"
        refresh_diagnostics(force=True)

def start_hermes_action(*, setup=False, delay=0.0):
    global hermes_worker, hermes_busy, hermes_cancel_event
    if closing or not lifecycle.ready or hermes_busy: return
    hermes_cancel_event = threading.Event()
    cancel_event = hermes_cancel_event
    hermes_busy = True
    status_text("正在从本机管理页打开 Hermes 配置终端……" if setup else "正在从本机管理页检查 Hermes 并尝试连接；请稍候……")
    update_buttons()
    def worker():
        try:
            try:
                if delay and cancel_event.wait(delay):
                    raise RuntimeError("Hermes 操作已取消")
                if setup:
                    # This single UI action reuses an existing installation. Only a
                    # fresh not-installed result enters the explicit install-confirmation flow.
                    current = read_hermes_status(cancel_event=cancel_event)
                    local_app_data = os.environ.get("LOCALAPPDATA", "")
                    has_existing = bool(local_app_data and has_official_hermes_cli(local_app_data))
                    has_data = bool(local_app_data and has_existing_hermes_home(local_app_data))
                    if current["status"] == "not_installed" and not has_existing:
                        if has_data:
                            hermes_result.put(("existing_data", current))
                        else:
                            hermes_result.put(("confirm_install", current))
                        return
                    open_hermes_setup(cancel_event=cancel_event)
                    current = read_hermes_status(cancel_event=cancel_event)
                    current.update({"status": "starting", "phase": "setup", "launched": True,
                                    "message": "Hermes setup opened. Complete provider login, then return and check the connection."})
                    hermes_result.put(("ok", current))
                    return
                current = read_hermes_status(cancel_event=cancel_event)
                if should_probe_hermes(str(current["status"])):
                    current = connect_hermes(timeout=30.0, cancel_event=cancel_event)
                hermes_result.put(("ok", current))
            except Exception as exc:
                safe_error = sanitize_diagnostics(f"{type(exc).__name__}: {exc}", 400)
                hermes_result.put(("error", safe_error))
        except BaseException as exc:
            hermes_result.put(("error", sanitize_diagnostics(f"{type(exc).__name__}: {exc}", 400)))
    hermes_worker = threading.Thread(target=worker, name="PadNoteHermesConnect", daemon=True)
    hermes_worker.start()

def start_pinned_installer_download():
    global hermes_worker, hermes_busy, hermes_cancel_event
    if closing or hermes_busy or installer_process is not None:
        return
    hermes_cancel_event = threading.Event()
    cancel_event = hermes_cancel_event
    hermes_busy = True
    status_text("正在确认安装状态并下载固定版本的 Hermes 安装脚本……")
    update_buttons()
    def worker():
        script_path = None
        try:
            if cancel_event.wait(3.1):
                raise RuntimeError("Hermes 操作已取消")
            local_app_data = os.environ.get("LOCALAPPDATA", "")
            if local_app_data and has_official_hermes_cli(local_app_data):
                hermes_result.put(("existing_install", {"status": "needs_setup", "phase": "discovery", "message": "An official Hermes executable already exists; refusing to reinstall."}))
                return
            if local_app_data and has_existing_hermes_home(local_app_data):
                hermes_result.put(("existing_data", {"status": "needs_setup", "phase": "discovery", "message": "Existing Hermes data was found; refusing to overwrite it."}))
                return
            status = read_hermes_status(cancel_event=cancel_event)
            if status.get("status") != "not_installed":
                hermes_result.put(("status_changed", status))
                return
            script_path = download_pinned_installer(hermes_installer_cache_dir(), cancel_event)
            # Let the management endpoint's 3-second status cache expire before the
            # final no-overwrite check immediately before launching PowerShell.
            if cancel_event.wait(3.1):
                raise RuntimeError("Hermes 操作已取消")
            status = read_hermes_status(cancel_event=cancel_event)
            if local_app_data and has_official_hermes_cli(local_app_data):
                script_path.unlink(missing_ok=True)
                hermes_result.put(("existing_install", {"status": "needs_setup", "phase": "discovery", "message": "An official Hermes executable already exists; refusing to reinstall."}))
                return
            if local_app_data and has_existing_hermes_home(local_app_data):
                script_path.unlink(missing_ok=True)
                hermes_result.put(("existing_data", {"status": "needs_setup", "phase": "discovery", "message": "Existing Hermes data was found; refusing to overwrite it."}))
                return
            if status.get("status") != "not_installed":
                script_path.unlink(missing_ok=True)
                hermes_result.put(("status_changed", status))
                return
            if cancel_event.is_set():
                raise RuntimeError("Hermes 操作已取消")
            process = launch_verified_installer(script_path, os.environ.get("SystemRoot", r"C:\Windows"), local_app_data)
            hermes_result.put(("installer_started", {"process": process, "script_path": str(script_path)}))
        except Exception as exc:
            if script_path is not None and script_path.exists():
                try: script_path.unlink()
                except OSError: pass
            hermes_result.put(("error", sanitize_diagnostics(f"{type(exc).__name__}: {exc}", 400)))
    hermes_worker = threading.Thread(target=worker, name="PadNoteHermesInstaller", daemon=True)
    hermes_worker.start()

def poll_hermes_installer():
    global installer_process, installer_script_path, last_error, hermes_pending, hermes_pending_setup
    process = installer_process
    if process is None:
        return
    code = process.poll()
    if code is None:
        return
    installer_process = None
    if code == 0:
        status_text("Hermes 安装终端已关闭，正在等待状态缓存更新并重新检查……")
        last_error = ""
        if closing:
            return
        if lifecycle.ready:
            start_hermes_action(delay=3.2)
        else:
            hermes_pending = True
            hermes_pending_setup = False
            if lifecycle.can_start:
                start_service()
                if lifecycle.process is None:
                    hermes_pending = False
    else:
        last_error = f"Hermes 安装终端退出代码 {code}。请检查可见 PowerShell 窗口中的诊断。"
        status_text("Hermes 安装没有报告成功。请查看 PowerShell 窗口并重试或复制诊断。")
        update_buttons(); refresh_diagnostics(force=True)

def finish_hermes_action():
    global hermes_worker, hermes_busy, hermes_status, hermes_instance, last_error, hermes_details, installer_process, installer_script_path
    try: outcome, result = hermes_result.get_nowait()
    except queue.Empty: return
    hermes_busy = False; hermes_worker = None
    if closing:
        return
    if outcome == "confirm_install":
        hermes_status = result.get("status", "not_installed")
        hermes_details = result
        answer = user32.MessageBoxW(main_hwnd,
            "即将下载并运行已校验的 Hermes 官方 Windows 安装脚本。\n\n来源：https://raw.githubusercontent.com/NousResearch/hermes-agent/bf0203a794a07aac3ff764972ab9087baa4e4f4e/scripts/install.ps1\n提交：bf0203a794a07aac3ff764972ab9087baa4e4f4e；固定 SHA-256 校验。\n目标：%LOCALAPPDATA%\\hermes。安装过程会下载并配置依赖，随后运行官方 hermes setup。\nPowerShell 将以当前用户权限打开可见窗口，不会请求管理员权限。\n首次配置由你选择模型供应商并完成登录。请在安装前确认这些内容。",
            "下载并配置 Hermes", 0x24)
        if answer == 6:
            start_pinned_installer_download()
        else:
            status_text("未下载 Hermes。你可以稍后重新点击“安装 / 配置 Hermes…”。")
        update_buttons(); refresh_diagnostics(force=True)
        return
    if outcome == "status_changed":
        hermes_status = result.get("status", "error")
        hermes_instance = result.get("instance_id", "")
        hermes_details = result
        status_text("Hermes 状态已变化；为避免覆盖现有安装，已取消安装。请重新检查连接。")
        if hermes_info_hwnd:
            user32.SetWindowTextW(hermes_info_hwnd, hermes_message_label(hermes_status, str(result.get("phase", "")), str(result.get("message", ""))))
        update_buttons(); refresh_diagnostics(force=True)
        return
    if outcome == "existing_install":
        hermes_status = result.get("status", "needs_setup")
        hermes_details = result
        status_text("检测到 Hermes 官方安装路径中已有程序；已取消重复安装并改为打开原有配置。")
        if hermes_info_hwnd:
            user32.SetWindowTextW(hermes_info_hwnd, "检测到已有 Hermes；正在打开原有配置向导。")
        update_buttons(); refresh_diagnostics(force=True)
        start_hermes_action(setup=True, delay=3.2)
        return
    if outcome == "existing_data":
        hermes_status = result.get("status", "needs_setup")
        hermes_details = result
        status_text("在官方 Hermes 目录中发现已有数据；为保护配置，已跳过安装。")
        if hermes_info_hwnd:
            user32.SetWindowTextW(hermes_info_hwnd, "检测到现有 Hermes 数据；请先查看官方 Windows 指南并确认如何修复。")
        webbrowser.open("https://hermes-agent.nousresearch.com/docs/user-guide/windows-native")
        update_buttons(); refresh_diagnostics(force=True)
        return
    if outcome == "installer_started":
        installer_process = result["process"]
        installer_script_path = str(result["script_path"])
        status_text("固定版本 Hermes 安装脚本已在可见 PowerShell 窗口启动。完成依赖安装、模型选择和登录后关闭该窗口，助手再检查结果。")
        if hermes_info_hwnd:
            user32.SetWindowTextW(hermes_info_hwnd, "安装终端已打开。完成依赖安装、模型选择和登录后关闭窗口。")
        update_buttons(); refresh_diagnostics(force=True)
        return
    if outcome == "ok":
        hermes_status = result.get("status", "error")
        hermes_instance = result.get("instance_id", "")
        hermes_details = result
        phase_copy = hermes_phase_label(str(result.get("phase", "")))
        message = str(result.get("message", ""))
        summary = "Hermes：" + hermes_label(hermes_status)
        if phase_copy: summary += " · " + phase_copy
        status_text(summary)
        if hermes_info_hwnd:
            user32.SetWindowTextW(hermes_info_hwnd, hermes_message_label(hermes_status, str(result.get("phase", "")), message))
        last_error = message if hermes_status == "error" else ""
    else:
        last_error = "Hermes 连接操作失败：" + str(result)
        if "已取消" in str(result):
            last_error = "Hermes 检查已取消。"
            status_text("Hermes 检查已取消。")
        else:
            status_text("无法完成 Hermes 连接。请查看诊断信息并复制给支持人员。")
    update_buttons(); refresh_diagnostics(force=True)

def hermes_label(status):
    return {"ready": "已连接", "needs_setup": "需要配置", "not_installed": "未安装", "ambiguous": "发现多个实例", "starting": "正在启动", "error": "检查失败"}.get(status, "状态未知")

def set_clipboard_text(text):
    if not user32.OpenClipboard(main_hwnd): return False, "Windows 剪贴板当前不可用。"
    handle = None
    try:
        if not user32.EmptyClipboard(): return False, "无法清空 Windows 剪贴板。"
        payload = text.encode("utf-16-le") + b"\x00\x00"
        handle = kernel32.GlobalAlloc(0x0002, len(payload))
        if not handle: return False, "Windows 无法分配剪贴板内存。"
        pointer = kernel32.GlobalLock(handle)
        if not pointer:
            kernel32.GlobalFree(handle); handle = None
            return False, "Windows 无法锁定剪贴板内存。"
        ctypes.memmove(pointer, payload, len(payload))
        kernel32.GlobalUnlock(handle)
        if not user32.SetClipboardData(13, handle):
            kernel32.GlobalFree(handle); handle = None
            return False, "无法写入 Windows 剪贴板。"
        handle = None  # Clipboard now owns the movable global memory.
        return True, "诊断已复制到剪贴板。"
    finally:
        if handle: kernel32.GlobalFree(handle)
        user32.CloseClipboard()

def save_diagnostics():
    buf = ctypes.create_unicode_buffer(32768)
    ofn = OPENFILENAMEW()
    ofn.lStructSize = ctypes.sizeof(ofn); ofn.hwndOwner = main_hwnd
    ofn.lpstrFilter = "文本文件 (*.txt)\0*.txt\0所有文件 (*.*)\0*.*\0\0"
    ofn.lpstrFile = buf; ofn.nMaxFile = len(buf); ofn.lpstrTitle = "保存 PadNote 诊断"
    ofn.lpstrDefExt = "txt"; ofn.Flags = 0x00000002 | 0x00000800
    if comdlg32.GetSaveFileNameW(ctypes.byref(ofn)):
        try:
            Path(buf.value).write_text(diagnostics_text(), encoding="utf-8")
            status_text("脱敏诊断已保存。")
        except OSError as exc:
            user32.MessageBoxW(main_hwnd, f"无法保存诊断：{exc}", "PadNote", 0x10)

def open_log_folder():
    global last_error
    try:
        data_dir().mkdir(parents=True, exist_ok=True)
        result = shell32.ShellExecuteW(main_hwnd, "open", str(data_dir()), None, None, 1)
        if result <= 32: raise OSError(f"Windows ShellExecuteW failed ({result})")
    except OSError as exc:
        last_error = f"{type(exc).__name__}: {exc}。请使用保存诊断功能选择其他位置。"
        status_text("无法打开日志目录。诊断仍可在此查看或另存。")
        refresh_diagnostics(force=True)

def enable_private_lan_rule():
    powershell = str(Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe")
    command = ("$n='PadNote Connection Assistant (8767)'; if (-not (Get-NetFirewallRule -DisplayName $n -ErrorAction SilentlyContinue)) "
               "{ New-NetFirewallRule -DisplayName $n -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8767 -Profile Private -RemoteAddress LocalSubnet | Out-Null }")
    result = shell32.ShellExecuteW(main_hwnd, "runas", powershell, f'-NoProfile -WindowStyle Hidden -Command "{command}"', None, 0)
    status_text("Windows 将询问是否仅允许专用网络和本地子网访问 TCP 8767。" if result > 32 else "无法打开防火墙授权。")

def apply_dpi_fonts():
    for hwnd, (size, weight, face) in control_font_sizes.items():
        new_font = gdi32.CreateFontW(scale(-size), 0, 0, 0, weight, 0, 0, 0, 1, 0, 0, 5, 0, face)
        if new_font:
            old_font = control_fonts.get(hwnd)
            user32.SendMessageW(hwnd, 0x30, new_font, 1)
            control_fonts[hwnd] = new_font
            font_handles.append(new_font)
            if old_font:
                gdi32.DeleteObject(old_font)
                try: font_handles.remove(old_font)
                except ValueError: pass

def resize_layout(hwnd, width, height):
    bottom = height - scale(48)
    layout = onboarding_layout(width * 96 // dpi, height * 96 // dpi)
    layout_handles = {
        tutorial_heading_hwnd: "tutorial_heading", tutorial_body_hwnd: "tutorial_body",
        hermes_info_hwnd: "hermes_info", path_hwnd: "log_path",
        diagnostics_heading_hwnd: "diagnostics_heading", details_hwnd: "details",
    }
    footer = {find_control(BUTTON_COPY), find_control(BUTTON_SAVE),
              find_control(BUTTON_FOLDER), find_control(BUTTON_FIREWALL)}
    for handle, x, y, w, h in controls:
        role = control_roles.get(handle)
        if handle in layout_handles:
            x, y, w, h = layout[layout_handles[handle]]
            user32.SetWindowPos(handle, None, scale(x), scale(y), scale(w), scale(h), 0x14)
            continue
        if role in ("header", "header-title") and y < scale(80):
            width_for_control = max(scale(340), width - scale(x))
            user32.SetWindowPos(handle, None, scale(x), scale(y), width_for_control, scale(h), 0x14)
        elif handle in footer:
            user32.SetWindowPos(handle, None, scale(x), bottom, scale(w), scale(h), 0x14)
        elif role in ("status", "body", "muted"):
            user32.SetWindowPos(handle, None, scale(x), scale(y), max(scale(140), width-scale(x)-scale(26)), scale(h), 0x14)

def wndproc(hwnd, message, wparam, lparam):
    global main_hwnd, status_hwnd, details_hwnd, path_hwnd, dpi, closing, last_error, session_log, previous_log, session_offset, hermes_pending, hermes_pending_setup, hermes_info_hwnd, tutorial_heading_hwnd, tutorial_body_hwnd, diagnostics_heading_hwnd, hermes_cancel_event
    if message == WM_CREATE:
        main_hwnd = hwnd
        if get_dpi_for_window:
            try: dpi = int(get_dpi_for_window(hwnd)) or dpi
            except OSError: pass
        if not user32.SetPropW(hwnd, "PadNoteConnectionAssistantOwnedWindow", ctypes.c_void_p(1)):
            return -1
        session_log, previous_log = load_saved_logs(data_dir() / "launcher.log")
        startup_error = parse_startup_error(session_log, ready=LOG_MARKER in session_log)
        saved_code = parse_exit_code(session_log)
        saved_title = None
        if startup_error:
            saved_title, saved_action = explain_exit(saved_code if saved_code is not None else -1,
                                                    was_ready=False, startup_error=startup_error)
            last_error = saved_action
        elif saved_code not in (None, 0):
            saved_title, last_error = explain_exit(saved_code, was_ready=LOG_MARKER in session_log)
        elif session_log.strip():
            saved_title = "已找到上次运行日志，请查看诊断。"
        try: session_offset = (data_dir() / "launcher.log").stat().st_size
        except OSError: session_offset = 0
        brushes.update(ivory=gdi32.CreateSolidBrush(IVORY), navy=gdi32.CreateSolidBrush(NAVY),
                       blue=gdi32.CreateSolidBrush(BLUE), pale=gdi32.CreateSolidBrush(PALE_BLUE),
                       white=gdi32.CreateSolidBrush(WHITE), muted=gdi32.CreateSolidBrush(rgb(232, 232, 232)))
        control(hwnd, "", 0, 0, 820, 78, role="header", font_size=15)
        control(hwnd, "PadNote 连接助手", 26, 15, 400, 38, role="header-title", font_size=27)
        control(hwnd, "本机运行 · TLS 配对 · 同一 Wi-Fi 连接", 28, 52, 560, 22, role="header", font_size=14)
        saved_code_label = str(saved_code) if saved_code is not None else "未记录"
        initial_status = (f"上次运行信息：{saved_title}（退出代码 {saved_code_label}）。请查看诊断。"
                          if saved_title else "已停止。准备好配对平板时，请点击启动。")
        status_hwnd = control(hwnd, initial_status, 26, 100, 760, 68, style=0x2000, role="status", font_size=17)
        control(hwnd, "启动助手", 26, 184, 126, 40, BUTTON_START, role="secondary")
        control(hwnd, "打开连接设置", 164, 184, 150, 40, BUTTON_OPEN, role="secondary")
        control(hwnd, "停止助手", 326, 184, 126, 40, BUTTON_STOP, role="secondary")
        control(hwnd, "一键连接 Hermes", 26, 238, 240, 46, BUTTON_HERMES_CONNECT, role="primary", font_size=17)
        control(hwnd, "安装 / 配置 Hermes…", 276, 238, 160, 46, BUTTON_HERMES_INSTALL, role="secondary", font_size=13)
        control(hwnd, "取消连接", 446, 238, 112, 46, BUTTON_HERMES_CANCEL, role="secondary")
        tutorial_heading_hwnd = control(hwnd, "连接小教程", 470, 188, 300, 28, role="section", font_size=17)
        tutorial_body_hwnd = control(hwnd, "一键连接会启动助手并检查 Hermes。需要时打开配置向导并完成模型登录。随后打开连接设置，同一 Wi-Fi 扫码并批准。Tailscale 不自动改 Serve、防火墙或网络类别。", 470, 220, 310, 118, role="body", font_size=13)
        hermes_info_hwnd = control(hwnd, "Hermes：尚未检查。模型登录由用户完成。", 28, 296, 420, 30, role="muted", font_size=13)
        path_hwnd = control(hwnd, str(data_dir() / "launcher.log"), 28, 326, 420, 24, style=ES_READONLY | 0x0080, role="muted", font_size=12)
        diagnostics_heading_hwnd = control(hwnd, "诊断信息", 26, 354, 170, 28, role="section", font_size=17)
        details_hwnd = control(hwnd, "", 26, 386, 760, 226, style=ES_MULTILINE | ES_READONLY | ES_AUTOVSCROLL | WS_VSCROLL, role="edit")
        control(hwnd, "复制诊断", 26, 628, 126, 38, BUTTON_COPY, role="secondary")
        control(hwnd, "保存诊断文本…", 164, 628, 154, 38, BUTTON_SAVE, role="secondary")
        control(hwnd, "打开日志目录", 330, 628, 142, 38, BUTTON_FOLDER, role="secondary")
        control(hwnd, "允许同 Wi-Fi 连接", 490, 628, 138, 38, BUTTON_FIREWALL, role="secondary", font_size=13)
        user32.SendMessageW(details_hwnd, 0x00C5, MAX_DIAGNOSTIC_CHARS * 2, 0)  # EM_SETLIMITTEXT
        user32.SetTimer(hwnd, 1, 500, None)
        update_buttons(); refresh_diagnostics(force=True)
        return 0
    if message == 0x14:  # WM_ERASEBKGND
        rect = wintypes.RECT()
        user32.GetClientRect(hwnd, ctypes.byref(rect))
        user32.FillRect(wparam, ctypes.byref(rect), brushes.get("ivory", 0))
        return 1
    if message == WM_CTLCOLORSTATIC:
        target = lparam
        role = control_roles.get(target)
        hdc = wparam
        if role in ("header", "header-title"):
            gdi32.SetTextColor(hdc, WHITE); gdi32.SetBkColor(hdc, NAVY); return brushes["navy"]
        if role == "status":
            gdi32.SetTextColor(hdc, NAVY); gdi32.SetBkColor(hdc, PALE_BLUE); return brushes["pale"]
        if role in ("title", "section"):
            gdi32.SetTextColor(hdc, NAVY); gdi32.SetBkColor(hdc, IVORY); return brushes["ivory"]
        if role == "muted":
            gdi32.SetTextColor(hdc, rgb(91, 105, 120)); gdi32.SetBkColor(hdc, IVORY); return brushes["ivory"]
        gdi32.SetTextColor(hdc, NAVY); gdi32.SetBkColor(hdc, IVORY); return brushes["ivory"]
    if message == WM_CTLCOLOREDIT:
        gdi32.SetTextColor(wparam, NAVY); gdi32.SetBkColor(wparam, WHITE); return brushes.get("white", 0)
    if message == WM_DRAWITEM:
        item = ctypes.cast(lparam, ctypes.POINTER(DRAWITEMSTRUCT)).contents
        role = control_roles.get(item.hwndItem, "secondary")
        active = bool(item.itemState & 0x11)
        disabled = bool(item.itemState & 0x0004)
        brush = brushes.get("muted" if disabled else "navy" if role == "primary" else "pale")
        user32.FillRect(item.hDC, ctypes.byref(item.rcItem), brush)
        font = control_fonts.get(item.hwndItem)
        old_font = gdi32.SelectObject(item.hDC, font) if font else None
        gdi32.SetTextColor(item.hDC, rgb(80, 87, 96) if disabled else WHITE if role == "primary" else NAVY); gdi32.SetBkMode(item.hDC, 1)
        user32.DrawTextW(item.hDC, user32_get_text(item.hwndItem), -1, ctypes.byref(item.rcItem), DT_SINGLELINE | DT_VCENTER | DT_CENTER)
        if active: user32.DrawFocusRect(item.hDC, ctypes.byref(item.rcItem))
        if old_font: gdi32.SelectObject(item.hDC, old_font)
        return 1
    if message == WM_COMMAND:
        ident = wparam & 0xffff
        if ident == BUTTON_START: start_service()
        elif ident == BUTTON_HERMES_CONNECT:
            if lifecycle.ready: start_hermes_action()
            elif lifecycle.can_start:
                hermes_pending = True
                hermes_pending_setup = False
                start_service()
            else: status_text("正在等待连接助手就绪；就绪后会自动检查 Hermes。")
        elif ident == BUTTON_HERMES_INSTALL:
            if lifecycle.ready: start_hermes_action(setup=True)
            elif lifecycle.can_start:
                hermes_pending = True
                hermes_pending_setup = True
                start_service()
            else: status_text("连接助手就绪后，请再次点击 Hermes 配置按钮。")
        elif ident == BUTTON_HERMES_CANCEL:
            if hermes_busy and hermes_cancel_event:
                hermes_cancel_event.set()
                status_text("已请求取消本机检查；若请求已发送，后台最多还需等待超时。")
                update_buttons()
        elif ident == BUTTON_STOP: stop_async()
        elif ident == BUTTON_OPEN:
            process = lifecycle.process
            if lifecycle.can_open and process is not None and process.poll() is None:
                webbrowser.open("http://127.0.0.1:8766/")
            else:
                update_status()
        elif ident == BUTTON_COPY:
            ok, text = set_clipboard_text(diagnostics_text())
            status_text(text)
        elif ident == BUTTON_SAVE: save_diagnostics()
        elif ident == BUTTON_FOLDER: open_log_folder()
        elif ident == BUTTON_FIREWALL: enable_private_lan_rule()
        elif ident == BUTTON_DETAILS: user32.SetFocus(details_hwnd)
        return 0
    if message == WM_TIMER:
        update_status()
        if closing and lifecycle.process is None and stop_worker is None:
            user32.DestroyWindow(hwnd)
        return 0
    if message == WM_SIZE:
        resize_layout(hwnd, lparam & 0xffff, (lparam >> 16) & 0xffff)
        return 0
    if message == WM_GETMINMAXINFO:
        info = ctypes.cast(lparam, ctypes.POINTER(MINMAXINFO)).contents
        info.ptMinTrackSize.x, info.ptMinTrackSize.y = scale(680), scale(590)
        return 0
    if message == WM_DPICHANGED:
        dpi = int(wparam & 0xffff) or 96
        rect = ctypes.cast(lparam, ctypes.POINTER(wintypes.RECT)).contents
        user32.SetWindowPos(hwnd, None, rect.left, rect.top, rect.right-rect.left, rect.bottom-rect.top, 0x14)
        for handle, x, y, w, h in controls:
            user32.SetWindowPos(handle, None, scale(x), scale(y), scale(w), scale(h), 0x14)
        apply_dpi_fonts()
        client = wintypes.RECT()
        user32.GetClientRect(hwnd, ctypes.byref(client))
        resize_layout(hwnd, client.right-client.left, client.bottom-client.top)
        return 0
    if message == WM_CLOSE:
        closing = True
        if hermes_cancel_event: hermes_cancel_event.set()
        lifecycle.closing = True
        update_buttons()
        if lifecycle.process is not None and not lifecycle.stopping: stop_async()
        elif lifecycle.process is None: user32.DestroyWindow(hwnd)
        return 0
    if message == WM_DESTROY:
        user32.KillTimer(hwnd, 1)
        user32.RemovePropW(hwnd, "PadNoteConnectionAssistantOwnedWindow")
        for handle in set(font_handles): gdi32.DeleteObject(handle)
        for brush in brushes.values():
            if brush: gdi32.DeleteObject(brush)
        user32.PostQuitMessage(0)
        return 0
    return user32.DefWindowProcW(hwnd, message, wparam, lparam)

def user32_get_text(hwnd):
    length = user32.SendMessageW(hwnd, 0x000E, 0, 0)
    buffer = ctypes.create_unicode_buffer(max(1, length + 1))
    user32.SendMessageW(hwnd, 0x000D, len(buffer), ctypes.addressof(buffer))
    return buffer.value

def main():
    global mutex, dpi, window_width, window_height
    try: user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
    except (AttributeError, OSError): pass
    dpi = int(get_dpi_for_system()) if get_dpi_for_system else 96
    ctypes.set_last_error(0)
    mutex = kernel32.CreateMutexW(None, False, MUTEX_NAME)
    if not mutex: return 1
    if ctypes.get_last_error() == 183:
        existing = user32.FindWindowW(WINDOW_CLASS, "PadNote Connection Assistant")
        if existing: user32.ShowWindow(existing, 5); user32.SetForegroundWindow(existing)
        kernel32.CloseHandle(mutex)
        return 0
    work = wintypes.RECT()
    if not user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(work), 0):
        work = wintypes.RECT(0, 0, int(user32.GetSystemMetrics(0)), int(user32.GetSystemMetrics(1)))
    work_width = max(680, (work.right - work.left) * 96 // dpi - 32)
    work_height = max(590, (work.bottom - work.top) * 96 // dpi - 32)
    window_width, window_height = min(820, work_width), min(730, work_height)
    width_px, height_px = scale(window_width), scale(window_height)
    x = work.left + max(0, (work.right-work.left-width_px)//2)
    y = work.top + max(0, (work.bottom-work.top-height_px)//2)
    instance = kernel32.GetModuleHandleW(None)
    cursor = user32.LoadCursorW(None, ctypes.cast(ctypes.c_void_p(32512), wintypes.LPCWSTR))
    callback = WNDPROC(wndproc)
    icon_path = str(Path(__file__).resolve().parent / "PadNote.ico")
    icon = user32.LoadImageW(None, icon_path, 1, scale(32), scale(32), 0x10)
    wc = WNDCLASSW(0, callback, 0, 0, instance, icon, cursor, None, None, WINDOW_CLASS)
    if not user32.RegisterClassW(ctypes.byref(wc)): return 1
    hwnd = user32.CreateWindowExW(0, WINDOW_CLASS, "PadNote Connection Assistant", WS_OVERLAPPEDWINDOW,
                                  x, y, width_px, height_px, None, None, instance, None)
    if not hwnd: return 1
    user32.ShowWindow(hwnd, 5)
    message = wintypes.MSG()
    while user32.GetMessageW(ctypes.byref(message), None, 0, 0) > 0:
        if user32.IsDialogMessageW(hwnd, ctypes.byref(message)): continue
        user32.TranslateMessage(ctypes.byref(message)); user32.DispatchMessageW(ctypes.byref(message))
    if mutex: kernel32.CloseHandle(mutex)
    return int(message.wParam)

if __name__ == "__main__": raise SystemExit(main())
