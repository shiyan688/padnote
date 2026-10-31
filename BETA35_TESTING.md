# 从示例笔记开始

Android 和 Windows 连接助手本轮更新至 beta.35；iPad 提供对应源码。

## 先在纸面上试一遍

在书架选择“打开示例笔记”。这是可以编辑的本地笔记，介绍书写、圈选提问、PDF 分享和电脑任务。打开示例不会发送模型或 Agent 请求。再次打开会继续使用已保存的示例，保留你的修改；如果已经删除，下次会新建一份。

## 选择希望得到的回答

从笔记进入“交给 Agent”，可以选择“简短批注”“完整推导”或“复习卡片”。每种预设都会填入可以修改的任务说明；确认发送前，检查接收 Agent、材料与说明。预设不指定模型供应商或配音工具。

## 发送以后继续写

任务保留在“电脑任务”中，关闭详情后可以继续使用笔记。发送准备、上传、电脑接收与执行是不同阶段；尚未取得接收确认时，应在原任务中查询或使用同一任务编号恢复，避免另建重复任务。离开详情不等于停止电脑上的执行。

Android 将文字结果保留在详情主层，连接身份和技术记录可以按需展开。iPad 也会说明当前等待的含义，错误详情可展开查看。遇到需要批准的操作，使用可用的审批入口；信息不足时到电脑上处理。

## 让结果回到笔记

完成的文字结果可以先预览再保存。Android 预览会显示目标笔记和新增内容的起始页，也可选择另存为新笔记；默认仍追加到目标笔记末尾。iPad 可选择目标笔记或新笔记，保存后打开结果时只有一个返回入口，并保留编辑器的保存检查。

相同结果的去重、来源信息和保存冲突保护沿用格式 9。交换含结果的 `.padnote` 文件时，接收端需要支持格式 9；PDF 分享不受影响。当前未提供任意中间页插入或自动覆盖原文字的操作。

## 电脑助手的四个步骤

1. 选择 Agent，检测接口。
2. 选择电脑网络地址，准备配对码。
3. 平板扫码，回电脑核对并批准。
4. 从平板明确发送第一条文字任务，再查看结果。

页面导航只带你到对应操作，不会自动发送任务。配置存在不代表接口当前可用；检测过期时需要重新检查。首次测试可使用“只回复连接成功，不要操作文件”。

## 本轮验证记录

- Android：422 项单元测试、6 项隔离模拟器原生测试通过；lint 0 个错误，四种架构构建完成。
- iPad：324 项单元测试、2 项模拟器界面测试通过，覆盖结果保存、返回、重开、去重与冲突。
- 电脑助手：461 项自动回归中 455 项通过，6 项平台专属用例跳过；三组浏览器导航与恢复检查通过。

界面测试使用合成任务与材料。安装包、源码与校验文件见 [GitHub Releases](https://github.com/shiyan688/padnote/releases)。

# Getting started with the sample note

Android and the Windows Connection Assistant update to beta.35. The corresponding iPad source is included.

Choose **Open sample note** on the bookshelf. It creates an editable local guide without contacting a model or Agent. Opening it again keeps your changes; deleting it allows a fresh sample to be created next time.

Paper tasks now offer **Brief annotation**, **Full derivation**, and **Review cards**. These fill in editable instructions without choosing a provider. Review the recipient and materials before sending.

You can leave task details and keep writing. Preparation, upload, receipt, and execution have different meanings. If delivery is uncertain, inspect or recover the original task instead of creating a duplicate. Leaving the page does not stop execution on the computer.

Completed text stays visible in task details; technical information is expandable. Preview a result before adding it to the end of a note or saving it as a new note. Android shows the destination and starting page. On iPad, the result editor has one return action that retains its save checks. Arbitrary insertion between existing pages is not included in this update.

The desktop guide links to Agent checks, pairing setup, computer approval, and the first task. Navigation never sends a task automatically. An existing configuration is not proof of a healthy interface; stale checks must be refreshed.

Verification: Android 422 unit tests and 6 native tests passed; iPad 324 unit tests and 2 UI tests passed; desktop 455 passed and 6 platform-specific tests skipped, plus 3 browser checks. UI checks use synthetic tasks and materials.
