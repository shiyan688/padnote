# beta.18 演示素材制作

这里的工具只剪辑真实设备录屏并叠加说明字幕，不绘制或替换 PadNote 界面。请使用合成笔记与合成任务内容。源录屏在本地工作目录保留；不要把原始录屏、账号画面、个人笔记或令牌放入公开源码。

## 1. 准备工作目录

在仓库外或本次 review 目录建立独立工作区：

```text
demo-work/
  recordings/raw/       # root 提供的原始 screenrecord，保留原文件
  scenes/               # 教学版与宣发版的时间点/字幕 JSON
  output/               # 两份成片
  metadata/             # SRT、渲染/哈希与探测回执
  qa/                   # 联系表与逐段关键帧
```

每个时间点必须由 root 逐帧观看录屏后填写；字幕只描述画面已发生的动作。`render-spec.schema.json` 给出字段格式。两份成片时长都应为 30–60 秒，包含片头/片尾卡、横屏 1920×1080、30 fps、无音轨、简体中文字幕。

## 2. 找到已有工具

macOS arm64 环境可复用本仓库留存的 FFmpeg 7.1：

```text
dist/todo-review/demo-media-runtime-20261013-r1/extracted/imageio_ffmpeg/binaries/ffmpeg-macos-aarch64-v7.1
```

系统 `ffmpeg`/`ffprobe` 当前未安装；不要为此下载大型工具链或改系统代理。可传入已获准的 FFmpeg/ffprobe 绝对路径。字幕字体优先使用系统已有的 `/System/Library/Fonts/STHeiti Medium.ttc` 或 `/System/Library/Fonts/Hiragino Sans GB.ttc`；工具也会探测 Linux Noto CJK 和 Windows 微软雅黑字体。当前主机上两款系统字体均可被 Pillow 打开。

## 3. 渲染两版

从仓库根目录运行，`--workspace` 指向私有 `demo-work`，字幕时间 JSON 中的录屏路径和输出路径都相对于该工作区：

```sh
python3 dist/todo-review/beta18-github-20261028-r1/source/demo/scripts/render_demo.py \
  --workspace dist/todo-review/beta18-github-20261028-r1/demo-work \
  --scenes dist/todo-review/beta18-github-20261028-r1/demo-work/scenes/tutorial.json \
  --ffmpeg dist/todo-review/demo-media-runtime-20261013-r1/extracted/imageio_ffmpeg/binaries/ffmpeg-macos-aarch64-v7.1 \
  --font '/System/Library/Fonts/STHeiti Medium.ttc'
```

对 `promo.json` 再运行一次。要生成真 ffprobe JSON 摘要，追加 `--ffprobe /绝对路径/ffprobe`。目前没有可复用的 ffprobe 二进制；未提供时，成片清单会明确记录 `available: false`，FFmpeg 的容器/视频摘要仍留在编码日志，不会伪称为 ffprobe 结果。工具同时保留逐段 SHA-256、烧录字幕的 SRT 时间稿、成片 SHA-256、关键帧和联系表。

原始录屏只作为输入读取。画面按比例缩放并留边，不裁掉 UI；可在审核字幕与内容后挑选实际帧作 README 配图。成片的标题卡、结束卡和字幕是解释文字，须避免暗示未发生的 AI 生成、任务送达或成功结果。

## 4. README 图片/GIF

由 root 审阅成片并给出实际时间点后，直接从成片提取 PNG 封面；可选 GIF 最长 8 秒、720 像素宽、8 fps：

```sh
python3 dist/todo-review/beta18-github-20261028-r1/source/demo/scripts/extract_readme_media.py \
  --video dist/todo-review/beta18-github-20261028-r1/demo-work/output/tutorial.mp4 \
  --ffmpeg dist/todo-review/demo-media-runtime-20261013-r1/extracted/imageio_ffmpeg/binaries/ffmpeg-macos-aarch64-v7.1 \
  --poster dist/todo-review/beta18-github-20261028-r1/source/docs/media/beta18-demo-poster.png \
  --poster-at 12.0 \
  --gif dist/todo-review/beta18-github-20261028-r1/source/docs/media/beta18-demo.gif \
  --gif-start 20.0 --gif-seconds 6
```

示例时间仅为参数格式，必须替换为 root 审核后的真实时点；此命令尚未对任何录屏执行。工具不会写 README，root 在确认图片/GIF、字幕与 SHA 后再加入链接。Release 的两份 MP4 与源码中的精选 PNG/GIF 分开管理。
