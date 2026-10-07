import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {win32, posix} from 'node:path';
import test from 'node:test';
import {rendererProjectLocation} from '../scripts/renderer-project.js';

const require = createRequire(import.meta.url);
const rendererPlugin = require('@revideo/renderer/lib/server/renderer-plugin.js').rendererPlugin as (
  projectSettings: unknown,
  variables: unknown,
  ffmpegSettings: unknown,
  projectFile: string,
) => {load(id: string): Promise<string | undefined>};
const ts = require('typescript') as typeof import('typescript');

for (const {label, pathApi, root} of [
  {
    label: 'Windows root with Unicode, spaces, and quotes',
    pathApi: win32,
    root: String.raw`C:\PadNote\测试 workspace\O'Brien\skill`,
  },
  {
    label: 'POSIX root with Unicode, spaces, and quotes',
    pathApi: posix,
    root: "/opt/PadNote/测试 workspace/O'Brien/skill",
  },
]) {
  test(`renderer project import resolves under ${label}`, async () => {
    const location = rendererProjectLocation(root);
    assert.equal(location.root, root);
    assert.equal(location.projectFile, '/renderer/project.ts');

    // This is the locked renderer's actual path.join(process.cwd(), projectFile)
    // operation. process.cwd() is the Skill root in VideoWorker's invocation.
    assert.equal(pathApi.join(root, location.projectFile), pathApi.join(root, 'renderer/project.ts'));

    // Exercise the locked renderer plugin's real virtual-module load without
    // linking/evaluating the generated module or starting Vite/Puppeteer.
    const plugin = rendererPlugin({}, undefined, undefined, location.projectFile);
    const source = await plugin.load('\0virtual:renderer');
    assert.equal(typeof source, 'string');
    const compiled = ts.transpileModule(source!, {
      compilerOptions: {module: ts.ModuleKind.ESNext},
      reportDiagnostics: true,
    });
    assert.deepEqual(compiled.diagnostics ?? [], []);
    const ast = ts.createSourceFile('virtual-renderer.ts', source!, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    const projectImport = ast.statements.filter(ts.isImportDeclaration).find(statement =>
      ts.isStringLiteral(statement.moduleSpecifier)
      && statement.moduleSpecifier.text === '/renderer/project.ts');
    assert.ok(projectImport, 'virtual module must import the root-relative project ID');
    assert.ok(ts.isStringLiteral(projectImport.moduleSpecifier));
    assert.equal(projectImport.moduleSpecifier.text, '/renderer/project.ts');
    assert.equal(source!.includes('\\renderer\\project.ts'), false);
    assert.equal(/[\t\r\n]/.test(projectImport.moduleSpecifier.text), false);
  });
}
