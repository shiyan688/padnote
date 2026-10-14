"""Small native Windows status window for the bundled connection assistant."""
from __future__ import annotations

import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import secrets
import subprocess
import sys
import webbrowser

if os.name != "nt":
    raise SystemExit("The PadNote graphical launcher runs on Windows.")

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
WNDPROC = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, wintypes.HWND, wintypes.UINT,
                             wintypes.WPARAM, wintypes.LPARAM)

class WNDCLASSW(ctypes.Structure):
    _fields_ = [("style", wintypes.UINT), ("lpfnWndProc", WNDPROC),
                ("cbClsExtra", ctypes.c_int), ("cbWndExtra", ctypes.c_int),
                ("hInstance", wintypes.HINSTANCE), ("hIcon", wintypes.HICON),
                ("hCursor", wintypes.HANDLE), ("hbrBackground", wintypes.HANDLE),
                ("lpszMenuName", wintypes.LPCWSTR), ("lpszClassName", wintypes.LPCWSTR)]

WNDPROC_CALL = 0x0080
WS_OVERLAPPEDWINDOW = 0x00CF0000
WS_VISIBLE = 0x10000000
WS_CHILD = 0x40000000
WS_TABSTOP = 0x00010000
BS_PUSHBUTTON = 0
WM_DESTROY, WM_COMMAND, WM_TIMER = 0x0002, 0x0111, 0x0113
WM_DPICHANGED = 0x02E0
SW_SHOW = 5
BUTTON_START, BUTTON_OPEN, BUTTON_STOP = 101, 102, 103
BUTTON_FIREWALL = 104
WINDOW_CLASS = "PadNoteConnectionAssistantWindow"
MUTEX_NAME = "Local\\PadNoteConnectionAssistantLauncher"

main_hwnd = None
status_hwnd = None
child: subprocess.Popen | None = None
mutex = None
controls = []
dpi = 96
log_stream = None
service_ready = False
shutdown_event = None
shutdown_name = None
LOG_MARKER = "PadNote management UI: http://127.0.0.1:8766"

# HWND and HANDLE are pointer-sized on x64; declare all native signatures so
# ctypes cannot truncate them to its default 32-bit integer type.
user32.CreateWindowExW.restype = wintypes.HWND
user32.CreateWindowExW.argtypes = [wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR,
    wintypes.DWORD, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
    wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, wintypes.LPVOID]
user32.DefWindowProcW.restype = ctypes.c_ssize_t
user32.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.RegisterClassW.restype = wintypes.ATOM
user32.RegisterClassW.argtypes = [ctypes.POINTER(WNDCLASSW)]
user32.FindWindowW.restype = wintypes.HWND
user32.FindWindowW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR]
user32.SetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPCWSTR]
user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
user32.SetForegroundWindow.argtypes = [wintypes.HWND]
user32.UpdateWindow.argtypes = [wintypes.HWND]
user32.SetPropW.argtypes = [wintypes.HWND, wintypes.LPCWSTR, wintypes.HANDLE]
user32.SetPropW.restype = wintypes.BOOL
user32.RemovePropW.argtypes = [wintypes.HWND, wintypes.LPCWSTR]
user32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int,
                                ctypes.c_int, ctypes.c_int, wintypes.UINT]
try:
    get_dpi_for_system = user32.GetDpiForSystem
    get_dpi_for_system.restype = wintypes.UINT
except AttributeError:
    get_dpi_for_system = None
try:
    get_dpi_for_window = user32.GetDpiForWindow
    get_dpi_for_window.argtypes = [wintypes.HWND]
    get_dpi_for_window.restype = wintypes.UINT
except AttributeError:
    get_dpi_for_window = None
