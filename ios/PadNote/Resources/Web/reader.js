'use strict';
const escapeHTML = s => s.replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
let renderingRevision = 0;
const PAPER_CHUNK_HEIGHT = 960;

function waitForFrames(timeout = 120) {
    return new Promise(resolve => {
        let completed = false;
        const finish = () => { if (!completed) { completed = true; resolve(); } };
        setTimeout(finish, timeout);
        requestAnimationFrame(() => requestAnimationFrame(finish));
    });
}

async function settleLayout() {
    // Detached or background WKWebViews may not receive animation frames, and
    // a damaged font resource must not stall paper compilation indefinitely.
    let fontsReady = false;
    await Promise.race([
        document.fonts.ready.then(() => { fontsReady = true; }),
        new Promise(resolve => setTimeout(resolve, 500))
    ]);
    await waitForFrames();
    return fontsReady;
}

// WebKit snapshots do not include horizontally scrolled content. Scale only
// formulas that truly exceed the paper column, so every symbol remains in the
// exported image while ordinary equations keep their normal type size.
function fitDisplayMath(root) {
    const available = Math.max(1, root.clientWidth);
    root.querySelectorAll('.katex-display > .katex').forEach(math => {
        math.style.fontSize = '';
        math.classList.remove('wrapped-formula', 'fitted-formula');
        const bases = Array.from(math.querySelectorAll('.katex-html > .base'));
        const naturalWidth = Math.max(math.scrollWidth, math.getBoundingClientRect().width);
        const widestBase = Math.max(0, ...bases.map(base => Math.max(base.scrollWidth, base.getBoundingClientRect().width)));
        if (naturalWidth > available && bases.length > 1) {
            // KaTeX emits sibling .base spans at valid expression boundaries.
            // Let those pieces wrap at the normal font size before considering
            // the small-type fallback needed for a single indivisible piece.
            math.classList.add('wrapped-formula');
        }
        if (widestBase > available || (bases.length <= 1 && naturalWidth > available)) {
            const currentSize = parseFloat(getComputedStyle(math).fontSize) || 16;
            const indivisibleWidth = bases.length > 1 ? widestBase : naturalWidth;
            math.style.fontSize = (currentSize * available / indivisibleWidth) + 'px';
            math.classList.add('fitted-formula');
        }
    });
}

// Mermaid sometimes emits a width-limited SVG inside a tall fixed box. Use
// its viewBox as the source of truth and choose both dimensions from one scale
// factor. Wide and portrait diagrams therefore retain their actual aspect.
function fitDiagrams(root) {
    const availableWidth = Math.max(1, root.clientWidth);
    root.querySelectorAll('.diagram svg').forEach(svg => {
        const viewBox = svg.viewBox && svg.viewBox.baseVal;
        let naturalWidth = viewBox && viewBox.width > 0 ? viewBox.width : parseFloat(svg.getAttribute('width'));
        let naturalHeight = viewBox && viewBox.height > 0 ? viewBox.height : parseFloat(svg.getAttribute('height'));
        if (!(naturalWidth > 0) || !(naturalHeight > 0)) {
            const box = svg.getBBox(); naturalWidth = box.width; naturalHeight = box.height;
        }
        if (!(naturalWidth > 0) || !(naturalHeight > 0)) return;
        const scale = Math.min(1, availableWidth / naturalWidth, PAPER_CHUNK_HEIGHT / naturalHeight);
        svg.setAttribute('width', String(naturalWidth * scale));
        svg.setAttribute('height', String(naturalHeight * scale));
        svg.style.width = (naturalWidth * scale) + 'px';
        svg.style.height = (naturalHeight * scale) + 'px';
        svg.style.maxWidth = '100%';
        svg.style.maxHeight = 'none';
        svg.style.aspectRatio = naturalWidth + ' / ' + naturalHeight;
        svg.setAttribute('preserveAspectRatio', 'xMidYMin meet');
    });
}

