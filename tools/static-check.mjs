import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { createRequire } from 'node:module';
import { extname, join } from 'node:path';

const projectRoot = new URL('../', import.meta.url).pathname;
const jsonFiles = [];

function assertAndroidStringResource(source, key, resourceFiles) {
  const reference = `R.string.${key}`;
  if (!source.includes(reference)) {
    throw new Error(`Android UI must reference localized resource ${reference}`);
  }
  for (const [locale, resourceFile] of Object.entries(resourceFiles)) {
    const path = join(projectRoot, 'android/app/src/main/res', locale, resourceFile);
    if (!existsSync(path)) {
      throw new Error(`Missing Android string resource file: ${locale}/${resourceFile}`);
    }
    const xml = readFileSync(path, 'utf8');
    if (!new RegExp(`<string\\s+name="${key}"(?:\\s|>)`).test(xml)) {
      throw new Error(`Missing localized Android string resource: ${locale}/${key}`);
    }
  }
}

function walk(directory) {
  for (const name of readdirSync(directory)) {
    if (directory === join(projectRoot, 'tools/hermes') && name === '.runtime') continue;
    if (name === '.git' || name === '.idea' || name === '.hvigor' || name === 'oh_modules' || name === 'node_modules' || name === '.local-cache' || name === '.local-output' || name === 'build') {
      continue;
    }
    const path = join(directory, name);
    if (statSync(path).isDirectory()) {
      walk(path);
      continue;
    }
    if (extname(path) === '.json' || extname(path) === '.json5') {
      jsonFiles.push(path);
    }
  }
}

walk(projectRoot);

for (const path of jsonFiles) {
  JSON.parse(readFileSync(path, 'utf8'));
}

const requiredFiles = [
  'AppScope/app.json5',
  'build-profile.json5',
  'hvigor/hvigor-config.json5',
  'entry/src/main/module.json5',
  'entry/src/main/ets/entryability/EntryAbility.ets',
  'entry/src/main/ets/pages/Index.ets',
  'entry/src/main/ets/model/InkModel.ets',
  'entry/src/main/ets/services/NoteStore.ets',
  'entry/src/main/resources/base/profile/main_pages.json',
  'android/app/build.gradle',
  'android/app/src/main/AndroidManifest.xml',
  'android/app/src/main/java/com/padnote/android/MainActivity.java',
  'android/app/src/main/java/com/padnote/android/NoteCanvasView.java',
  'android/app/src/main/java/com/padnote/android/NoteStore.java',
  'android/app/src/main/java/com/padnote/android/ToolIconButton.java',
  'android/app/src/main/java/com/padnote/android/ColorWheelView.java',
  'android/app/src/main/java/com/padnote/android/ColorSwatchButton.java',
  'android/app/src/main/java/com/padnote/android/SizePreviewView.java',
  'android/app/src/main/java/com/padnote/android/StrokeWidthPreviewView.java',
  'android/app/src/main/java/com/padnote/android/AiConfigStore.java',
  'android/app/src/main/java/com/padnote/android/OpenAiCompatibleClient.java',
  'android/app/src/main/java/com/padnote/android/AiMathWebView.java',
  'android/app/src/main/java/com/padnote/android/CompiledTextWebView.java',
  'android/app/src/main/java/com/padnote/android/NoteTextBox.java',
  'android/app/src/main/java/com/padnote/android/NoteTextBoxView.java',
  'android/app/src/main/java/com/padnote/android/TextFlow.java',
  'android/app/src/main/java/com/padnote/android/PageStyle.java',
  'android/app/src/main/java/com/padnote/android/PageStylePreviewView.java',
  'android/app/src/main/java/com/padnote/android/NoteTool.java',
  'android/app/src/main/java/com/padnote/android/NoteToolRegistry.java',
  'android/app/src/main/java/com/padnote/android/NoteToolContext.java',
  'android/app/src/main/java/com/padnote/android/NoteTools.java',
  'android/app/src/main/java/com/padnote/android/PageMap.java',
  'android/app/src/main/java/com/padnote/android/PlacementResolver.java',
  'android/app/src/main/assets/katex/katex.min.js',
  'android/app/src/main/assets/katex/katex.min.css',
  'android/app/src/main/assets/katex/fonts/KaTeX_Main-Regular.woff2',
  'android/app/src/main/assets/katex/LICENSE',
  'android/app/src/main/assets/mermaid/mermaid.min.js',
  'docs/DESIGN_LANGUAGE.md',
  'android/app/src/main/java/com/padnote/android/CoverStore.java',
  'android/app/src/main/assets/mermaid/LICENSE',
  'android/app/src/main/java/com/padnote/android/VaultStore.java',
  'THIRD_PARTY_NOTICES.md',
  'android/gradle/wrapper/gradle-wrapper.jar',
  'android/app/src/androidTest/java/com/padnote/android/BookshelfSmokeTest.java',
  'tools/emulator/start-headless.sh',
  'tools/emulator/stop-headless.sh',
  'tools/emulator/run-ui-smoke.sh',
  'docs/AGENT_INTEGRATION.md',
  'tools/run-agent-probe.sh',
  'tools/agent-probe/build.gradle',
  'tools/agent-probe/src/main/java/com/padnote/agentprobe/HermesProbe.java',
  'tools/agent-probe/src/main/java/com/padnote/agentprobe/OpenClawProbe.java',
  'tools/agent-probe/src/test/java/com/padnote/agentprobe/HermesProbeTest.java',
  'tools/agent-probe/src/test/java/com/padnote/agentprobe/OpenClawProbeTest.java'
];

