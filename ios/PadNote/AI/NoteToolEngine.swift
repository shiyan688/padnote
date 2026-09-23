import Foundation
import UIKit

/// The authority granted for one AI task. Existing content always needs an
/// explicit `.modifyExisting` grant; the default permits reads and new flows.
public enum NoteToolPermission: Int, Codable, Comparable {
    case readOnly = 0
    case createInFreeSpace = 1
    case modifyExisting = 2
    public static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }
}

public struct NoteToolVaultEntry: Codable, Equatable, Identifiable {
    public var id: String
    public var title: String
    public var markdown: String
    public init(id: String, title: String, markdown: String) {
        self.id = id; self.title = title; self.markdown = markdown
    }
}

public struct NoteToolResult {
    public let jsonString: String
    /// A proposed in-memory document. The caller decides when to persist it.
    public let proposedNote: NoteDocument?
    public let mutated: Bool
    public init(jsonString: String, proposedNote: NoteDocument? = nil, mutated: Bool = false) {
        self.jsonString = jsonString; self.proposedNote = proposedNote; self.mutated = mutated
    }
}

/// Pure, snapshot-based implementation of the Android v1 tool semantics.
/// It has no file, vault, canvas, or network access and therefore cannot cause
/// a note mutation until the owner applies `proposedNote` explicitly.
public final class NoteToolEngine {
    public static let bandCount = 8
    private let original: NoteDocument
    private var proposed: NoteDocument
    private let currentPage: Int
    private let selection: CGRect?
    private let vault: [NoteToolVaultEntry]
    private var completedCalls = Set<String>()

    public init(note: NoteDocument, currentPage: Int = 0, selectionBounds: CGRect? = nil,
                vault: [NoteToolVaultEntry] = []) {
        self.original = note; self.proposed = note
        self.currentPage = max(0, currentPage); self.selection = selectionBounds; self.vault = vault
    }

    public var proposedNote: NoteDocument { proposed }

    public func invoke(name: String, callID: String, argumentsJSON: String = "{}",
                       authorization: NoteToolPermission = .readOnly) -> NoteToolResult {
        guard !name.isEmpty, !callID.isEmpty else { return failure("工具名和 callID 不能为空") }
        guard let data = argumentsJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let args = object as? [String: Any] else { return failure("arguments 必须是 JSON 对象") }
        if completedCalls.contains(callID) {
            return result(["ok": true, "idempotent": true, "message": "该 callID 已执行，未重复应用"], mutated: false)
        }
        let outcome: NoteToolResult
        switch name {
        case "read_page_map": outcome = readPageMap(args)
        case "write_text": outcome = writeText(args, authorization: authorization)
        case "draw_diagram": outcome = drawDiagram(args, authorization: authorization)
        case "set_text_flow_style": outcome = style(args, authorization: authorization)
        case "move_text_flow": outcome = move(args, authorization: authorization)
        case "search_vault": outcome = searchVault(args)
        case "read_vault_note": outcome = readVault(args)
        default: outcome = failure("未注册的工具：\(name)")
        }
        if outcome.mutated { completedCalls.insert(callID) }
        return outcome
    }

