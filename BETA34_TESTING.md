# Beta 34：把任务结果加回纸面

## Android

1. 打开一条已完成的电脑 Agent 任务，在任务详情中选择“添加结果到笔记”。
2. 先阅读完整预览，再确认添加到原笔记。若原笔记不可用，可选择另存为新笔记。
3. 保存完成后，应用返回纸面并定位到新增内容。新增文字位于笔记末尾，原有文字、笔迹和 PDF 页面保持原样。
4. 同一任务结果再次添加时会提示已存在，不会重复插入。预览期间如果笔记或任务发生变化，应用会要求重新检查。
5. 新增的结果文字仍可编辑，也可以随笔记导出或分享。其来源信息随文字流保存；编辑文字不会删除来源记录。
6. 超出安全输入上限的结果不会被截短或添加。完整原文仍可在任务详情中选择并复制。

Android beta.34 使用 versionCode 67。验证结果：422 项 JVM 测试通过，lint 0 个错误、164 条警告，4 个 ABI 已构建；隔离 API 35 模拟器上的 6 项 instrumentation 测试全部通过，覆盖任务详情、预览、确认、持久保存、重开、去重、过期预览冲突、纸面内容保留及备份恢复。测试使用合成任务与材料。

含有 Agent 结果的笔记使用格式 9 保存来源信息。交换 `.padnote` 文件或恢复备份时，接收端需支持格式 9（Android beta.34 或更新后的 iPad 源码）；旧版本会拒绝格式 9，不会静默丢弃来源信息。未添加结果的普通笔记沿用原格式，PDF 分享不受影响。

## 其他平台

Windows 连接助手继续使用 beta.33。iPad 的 324 项单元测试和 2 项 UI 测试在模拟器中通过，覆盖预览、保存、重开、去重及过期预览拒绝；本轮提供 Xcode 源码，没有签名 IPA。

# Beta 34: Add a task result to the page

## Android

1. Open a completed computer-Agent task and choose **Add result to note** in its details.
2. Review the full preview, then confirm adding it to the original note. If the original note is unavailable, you can save the result as a new note.
3. After the save completes, PadNote returns to the page and moves to the new content. The text is appended at the end; existing text, handwriting, and PDF pages are preserved.
4. Adding the same task result again reports that it is already present instead of inserting a duplicate. If the note or task changes while the preview is open, review it again before continuing.
5. The added text remains editable and can be included when exporting or sharing the note. Its source details are stored with the text flow and remain available after edits.
6. Results above the safe input limit are not truncated or added. The complete output remains selectable and can be copied from task details.

Android beta.34 uses versionCode 67. Verification: 422 JVM tests passed; lint reported 0 errors and 164 warnings; all 4 ABIs built. All 6 instrumentation tests passed on an isolated API 35 emulator, covering task details, preview, confirmation, durable save, reopen, deduplication, stale-preview conflict, preservation of page content, and backup/restore. The tests use synthetic tasks and materials.

Notes containing an Agent result use format 9 to keep its source information with the text. The receiving device must support format 9 when exchanging `.padnote` files or restoring backups (Android beta.34 or newer iPad source). Older versions reject format 9 rather than silently dropping provenance. Notes without an added result keep the existing format; PDF sharing is unaffected.

## Other platforms

The Windows Connection Assistant remains beta.33. The iPad source passed 324 unit tests and 2 UI tests in the simulator, covering preview, save, reopen, deduplication, and stale-preview rejection. This release includes the Xcode source without a signed IPA.