for (const relativePath of requiredFiles) {
  if (!existsSync(join(projectRoot, relativePath))) {
    throw new Error(`Missing required project file: ${relativePath}`);
  }
}

if ((statSync(join(projectRoot, 'tools/run-agent-probe.sh')).mode & 0o111) === 0) {
  throw new Error('Agent probe entry must be executable.');
}

const pages = JSON.parse(readFileSync(join(projectRoot,
  'entry/src/main/resources/base/profile/main_pages.json'), 'utf8'));
for (const page of pages.src) {
  const pagePath = join(projectRoot, 'entry/src/main/ets', `${page}.ets`);
  if (!existsSync(pagePath)) {
    throw new Error(`Declared page does not exist: ${pagePath}`);
  }
}

const indexSource = readFileSync(join(projectRoot, 'entry/src/main/ets/pages/Index.ets'), 'utf8');
for (const requiredToken of ['touch.pressure', 'TouchType.Down', 'TouchType.Move', 'TouchType.Up',
  'NoteStore.save', 'NoteStore.load']) {
  if (!indexSource.includes(requiredToken)) {
    throw new Error(`Ink implementation is missing required token: ${requiredToken}`);
  }
}

const androidCanvasSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/NoteCanvasView.java'), 'utf8');
for (const requiredToken of ['getHistoricalPressure', 'getPressure', 'TOOL_TYPE_STYLUS',
  'requestUnbufferedDispatch', 'Tool.ERASER', 'Tool.LASSO', 'pointInsidePolygon',
  'onDocumentChanged', 'undoStack', 'redoStack', 'clipStrokeOutsideCircle',
  'eraseAlongPath', 'getTouchMajor', 'setEraserSizeDp', 'renderAiSelection',
  'selectionMaskPoints', 'Bitmap.CompressFormat.PNG', 'navigationGesture',
  'viewportScale', 'bottomPullDistance', 'addPageInternal', 'pageCount',
  'nextPageRevealFraction', 'nextPageCommitFraction', 'animateViewportBackToDocument',
  'textBoxes', 'NoteTextBox.Format', 'resizeTextBox',
  'turningTowardEarlierPages', 'R.string.canvas_turn_earlier',
  'isNewPageCreationPullActive', 'Spring-back is reserved', 'Tool.TEXT',
  'onTextInsertRequested', 'addTextBoxAt', 'commitNewTextBox', 'discardNewTextBox',
  'addAiResultTextBox', 'R.string.canvas_ai_flow_done',
  'onTextBoxSelectionChanged', 'selectTextBoxInsideLasso', 'textBoxIntersectsLasso',
  'reflowFlow', 'layoutFlow', 'splitTextFlowBlocks', 'expandOversizedTextBlocks',
  'flowCount', 'fontSizeSp',
  // schema 5: flows are the fact source, fragments are derived
  'textFlows', 'migrateLegacyTextBoxes', 'rebuildAllFlows',
  'anchorPageIndex', 'anchorForDraggedFragment', 'previewTextBoxDrag',
  'setTextFlowFontSize', 'flow.lineHeight',
  // page styles: paper is chosen at creation and fixed thereafter
  'pageStyle', 'drawPaperRuling', 'applyPageStyleForNewNote', 'rulingGap',
  // measured-height feedback that corrects a clipping estimate
  'applyMeasuredFragmentHeight', 'heightCorrection',
  // corner handle solves font size from the drawn area
  'scaleTextFlowToArea', 'measuredFlowHeight',
  'moveTextFlowByPages', 'updateEdgeHoldScroll', 'drawDragPreview',
  'getFirstVisiblePageIndex', 'BLOCK_HEIGHT_SAFETY', 'BlockKind']) {
  if (!androidCanvasSource.includes(requiredToken)) {
    throw new Error(`Android ink implementation is missing required token: ${requiredToken}`);
  }
}
assertAndroidStringResource(androidCanvasSource, 'canvas_turn_earlier', {
  values: 'strings_canvas.xml', 'values-zh-rCN': 'strings_canvas.xml'
});
assertAndroidStringResource(androidCanvasSource, 'canvas_ai_flow_done', {
  values: 'strings_canvas.xml', 'values-zh-rCN': 'strings_canvas.xml'
});

const compiledTextSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/CompiledTextWebView.java'), 'utf8');
for (const requiredToken of ['setBlockNetworkLoads(true)',
  'setAllowUniversalAccessFromFileURLs(false)', 'markdownToHtml', 'normalizeLatexSource',
  'flattenDisplayMathBlocks', 'katex.render', 'htmlAndMathml',
  'void render(NoteTextBox.Format',
  // vault reader: offline Mermaid diagram rendering
  'buildDocumentHtml', 'renderDocument', 'pre class="mermaid"', 'mermaid.initialize']) {
  if (!compiledTextSource.includes(requiredToken)) {
    throw new Error(`Compiled text renderer is missing required token: ${requiredToken}`);
  }
}

// The vault is plain Markdown files with front-matter; Obsidian compatibility
// and staleness tracking both depend on these tokens staying put.
const vaultStoreSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/VaultStore.java'), 'utf8');
for (const requiredToken of ['note-id', 'digitized-epoch', 'source-modified',
  'sanitizeTitle', 'embedTextFlow', '.md.tmp',
  // the tool layer's read-only view; storage must keep implementing it
  'implements NoteTools.VaultReader']) {
  if (!vaultStoreSource.includes(requiredToken)) {
    throw new Error(`Vault store is missing required token: ${requiredToken}`);
  }
}
const textObjectSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/NoteTextBoxView.java'), 'utf8');
for (const requiredToken of ['onInterceptTouchEvent', 'onResize', 'dispatchDraw',
  'dispatchTouchEvent', 'permanent invisible touch shield',
  'beginInlineEditing', 'sourceEditor', 'LIVE_PREVIEW_DELAY_MS',
  'listener.onCommit', 'InputMethodManager', 'selectedDeleteButton',
  'fontDecreaseButton', 'fontIncreaseButton', 'draftFontSizeSp',
  'R.string.widget_delete_this_box', 'listener.onDelete']) {
  if (!textObjectSource.includes(requiredToken)) {
    throw new Error(`PPT-style text object is missing required token: ${requiredToken}`);
  }
}
assertAndroidStringResource(textObjectSource, 'widget_delete_this_box', {
  values: 'strings_widgets.xml', 'values-zh-rCN': 'strings_widgets.xml'
});
const textModelSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/NoteTextBox.java'), 'utf8');
for (const requiredToken of ['flowId', 'flowIndex', 'flowCount', 'fragmentSource',
  'fontSizeSp', 'displaySource()', 'pageIndex']) {
  if (!textModelSource.includes(requiredToken)) {
    throw new Error(`Cross-page text model is missing required token: ${requiredToken}`);
  }
}

// The flow is the single write point for page text; fragments must stay derived.
const textFlowSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/TextFlow.java'), 'utf8');
for (const requiredToken of ['anchorPageIndex', 'anchorXInPage', 'anchorYInPage',
  'MAX_SOURCE_LENGTH', 'clampFontSize', 'clampLineHeight', 'DEFAULT_LINE_HEIGHT',
  'toJson', 'fromJson']) {
  if (!textFlowSource.includes(requiredToken)) {
    throw new Error(`TextFlow fact source is missing required token: ${requiredToken}`);
  }
}