    public func describeToolsJSON() -> String {
        let placement: [String: Any] = ["type": "object", "description": "位置约束，不要给像素坐标。", "properties": [
            "relativeTo": ["type": "string"], "position": ["type": "string", "enum": ["below", "above", "right", "left"]],
            "page": ["type": "integer"], "bands": ["type": "string"], "slot": ["type": "string"], "widthDp": ["type": "number"]]]
        func f(_ name: String, _ description: String, _ properties: [String: Any], _ required: [String]) -> [String: Any] {
            ["type": "function", "function": ["name": name, "description": description, "parameters": ["type": "object", "properties": properties, "required": required]]]
        }
        let tools: [[String: Any]] = [
            f("read_page_map", "读取笔记结构与空白区域，规划写入位置。", ["page": ["type":"integer"]], []),
            f("write_text", "在页面空白处插入可编辑文字。", ["content":["type":"string"], "format":["type":"string", "enum":["markdown","latex"]], "placement":placement], ["content","placement"]),
            f("draw_diagram", "插入可编辑的 Mermaid 示意图。", ["code":["type":"string"], "placement":placement], ["code","placement"]),
            f("set_text_flow_style", "调整已有文字流样式。", ["flowId":["type":"string"], "fontSizeSp":["type":"number"], "lineHeight":["type":"number"], "widthDp":["type":"number"]], ["flowId"]),
            f("move_text_flow", "移动已有文字流到页面空白处。", ["flowId":["type":"string"], "placement":placement], ["flowId","placement"]),
            f("search_vault", "搜索知识库正文。", ["query":["type":"string"]], ["query"]),
            f("read_vault_note", "读取知识库笔记全文或指定页内容。", ["id":["type":"string"], "title":["type":"string"], "page":["type":"integer"]], [])]
        return encode(tools) ?? "[]"
    }

    private func readPageMap(_ args: [String: Any]) -> NoteToolResult {
        let requested = (args["page"] as? Int).map { max(0, $0 - 1) } ?? currentPage
        var pages: [[String: Any]] = []
        for page in 0..<proposed.pageCount {
            let detailed = abs(page - requested) <= 1
            let occupied = occupiedBands(page)
            var entry: [String: Any] = ["pageIndex": page, "summary": !detailed,
                "freeBandCount": occupied.filter { !$0 }.count, "approximateFreeLines": Int(Double(occupied.filter { !$0 }.count) * proposed.pageHeight / 8 / 22), "pdfBackground": page < proposed.pdfPageCount]
            if detailed {
                entry["textFlows"] = proposed.textFlows.filter { $0.anchorPageIndex == page }.map {
                    ["flowId": $0.id, "source": $0.source, "format": $0.format, "fontSizeSp": $0.fontSizeSp,
                     "lineHeight": $0.lineHeight, "bands": bandString(for: flowBounds($0).rect)]
                }
                entry["inkClusters"] = inkClusters(page).prefix(200).map { ["clusterId": $0.id, "bands": bandString(for: $0.rect), "strokeCount": $0.count, "recognized": false] }
                entry["freeBands"] = occupied.enumerated().filter { !$0.element }.map { $0.offset + 1 }
            } else { entry["textFlowCount"] = proposed.textFlows.filter { $0.anchorPageIndex == page }.count }
            pages.append(entry)
        }
        var payload: [String: Any] = ["pageCount": proposed.pageCount, "bandsPerPage": Self.bandCount,
            "pageSize": ["widthDp": proposed.pageWidth, "heightDp": proposed.pageHeight], "pages": pages]
        if let selection { let p = pageForWorldY(selection.midY); payload["selection"] = ["pageIndex": p, "bands": bandString(for: selection.offsetBy(dx: 0, dy: -Double(p) * (proposed.pageHeight + proposed.pageGap)))] }
        return result(payload)
    }