user32.IsDialogMessageW.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.MSG)]
user32.IsDialogMessageW.restype = wintypes.BOOL
user32.LoadCursorW.restype = wintypes.HANDLE
user32.LoadCursorW.argtypes = [wintypes.HINSTANCE, wintypes.LPCWSTR]
user32.SetTimer.argtypes = [wintypes.HWND, ctypes.c_size_t, wintypes.UINT, wintypes.LPVOID]
user32.KillTimer.argtypes = [wintypes.HWND, ctypes.c_size_t]
user32.GetMessageW.restype = ctypes.c_int
user32.GetMessageW.argtypes = [ctypes.POINTER(wintypes.MSG), wintypes.HWND, wintypes.UINT, wintypes.UINT]
user32.TranslateMessage.argtypes = [ctypes.POINTER(wintypes.MSG)]
user32.DispatchMessageW.argtypes = [ctypes.POINTER(wintypes.MSG)]
user32.PostQuitMessage.argtypes = [ctypes.c_int]
kernel32.CreateMutexW.restype = wintypes.HANDLE
kernel32.CreateMutexW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR]
kernel32.CreateEventW.restype = wintypes.HANDLE
kernel32.CreateEventW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR]
kernel32.SetEvent.argtypes = [wintypes.HANDLE]
kernel32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
kernel32.WaitForSingleObject.restype = wintypes.DWORD
kernel32.GetModuleHandleW.restype = wintypes.HINSTANCE
kernel32.GetModuleHandleW.argtypes = [wintypes.LPCWSTR]
kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
shell32 = ctypes.WinDLL("shell32", use_last_error=True)
shell32.ShellExecuteW.restype = ctypes.c_ssize_t
shell32.ShellExecuteW.argtypes = [wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR,
                                  wintypes.LPCWSTR, wintypes.LPCWSTR, ctypes.c_int]

def text_control(parent, text, x, y, width, height, *, button_id=None):
    style = WS_CHILD | WS_VISIBLE | WS_TABSTOP
    cls = "STATIC"
    if button_id is not None:
        cls, style = "BUTTON", style | BS_PUSHBUTTON
    handle = user32.CreateWindowExW(0, cls, text, style, scale(x), scale(y), scale(width), scale(height),
                                    parent, button_id, kernel32.GetModuleHandleW(None), None)
    controls.append((handle, x, y, width, height))
    return handle

def scale(value: int) -> int:
    return (value * dpi + 48) // 96

def layout_controls() -> None:
    for handle, x, y, width, height in controls:
        user32.SetWindowPos(handle, None, scale(x), scale(y), scale(width), scale(height), 0x0014)

def system_dpi() -> int:
    return int(get_dpi_for_system()) if get_dpi_for_system else 96

def window_dpi(hwnd) -> int:
    return int(get_dpi_for_window(hwnd)) if get_dpi_for_window else dpi

def set_status(value: str) -> None:
    if status_hwnd:
        user32.SetWindowTextW(status_hwnd, value)

def start_service() -> None:
    global child, log_stream, service_ready, shutdown_event, shutdown_name
    if child is not None and child.poll() is None:
        set_status("运行中，管理页面已就绪。" if service_ready else "正在启动，等待本机管理服务就绪…")
        return
    root = Path(__file__).resolve().parent
    # Match the established state location so installing this Windows build
    # does not silently orphan an existing Windows-side pairing and credential store.
    data = Path.home() / ".local" / "share" / "padnote-connection-assistant"
    try:
        data.mkdir(parents=True, exist_ok=True)
        executable = Path(sys.executable).with_name("pythonw.exe")
        log_stream = (data / "launcher.log").open("w", encoding="utf-8", buffering=1)
        service_ready = False
        shutdown_name = "Local\\PadNoteConnectionAssistantShutdown-" + secrets.token_hex(16)
        shutdown_event = kernel32.CreateEventW(None, True, False, shutdown_name)
        if not shutdown_event:
            raise OSError(ctypes.get_last_error(), "could not create the private shutdown event")
        environment = os.environ.copy()
        environment["PYTHONDONTWRITEBYTECODE"] = "1"
        child = subprocess.Popen([str(executable), str(root / "start.py"), "--lan",
                                  "--no-browser", "--state-dir", str(data),
                                  "--shutdown-event", shutdown_name],
                                 cwd=str(root), env=environment,
                                 creationflags=subprocess.CREATE_NO_WINDOW,
                                 stdin=subprocess.DEVNULL, stdout=log_stream,
                                 stderr=subprocess.STDOUT, close_fds=True)
        set_status("正在启动，等待本机管理服务就绪…")
    except OSError as error:
        if shutdown_event:
            kernel32.CloseHandle(shutdown_event)
            shutdown_event = None
        if log_stream:
            log_stream.close()
            log_stream = None
        set_status(f"无法启动连接助手：{error}")

