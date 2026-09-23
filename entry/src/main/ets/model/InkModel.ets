export interface InkPoint {
  x: number;
  y: number;
  timestamp: number;
  pressure: number;
}

export interface InkStroke {
  id: string;
  color: string;
  baseWidth: number;
  createdAt: number;
  points: Array<InkPoint>;
}

export interface InkDocument {
  schemaVersion: number;
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  canvasWidth: number;
  canvasHeight: number;
  strokes: Array<InkStroke>;
}

export function createEmptyDocument(width: number = 0, height: number = 0): InkDocument {
  const now = Date.now();
  return {
    schemaVersion: 1,
    id: `note-${now}`,
    title: '未命名笔记',
    createdAt: now,
    updatedAt: now,
    canvasWidth: width,
    canvasHeight: height,
    strokes: []
  };
}

export function countPoints(strokes: Array<InkStroke>): number {
  let total = 0;
  for (let index = 0; index < strokes.length; index++) {
    total += strokes[index].points.length;
  }
  return total;
}
