export interface RenderClip {
  scene_id: string;
  src: string;
  duration_ms: number;
}

export interface RenderScene {
  id: string;
  learning_objective: string;
  narration: string;
  screen_text: string[];
  visual: {
    type: 'title' | 'formula_steps' | 'concept_map' | 'process' | 'comparison' | 'annotated_source' | 'quantity_change';
    data: Record<string, any>;
  };
  animation_intent: {kind: string; explanation: string};
}

export interface RenderPayload {
  episode: {title: string};
  scenes: RenderScene[];
  clips: RenderClip[];
  keyframe_mode?: boolean;
}
