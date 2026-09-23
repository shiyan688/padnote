'use strict';
const escapeHTML = s => s.replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
let renderingRevision = 0;
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
                const ordered = line.match(/^\s*\d+[.)]\s+(.*)/);
                const quote = line.match(/^>\s?(.*)/);
                const kind = unordered ? 'ul' : ordered ? 'ol' : null;
                if (kind !== listType) { if (listElement) root.append(listElement); listElement = null; listType = kind; }
                if (kind) {
                    if (!listElement) listElement = document.createElement(kind);
                    const item = document.createElement('li'); item.innerHTML = inline((unordered || ordered)[1]) || '<br>'; listElement.append(item); return;
                }
                if (listElement) { root.append(listElement); listElement = null; listType = null; }
                const element = document.createElement(heading ? 'h' + heading[1].length : quote ? 'blockquote' : 'p');
                element.innerHTML = inline(heading ? heading[2] : quote ? quote[1] : line) || '<br>';
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
    await document.fonts.ready;
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const chunks = [];
    let previous = 0;
    for (const block of root.children) {
        if (!block.textContent.trim() && !block.querySelector('svg')) continue;
        const box = block.getBoundingClientRect();
        const bottom = Math.ceil(box.bottom + parseFloat(getComputedStyle(block).marginBottom || 0));
        if (bottom <= previous) continue;
        let boundaries = [bottom];
        if (bottom - previous > 768 && !block.querySelector('svg') && !block.querySelector('.katex-display')) {
            const protectedRects = Array.from(block.querySelectorAll('.katex')).map(e => e.getBoundingClientRect());
            const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
            const candidates = [];
            while (walker.nextNode()) {
                const range = document.createRange(); range.selectNodeContents(walker.currentNode);
                for (const rect of range.getClientRects()) {
                    const end = Math.ceil(rect.bottom + 2);
                    if (!protectedRects.some(r => r.top < end && r.bottom > end)) candidates.push(end);
                }
            }
            const sorted = Array.from(new Set(candidates)).sort((a,b) => a-b);
            boundaries = [];
            let start = previous;
            while (bottom - start > 768) {
                const fitting = sorted.filter(y => y > start && y <= start + 768);
                if (!fitting.length) break;
                start = fitting[fitting.length - 1]; boundaries.push(start);
            }
            boundaries.push(bottom);
        }
        for (const end of boundaries) {
            if (end > previous) chunks.push({top:previous, height:end-previous});
            previous = end;
        }
    }
    return {chunks, height:previous, formulas:root.querySelectorAll('.katex').length};
};
window.positionPaper = async function(top) {
    document.getElementById('content').style.transform = 'translateY(' + (-Number(top)) + 'px)';
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    return true;
};
