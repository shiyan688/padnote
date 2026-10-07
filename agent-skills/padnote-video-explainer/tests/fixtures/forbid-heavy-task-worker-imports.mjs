import {registerHooks} from 'node:module';

const heavySpecifiers = new Set([
  './build-storyboard.js',
  './render-video.js',
  './validate-result.js',
  './revision-install.js',
  'puppeteer',
  '@revideo/renderer',
  'katex',
  '@ffmpeg-installer/ffmpeg',
  '@ffprobe-installer/ffprobe',
]);

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (heavySpecifiers.has(specifier)) {
      throw new Error(`heavy task-worker dependency resolved unexpectedly: ${specifier}`);
    }
    return nextResolve(specifier, context);
  },
});
