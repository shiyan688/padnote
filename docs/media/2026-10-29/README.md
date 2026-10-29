# 界面演示素材 — 2026-10-29

`beta28-desktop-ui.png` 是 beta.28 电脑助手浏览器管理页的合成界面预览。使用候选的真实 HTML 渲染器、CSS、JavaScript 和现有二维码编码器，在全新 Chrome 上下文生成；视口为 1440 × 900，保存完整页面。不是 Windows 原生启动器截图，也不是 Agent 执行过程。

Agent、设备、地址、时间、申请和状态均为合成夹具。二维码只包含保留域 `demo.invalid` 和演示字段，不可用于连接实际助手。有效期由预览时钟设为五分钟，不改变产品的配对规则。没有使用私人笔记、真实配对令牌或用户账户。

验证覆盖四种窗口宽度、主操作可见性、键盘跳转、二维码比例，以及检测结果经过 61 秒后转为过期的界面变化。外部请求和 POST 请求均被阻止。其他目录中的旧版本演示保留其原始来源说明。

The image is a synthetic preview of the beta.28 browser management UI, rendered with the product UI code. It is not a native Windows capture or a recording of Agent execution. All connection and device data are fabricated; the QR payload uses the reserved `demo.invalid` domain.

## Windows 原生窗口

`beta28-windows-launcher.png` 来自 Windows 11 上实际运行的 Win32 启动器，使用本版便携包的 CPython 3.13.15、完整候选源码和隔离合成配置。图中“已就绪”为合成生命周期状态，没有启动助手服务或联系真实 Agent。测试按钮操作被拦截；独立窗口在八秒后自动关闭。

使用窗口自身的 WM_PRINT / PrintWindow 渲染到内存位图，没有截取其他桌面内容。原始 BMP 无损转换为 PNG，没有重绘或修改界面像素。日志显示路径为明确的演示路径；真实用户日志和配置未读入。验证覆盖停止与就绪两个窗口、96 DPI，以及短窗口中下方诊断按钮获得焦点后进入可见区域。安装、升级、卸载与其他 DPI 的实机检查不在这份演示的证明范围内。

The Windows image is an actual Win32 window rendering with synthetic state, captured from its own HWND and losslessly converted from BMP to PNG. It does not show a real Agent connection or installer run.