async function finishRichLayout(root) {
    const firstFontsReady = await settleLayout();
    fitDisplayMath(root);
    fitDiagrams(root);
    const secondFontsReady = await settleLayout();
    return firstFontsReady || secondFontsReady;
}
function inline(source, diagnostics) {
    const pattern = /\\\[([\s\S]*?)\\\]|\$\$([\s\S]*?)\$\$|\\\(([\s\S]*?)\\\)|\$([^$\n]+)\$|`([^`\n]+)`/g;
    let output = '', last = 0, match;
    function prose(s) {
        return escapeHTML(s)
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/__([^_]+)__/g, '<strong>$1</strong>')
            .replace(/~~([^~]+)~~/g, '<del>$1</del>')
            .replace(/\*([^*]+)\*/g, '<em>$1</em>')
            .replace(/_([^_]+)_/g, '<em>$1</em>')
            .replace(/\[([^\]]+)\]\([^)]+\)/g, '<span class="md-link">$1</span>');
    }
    while ((match = pattern.exec(source))) {
        output += prose(source.slice(last, match.index));
        if (match[5] !== undefined) output += '<code>' + escapeHTML(match[5]) + '</code>';
        else {
            const formula = match[1] ?? match[2] ?? match[3] ?? match[4];
            if (!window.katex) output += escapeHTML(match[0]);
            else {
                try {
                    output += katex.renderToString(formula, {throwOnError:true, trust:false, strict:'ignore', maxExpand:1000, maxSize:20, displayMode:match[1] !== undefined || match[2] !== undefined});
                } catch (_) {
                    diagnostics.push({kind:'syntax', detail:'katex'});
                    output += '<span class="katex-source-error">' + escapeHTML(match[0]) + '</span>';
                }
            }
        }
        last = pattern.lastIndex;
    }
    return output + prose(source.slice(last));
}

// C38 profile 2 tokenizes inline nodes before emphasis, so delimiters can
// enclose math nodes without leaking into the paper and code stays opaque.
function parseProfile2Inline(source, depth = 0) {
    if (depth > 24) return [{type:'text', value:source}];
    const nodes = [];
    let text = '';
    const flush = () => { if (text) { nodes.push({type:'text', value:text}); text = ''; } };
    const escapedAt = new Uint8Array(source.length);
    let backslashRun = 0;
    for (let index = 0; index < source.length; index++) {
        escapedAt[index] = backslashRun & 1;
        backslashRun = source[index] === '\\' ? backslashRun + 1 : 0;
    }
    const isEscaped = index => index >= 0 && index < escapedAt.length && escapedAt[index] === 1;
    // Delimiter lookahead is bounded linearly in source size as well as by the
    // recursive nesting depth, so malformed input cannot trigger unbounded scans.
    let matchingBudget = source.length * 32 + 1024;
    const matchingStep = () => matchingBudget-- > 0;
    const mathAt = index => {
        if (source.startsWith('\\(', index) && !isEscaped(index)) return {open:'\\(', close:'\\)', display:false};
        if (source.startsWith('\\[', index) && !isEscaped(index)) return {open:'\\[', close:'\\]', display:true};
        if (source.startsWith('$$', index) && !isEscaped(index)) return {open:'$$', close:'$$', display:true};
        if (source[index] === '$' && !isEscaped(index)) return {open:'$', close:'$', display:false};
        return null;
    };
    const findMathClose = (close, from) => {
        for (let cursor = from; cursor <= source.length - close.length; cursor++) {
            if (!matchingStep()) return -1;
            if (source.startsWith(close, cursor) && !isEscaped(cursor)) return cursor;
        }
        return -1;
    };
    const runLength = (index, marker) => {
        let end = index;
        while (end < source.length && source[end] === marker) end++;
        return end - index;
    };
    const codeClose = (from, expectedRun) => {
        for (let i = from; i < source.length;) {
            if (!matchingStep()) return -1;
            if (source[i] !== '`') { i++; continue; }
            const run = runLength(i, '`');
            if (run === expectedRun && !isEscaped(i)) return i;
            i += run;
        }
        return -1;
    };
    const delimiterAt = index => {
        const marker = source[index];
        if (marker === '~') {
            const run = runLength(index, marker);
            return run === 2 && !isEscaped(index) ? {marker:'~~', run, type:'strikethrough'} : null;
        }
        if (marker !== '*' && marker !== '_') return null;
        const run = runLength(index, marker);
        if (isEscaped(index)) return null;
        if (run === 3) return {marker:marker.repeat(3), run, type:'triple'};
        if (run === 2) return {marker:marker.repeat(2), run, type:'strong'};
        if (run === 1) return {marker, run, type:'emphasis'};
        return null;
    };
    const findClosing = (delimiter, from, nestingDepth = 0) => {
        if (nestingDepth > 24) return -1;
        for (let i = from; i < source.length;) {
            if (!matchingStep()) return -1;
            if (source[i] === '`') {
                const run = runLength(i, '`');
                const end = codeClose(i + run, run);
                if (end >= 0) { i = end + run; continue; }
                i += run; continue;
            }
            const math = mathAt(i);
            if (math) {
                const end = findMathClose(math.close, i + math.open.length);
                if (end >= 0 && (math.display || !source.slice(i, end).includes('\n'))) {
                    i = end + math.close.length;
                    continue;
                }
            }
            if (source[i] === '\\') { i += Math.min(2, source.length - i); continue; }
            const marker = delimiterAt(i);
            if (marker) {
                if (marker.marker === delimiter) return i;
                if (!/\s/.test(source[i + marker.run] || ' ')) {
                    const nestedEnd = findClosing(marker.marker, i + marker.run, nestingDepth + 1);
                    if (nestedEnd > i + marker.run && !/\s/.test(source[nestedEnd - 1] || ' ')) {
                        i = nestedEnd + marker.run;
                        continue;
                    }
                }
                // Marker runs are atomic. A single `*` close may never consume
                // one character from a `**`/`***` token (and vice versa).
                i += marker.run;
                continue;
            }
            if (source[i] === '*' || source[i] === '_' || source[i] === '~') {
                i += runLength(i, source[i]);
                continue;
            }
            i++;
        }
        return -1;
    };
    for (let i = 0; i < source.length;) {
        // Recognize TeX delimiters before Markdown's general backslash escape
        // rule. Otherwise \( and \[ are reduced to literal parentheses/brackets.
        const math = mathAt(i);
        if (math) {
            const end = findMathClose(math.close, i + math.open.length);
            if (end > i + math.open.length && (math.display || !source.slice(i, end).includes('\n'))) {
                flush(); nodes.push({type:'math', value:source.slice(i + math.open.length, end), display:math.display});
                i = end + math.close.length; continue;
            }
            // Unmatched delimiters remain source text instead of being eaten as
            // an ordinary Markdown escape.
            text += math.open; i += math.open.length; continue;
        }
        if (source[i] === '\\' && i + 1 < source.length) {
            const next = source[i + 1];
            if ('\\`*_{}[]()#+-.!>$'.includes(next)) { text += next; i += 2; continue; }
        }
        if (source[i] === '`') {
            const run = runLength(i, '`');
            const end = codeClose(i + run, run);
            if (end >= 0) { flush(); nodes.push({type:'code', value:source.slice(i + run, end)}); i = end + run; continue; }
            text += source.slice(i, i + run); i += run; continue;
        }
        if (source[i] === '[') {
            const closeLabel = source.indexOf('](', i + 1);
            if (closeLabel > i) {
                const closeDestination = source.indexOf(')', closeLabel + 2);
                if (closeDestination > closeLabel + 2) {
                    flush();
                    nodes.push({type:'link', destination:source.slice(closeLabel + 2, closeDestination),
                        children:parseProfile2Inline(source.slice(i + 1, closeLabel), depth + 1)});
                    i = closeDestination + 1; continue;
                }
            }
        }
        const marker = delimiterAt(i);
        if (marker && i + marker.run < source.length && !/\s/.test(source[i + marker.run])) {
            const end = findClosing(marker.marker, i + marker.run);
            if (end > i + marker.run && !/\s/.test(source[end - 1])) {
                flush();
                const children = parseProfile2Inline(source.slice(i + marker.run, end), depth + 1);
                nodes.push(marker.type === 'triple'
                    ? {type:'strong', children:[{type:'emphasis', children}]}
                    : {type:marker.type, children});
                i = end + marker.run; continue;
            }
            text += source.slice(i, i + marker.run); i += marker.run; continue;
        }
        if (!marker && (source[i] === '*' || source[i] === '_' || source[i] === '~')) {
            const run = runLength(i, source[i]);
            text += source.slice(i, i + run); i += run; continue;
        }
        text += source[i++];
    }
    flush();
    return nodes;
}

