import { common } from '@kit.AbilityKit';
import { promptAction } from '@kit.ArkUI';
import { countPoints, createEmptyDocument, InkDocument, InkPoint, InkStroke } from '../model/InkModel';
import { NoteStore } from '../services/NoteStore';

@Entry
@Component
struct Index {
  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private context: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
  private appContext: common.UIAbilityContext | undefined = undefined;
  private currentStroke: InkStroke | undefined = undefined;
  private saveTimer: number = -1;
  private strokeSerial: number = 0;

  @State private document: InkDocument = createEmptyDocument();
  @State private selectedColor: string = '#17212B';
  @State private selectedWidth: number = 4;
  @State private pointCount: number = 0;
  @State private saveStatus: string = '尚未书写';
  @State private lastPressure: number = 0;
  @State private canvasReady: boolean = false;

  aboutToAppear(): void {
    this.appContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.restoreDocument();
  }

  aboutToDisappear(): void {
    if (this.saveTimer >= 0) {
      clearTimeout(this.saveTimer);
      this.saveTimer = -1;
    }
    this.saveDocument(false);
  }

  private restoreDocument(): void {
    if (this.appContext === undefined) {
      return;
    }
    const restored = NoteStore.load(this.appContext);
    if (restored === undefined || restored.schemaVersion !== 1) {
      return;
    }
    this.document = restored;
    this.pointCount = countPoints(restored.strokes);
    this.saveStatus = '已恢复本地笔记';
    if (this.canvasReady) {
      this.redrawDocument();
    }
  }

  private onCanvasReady(): void {
    this.canvasReady = true;
    this.document.canvasWidth = this.context.width;
    this.document.canvasHeight = this.context.height;
    this.redrawDocument();
  }

  private createPoint(touch: TouchObject): InkPoint {
    return {
      x: touch.x,
      y: touch.y,
      timestamp: Date.now(),
      pressure: touch.pressure
    };
  }

  private beginStroke(point: InkPoint): void {
    this.strokeSerial += 1;
    this.currentStroke = {
      id: `stroke-${Date.now()}-${this.strokeSerial}`,
      color: this.selectedColor,
      baseWidth: this.selectedWidth,
      createdAt: Date.now(),
      points: [point]
    };
    this.document.strokes.push(this.currentStroke);
    this.pointCount += 1;
    this.lastPressure = point.pressure;
    this.drawDot(point, this.currentStroke);
    this.saveStatus = '书写中';
  }

  private appendPoint(point: InkPoint): void {
    if (this.currentStroke === undefined) {
      return;
    }
    const points = this.currentStroke.points;
    const previous = points[points.length - 1];
    const dx = point.x - previous.x;
    const dy = point.y - previous.y;
    if (dx * dx + dy * dy < 0.35) {
      return;
    }
    points.push(point);
    this.pointCount += 1;
    this.lastPressure = point.pressure;
    this.drawSegment(previous, point, this.currentStroke);
  }

  private finishStroke(point?: InkPoint): void {
    if (this.currentStroke === undefined) {
      return;
    }
    if (point !== undefined) {
      this.appendPoint(point);
    }
    this.currentStroke = undefined;
    this.document.updatedAt = Date.now();
    this.saveStatus = '等待自动保存';
    this.scheduleSave();
  }

  private handleTouch(event?: TouchEvent): void {
    if (event === undefined) {
      return;
    }
    if (event.type === TouchType.Down && event.touches.length > 0) {
      this.beginStroke(this.createPoint(event.touches[0]));
      return;
    }
    if (event.type === TouchType.Move && event.touches.length > 0) {
      this.appendPoint(this.createPoint(event.touches[0]));
      return;
    }
    if (event.type === TouchType.Up) {
      const point = event.changedTouches.length > 0 ? this.createPoint(event.changedTouches[0]) : undefined;
      this.finishStroke(point);
      return;
    }
    if (event.type === TouchType.Cancel) {
      this.finishStroke();
    }
  }

