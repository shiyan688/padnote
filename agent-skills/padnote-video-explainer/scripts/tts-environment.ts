export const TTS_RUNTIME_ENV_ALLOWLIST = [
  'PATH',
  'Path',
  'SystemRoot',
  'SYSTEMROOT',
  'WINDIR',
  'COMSPEC',
  'PATHEXT',
  'TEMP',
  'TMP',
  'TMPDIR',
  'LANG',
  'LC_ALL',
  'LC_CTYPE',
  'TZ',
  'SSL_CERT_FILE',
  'SSL_CERT_DIR',
  'NODE_EXTRA_CA_CERTS',
] as const;

// This is the complete provider-specific inheritance surface for the bundled
// adapter. Another adapter must add and review its exact names here.
export const TTS_PROVIDER_ENV_ALLOWLIST = [
  'PADNOTE_TTS_ENV_FILE',
  'DASHSCOPE_API_KEY',
  'DASHSCOPE_BASE_URL',
  'DASHSCOPE_TTS_MODEL',
  'DASHSCOPE_TTS_VOICE',
  'DASHSCOPE_TTS_LANGUAGE',
  'DASHSCOPE_TTS_INSTRUCTIONS',
  'DASHSCOPE_TTS_OPTIMIZE_INSTRUCTIONS',
] as const;

export interface TtsSceneEnvironment {
  sceneId: string;
  text: string;
  language: string;
  voiceProfile: string;
  speed: string;
  output: string;
  idempotencyKey?: string;
}

export function buildTtsChildEnvironment(
  scene: TtsSceneEnvironment,
  hostEnvironment: NodeJS.ProcessEnv = process.env,
): NodeJS.ProcessEnv {
  const child: NodeJS.ProcessEnv = {};
  for (const name of [...TTS_RUNTIME_ENV_ALLOWLIST, ...TTS_PROVIDER_ENV_ALLOWLIST]) {
    const value = hostEnvironment[name];
    if (value !== undefined) child[name] = value;
  }
  child.PADNOTE_TTS_SCENE_ID = scene.sceneId;
  child.PADNOTE_TTS_TEXT = scene.text;
  child.PADNOTE_TTS_LANGUAGE = scene.language;
  child.PADNOTE_TTS_VOICE_PROFILE = scene.voiceProfile;
  child.PADNOTE_TTS_SPEED = scene.speed;
  child.PADNOTE_TTS_OUTPUT = scene.output;
  if (scene.idempotencyKey) child.PADNOTE_TTS_IDEMPOTENCY_KEY = scene.idempotencyKey;
  return child;
}
