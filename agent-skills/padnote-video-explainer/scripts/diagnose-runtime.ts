import {createRequire} from 'node:module';
import {dirname, extname, isAbsolute, win32} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {lstat} from 'node:fs/promises';

export type CheckStatus = 'available' | 'missing' | 'unchecked' | 'not_configured';
export type CheckReason =
  | 'modules_resolved' | 'module_missing'
  | 'installed_executable_found' | 'executable_missing' | 'configuration_unavailable'
  | 'adapter_not_configured'
  | 'worker_not_configured';

export interface RuntimeCheck {status: CheckStatus; reason: CheckReason}
export interface RuntimeDiagnostic {
  schema_version: '1.0';
  runtime_verified: false;
  video_ready: false;
  checks: {
    worker_modules: RuntimeCheck;
    storyboard_browser: RuntimeCheck;
    render_browser: RuntimeCheck;
    ffmpeg: RuntimeCheck;
    ffprobe: RuntimeCheck;
    tts: RuntimeCheck;
  };
}

const skillRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const requireFromSkill = createRequire(pathToFileURL(`${skillRoot}/package.json`));
const runtimeModules = [
  'tsx', 'ajv', 'katex', 'puppeteer', '@revideo/2d', '@revideo/core', '@revideo/renderer',
  '@revideo/ui', '@revideo/vite-plugin',
];

export interface RuntimeResolver {
  resolveModule(name: string): unknown;
  renderBrowserPath(): Promise<string>;
  storyboardBrowserPath(): Promise<string>;
  packagedExecutablePath(name: string): string;
  executableExists(path: string): Promise<boolean>;
}

const installedResolver: RuntimeResolver = {
  resolveModule: name => requireFromSkill.resolve(name),
  renderBrowserPath: async () => {
    const puppeteer = requireFromSkill('puppeteer') as {executablePath: () => Promise<string>};
    return puppeteer.executablePath();
  },
  storyboardBrowserPath: async () => {
    const puppeteer = requireFromSkill('puppeteer') as {executablePath: (options: {headless: 'shell'}) => Promise<string>};
    return puppeteer.executablePath({headless: 'shell'});
  },
  packagedExecutablePath: name => {
    const exported = requireFromSkill(name) as {path?: unknown};
    if (typeof exported.path !== 'string') throw new Error('binary missing');
    return exported.path;
  },
  executableExists: async path => {
    try {
      const info = await lstat(path);
      return executableStatIsUsable(path, info, process.platform);
    } catch {
      return false;
    }
  },
};

interface ExecutableStat {
  mode: number;
  nlink: number;
  isFile(): boolean;
  isSymbolicLink(): boolean;
}

export function executableStatIsUsable(
  path: string, info: ExecutableStat, platform: NodeJS.Platform = process.platform,
): boolean {
  const absolute = platform === 'win32' ? win32.isAbsolute(path) : isAbsolute(path);
  if (!absolute || !info.isFile() || info.isSymbolicLink() || info.nlink !== 1) return false;
  if (platform === 'win32') return extname(path).toLowerCase() === '.exe';
  return (info.mode & 0o111) !== 0;
}

export async function diagnoseRuntime(resolver: RuntimeResolver = installedResolver): Promise<RuntimeDiagnostic> {
  const modules = checkModules(resolver);
  const renderBrowser = await checkBrowser(resolver, 'render');
  const storyboardBrowser = await checkBrowser(resolver, 'storyboard');
  const ffmpeg = await checkPackagedExecutable(resolver, '@ffmpeg-installer/ffmpeg');
  const ffprobe = await checkPackagedExecutable(resolver, '@ffprobe-installer/ffprobe');
  const tts = {status: 'not_configured', reason: 'adapter_not_configured'} as const;
  return {
    schema_version: '1.0',
    // These checks only resolve local dependencies and inspect executable files.
    // They do not launch browsers/tools or validate a real task end to end.
    runtime_verified: false,
    video_ready: false,
    checks: {
      worker_modules: modules,
      storyboard_browser: storyboardBrowser,
      render_browser: renderBrowser,
      ffmpeg,
      ffprobe,
      tts,
    },
  };
}

export function diagnoseCliArguments(args: readonly string[], resolver: RuntimeResolver = installedResolver): Promise<RuntimeDiagnostic> {
  return args.length === 0 ? diagnoseRuntime(resolver) : Promise.resolve(failedDiagnostic());
}

function checkModules(resolver: RuntimeResolver): RuntimeCheck {
  try {
    for (const name of runtimeModules) resolver.resolveModule(name);
    return {status: 'available', reason: 'modules_resolved'};
  } catch {
    return {status: 'missing', reason: 'module_missing'};
  }
}

async function checkBrowser(resolver: RuntimeResolver, browser: 'render' | 'storyboard'): Promise<RuntimeCheck> {
  try {
    const path = browser === 'render' ? await resolver.renderBrowserPath() : await resolver.storyboardBrowserPath();
    return await checkExecutable(resolver, path);
  } catch {
    return {status: 'unchecked', reason: 'configuration_unavailable'};
  }
}

async function checkPackagedExecutable(resolver: RuntimeResolver, packageName: string): Promise<RuntimeCheck> {
  try {
    return await checkExecutable(resolver, resolver.packagedExecutablePath(packageName));
  } catch {
    return {status: 'missing', reason: 'executable_missing'};
  }
}

async function checkExecutable(resolver: RuntimeResolver, path: string): Promise<RuntimeCheck> {
  try {
    // Do not follow a replaced link and do not claim that dynamic loading works.
    if (!(await resolver.executableExists(path))) {
      return {status: 'missing', reason: 'executable_missing'};
    }
    return {status: 'available', reason: 'installed_executable_found'};
  } catch {
    return {status: 'missing', reason: 'executable_missing'};
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2);
  const diagnostic = diagnoseCliArguments(args);
  diagnostic.then(result => {
    process.stdout.write(`${JSON.stringify(result)}\n`);
    if (args.length !== 0) process.exitCode = 2;
  }).catch(() => {
    process.stdout.write(`${JSON.stringify({
      schema_version: '1.0', runtime_verified: false, video_ready: false,
      checks: Object.fromEntries([
        'worker_modules', 'storyboard_browser', 'render_browser', 'ffmpeg', 'ffprobe', 'tts',
      ].map(name => [name, {status: 'unchecked', reason: 'configuration_unavailable'}])),
    })}\n`);
    process.exitCode = 1;
  });
}

function failedDiagnostic(): RuntimeDiagnostic {
  const check: RuntimeCheck = {status: 'unchecked', reason: 'configuration_unavailable'};
  return {
    schema_version: '1.0', runtime_verified: false, video_ready: false,
    checks: {
      worker_modules: check, storyboard_browser: check, render_browser: check,
      ffmpeg: check, ffprobe: check,
      tts: {status: 'not_configured', reason: 'adapter_not_configured'},
    },
  };
}