function renderProfile2Inline(nodes, parent, diagnostics, strong = false) {
    for (const node of nodes) {
        if (node.type === 'text') parent.append(document.createTextNode(node.value));
        else if (node.type === 'code') {
            const code = document.createElement('code'); code.textContent = node.value; parent.append(code);
        } else if (node.type === 'math') {
            if (!window.katex) { parent.append(document.createTextNode('$' + node.value + '$')); continue; }
            try {
                const value = strong ? '\\boldsymbol{' + node.value + '}' : node.value;
                const span = document.createElement('span');
                span.innerHTML = katex.renderToString(value, {throwOnError:true, trust:false, strict:'ignore', maxExpand:1000, maxSize:20, displayMode:!!node.display});
                if (node.display) span.className = 'profile2-display-math';
                parent.append(span);
            } catch (_) {
                diagnostics.push({kind:'syntax', detail:'katex'});
                const literal = document.createElement('span'); literal.className = 'katex-source-error'; literal.textContent = '$' + node.value + '$'; parent.append(literal);
            }
        } else if (node.type === 'strong' || node.type === 'emphasis' || node.type === 'strikethrough') {
            const tag = node.type === 'strong' ? 'strong' : node.type === 'emphasis' ? 'em' : 'del';
            const element = document.createElement(tag);
            renderProfile2Inline(node.children, element, diagnostics, strong || node.type === 'strong'); parent.append(element);
        } else if (node.type === 'link') {
            const element = document.createElement('span'); element.className = 'md-link';
            element.setAttribute('data-destination', node.destination);
            renderProfile2Inline(node.children, element, diagnostics, strong); parent.append(element);
        }
    }
}

