export interface RendererProjectLocation {
  root: string;
  projectFile: string;
}

/**
 * The renderer plugin embeds projectFile directly in an ESM import, and the
 * renderer resolves it with path.join(process.cwd(), projectFile). Keep this
 * as a Vite-root-relative POSIX module ID; an absolute Windows path is both an
 * invalid JS string literal and is incorrectly joined by the upstream code.
 */
export function rendererProjectLocation(root: string): RendererProjectLocation {
  return {root, projectFile: '/renderer/project.ts'};
}