  private pressureWidth(point: InkPoint, baseWidth: number): number {
    const pressure = point.pressure > 0 ? Math.min(point.pressure, 1) : 0.5;
    return baseWidth * (0.45 + pressure * 0.95);
  }

  private drawDot(point: InkPoint, stroke: InkStroke): void {
    this.context.beginPath();
    this.context.fillStyle = stroke.color;
    this.context.arc(point.x, point.y, this.pressureWidth(point, stroke.baseWidth) / 2, 0, Math.PI * 2);
    this.context.fill();
  }

  private drawSegment(from: InkPoint, to: InkPoint, stroke: InkStroke): void {
    this.context.beginPath();
    this.context.strokeStyle = stroke.color;
    this.context.lineCap = 'round';
    this.context.lineJoin = 'round';
    this.context.lineWidth = (this.pressureWidth(from, stroke.baseWidth) +
      this.pressureWidth(to, stroke.baseWidth)) / 2;
    this.context.moveTo(from.x, from.y);
    this.context.lineTo(to.x, to.y);
    this.context.stroke();
  }

  private redrawDocument(): void {
    if (!this.canvasReady) {
      return;
    }
    this.context.clearRect(0, 0, this.context.width, this.context.height);
    this.drawPaperGuides();
    const strokes = this.document.strokes;
    for (let strokeIndex = 0; strokeIndex < strokes.length; strokeIndex++) {
      const stroke = strokes[strokeIndex];
      if (stroke.points.length === 1) {
        this.drawDot(stroke.points[0], stroke);
        continue;
      }
      for (let pointIndex = 1; pointIndex < stroke.points.length; pointIndex++) {
        this.drawSegment(stroke.points[pointIndex - 1], stroke.points[pointIndex], stroke);
      }
    }
  }

  private drawPaperGuides(): void {
    const guideGap = 32;
    this.context.save();
    this.context.strokeStyle = '#DCE3E8';
    this.context.lineWidth = 0.6;
    for (let y = guideGap; y < this.context.height; y += guideGap) {
      this.context.beginPath();
      this.context.moveTo(0, y);
      this.context.lineTo(this.context.width, y);
      this.context.stroke();
    }
    this.context.restore();
  }

  private undo(): void {
    if (this.document.strokes.length === 0 || this.currentStroke !== undefined) {
      return;
    }
    this.document.strokes.pop();
    this.document.updatedAt = Date.now();
    this.pointCount = countPoints(this.document.strokes);
    this.redrawDocument();
    this.scheduleSave();
  }

  private clearAll(): void {
    if (this.currentStroke !== undefined) {
      return;
    }
    this.document.strokes = [];
    this.document.updatedAt = Date.now();
    this.pointCount = 0;
    this.lastPressure = 0;
    this.redrawDocument();
    this.scheduleSave();
  }

  private scheduleSave(): void {
    if (this.saveTimer >= 0) {
      clearTimeout(this.saveTimer);
    }
    this.saveTimer = setTimeout(() => {
      this.saveTimer = -1;
      this.saveDocument(false);
    }, 600);
  }

  private saveDocument(showToast: boolean): void {
    if (this.appContext === undefined) {
      this.saveStatus = '保存失败：无应用上下文';
      return;
    }
    this.document.updatedAt = Date.now();
    this.document.canvasWidth = this.context.width;
    this.document.canvasHeight = this.context.height;
    const succeeded = NoteStore.save(this.appContext, this.document);
    this.saveStatus = succeeded ? '已保存到本机' : '本地保存失败';
    if (showToast) {
      promptAction.showToast({ message: this.saveStatus });
    }
  }

  @Builder
  ToolButton(label: string, enabled: boolean, handler: () => void) {
    Button(label)
      .height(40)
      .fontSize(14)
      .fontColor(enabled ? '#17212B' : '#9AA4AD')
      .backgroundColor(enabled ? '#FFFFFF' : '#ECEFF1')
      .borderRadius(12)
      .enabled(enabled)
      .onClick(handler)
  }