    private func writeText(_ args: [String: Any], authorization: NoteToolPermission) -> NoteToolResult {
        guard authorization >= .createInFreeSpace else { return denied("write_text") }
        guard let content = args["content"] as? String, !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return failure("content 不能为空") }
        guard content.count <= 100_000 else { return failure("content 超出单个文本流上限") }
        let format = (args["format"] as? String)?.lowercased() ?? "markdown"
        guard ["markdown", "latex"].contains(format) else { return failure("format 必须是 markdown 或 latex") }
        return insert(content: content, format: format, placement: args["placement"] as? [String: Any])
    }

    private func drawDiagram(_ args: [String: Any], authorization: NoteToolPermission) -> NoteToolResult {
        guard authorization >= .createInFreeSpace else { return denied("draw_diagram") }
        guard let code = args["code"] as? String, validMermaid(code) else { return failure("示意图不支持围栏、HTML、配置指令或点击链接") }
        return insert(content: "```mermaid\n\(code.trimmingCharacters(in: .whitespacesAndNewlines))\n```", format: "markdown", placement: args["placement"] as? [String: Any])
    }

    private func insert(content: String, format: String, placement: [String: Any]?) -> NoteToolResult {
        guard let resolved = resolve(placement, content: content, format: format) else { return failure("placement 无法解析或没有可避让的空白区域") }
        guard resolved.page < 500 else { return failure("笔记最多支持 500 页") }
        let snapshot = proposed
        proposed.pageCount = max(proposed.pageCount, resolved.page + 1)
        let flow = NoteTextFlow(format: format, source: content, fontSizeSp: 16, lineHeight: 1.35,
                                width: resolved.width, anchorPageIndex: resolved.page, anchorXInPage: resolved.x, anchorYInPage: resolved.y)
        proposed.textFlows.append(flow)
        let required = NoteTextLayout.requiredPageCount(proposed)
        guard required <= 500, NoteTextLayout.canFullyLayout(flow, pageHeight: proposed.pageHeight) else { proposed = snapshot; return failure("文字分页超过 500 页") }
        proposed.pageCount = max(proposed.pageCount, required)
        let bounds = flowBounds(flow)
        let payload: [String: Any] = ["flowId": flow.id, "occupiedPages": Array(Set(NoteTextLayout.fragments(flow, pageHeight: proposed.pageHeight).map(\.page))).sorted(),
            "addedPage": proposed.pageCount > original.pageCount, "actual": ["page": resolved.page + 1, "bands": bandString(for: bounds.rect), "interpretation": resolved.note],
            "pdfBackground": resolved.page < proposed.pdfPageCount]
        return result(payload, mutated: true)
    }

    private func style(_ args: [String: Any], authorization: NoteToolPermission) -> NoteToolResult {
        guard authorization >= .modifyExisting else { return denied("set_text_flow_style") }
        guard let id = args["flowId"] as? String, let index = proposed.textFlows.firstIndex(where: { $0.id == id }) else { return failure("找不到文字流") }
        var flow = proposed.textFlows[index]
        if let value = args["fontSizeSp"] as? Double { guard value.isFinite, (10...32).contains(value) else { return failure("fontSizeSp 必须在 10 到 32") }; flow.fontSizeSp = value }
        if let value = args["lineHeight"] as? Double { guard value.isFinite, (1.1...2).contains(value) else { return failure("lineHeight 必须在 1.1 到 2.0") }; flow.lineHeight = value }
        if let value = args["widthDp"] as? Double { guard value.isFinite, (120...proposed.pageWidth).contains(value) else { return failure("widthDp 超出页面范围") }; flow.width = min(value, proposed.pageWidth - flow.anchorXInPage - 16) }
        let snapshot = proposed
        proposed.textFlows[index] = flow
        guard NoteTextLayout.canFullyLayout(proposed.textFlows[index], pageHeight: proposed.pageHeight) else { proposed = snapshot; return failure("文字分页超过 500 页") }
        proposed.pageCount = NoteTextLayout.requiredPageCount(proposed)
        return result(["flowId": id, "actual": ["page": flow.anchorPageIndex + 1, "bands": bandString(for: flowBounds(flow).rect)]], mutated: true)
    }

    private func move(_ args: [String: Any], authorization: NoteToolPermission) -> NoteToolResult {
        guard authorization >= .modifyExisting else { return denied("move_text_flow") }
        guard let id = args["flowId"] as? String, let index = proposed.textFlows.firstIndex(where: { $0.id == id }) else { return failure("找不到文字流") }
        guard let resolved = resolve(args["placement"] as? [String: Any], content: proposed.textFlows[index].source, excluding: id) else { return failure("placement 无法解析或没有空白区域") }
        let snapshot = proposed
        proposed.textFlows[index].width = resolved.width; proposed.textFlows[index].anchorPageIndex = resolved.page; proposed.textFlows[index].anchorXInPage = resolved.x; proposed.textFlows[index].anchorYInPage = resolved.y
        guard NoteTextLayout.canFullyLayout(proposed.textFlows[index], pageHeight: proposed.pageHeight) else { proposed = snapshot; return failure("文字分页超过 500 页") }
        proposed.pageCount = NoteTextLayout.requiredPageCount(proposed)
        return result(["flowId": id, "actual": ["page": resolved.page + 1, "bands": bandString(for: flowBounds(proposed.textFlows[index]).rect)]], mutated: true)
    }

    private func searchVault(_ args: [String: Any]) -> NoteToolResult {
        guard let query = args["query"] as? String, !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return failure("query 不能为空") }
        let needle = query.lowercased(); var excerpts: [[String: Any]] = []; var count = 0
        for note in vault { for (lineIndex, line) in note.markdown.components(separatedBy: "\n").enumerated() where line.lowercased().contains(needle) { count += 1; if excerpts.count < 30 { excerpts.append(["note": note.title, "id": note.id, "line": lineIndex + 1, "text": String(line.prefix(160))]) } } }
        return result(["query": query, "totalMatches": count, "excerpts": excerpts, "excerptsCapped": count > excerpts.count])
    }

    private func readVault(_ args: [String: Any]) -> NoteToolResult {
        guard let key = (args["id"] as? String) ?? (args["title"] as? String), let note = vault.first(where: { $0.id == key || $0.title == key }) else { return failure("找不到知识库笔记") }
        let page = args["page"] as? Int
        let content: String
        if let page { let lines = note.markdown.components(separatedBy: "\n"); let marker = "第 \(page) 页"; let start = lines.firstIndex { $0.contains(marker) || $0.contains("第\(page)页") }; let end = start.flatMap { s in lines[(s + 1)...].firstIndex { $0.range(of: "^## 第 \\d+ 页", options: .regularExpression) != nil } }.map { $0 } ?? lines.count; content = start.map { lines[$0..<end].joined(separator: "\n") } ?? "" }
        else { content = String(note.markdown.prefix(20_000)) }
        return result(["id": note.id, "title": note.title, "markdown": content, "truncated": note.markdown.count > content.count])
    }

    private struct Resolved { let page: Int; let x: Double; let y: Double; let width: Double; let note: String }
    private func resolve(_ placement: [String: Any]?, content: String, excluding: String? = nil, format: String = "markdown") -> Resolved? {
        guard let placement, Set(placement.keys).isSubset(of: ["relativeTo", "position", "page", "bands", "slot", "widthDp"]) else { return nil }
        if let value = placement["widthDp"], !(value is NSNumber) { return nil }
        let requestedWidth = (placement["widthDp"] as? Double) ?? min(proposed.pageWidth - 32, 380)
        guard requestedWidth.isFinite, requestedWidth > 0 else { return nil }
        let width = min(max(requestedWidth, 120), proposed.pageWidth - 32)
        guard width > 0 else { return nil }
        var page = currentPage, x = 16.0, y = 40.0, interpretation = "页面空白处"
        if let anchor = placement["relativeTo"] as? String {
            guard let rect = anchorRect(anchor) else { return nil }
            page = pageForWorldY(rect.midY)
            let position = placement["position"] as? String ?? "below"
            guard ["above", "below", "left", "right"].contains(position) else { return nil }
            interpretation = "\(anchor) 的\(position)"
            x = rect.minX
            switch position {
            case "above": y = rect.minY - 90
            case "right": x = rect.maxX + 18; y = rect.minY
            case "left": x = rect.minX - width - 18; y = rect.minY
            default: y = rect.maxY + 18
            }
            if x < 16 || x + width > proposed.pageWidth - 16 { x = rect.minX; y = rect.maxY + 18 }
            y -= Double(page) * (proposed.pageHeight + proposed.pageGap)
        } else if let number = placement["page"] as? Int, (1...500).contains(number) {
            page = min(number - 1, proposed.pageCount - 1)
            if let bands = placement["bands"] as? String {
                guard bands.range(of: "^[1-8](-[1-8])?$", options: .regularExpression) != nil,
                      let first = Int(bands.prefix(1)) else { return nil }
                y = Double(first - 1) * proposed.pageHeight / 8 + 16
            }
            if let slot = placement["slot"] as? String {
                guard slot == "free.largest" else { return nil }
                if let free = largestFreeRect(page) { x = free.minX; y = free.minY }
                else { page += 1; y = 40 }
            }
        } else { return nil }
        // Original PDF pages are immutable backgrounds. AI answers start in
        // the annotation suffix and can create it when none exists yet.
        if page < proposed.pdfPageCount { page = proposed.pdfPageCount; x = 16; y = 40; interpretation += "（PDF 附注页）" }
        x = min(max(16, x), proposed.pageWidth - width - 16)
        y = max(40, y)
        for _ in 0..<2000 {
            if y >= proposed.pageHeight - 40 { page += 1; y = 40 }
            guard page < 500 else { return nil }
            var candidate = proposed.textFlows.first(where: { $0.id == excluding }) ?? NoteTextFlow(format: format, source: content)
            candidate.width = width; candidate.anchorPageIndex = page; candidate.anchorXInPage = x; candidate.anchorYInPage = y
            guard NoteTextLayout.canFullyLayout(candidate, pageHeight: proposed.pageHeight) else { return nil }
            let fragments = NoteTextLayout.fragments(candidate, pageHeight: proposed.pageHeight)
            guard !fragments.isEmpty else { return nil }
            if let collision = fragments.first(where: { collides($0.rect, page: $0.page, excluding: excluding) }) {
                if collision.page == page { y += 24 }
                else { page = collision.page + 1; y = 40 }
                continue
            }
            return Resolved(page: page, x: x, y: y, width: width, note: interpretation)
        }
        return nil
    }

    private func anchorRect(_ name: String) -> CGRect? {
        if name == "selection" { return selection }
        if let flow = proposed.textFlows.first(where: { $0.id == name }) {
            let bounds = flowBounds(flow)
            return bounds.rect.offsetBy(dx: 0, dy: Double(bounds.page) * (proposed.pageHeight + proposed.pageGap))
        }
        for (index, stroke) in proposed.strokes.enumerated() {
            if name == "ink-p\(strokePage(stroke))-c\(index + 1)" { return strokeBounds(stroke) }
        }
        return nil
    }
    private func pageForWorldY(_ y: Double) -> Int { min(max(0, Int(y / (proposed.pageHeight + proposed.pageGap))), max(0, proposed.pageCount - 1)) }
    private func estimatedHeight(_ content: String) -> Double { let flow = NoteTextFlow(source: content, width: min(proposed.pageWidth - 32, 380), anchorPageIndex: 0, anchorXInPage: 16, anchorYInPage: 40); let fragments = NoteTextLayout.fragments(flow, pageHeight: proposed.pageHeight); return max(42, fragments.first?.rect.height ?? 42) }
    private func collides(_ rect: CGRect, page: Int, excluding: String?) -> Bool { if proposed.strokes.contains(where: { stroke in strokePage(stroke) == page && strokeRect(stroke).intersects(rect.insetBy(dx: -8, dy: -8)) }) { return true }; return proposed.images.contains { $0.page == page && CGRect(x: $0.x, y: $0.y, width: $0.width, height: $0.height).intersects(rect) } || proposed.textFlows.contains { flow in guard flow.id != excluding else { return false }; return NoteTextLayout.fragments(flow, pageHeight: proposed.pageHeight).contains { $0.page == page && $0.rect.intersects(rect) } } }
    private func occupiedBands(_ page: Int) -> [Bool] { (0..<Self.bandCount).map { band in let r = CGRect(x: 0, y: Double(band) * proposed.pageHeight / 8, width: proposed.pageWidth, height: proposed.pageHeight / 8); return proposed.strokes.contains { strokePage($0) == page && strokeRect($0).intersects(r) } || proposed.textFlows.contains { flow in NoteTextLayout.fragments(flow, pageHeight: proposed.pageHeight).contains { $0.page == page && $0.rect.intersects(r) } } || proposed.images.contains { $0.page == page && CGRect(x: $0.x, y: $0.y, width: $0.width, height: $0.height).intersects(r) } } }
    private func largestFreeRect(_ page: Int) -> CGRect? { let free = occupiedBands(page); var best: (Int, Int)?; var start: Int?; for i in 0...free.count { if i < free.count && !free[i] { if start == nil { start = i } } else if let s = start { if best == nil || i - s > best!.1 - best!.0 { best = (s, i - 1) }; start = nil } }; guard let (first,last) = best else { return nil }; return CGRect(x: 16, y: Double(first) * proposed.pageHeight / 8 + 16, width: proposed.pageWidth - 32, height: Double(last - first + 1) * proposed.pageHeight / 8 - 32) }
    private func flowBounds(_ flow: NoteTextFlow) -> (page: Int, rect: CGRect) { if let fragment = NoteTextLayout.fragments(flow, pageHeight: proposed.pageHeight).first { return (fragment.page, fragment.rect) }; return (flow.anchorPageIndex, CGRect(x: flow.anchorXInPage, y: flow.anchorYInPage, width: flow.width, height: 42)) }
    private func strokePage(_ stroke: InkStroke) -> Int { pageForWorldY(strokeBounds(stroke).midY) }
    private func strokeRect(_ stroke: InkStroke) -> CGRect { var r = strokeBounds(stroke); let page = strokePage(stroke); r.origin.y -= Double(page) * (proposed.pageHeight + proposed.pageGap); return r }
    private func strokeBounds(_ stroke: InkStroke) -> CGRect { let xs = stroke.points.map(\.x), ys = stroke.points.map(\.y); return CGRect(x: xs.min() ?? 0, y: ys.min() ?? 0, width: max(1, (xs.max() ?? 0) - (xs.min() ?? 0)), height: max(1, (ys.max() ?? 0) - (ys.min() ?? 0))) }
    private struct Cluster { let id: String; let rect: CGRect; let count: Int }
    private func inkClusters(_ page: Int? = nil) -> [Cluster] { proposed.strokes.enumerated().compactMap { index, stroke in let p = strokePage(stroke); guard page == nil || p == page else { return nil }; return Cluster(id: "ink-p\(p)-c\(index + 1)", rect: strokeRect(stroke), count: 1) } }
    private func bandString(for rect: CGRect) -> String { let first = max(1, min(8, Int(rect.minY / proposed.pageHeight * 8) + 1)); let last = max(first, min(8, Int(rect.maxY / proposed.pageHeight * 8) + 1)); return first == last ? "\(first)" : "\(first)-\(last)" }
    private func validMermaid(_ code: String) -> Bool { let value = code.trimmingCharacters(in: .whitespacesAndNewlines); guard value.count <= 8000, value.range(of: "^(flowchart|graph) (TD|TB|BT|LR|RL)|^(sequenceDiagram|stateDiagram-v2)", options: [.regularExpression, .caseInsensitive]) != nil else { return false }; return !value.contains("```") && !value.contains("%%{") && value.range(of: "(?i)<[a-z!/]|\\b(click|href)\\b", options: .regularExpression) == nil }
    private func denied(_ name: String) -> NoteToolResult { failure("工具 \(name) 需要更高权限，请先确认后再执行") }
    private func failure(_ message: String) -> NoteToolResult { result(["ok": false, "error": message]) }
    private func result(_ value: [String: Any], mutated: Bool = false) -> NoteToolResult { NoteToolResult(jsonString: encode(value) ?? "{\"ok\":false}", proposedNote: mutated ? proposed : nil, mutated: mutated) }
    private func encode(_ value: Any) -> String? { guard JSONSerialization.isValidJSONObject(value), let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) else { return nil }; return String(data: data, encoding: .utf8) }
}