function appendProfile2Markdown(source, root, diagnostics, diagrams) {
    const lines = source.replace(/\r\n?/g, '\n').split('\n');
    const isRule = line => /^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line);
    const tableCells = line => line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim());
    const alignmentRow = line => {
        const cells = tableCells(line);
        if (!cells.length || !cells.every(cell => /^:?-{3,}:?$/.test(cell))) return null;
        return cells.map(cell => cell.startsWith(':') && cell.endsWith(':') ? 'center'
            : cell.endsWith(':') ? 'right' : cell.startsWith(':') ? 'left' : 'left');
    };
    const appendInlineBlock = (tag, value) => {
        const element = document.createElement(tag);
        renderProfile2Inline(parseProfile2Inline(value), element, diagnostics);
        root.append(element);
    };
    const appendDisplayMath = formula => {
        const block = document.createElement('div'); block.className = 'profile2-display-math';
        if (!window.katex || typeof window.katex.renderToString !== 'function') {
            diagnostics.push({kind:'resource', detail:'katex-script'}); block.textContent = formula;
        } else {
            try { block.innerHTML = katex.renderToString(formula, {throwOnError:true, trust:false, strict:'ignore', maxExpand:1000, maxSize:20, displayMode:true}); }
            catch (_) { diagnostics.push({kind:'syntax', detail:'katex'}); block.textContent = formula; }
        }
        root.append(block);
    };
    const appendTable = (headerLine, alignments, bodyLines) => {
        const table = document.createElement('table');
        const head = document.createElement('thead'); const headRow = document.createElement('tr');
        tableCells(headerLine).forEach((cell, index) => {
            const th = document.createElement('th'); th.style.textAlign = alignments[index] || 'left';
            renderProfile2Inline(parseProfile2Inline(cell), th, diagnostics); headRow.append(th);
        });
        head.append(headRow); table.append(head);
        const body = document.createElement('tbody');
        for (const line of bodyLines) {
            if (!line.includes('|')) break;
            const cells = tableCells(line); const row = document.createElement('tr');
            for (let i = 0; i < tableCells(headerLine).length; i++) {
                const td = document.createElement('td'); td.style.textAlign = alignments[i] || 'left';
                renderProfile2Inline(parseProfile2Inline(cells[i] || ''), td, diagnostics); row.append(td);
            }
            body.append(row);
        }
        table.append(body); root.append(table);
    };
    let i = 0, prose = [];
    const flush = () => {
        for (let j = 0; j < prose.length;) {
            const line = prose[j];
            if (!line.trim()) { j++; continue; }
            const displayStart = line.trim().startsWith('\\[') ? ['\\[', '\\]']
                : line.trim().startsWith('$$') ? ['$$', '$$'] : null;
            if (displayStart) {
                const firstLine = line.trim();
                const inlineEnd = firstLine.indexOf(displayStart[1], displayStart[0].length);
                let formula = '', endLine = j;
                if (inlineEnd >= 0) {
                    formula = firstLine.slice(displayStart[0].length, inlineEnd);
                    if (firstLine.slice(inlineEnd + displayStart[1].length).trim()) {
                        appendInlineBlock('p', line); j++; continue;
                    }
                } else {
                    const pieces = [firstLine.slice(displayStart[0].length)];
                    let closed = false;
                    for (let k = j + 1; k < prose.length; k++) {
                        const close = prose[k].indexOf(displayStart[1]);
                        if (close >= 0) {
                            pieces.push(prose[k].slice(0, close)); endLine = k; closed = true;
                            if (prose[k].slice(close + displayStart[1].length).trim()) pieces.push(prose[k].slice(close + displayStart[1].length));
                            break;
                        }
                        pieces.push(prose[k]);
                    }
                    if (!closed) { appendInlineBlock('p', line); j++; continue; }
                    formula = pieces.join('\n');
                }
                appendDisplayMath(formula.trim()); j = endLine + 1; continue;
            }
            if (isRule(line)) {
                const rule = document.createElement('hr'); rule.className = 'thematic-break'; root.append(rule); j++; continue;
            }
            if (j + 1 < prose.length && prose[j].includes('|')) {
                const alignments = alignmentRow(prose[j + 1]);
                if (alignments && tableCells(prose[j]).length === alignments.length) {
                    let end = j + 2; while (end < prose.length && prose[end].includes('|')) end++;
                    appendTable(prose[j], alignments, prose.slice(j + 2, end)); j = end; continue;
                }
            }
            const heading = line.match(/^(#{1,3})\s+(.*)$/);
            const quote = line.match(/^>\s?(.*)$/);
            const unordered = line.match(/^\s*[-*+]\s+(.*)$/);
            const ordered = line.match(/^\s*(\d+)[.)]\s+(.*)$/);
            if (heading) { appendInlineBlock('h' + heading[1].length, heading[2]); j++; continue; }
            if (quote) { appendInlineBlock('blockquote', quote[1]); j++; continue; }
            if (unordered || ordered) {
                const kind = unordered ? 'ul' : 'ol'; const list = document.createElement(kind);
                if (ordered) list.start = Number(ordered[1]);
                while (j < prose.length) {
                    const item = prose[j].match(unordered ? /^\s*[-*+]\s+(.*)$/ : /^\s*(\d+)[.)]\s+(.*)$/);
                    if (!item) break;
                    const li = document.createElement('li'); renderProfile2Inline(parseProfile2Inline(unordered ? item[1] : item[2]), li, diagnostics); list.append(li); j++;
                }
                root.append(list); continue;
            }
            appendInlineBlock('p', line); j++;
        }
        prose = [];
    };
    while (i < lines.length) {
        const fence = lines[i].match(/^\s*```([^\n]*)$/);
        if (!fence) { prose.push(lines[i++]); continue; }
        flush();
        const language = fence[1].trim().toLowerCase(); const code = []; i++;
        while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) code.push(lines[i++]);
        if (i < lines.length) i++;
        const element = document.createElement(language === 'mermaid' ? 'div' : 'pre');
        element.textContent = code.join('\n');
        if (language === 'mermaid') { element.className = 'diagram'; diagrams.push(element); }
        else element.className = 'code-block';
        root.append(element);
    }
    flush();
}

window.renderNote = async function (source, size, format = 'markdown', profileVersion = 1) {
    const revision = ++renderingRevision;
    const diagnostics = [];
    document.body.style.fontSize = Number(size) + 'px';
    const root = document.getElementById('content');
    root.replaceChildren();
    // Accessing cssRules for a file stylesheet is not portable in WKWebView:
    // WebKit may reject it even though the sheet is loaded. Probe an authored
    // computed property instead, which also detects a missing/broken sheet.
    if (getComputedStyle(document.body).overflowWrap !== 'anywhere') {
        diagnostics.push({kind:'resource', detail:'reader-style'});
    }
    const containsMath = format === 'latex' || /\\\[[\s\S]*?\\\]|\$\$[\s\S]*?\$\$|\\\([\s\S]*?\\\)|\$[^$\n]+\$/.test(source);
    if (containsMath && (!window.katex || typeof window.katex.renderToString !== 'function')) {
        diagnostics.push({kind:'resource', detail:'katex-script'});
    }
    if (format === 'latex') {
        let formula = source.trim();
        for (const [start, end] of [['\\[','\\]'], ['\\(','\\)'], ['$$','$$'], ['$','$']]) {
            if (formula.startsWith(start) && formula.endsWith(end) && formula.length >= start.length + end.length) {
                formula = formula.slice(start.length, -end.length).trim(); break;
            }
        }
        const block = document.createElement('div');
        if (!window.katex || typeof window.katex.renderToString !== 'function') {
            diagnostics.push({kind:'resource', detail:'katex-script'});
            return {formulas:0, height:Math.max(1, root.scrollHeight), diagnostics};
        }
        try {
            block.innerHTML = katex.renderToString(formula, {throwOnError:true, trust:false, strict:'ignore', maxExpand:1000, maxSize:20, displayMode:true});
        } catch (_) {
            diagnostics.push({kind:'syntax', detail:'katex'});
            block.textContent = source;
        }
        root.append(block);
        const fontsReady = await finishRichLayout(root);
        if (root.querySelector('.katex-error')) diagnostics.push({kind:'syntax', detail:'katex'});
        const renderedMath = root.querySelector('.katex');
        if (renderedMath) {
            const family = getComputedStyle(renderedMath).fontFamily || '';
            if (!family.includes('KaTeX') || !fontsReady || !document.fonts.check('16px "KaTeX_Main"')) {
                diagnostics.push({kind:'resource', detail:'katex-font'});
            }
        }
        return {formulas:root.querySelectorAll('.katex').length, height:root.scrollHeight, diagnostics};
    }
    const diagrams = [];
    if (profileVersion === 2) {
        appendProfile2Markdown(source, root, diagnostics, diagrams);
        if (diagrams.length && (!window.mermaid || typeof window.mermaid.render !== 'function')) {
            diagnostics.push({kind:'resource', detail:'mermaid-script'});
        } else if (diagrams.length) {
            mermaid.initialize({startOnLoad:false, securityLevel:'strict', theme:'neutral', suppressErrorRendering:true, maxTextSize:50000});
            for (let i = 0; i < diagrams.length; i++) {
                const element = diagrams[i], original = element.textContent;
                try {
                    const {svg} = await mermaid.render('diagram-' + revision + '-' + i, original);
                    if (revision !== renderingRevision) return;
                    element.innerHTML = svg;
                } catch (_) {
                    diagnostics.push({kind:'syntax', detail:'mermaid'});
                    element.textContent = '图表无法显示，请检查 Mermaid 语法。\n' + original;
                }
            }
        }
        const fontsReady = await finishRichLayout(root);
        if (root.querySelector('.katex-error')) diagnostics.push({kind:'syntax', detail:'katex'});
        const renderedMath = root.querySelector('.katex');
        if (renderedMath && (!window.katex || !fontsReady || !document.fonts.check('16px "KaTeX_Main"'))) {
            diagnostics.push({kind:'resource', detail:'katex-font'});
        }
        return {formulas:root.querySelectorAll('.katex').length, height:root.scrollHeight, diagnostics};
    }
    const lines = source.replace(/\r\n?/g, '\n').split('\n');
    let proseLines = [];
    function appendProseLines(values) {
        if (!values.length) return;
        // Keep display formula blocks together even when their source spans lines.
        const blocks = values.join('\n').split(/(\\\[[\s\S]*?\\\]|\$\$[\s\S]*?\$\$)/g);
        blocks.forEach(block => {
            if (!block.trim()) return;
            if (/^(\\\[|\$\$)/.test(block)) {
                const div = document.createElement('div'); div.innerHTML = inline(block, diagnostics); root.append(div); return;
            }
            let listType = null, listElement = null;
            block.split('\n').forEach(line => {
                const heading = line.match(/^(#{1,3})\s+(.*)/);
                const unordered = line.match(/^\s*[-*+]\s+(.*)/);
                const ordered = line.match(/^\s*(\d+)[.)]\s+(.*)/);
                const quote = line.match(/^>\s?(.*)/);
                const kind = unordered ? 'ul' : ordered ? 'ol' : null;
                if (kind !== listType) { if (listElement) root.append(listElement); listElement = null; listType = kind; }
                if (kind) {
                    if (!listElement) {
                        listElement = document.createElement(kind);
                        if (ordered) listElement.start = Number(ordered[1]);
                    }
                    const item = document.createElement('li');
                    item.innerHTML = inline(unordered ? unordered[1] : ordered[2], diagnostics) || '<br>';
                    listElement.append(item); return;
                }
                if (listElement) { root.append(listElement); listElement = null; listType = null; }
                // Markdown blank lines separate blocks; they are not visible
                // paragraphs. Fenced code is handled separately and keeps its
                // blank lines through textContent.
                if (!line.trim()) return;
                const element = document.createElement(heading ? 'h' + heading[1].length : quote ? 'blockquote' : 'p');
                element.innerHTML = inline(heading ? heading[2] : quote ? quote[1] : line, diagnostics);
                root.append(element);
            });
            if (listElement) root.append(listElement);
        });
    }
    function appendFence(language, code) {
        const element = document.createElement(language === 'mermaid' ? 'div' : 'pre');
        element.textContent = code;
        if (language === 'mermaid') { element.className = 'diagram'; diagrams.push(element); }
        else element.className = 'code-block';
        root.append(element);
    }
    for (let i = 0; i < lines.length;) {
        const fence = lines[i].match(/^\s*```([^\n]*)$/);
        if (!fence) { proseLines.push(lines[i++]); continue; }
        appendProseLines(proseLines); proseLines = [];
        const language = fence[1].trim().toLowerCase(); let code = []; i++;
        while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) code.push(lines[i++]);
        if (i < lines.length) i++;
        appendFence(language, code.join('\n'));
    }
    appendProseLines(proseLines);
    if (diagrams.length && (!window.mermaid || typeof window.mermaid.render !== 'function')) {
        diagnostics.push({kind:'resource', detail:'mermaid-script'});
    } else if (diagrams.length) {
        mermaid.initialize({startOnLoad:false, securityLevel:'strict', theme:'neutral', suppressErrorRendering:true, maxTextSize:50000});
        for (let i = 0; i < diagrams.length; i++) {
            const element = diagrams[i], original = element.textContent;
            try {
                const {svg} = await mermaid.render('diagram-' + revision + '-' + i, original);
                if (revision !== renderingRevision) return;
                element.innerHTML = svg;
            } catch (_) {
                diagnostics.push({kind:'syntax', detail:'mermaid'});
                element.textContent = '图表无法显示，请检查 Mermaid 语法。\n' + original;
            }
        }
    }
    const fontsReady = await finishRichLayout(root);
    if (root.querySelector('.katex-error')) diagnostics.push({kind:'syntax', detail:'katex'});
    const renderedMath = root.querySelector('.katex');
    if (renderedMath) {
        const family = getComputedStyle(renderedMath).fontFamily || '';
        if (!window.katex || typeof window.katex.renderToString !== 'function') {
            diagnostics.push({kind:'resource', detail:'katex-script'});
        } else if (!family.includes('KaTeX') || !fontsReady || !document.fonts.check('16px "KaTeX_Main"')) {
            diagnostics.push({kind:'resource', detail:'katex-font'});
        }
    }
    return {formulas:root.querySelectorAll('.katex').length, height:root.scrollHeight, diagnostics};
};