  build() {
    Column() {
      Row() {
        Column() {
          Text('PadNote')
            .fontSize(24)
            .fontWeight(FontWeight.Bold)
            .fontColor('#17212B')
          Text('鸿蒙手写底座 · M0')
            .fontSize(12)
            .fontColor('#74808B')
        }
        .alignItems(HorizontalAlign.Start)

        Blank()

        Text(`${this.document.strokes.length} 笔 · ${this.pointCount} 点`)
          .fontSize(13)
          .fontColor('#596672')
      }
      .width('100%')
      .padding({ left: 20, right: 20, top: 14, bottom: 10 })

      Scroll() {
        Row({ space: 8 }) {
          Text('笔宽')
            .fontSize(13)
            .fontColor('#596672')
          Button('细')
            .height(36)
            .backgroundColor(this.selectedWidth === 2 ? '#17212B' : '#FFFFFF')
            .fontColor(this.selectedWidth === 2 ? '#FFFFFF' : '#17212B')
            .onClick(() => this.selectedWidth = 2)
          Button('中')
            .height(36)
            .backgroundColor(this.selectedWidth === 4 ? '#17212B' : '#FFFFFF')
            .fontColor(this.selectedWidth === 4 ? '#FFFFFF' : '#17212B')
            .onClick(() => this.selectedWidth = 4)
          Button('粗')
            .height(36)
            .backgroundColor(this.selectedWidth === 7 ? '#17212B' : '#FFFFFF')
            .fontColor(this.selectedWidth === 7 ? '#FFFFFF' : '#17212B')
            .onClick(() => this.selectedWidth = 7)

          Text('颜色')
            .fontSize(13)
            .fontColor('#596672')
            .margin({ left: 6 })
          Button('墨')
            .height(36)
            .backgroundColor('#17212B')
            .fontColor('#FFFFFF')
            .border({ width: this.selectedColor === '#17212B' ? 3 : 0, color: '#D7A24A' })
            .onClick(() => this.selectedColor = '#17212B')
          Button('蓝')
            .height(36)
            .backgroundColor('#285EA8')
            .fontColor('#FFFFFF')
            .border({ width: this.selectedColor === '#285EA8' ? 3 : 0, color: '#D7A24A' })
            .onClick(() => this.selectedColor = '#285EA8')
          Button('朱')
            .height(36)
            .backgroundColor('#B64A3B')
            .fontColor('#FFFFFF')
            .border({ width: this.selectedColor === '#B64A3B' ? 3 : 0, color: '#D7A24A' })
            .onClick(() => this.selectedColor = '#B64A3B')

          this.ToolButton('撤销', this.document.strokes.length > 0, () => this.undo())
          this.ToolButton('清空', this.document.strokes.length > 0, () => this.clearAll())
          this.ToolButton('保存', true, () => this.saveDocument(true))
        }
        .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Off)
      .width('100%')
      .height(58)
      .backgroundColor('#EEF1F2')

      Stack() {
        Canvas(this.context)
          .width('100%')
          .height('100%')
          .backgroundColor('#FBFAF6')
          .onReady(() => this.onCanvasReady())
          .onTouch((event?: TouchEvent) => this.handleTouch(event))

        if (this.document.strokes.length === 0) {
          Column({ space: 8 }) {
            Text('落笔开始')
              .fontSize(22)
              .fontWeight(FontWeight.Medium)
              .fontColor('#7E898F')
            Text('这一版已记录坐标、时间与压力，数据会自动保存在本机')
              .fontSize(13)
              .fontColor('#9AA3A8')
          }
          .hitTestBehavior(HitTestMode.None)
        }
      }
      .layoutWeight(1)
      .width('100%')

      Row() {
        Text(this.saveStatus)
          .fontSize(12)
          .fontColor('#65717B')
        Blank()
        Text(`压力 ${this.lastPressure.toFixed(2)}`)
          .fontSize(12)
          .fontColor('#65717B')
      }
      .width('100%')
      .height(34)
      .padding({ left: 16, right: 16 })
      .backgroundColor('#F0EEE8')
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F4F1E8')
  }
}
