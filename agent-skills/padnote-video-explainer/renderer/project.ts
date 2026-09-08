import {makeProject} from '@revideo/core';
import lessonScene from './lesson-scene.tsx?scene';

export default makeProject({
  scenes: [lessonScene],
  settings: {
    shared: {
      background: '#f4f7fb',
      size: {x: 1080, y: 1920},
    },
    rendering: {
      fps: 30,
      resolutionScale: 1,
    },
  },
});
