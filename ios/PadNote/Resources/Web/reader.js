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
    await Promise.race([document.fonts.ready, new Promise(resolve => setTimeout(resolve, 500))]);
    await waitForFrames();
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
    await settleLayout();
    fitDisplayMath(root);
    fitDiagrams(root);
    await settleLayout();
}
function inline(source) {
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
            output += window.katex ? katex.renderToString(formula, {throwOnError:false, trust:false, strict:'ignore', maxExpand:1000, maxSize:20, displayMode:match[1] !== undefined || match[2] !== undefined}) : escapeHTML(match[0]);
        }
        last = pattern.lastIndex;
    }
    return output + prose(source.slice(last));
}
window.renderNote = async function (source, size, format = 'markdown') {
    const revision = ++renderingRevision;
    document.body.style.fontSize = Number(size) + 'px';
    const root = document.getElementById('content');
    root.replaceChildren();
    if (format === 'latex') {
        let formula = source.trim();
        for (const [start, end] of [['\\[','\\]'], ['\\(','\\)'], ['$$','$$'], ['$','$']]) {
            if (formula.startsWith(start) && formula.endsWith(end) && formula.length >= start.length + end.length) {
                formula = formula.slice(start.length, -end.length).trim(); break;
            }
        }
        const block = document.createElement('div');
        block.innerHTML = katex.renderToString(formula, {throwOnError:false, trust:false, strict:'ignore', maxExpand:1000, maxSize:20, displayMode:true});
        root.append(block);
        await finishRichLayout(root);
        return {formulas:root.querySelectorAll('.katex').length, height:root.scrollHeight};
    }
    const diagrams = [];
    const lines = source.replace(/\r\n?/g, '\n').split('\n');
    let proseLines = [];
    function appendProseLines(values) {
        if (!values.length) return;
        // Keep display formula blocks together even when their source spans lines.
        const blocks = values.join('\n').split(/(\\\[[\s\S]*?\\\]|\$\$[\s\S]*?\$\$)/g);
        blocks.forEach(block => {
            if (!block.trim()) return;
            if (/^(\\\[|\$\$)/.test(block)) {
                const div = document.createElement('div'); div.innerHTML = inline(block); root.append(div); return;
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
                    item.innerHTML = inline(unordered ? unordered[1] : ordered[2]) || '<br>';
                    listElement.append(item); return;
                }
                if (listElement) { root.append(listElement); listElement = null; listType = null; }
                // Markdown blank lines separate blocks; they are not visible
                // paragraphs. Fenced code is handled separately and keeps its
                // blank lines through textContent.
                if (!line.trim()) return;
                const element = document.createElement(heading ? 'h' + heading[1].length : quote ? 'blockquote' : 'p');
                element.innerHTML = inline(heading ? heading[2] : quote ? quote[1] : line);
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
    if (window.mermaid && diagrams.length) {
        mermaid.initialize({startOnLoad:false, securityLevel:'strict', theme:'neutral', suppressErrorRendering:true, maxTextSize:50000});
        for (let i = 0; i < diagrams.length; i++) {
            const element = diagrams[i], original = element.textContent;
            try {
                const {svg} = await mermaid.render('diagram-' + revision + '-' + i, original);
                if (revision !== renderingRevision) return;
                element.innerHTML = svg;
            } catch (_) { element.textContent = '图表无法显示，请检查 Mermaid 语法。\n' + original; }
        }
    }
    await finishRichLayout(root);
    return {formulas:root.querySelectorAll('.katex').length, height:root.scrollHeight};
};

// Paper snapshots use exactly the same offline renderer as the preview.
// Blocks stay together; exceptionally long prose/code is split at measured
// line boundaries. Formula and Mermaid nodes are never sliced mid-expression.
window.preparePaper = async function(source, size, spacing, format) {
    document.body.classList.add('paper');
    document.body.style.padding = '0';
    document.body.style.lineHeight = Math.max(1.1, Math.min(2, Number(spacing)));
    const root = document.getElementById('content');
    root.style.transform = '';
    await window.renderNote(source, size, format);
    await finishRichLayout(root);
    const chunks = [];
    const records = Array.from(root.children).filter(block => block.textContent.trim() || block.querySelector('svg')).map(block => {
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
    return {chunks, height:previous, formulas:root.querySelectorAll('.katex').length};
};
window.positionPaper = async function(top) {
    document.getElementById('content').style.transform = 'translateY(' + (-Number(top)) + 'px)';
    await waitForFrames();
    return true;
};
