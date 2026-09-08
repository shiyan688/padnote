import {Audio, Img, Latex, Layout, Line, Rect, Txt, makeScene2D} from '@revideo/2d';
import {waitFor, useScene} from '@revideo/core';
import type {Node, View2D} from '@revideo/2d';
import type {RenderClip, RenderPayload, RenderScene} from './scene-types.js';

const font = 'Droid Sans Fallback, sans-serif';
const ink = '#172033';
const muted = '#526175';
const accent = '#315efb';

export default makeScene2D('lesson', function* (view) {
  const payload = useScene().variables.get<RenderPayload>('payload', {
    episode: {title: 'PadNote'},
    scenes: [],
    clips: [],
  })();
  if (payload.scenes.length !== payload.clips.length) {
    throw new Error('render payload scenes and clips must have equal length');
  }
  for (let index = 0; index < payload.scenes.length; index += 1) {
    const scene = payload.scenes[index]!;
    const clip = payload.clips[index]!;
    if (scene.id !== clip.scene_id) throw new Error(`audio scene mismatch: ${scene.id}`);
    yield* renderScene(view, scene, clip, payload.keyframe_mode === true);
  }
});

function* renderScene(view: View2D, scene: RenderScene, clip: RenderClip, keyframeMode: boolean) {
  if (scene.visual.type === 'quantity_change') {
    yield* renderQuantity(view, scene, clip, keyframeMode);
    return;
  }
  const root = new Rect({
    width: 1080,
    height: 1920,
    fill: '#f4f7fb',
  });
  const reveal = buildVisual(root, scene);
  view.add(root);
  if (keyframeMode) {
    for (const node of reveal) node.opacity(1);
    yield root;
    yield* waitFor(1);
    root.remove();
    return;
  }
  const audio = new Audio({src: clip.src, play: true, awaitCanPlay: true});
  view.add(audio);
  yield root;
  yield audio;
  const slot = clip.duration_ms / 1000 / Math.max(1, reveal.length);
  for (const node of reveal) {
    const fade = Math.min(0.32, slot / 2);
    yield* node.opacity(1, fade);
    yield* waitFor(slot - fade);
  }
  if (reveal.length === 0) yield* waitFor(slot);

  audio.remove();
  root.remove();
}

function* renderQuantity(view: View2D, scene: RenderScene, clip: RenderClip, keyframeMode: boolean) {
  const states = scene.visual.data.states as Array<{label: string; value: number}>;
  const max = Math.max(...states.map(state => state.value));
  const root = new Rect({width: 1080, height: 1920, fill: '#f4f7fb'});
  root.add(new Txt({text: scene.learning_objective, y: -730, width: 860,
    fontFamily: font, fontSize: 46, textWrap: true, fill: ink}));
  root.add(new Rect({x: -420, offsetX: -1, width: 840, height: 150,
    fill: '#dce5f2', radius: 12}));
  const bar = new Rect({x: -420, offsetX: -1, width: states[0]!.value / max * 840,
    height: 150, fill: accent, radius: 12});
  const label = new Txt({y: -190, width: 850, fontFamily: font, fontSize: 54,
    textWrap: true, fill: ink});
  root.add(bar);
  root.add(label);
  root.add(new Txt({y: 230, text: `固定比例：满格 ${max} ${scene.visual.data.unit}`,
    fontFamily: font, fontSize: 34, fill: muted}));
  root.add(new Txt({y: 690, text: scene.screen_text.join(' · '), width: 850,
    fontFamily: font, fontSize: 38, textWrap: true, fill: ink}));
  view.add(root);
  const audio = keyframeMode ? undefined : new Audio({src: clip.src, play: true, awaitCanPlay: true});
  if (audio) view.add(audio);
  yield root;
  if (audio) yield audio;
  const beat = keyframeMode ? 0 : clip.duration_ms / 1000 / states.length;
  for (const [index, state] of states.entries()) {
    label.text(`${state.label}：${state.value} ${scene.visual.data.unit}`);
    const duration = index === 0 || keyframeMode ? 0 : Math.min(1.2, beat / 2);
    yield* bar.width(state.value / max * 840, duration);
    if (!keyframeMode) yield* waitFor(beat - duration);
  }
  if (keyframeMode) yield* waitFor(1);
  audio?.remove();
  root.remove();
}

