# Android 旧笔记迁移回归夹具

这里的文件是依据各版迁移结构构造的回归夹具，并非用户设备上的历史导出实物。真实历史导出仍需另行收集并做真机验收。

每个有效 JSON 都保留一条有固定 ID 的笔迹，以便确认载入及回存没有丢失已有内容；测试使用 Android 当前生产 `NoteCanvasView.loadJsonDocument` 载入，再用 `toJsonDocument` 回存为 schema 8。

| 文件 | 所覆盖的旧结构 | 必须保留的结果 |
| --- | --- | --- |
| `schema1.json` | 仅有 `canvasWidth`/`canvasHeight` 的单页画布 | 旧笔迹、单页尺寸；采用旧版横线纸默认值 |
| `schema2.json` | 分页几何与旧视口字段 | 两页、第二页的世界坐标笔迹 |
| `schema3.json` | 单个持久化 `textBoxes` | LaTeX 源码及页内锚点迁移为一个文字流 |
| `schema4.json` | 同一 flow 的多个重复源码片段，且数组次序倒置 | 只取最低 `flowIndex` 作为流头；世界坐标换算成第 2 页页内锚点 |
| `schema5.json` | 首个只持久化 `textFlows` 的格式，缺少 `lineHeight` | 流源码、页码和锚点；缺省行距为 1.35 |
| `schema6.json` | `pageStyle` | 方格、A4、横向以及 LaTeX 流 |
| `schema7.json` + `schema7-source.pdf` | 一页 PDF 背景加第二页普通纸注释 | PDF 页数、总页数和第二页文字锚点；配套 PDF 是本目录构造的最小一页 PDF |
| `schema8.json` | 当前图片结构 | 点阵纸、文字流、2×2 PNG 图片及几何 |
| `schema8-corrupt-tail.json` | 前段对象有效、尾部 flow ID 无效 | 整次载入失败，载入前的活文档保持不变 |

Android instrumentation test APK 不能直接读取仓库的 `docs` 目录，因此这些文件按字节镜像到 `android/app/src/androidTest/assets/legacy-notes/`。JVM 测试校验两个目录的文件名与内容完全一致，缺少任一文件会直接失败，不会跳过。