// Paper snapshots use exactly the same offline renderer as the preview.
// Blocks stay together; exceptionally long prose/code is split at measured
// line boundaries. Formula and Mermaid nodes are never sliced mid-expression.
window.preparePaper = async function(source, size, spacing, format, profileVersion = 1) {
    document.body.classList.add('paper');
    document.body.style.padding = '0';
    document.body.style.lineHeight = Math.max(1.1, Math.min(2, Number(spacing)));
    const root = document.getElementById('content');
    root.style.transform = '';
    const rendered = await window.renderNote(source, size, format, profileVersion);
    await finishRichLayout(root);
    const chunks = [];
    const records = Array.from(root.children).filter(block =>
        block.textContent.trim() || block.querySelector('svg') || block.matches('hr,.thematic-break')
    ).map(block => {
        const box = block.getBoundingClientRect();
        return {block, bottom:Math.ceil(box.bottom + parseFloat(getComputedStyle(block).marginBottom || 0))};
    });
    let previous = 0;
    const append = end => {
        end = Math.ceil(end);
        if (end > previous) { chunks.push({top:previous, height:end-previous}); previous = end; }
    };
    const lineBoundaries = block => {
        const protectedRects = Array.from(block.querySelectorAll('.katex')).map(e => e.getBoundingClientRect());
        const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
        const values = [];
        while (walker.nextNode()) {
            const range = document.createRange(); range.selectNodeContents(walker.currentNode);
            for (const rect of range.getClientRects()) {
                const end = Math.ceil(rect.bottom + 2);
                if (!protectedRects.some(r => r.top < end && r.bottom > end)) values.push(end);
            }
        }
        return Array.from(new Set(values)).sort((a,b) => a-b);
    };
    const appendSplitBlock = (record, minimumFirstEnd = previous) => {
        const atomic = record.block.querySelector('svg,.katex-display') || record.block.classList.contains('diagram');
        if (record.bottom - previous <= PAPER_CHUNK_HEIGHT || atomic) { append(record.bottom); return; }
        const boundaries = lineBoundaries(record.block);
        while (record.bottom - previous > PAPER_CHUNK_HEIGHT) {
            const fitting = boundaries.filter(y => y >= minimumFirstEnd && y > previous && y <= previous + PAPER_CHUNK_HEIGHT);
            if (!fitting.length) break;
            append(fitting[fitting.length - 1]); minimumFirstEnd = previous;
        }
        append(record.bottom);
    };
    for (let i = 0; i < records.length; i++) {
        const current = records[i];
        const isHeading = /^H[1-3]$/.test(current.block.tagName);
        if (isHeading && i + 1 < records.length) {
            // Treat consecutive headings as one semantic prefix and bind the
            // whole chain to the first actual content block.
            let contentIndex = i + 1;
            while (contentIndex < records.length && /^H[1-3]$/.test(records[contentIndex].block.tagName)) contentIndex++;
            if (contentIndex >= records.length) {
                append(records[records.length - 1].bottom);
                break;
            }
            const following = records[contentIndex];
            i = contentIndex;
            const atomic = following.block.querySelector('svg,.katex-display') || following.block.classList.contains('diagram');
            if (atomic || following.bottom - previous <= PAPER_CHUNK_HEIGHT) {
                append(following.bottom);
            } else {
                // A heading is emitted with at least the first rendered line
                // of its body. Atomic formula/diagram groups stay intact and
                // are scaled by the native page layout if exceptionally tall.
                const lines = lineBoundaries(following);
                const headingBottom = records[contentIndex - 1].bottom;
                const firstLine = lines.find(y => y > headingBottom);
                if (firstLine && firstLine - previous <= PAPER_CHUNK_HEIGHT) {
                    append(firstLine);
                    appendSplitBlock(following);
                } else {
                    append(following.bottom);
                }
            }
        } else {
            appendSplitBlock(current);
        }
    }
    return {chunks, height:previous, formulas:root.querySelectorAll('.katex').length, diagnostics:rendered.diagnostics || []};
};
window.positionPaper = async function(top) {
    document.getElementById('content').style.transform = 'translateY(' + (-Number(top)) + 'px)';
    await waitForFrames();
    return true;
};
