# Third-party license inventory

Audited on 2026-09-02 from the clean Linux x64 install represented by `package-lock.json`. Every installed npm package is listed below as `name@version`. Package license metadata was read from its installed `package.json`; distribution must retain the license files shipped inside each npm package.

Important runtime components:

| Component | Version/build | License |
| --- | --- | --- |
| Revideo packages | 0.11.0 | MIT |
| KaTeX and bundled KaTeX fonts | 0.18.5 | MIT |
| MathJax full | 3.2.2 | Apache-2.0 |
| Puppeteer | 25.3.0 | Apache-2.0 |
| Chrome for Testing / Chromium | 150.0.7871.24 | BSD-3-Clause plus Chromium third-party notices shipped with the downloaded browser |
| `@ffmpeg-installer/ffmpeg` wrapper | 1.1.0 | LGPL-2.1 |
| bundled linux-x64 FFmpeg binary | `N-47683-g0e8eb07980-static` | GPLv3 build; reports `--enable-gpl --enable-version3` |
| `@ffprobe-installer/ffprobe` wrapper | 2.1.2 | LGPL-2.1 |
| bundled linux-x64 FFprobe binary | `N-66595-gc2b38619c0-static` | GPL-3.0 build; reports `--enable-gpl --enable-version3` |
| Droid Sans Fallback system font | Debian `fonts-droid-fallback` 8.1.0r7 | Apache-2.0 |
| local Node toolchain cache | 22.22.0 | Node.js license and bundled dependency notices; cache only, not committed |

The worker records the actual FFmpeg path, version line, build configuration, and GPL build designation in every `render.manifest.json`. Revideo telemetry is disabled with `DISABLE_TELEMETRY=true`.

## Apache-2.0

`@puppeteer/browsers@3.0.6`, `chromium-bidi@16.0.1`, `detect-libc@2.1.2`, `hls.js@1.7.1`, `mathjax-full@3.2.2`, `mhchemparser@4.2.1`, `mj-context-menu@0.6.1`, `puppeteer-core@25.3.0`, `puppeteer@25.3.0`, `speech-rule-engine@4.1.4`, `typescript@5.9.2`, `webdriver-bidi-protocol@0.4.2`.

## BSD-3-Clause

`devtools-protocol@0.0.1638949`, `fast-uri@3.1.6`, `mp4box@0.5.4`, `source-map-js@1.2.1`, `source-map@0.7.6`.

## GPL binary packages

`@ffmpeg-installer/linux-x64@4.1.0` (metadata: GPLv3), `@ffprobe-installer/linux-x64@5.2.0` (metadata: GPL-3.0).

## ISC

`cliui@9.0.1`, `dezalgo@1.0.4`, `fastq@1.20.3`, `get-caller-file@2.0.5`, `glob-parent@5.1.2`, `isexe@2.0.0`, `once@1.4.0`, `picocolors@1.1.1`, `which@1.3.1`, `wrappy@1.0.2`, `y18n@5.0.8`, `yargs-parser@22.0.0`.

## LGPL-2.1 wrappers

`@ffmpeg-installer/ffmpeg@1.1.0`, `@ffprobe-installer/ffprobe@2.1.2`.

## MIT

`@codemirror/language@6.12.4`, `@codemirror/state@6.7.2`, `@codemirror/view@6.43.10`, `@esbuild/linux-x64@0.28.2`, `@lezer/common@1.5.2`, `@lezer/highlight@1.2.3`, `@lezer/lr@1.4.10`, `@marijn/find-cluster-break@1.0.4`, `@noble/hashes@1.8.0`, `@nodelib/fs.scandir@2.1.5`, `@nodelib/fs.stat@2.0.5`, `@nodelib/fs.walk@1.2.8`, `@oxc-project/types@0.147.0`, `@paralleldrive/cuid2@2.3.1`, `@posthog/core@1.50.2`, `@posthog/types@1.407.2`, `@preact/signals-core@1.14.4`, `@preact/signals@2.11.1`, `@revideo/2d@0.11.0`, `@revideo/core@0.11.0`, `@revideo/ffmpeg@0.11.0`, `@revideo/renderer@0.11.0`, `@revideo/telemetry@0.11.0`, `@revideo/ui@0.11.0`, `@revideo/vite-plugin@0.11.0`, `@rive-app/canvas-advanced@2.7.3`, `@rolldown/binding-linux-x64-gnu@1.2.6`, `@rolldown/binding-linux-x64-musl@1.2.6`, `@rolldown/pluginutils@1.0.1`, `@types/hast@2.3.10`, `@types/node@24.3.0`, `@types/unist@2.0.11`, `@wooorm/starry-night@1.7.0`, `@xmldom/xmldom@0.9.10`, `ajv@8.20.0`, `ansi-regex@6.3.0`, `ansi-styles@6.2.3`, `asap@2.0.6`, `async@0.2.10`, `braces@3.0.3`, `code-fns@0.8.2`, `commander@13.1.0`, `commander@15.0.0`, `crelt@1.0.7`, `culori@4.0.2`, `emoji-regex@10.6.0`, `esbuild@0.28.2`, `escalade@3.2.0`, `esm@3.2.25`, `fast-deep-equal@3.1.3`, `fast-glob@3.3.3`, `fdir@6.5.0`, `fill-range@7.1.1`, `fluent-ffmpeg@2.1.3`, `follow-redirects@1.16.0`, `formidable@3.5.4`, `get-east-asian-width@1.6.0`, `import-meta-resolve@2.2.2`, `is-extglob@2.1.1`, `is-glob@4.0.3`, `is-number@7.0.0`, `json-schema-traverse@1.0.0`, `katex@0.18.5`, `lilconfig@3.1.3`, `merge2@1.4.1`, `micromatch@4.0.8`, `mime-db@1.54.0`, `mime-types@3.0.2`, `mitt@3.0.1`, `modern-tar@0.7.7`, `mp4-wasm@1.0.6`, `nanoid@3.3.18`, `parse-svg-path@0.2.0`, `picomatch@2.3.2`, `picomatch@4.0.7`, `postcss@8.5.26`, `posthog-node@5.51.6`, `preact@10.29.8`, `queue-microtask@1.2.3`, `require-from-string@2.0.2`, `reusify@1.1.0`, `rolldown@1.2.6`, `run-parallel@1.2.0`, `string-width@7.2.0`, `string-width@8.2.2`, `strip-ansi@7.2.0`, `style-mod@4.1.3`, `tinyglobby@0.2.17`, `to-regex-range@5.0.1`, `tsx@4.23.13`, `typed-query-selector@2.12.2`, `undici-types@7.10.0`, `uuid@14.0.2`, `vite@8.2.2`, `vscode-oniguruma@1.7.0`, `vscode-textmate@9.3.2`, `w3c-keyname@2.2.8`, `wicked-good-xpath@1.3.0`, `wrap-ansi@9.0.2`, `ws@8.21.3`, `yargs@18.1.0`, `zod@3.25.76`.

## MPL-2.0

`lightningcss-linux-x64-gnu@1.33.0`, `lightningcss-linux-x64-musl@1.33.0`, `lightningcss@1.33.0`.

Platform-specific optional packages present only in the lock file are not installed on this Linux x64 worker. A distributor targeting another platform must repeat the install-time license audit for the platform-specific binaries selected by npm.