def stop_service() -> None:
    global child, log_stream, service_ready, shutdown_event, shutdown_name
    if child is None or child.poll() is not None:
        child = None
        service_ready = False
        if shutdown_event:
            kernel32.CloseHandle(shutdown_event)
            shutdown_event = None
        shutdown_name = None
        if log_stream:
            log_stream.close()
            log_stream = None
        set_status("已停止。")
        return
    graceful = False
    set_status("正在请求助手正常关闭并释放服务资源；运行中的 Agent 请求可能会被中断。")
    if main_hwnd:
        user32.UpdateWindow(main_hwnd)
    if shutdown_event:
        kernel32.SetEvent(shutdown_event)
    try:
        child.wait(timeout=8)
        graceful = True
    except subprocess.TimeoutExpired:
        # Last resort is limited to this Popen handle owned by this launcher.
        child.kill()
        child.wait(timeout=5)
    child = None
    service_ready = False
    if shutdown_event:
        kernel32.CloseHandle(shutdown_event)
        shutdown_event = None
    shutdown_name = None
    if log_stream:
        log_stream.close()
        log_stream = None
    set_status("已正常停止。" if graceful else "正常停止超时，已结束连接助手进程。")

def update_status() -> None:
    global child, log_stream, service_ready, shutdown_event, shutdown_name
    if child is not None:
        code = child.poll()
        if code is not None:
            was_ready = service_ready
            if log_stream:
                log_stream.close()
                log_stream = None
            child = None
            service_ready = False
            if shutdown_event:
                kernel32.CloseHandle(shutdown_event)
                shutdown_event = None
            shutdown_name = None
            if not was_ready:
                set_status("连接助手在启动期间退出，请查看 launcher.log。")
            else:
                set_status(f"连接助手已退出（代码 {code}）。如端口被占用，请关闭占用程序后重试。")
        elif log_stream:
            log_stream.flush()
            try:
                with Path(log_stream.name).open("r", encoding="utf-8", errors="replace") as stream:
                    service_ready = LOG_MARKER in stream.read()
                if service_ready:
                    set_status("运行中，本机管理页面已就绪。")
            except OSError:
                pass

def enable_private_lan_rule() -> None:
    """Offer an explicit UAC-elevated, Private + LocalSubnet-only rule."""
    powershell = str(Path(os.environ.get("SystemRoot", r"C:\Windows")) /
                     "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe")
    command = ("$n='PadNote Connection Assistant (8767)'; "
               "if (-not (Get-NetFirewallRule -DisplayName $n -ErrorAction SilentlyContinue)) "
               "{ New-NetFirewallRule -DisplayName $n -Direction Inbound -Action Allow "
               "-Protocol TCP -LocalPort 8767 -Profile Private -RemoteAddress LocalSubnet | Out-Null }")
    params = f'-NoProfile -WindowStyle Hidden -Command "{command}"'
    result = shell32.ShellExecuteW(main_hwnd, "runas", powershell, params, None, 0)
    if result <= 32:
        set_status("未能启动防火墙设置。局域网连接可能需要专用网络规则。")
    else:
        set_status("请在 Windows 用户账户控制提示中确认：仅允许专用网络、本地子网访问 TCP 8767。")