function buildVisual(root: Rect, scene: RenderScene): Node[] {
  const header = new Txt({
    text: scene.learning_objective,
    x: -420,
    y: -780,
    width: 840,
    offsetX: -1,
    fill: accent,
    fontFamily: font,
    fontSize: 30,
    fontWeight: 700,
  });
  const footer = new Rect({
    y: 760,
    width: 900,
    minHeight: 170,
    radius: 28,
    fill: '#ffffff',
    padding: 34,
    shadowColor: '#17203318',
    shadowBlur: 24,
    children: new Txt({
      text: scene.screen_text.join('  ·  '),
      width: 790,
      fill: muted,
      fontFamily: font,
      fontSize: 34,
      lineHeight: 48,
      textWrap: true,
      textAlign: 'center',
    }),
  });
  root.add(header);
  root.add(footer);

  switch (scene.visual.type) {
    case 'quantity_change':
      throw new Error('quantity_change uses the continuous quantity renderer');
    case 'title': {
      const title = new Txt({
        text: String(scene.visual.data.title),
        width: 840,
        y: -90,
        fill: ink,
        fontFamily: font,
        fontSize: 96,
        fontWeight: 700,
        lineHeight: 122,
        textWrap: true,
        textAlign: 'center',
        opacity: 0,
      });
      const subtitle = new Txt({
        text: String(scene.visual.data.subtitle ?? ''),
        width: 820,
        y: 120,
        fill: muted,
        fontFamily: font,
        fontSize: 42,
        lineHeight: 58,
        textWrap: true,
        textAlign: 'center',
        opacity: 0,
      });
      root.add(title);
      root.add(subtitle);
      return [title, subtitle];
    }
    case 'formula_steps': {
      const steps = scene.visual.data.steps as string[];
      const container = new Layout({
        layout: true,
        direction: 'column',
        gap: 34,
        width: 900,
        y: -30,
        alignItems: 'center',
      });
      const nodes = steps.map((step, index) => {
        const equation = new Latex({tex: `{\\color{${ink}} ${step}}`, layout: false});
        const fit = () => Math.min(90 / equation.naturalSize().y, 750 / equation.naturalSize().x);
        equation.width(() => equation.naturalSize().x * fit());
        equation.height(() => equation.naturalSize().y * fit());
        return new Rect({
        width: 900,
        height: 205,
        radius: 30,
        fill: index === steps.length - 1 ? '#eaf0ff' : '#ffffff',
        stroke: index === steps.length - 1 ? '#9bb2ff' : '#dfe6f1',
        lineWidth: 3,
        opacity: 0,
        children: equation,
      });
      });
      container.add(nodes);
      root.add(container);
      return nodes;
    }
    case 'process': {
      const steps = scene.visual.data.steps as string[];
      const height = Math.min(150, 960 / steps.length);
      const container = new Layout({
        layout: true,
        direction: 'column',
        gap: 22,
        width: 860,
        y: -20,
        alignItems: 'center',
      });
      const nodes = steps.map((step, index) => new Rect({
        width: 820,
        height,
        radius: 26,
        fill: index === steps.length - 1 ? '#eaf0ff' : '#ffffff',
        stroke: '#cbd7ee',
        lineWidth: 3,
        opacity: 0,
        children: new Txt({
          text: `${String(index + 1).padStart(2, '0')}   ${step}`,
          width: 720,
          fill: ink,
          fontFamily: font,
          fontSize: 38,
          fontWeight: 600,
          textWrap: true,
        }),
      }));
      container.add(nodes);
      root.add(container);
      return nodes;
    }
    case 'comparison': {
      const sides = [scene.visual.data.left, scene.visual.data.right] as Array<{title: string; items: string[]}>;
      const cards = sides.map((side, index) => {
        const content = new Layout({layout: true, direction: 'column', gap: 30, width: 350, alignItems: 'center'});
        content.add(new Txt({
          text: side.title,
          width: 340,
          fill: index === 0 ? '#315efb' : '#9b4bca',
          fontFamily: font,
          fontSize: 54,
          fontWeight: 700,
          textAlign: 'center',
        }));
        for (const item of side.items) content.add(new Txt({
          text: `• ${item}`,
          width: 340,
          fill: ink,
          fontFamily: font,
          fontSize: 32,
          lineHeight: 44,
          textWrap: true,
        }));
        return new Rect({
          width: 420,
          minHeight: 720,
          padding: 34,
          radius: 30,
          fill: '#ffffff',
          stroke: index === 0 ? '#9bb2ff' : '#d8acee',
          lineWidth: 3,
          opacity: 0,
          children: content,
        });
      });
      root.add(new Layout({layout: true, direction: 'row', gap: 30, y: -25, children: cards}));
      return cards;
    }
    case 'concept_map': {
      const data = scene.visual.data as {
        nodes: Array<{id: string; label: string}>;
        edges: Array<{from: string; to: string; label?: string}>;
      };
      const positions = new Map<string, [number, number]>();
      data.nodes.forEach((node, index) => {
        const angle = -Math.PI / 2 + index * Math.PI * 2 / data.nodes.length;
        positions.set(node.id, [Math.cos(angle) * 330, Math.sin(angle) * 410 - 30]);
      });
      const reveals: Node[] = [];
      for (const edge of data.edges) {
        const from = positions.get(edge.from)!;
        const to = positions.get(edge.to)!;
        const line = new Line({
          points: [from, to],
          stroke: '#91a2bd',
          lineWidth: 6,
          endArrow: true,
          arrowSize: 18,
          opacity: 0,
        });
        root.add(line);
        reveals.push(line);
        if (edge.label) {
          const label = new Txt({
            text: edge.label,
            x: (from[0] + to[0]) / 2,
            y: (from[1] + to[1]) / 2 - 28,
            fill: '#9b4bca',
            fontFamily: font,
            fontSize: 27,
            fontWeight: 700,
          });
          root.add(label);
        }
      }
      for (const node of data.nodes) {
        const position = positions.get(node.id)!;
        const card = new Rect({
          position,
          width: 280,
          height: 130,
          radius: 65,
          fill: '#ffffff',
          stroke: '#7291ff',
          lineWidth: 4,
          opacity: 0,
          children: new Txt({
            text: node.label,
            width: 220,
            fill: ink,
            fontFamily: font,
            fontSize: 36,
            fontWeight: 700,
            textWrap: true,
            textAlign: 'center',
          }),
        });
        root.add(card);
        reveals.push(card);
      }
      return reveals;
    }
    case 'annotated_source': {
      const source = new Img({
        src: String(scene.visual.data.asset_src),
        width: 820,
        height: 550,
        y: -300,
        radius: 30,
        stroke: '#cbd7ee',
        lineWidth: 3,
        opacity: 0,
      });
      const annotations = (scene.visual.data.annotations as string[]).map((annotation, index) => new Rect({
        width: 820,
        height: 68,
        radius: 20,
        fill: index % 2 === 0 ? '#eaf0ff' : '#f4eafa',
        opacity: 0,
        children: new Txt({
          text: `${index + 1}. ${annotation}`,
          width: 750,
          fill: ink,
          fontFamily: font,
          fontSize: 28,
          textWrap: true,
        }),
      }));
      root.add(source);
      root.add(new Layout({layout: true, direction: 'column', gap: 16, y: 270, children: annotations}));
      return [source, ...annotations];
    }
  }
}