// The tool layer's safety properties are structural, so guard the tokens that
// encode them: a model must not be able to name an unregistered tool, reach
// handwriting, or supply raw geometry.
const toolRegistrySource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/NoteToolRegistry.java'), 'utf8');
for (const requiredToken of ['未注册的工具', 'isPermitted', 'PlacementResolver.Failure',
  'placement_unresolvable']) {
  if (!toolRegistrySource.includes(requiredToken)) {
    throw new Error(`Tool registry is missing required token: ${requiredToken}`);
  }
}
const toolsSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/NoteTools.java'), 'utf8');
for (const requiredToken of ['read_page_map', 'write_text', 'set_text_flow_style',
  'move_text_flow', 'relativeTo', 'bands',
  // knowledge-base readers: the model can search and read, never write, the vault
  'search_vault', 'read_vault_note', 'VaultReader']) {
  if (!toolsSource.includes(requiredToken)) {
    throw new Error(`v1 tool set is missing required token: ${requiredToken}`);
  }
}
// v1 deliberately grants no authority over ink; without recognition the model
// cannot know what a stroke cluster means.
for (const forbiddenToken of ['move_ink', 'delete_flow', 'delete_text', 'edit_stroke']) {
  if (toolsSource.includes(forbiddenToken)) {
    throw new Error(`v1 must not expose destructive or ink tools: ${forbiddenToken}`);
  }
}
const pageMapSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/PageMap.java'), 'utf8');
for (const requiredToken of ['BAND_COUNT', 'cjkChars', 'freeRegions', 'inkClusters',
  'recognized']) {
  if (!pageMapSource.includes(requiredToken)) {
    throw new Error(`Page map is missing required token: ${requiredToken}`);
  }
}
const clientToolSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/OpenAiCompatibleClient.java'), 'utf8');
for (const requiredToken of ['tool_calls', 'tool_call_id', 'toProviderTools',
  'finish_reason', 'Completion',
  // the tool system prompt must tell the model the vault exists
  'search_vault', '知识库']) {
  if (!clientToolSource.includes(requiredToken)) {
    throw new Error(`AI client is missing tool-protocol token: ${requiredToken}`);
  }
}

const mathViewSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/AiMathWebView.java'), 'utf8');
for (const requiredToken of ['setBlockNetworkLoads(true)', 'setAllowUniversalAccessFromFileURLs(false)',
  'Content-Security-Policy', 'katex.render', 'containsMath']) {
  if (!mathViewSource.includes(requiredToken)) {
    throw new Error(`Local formula renderer is missing required token: ${requiredToken}`);
  }
}
const katexLicense = readFileSync(join(projectRoot,
  'android/app/src/main/assets/katex/LICENSE'), 'utf8');
if (!katexLicense.includes('The MIT License')) {
  throw new Error('Bundled KaTeX assets must retain the upstream MIT license.');
}
const mermaidLicense = readFileSync(join(projectRoot,
  'android/app/src/main/assets/mermaid/LICENSE'), 'utf8');
if (!mermaidLicense.includes('The MIT License')) {
  throw new Error('Bundled Mermaid assets must retain the upstream MIT license.');
}
const localRequire = createRequire(import.meta.url);
const katex = localRequire(join(projectRoot,
  'android/app/src/main/assets/katex/katex.min.js'));
const formulaSmokeTest = katex.renderToString(
  String.raw`f_n\longrightarrow 0\qquad\text{于 }L^1(\mathbb R).`,
  { displayMode: true, throwOnError: true });
if (!formulaSmokeTest.includes('katex-display') || !formulaSmokeTest.includes('mord')) {
  throw new Error('Bundled KaTeX failed the PadNote formula smoke test.');
}

const androidManifest = readFileSync(join(projectRoot, 'android/app/src/main/AndroidManifest.xml'), 'utf8');
if (!androidManifest.includes('android:allowBackup="false"')) {
  throw new Error('Android notes must not be included in default system backups.');
}
if (!androidManifest.includes('android.permission.INTERNET')) {
  throw new Error('Android AI client requires the INTERNET permission.');
}

const aiConfigSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/AiConfigStore.java'), 'utf8');
for (const requiredToken of ['AndroidKeyStore', 'AES/GCM/NoPadding', 'GCMParameterSpec',
  // switchable profiles: direct and split routes coexist as named slots
  'activeProfileId', 'transcribeConfig', 'answerConfig', 'migrateLegacyIfNeeded',
  'structurallyComplete', 'vendorPresets',
  // domestic token-plan presets ship their own key guidance
  'sk-sp-', 'token-plan.cn-beijing.maas.aliyuncs.com', '订阅 Key']) {
  if (!aiConfigSource.includes(requiredToken)) {
    throw new Error(`AI secure configuration is missing required token: ${requiredToken}`);
  }
}

const aiClientSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/OpenAiCompatibleClient.java'), 'utf8');
for (const requiredToken of ['HttpsURLConnection', 'image_url', 'chat/completions',
  'setInstanceFollowRedirects(false)', 'transcribe']) {
  if (!aiClientSource.includes(requiredToken)) {
    throw new Error(`AI client is missing required token: ${requiredToken}`);
  }
}

const typedProjectionSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/TypedNoteTimeProjection.java'), 'utf8');
if (!typedProjectionSource.includes('markdownProfileVersion')) {
  throw new Error('Typed note projection must preserve the Markdown profile field');
}
const typedProjectionCompact = typedProjectionSource.replace(/\s+/g, '');
if (!typedProjectionCompact.includes('schema>=10&&f.has("markdownProfileVersion")') ||
    !typedProjectionSource.includes('markdown_profile_requires_schema10')) {
  throw new Error('Typed note projection must preserve the profile for schema 10+ and reject it in legacy schemas');
}
const backupValidatorSource = readFileSync(join(projectRoot,
  'tools/validate-library-backup.py'), 'utf8');
const agentImageCases = JSON.parse(readFileSync(join(projectRoot,
  'tools/fixtures/AGENT_IMAGE_RECEIPT_CASES_R1.json'), 'utf8'));
const typographyCases = JSON.parse(readFileSync(join(projectRoot,
  'tools/fixtures/TYPOGRAPHY_CASES_R1.json'), 'utf8'));
for (const requiredToken of ['AGENT_IMAGE_FIELDS', 'canonical_agent_image_receipt',
  'validate_agent_image_receipt', 'IMAGE_RECEIPT_OLD_SCHEMA', 'IMAGE_RECEIPT_PNG_SHA',
  'json_integer_value(version, 1, 11)', 'NOTE_IMAGE_FIELDS', '_read_canonical_png',
  'IMAGE_TOTAL_SIZE', 'IMAGE_GEOMETRY']) {
  if (!backupValidatorSource.includes(requiredToken)) {
    throw new Error(`Archive validator is missing C39 image receipt check: ${requiredToken}`);
  }
}
if (agentImageCases.schema !== 'padnote.agent-image-receipt-cases.v1' ||
    agentImageCases.validSchema11.schemaVersion !== 11 ||
    agentImageCases.validSchema10.schemaVersion !== 10 ||
    agentImageCases.validSchema9.schemaVersion !== 9 ||
    !agentImageCases.validSchema11.images[0].agentImage ||
    !agentImageCases.cases.some(item => item.id === 'png_digest_mismatch' && !item.accepted) ||
    !agentImageCases.cases.some(item => item.id === 'duplicate_application_in_note' && !item.accepted)) {
  throw new Error('C39 shared Agent image receipt vectors are incomplete');
}
const schemaCases = typographyCases.schema_profile_cases ?? {};
if (schemaCases.schema_9_absent_profile_defaults_one?.accepted !== true ||
    schemaCases.schema_9_profile_field_rejected?.accepted !== false ||
    schemaCases.schema_10_missing_profile?.effective_profile !== 1 ||
    schemaCases.schema_10_profile_two?.accepted !== true ||
    schemaCases.schema_10_explicit_one_rejected?.accepted !== false ||
    agentImageCases.validSchema11.textFlows?.[0]?.markdownProfileVersion !== 2) {
  throw new Error('C39 schema 9/10/11 profile compatibility fixtures are incomplete');
}
if (!existsSync(join(projectRoot, 'docs/AGENT_IMAGE_TO_PAPER.md')) ||
    !existsSync(join(projectRoot, 'docs/AGENT_IMAGE_TO_PAPER.en.md'))) {
  throw new Error('Agent image placement compatibility guide is missing');
}

const noteStoreSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/NoteStore.java'), 'utf8');
for (const requiredToken of ['padnote-index.json', 'legacyMigrated', 'importDocument',
  'writeAtomic', 'MAX_NOTE_BYTES', 'schemaVersion", 10',
  'version >= 10', 'version < 10 && flowJson.has("markdownProfileVersion")',
  'textFlows']) {
  if (!noteStoreSource.includes(requiredToken)) {
    throw new Error(`Multi-note store is missing required token: ${requiredToken}`);
  }
}

const androidActivitySource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/MainActivity.java'), 'utf8');
for (const requiredToken of ['ACTION_OPEN_DOCUMENT', 'ACTION_CREATE_DOCUMENT',
  'showBookshelf', 'openNote', 'launchExportNote', 'aiOutputInline',
  'presentAiAnswer', 'handleAiCardResize', 'aiCardWidthDp', 'R.string.core_ai_write_mode_detail', 'R.string.core_ai_only_card',
  // profile manager + split route session state
  'showAiManagerDialog', 'showProfileEditorDialog', 'aiSessionTranscript',
  'startTranscriptionLeg', 'startAnswerLeg', 'R.string.core_two_step_help',
  // the registry is built in onCreate so vault readers get the store
  'NoteTools.createDefault()',
  // discoverability: onboarding card, vault guide, shared pill-button system
  'pillButton', 'addFirstRunGuideCard', 'showVaultGuideDialog', 'R.string.core_guide_title',
  // vault digitization: whole-note Markdown knowledge base
  'renderVaultSection', 'startDigitizationForCurrentNote',
  'showVaultReader', 'launchExportVaultFile',
  // taste rules live outside the model: design language doc + press feedback
  'applyPressFeedback',
  // covers: preset library + per-note cover file convention
  'CoverStore', 'showCoverPicker', 'setOnLongClickListener', 'R.string.cover_preset_add_button',
  'R.string.core_ai_answer_written_flow_notice']) {
  if (!androidActivitySource.includes(requiredToken)) {
    throw new Error(`Bookshelf activity is missing required token: ${requiredToken}`);
  }
}
assertAndroidStringResource(androidActivitySource, 'core_ai_write_mode_detail', {
  values: 'strings_core_more.xml', 'values-zh-rCN': 'strings_core_more.xml'
});
for (const key of ['core_ai_only_card', 'core_two_step_help', 'core_guide_title']) {
  assertAndroidStringResource(androidActivitySource, key, {
    values: 'strings.xml', 'values-zh-rCN': 'strings_core.xml'
  });
}
assertAndroidStringResource(androidActivitySource, 'cover_preset_add_button', {
  values: 'strings_covers.xml', 'values-zh-rCN': 'strings_covers.xml'
});
assertAndroidStringResource(androidActivitySource, 'core_ai_answer_written_flow_notice', {
  values: 'strings_core_more.xml', 'values-zh-rCN': 'strings_core_more.xml'
});

const digitizationSource = readFileSync(join(projectRoot,
  'android/app/src/main/java/com/padnote/android/DigitizationController.java'), 'utf8');
for (const requiredToken of ['private static final String PROMPT', 'transcriber.transcribe',
  'mergePageContent', 'vault.publishDigitization']) {
  if (!digitizationSource.includes(requiredToken)) {
    throw new Error(`Note digitization controller is missing required token: ${requiredToken}`);
  }
}

for (const [source, requiredTokens, label] of [
  [textFlowSource, ['version != 1 && version != 2', 'version == 2 && format != NoteTextBox.Format.MARKDOWN',
    'if (markdownProfileVersion == 2) json.put("markdownProfileVersion", 2)',
    '? parseMarkdownProfileVersion(json.opt("markdownProfileVersion")) : 1',
    'json.has("markdownProfileVersion") && format != NoteTextBox.Format.MARKDOWN'], 'Markdown profile serialization']
]) {
  for (const requiredToken of requiredTokens) {
    if (!source.includes(requiredToken)) {
      throw new Error(`${label} is missing required token: ${requiredToken}`);
    }
  }
}

console.log(`PadNote static check passed: ${jsonFiles.length} JSON/JSON5 files and ${requiredFiles.length} required files.`);