def wndproc(hwnd, message, wparam, lparam):
    global main_hwnd, status_hwnd, dpi
    if message == 0x0001:  # WM_CREATE
        main_hwnd = hwnd
        if not user32.SetPropW(hwnd, "PadNoteConnectionAssistantOwnedWindow", ctypes.c_void_p(1)):
            return -1
        text_control(hwnd, "PadNote 连接助手在本机运行，通过 TLS 与同一 Wi-Fi 下的平板配对。防火墙授权为可选。",
                     18, 14, 460, 30)
        status_hwnd = text_control(hwnd, "已停止。", 18, 48, 460, 52)
        text_control(hwnd, "启动", 18, 112, 112, 32, button_id=BUTTON_START)
        text_control(hwnd, "打开管理页面", 148, 112, 170, 32, button_id=BUTTON_OPEN)
        text_control(hwnd, "停止", 336, 112, 92, 32, button_id=BUTTON_STOP)
        text_control(hwnd, "允许同一 Wi-Fi 设备连接（专用网络、本地子网、TCP 8767）",
                     18, 158, 460, 28, button_id=BUTTON_FIREWALL)
        user32.SetTimer(hwnd, 1, 1000, None)
        return 0
    if message == WM_COMMAND:
        command = wparam & 0xFFFF
        if command == BUTTON_START:
            start_service()
        elif command == BUTTON_OPEN:
            if service_ready and child is not None and child.poll() is None:
                webbrowser.open("http://127.0.0.1:8766/")
            else:
                set_status("请先启动连接助手，并等待管理页面就绪。")
        elif command == BUTTON_STOP:
            stop_service()
        elif command == BUTTON_FIREWALL:
            enable_private_lan_rule()
        return 0
    if message == WM_TIMER:
        update_status()
        return 0
    if message == WM_DPICHANGED:
        dpi = int(wparam & 0xFFFF) or window_dpi(hwnd) or 96
        suggested = ctypes.cast(lparam, ctypes.POINTER(wintypes.RECT)).contents
        user32.SetWindowPos(hwnd, None, suggested.left, suggested.top,
                            suggested.right - suggested.left, suggested.bottom - suggested.top,
                            0x0014)
        layout_controls()
        return 0
    if message == WM_DESTROY:
        user32.KillTimer(hwnd, 1)
        stop_service()
        user32.RemovePropW(hwnd, "PadNoteConnectionAssistantOwnedWindow")
        user32.PostQuitMessage(0)
        return 0
    return user32.DefWindowProcW(hwnd, message, wparam, lparam)

def main() -> int:
    global mutex, dpi
    try:
        user32.SetProcessDpiAwarenessContext.argtypes = [ctypes.c_void_p]
        user32.SetProcessDpiAwarenessContext.restype = wintypes.BOOL
        user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
    except (AttributeError, OSError):
        pass
    dpi = system_dpi() or 96
    ctypes.set_last_error(0)
    mutex = kernel32.CreateMutexW(None, False, MUTEX_NAME)
    if not mutex:
        user32.MessageBoxW(None, "Could not create the launcher instance lock.", "PadNote", 0x10)
        return 1
    if ctypes.get_last_error() == 183:  # ERROR_ALREADY_EXISTS
        existing = user32.FindWindowW(WINDOW_CLASS, "PadNote Connection Assistant")
        if existing:
            user32.ShowWindow(existing, SW_SHOW)
            user32.SetForegroundWindow(existing)
        return 0
    instance = kernel32.GetModuleHandleW(None)
    cursor = user32.LoadCursorW(None, ctypes.cast(ctypes.c_void_p(32512), wintypes.LPCWSTR))
    callback = WNDPROC(wndproc)
    wc = WNDCLASSW(0, callback, 0, 0, instance, None, cursor,
                   ctypes.c_void_p(6), None, WINDOW_CLASS)
    if not user32.RegisterClassW(ctypes.byref(wc)):
        user32.MessageBoxW(None, "Could not initialize the PadNote window.", "PadNote", 0x10)
        return 1
    hwnd = user32.CreateWindowExW(0, WINDOW_CLASS, "PadNote Connection Assistant",
                                  WS_OVERLAPPEDWINDOW, scale(100), scale(100), scale(510), scale(250),
                                  None, None, instance, None)
    if not hwnd:
        user32.MessageBoxW(None, "Could not open the PadNote window.", "PadNote", 0x10)
        return 1
    user32.ShowWindow(hwnd, SW_SHOW)
    message = wintypes.MSG()
    while user32.GetMessageW(ctypes.byref(message), None, 0, 0) > 0:
        if user32.IsDialogMessageW(hwnd, ctypes.byref(message)):
            continue
        user32.TranslateMessage(ctypes.byref(message))
        user32.DispatchMessageW(ctypes.byref(message))
    kernel32.CloseHandle(mutex)
    return int(message.wParam)

if __name__ == "__main__":
    raise SystemExit(main())
