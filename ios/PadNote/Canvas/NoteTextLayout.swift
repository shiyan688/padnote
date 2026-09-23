import UIKit
import CoreText

/// Text ranges are laid out with the same CoreText metrics used for painting.
/// Keeping the complete source in the flow preserves Android interchange.
enum NoteTextLayout {
    private static let headingAttribute = NSAttributedString.Key("PadNoteHeading")
    struct Fragment {
        let page: Int
        let rect: CGRect
        let text: NSAttributedString
        var image: UIImage? = nil
    }
    private static let cache = NSCache<NSString, Entry>()
    private final class Entry {
        let fragments: [Fragment]
        init(_ fragments: [Fragment]) { self.fragments = fragments }
    }

    static func fragments(_ flow: NoteTextFlow, pageHeight: Double) -> [Fragment] {
        if let compiled = CompiledTextCache.entry(flow) {
            return compiledFragments(compiled.units, flow: flow, pageHeight: pageHeight)
        }
        let key = "\(flow.id)|\(flow.format)|\(flow.fontSizeSp)|\(flow.lineHeight)|\(flow.width)|\(flow.anchorPageIndex)|\(flow.anchorXInPage)|\(flow.anchorYInPage)|\(pageHeight)|\(flow.source)" as NSString
        if let value = cache.object(forKey: key) { return value.fragments }
        let text = attributed(flow)
        guard text.length > 0 else { return [] }
        let framesetter = CTFramesetterCreateWithAttributedString(text)
        var start = 0
        var page = flow.anchorPageIndex
        var fragments: [Fragment] = []
        while start < text.length, page < 500 {
            let y = page == flow.anchorPageIndex ? flow.anchorYInPage : 40
            let height = pageHeight - y - 40
            if height < flow.fontSizeSp * flow.lineHeight { page += 1; continue }
            var rect = CGRect(x: flow.anchorXInPage, y: y, width: flow.width, height: height)
            let path = CGPath(rect: CGRect(origin: .zero, size: rect.size), transform: nil)
            let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: start, length: 0), path, nil)
            let range = CTFrameGetVisibleStringRange(frame)
            if range.length == 0 {
                // A title can require more than the base-font line estimate
                // because of its larger type and paragraph spacing. Retry on
                // a clean page instead of treating that page-tail result as
                // the end of the source.
                if y > 40 { page += 1; continue }
                break
            }
            var visibleLength = range.length
            let proposed = text.attributedSubstring(from: NSRange(location: start, length: visibleLength))
            if let headingStart = trailingOrphanHeadingStart(in: proposed) {
                if headingStart == 0, y > 40 {
                    // The remaining paper can show the title but none of its
                    // content. Move the complete semantic group to a fresh page.
                    page += 1
                    continue
                } else if headingStart > 0 {
                    visibleLength = headingStart
                }
            }
            let visible = text.attributedSubstring(from: NSRange(location: start, length: visibleLength))
            let measured = CTFramesetterSuggestFrameSizeWithConstraints(CTFramesetterCreateWithAttributedString(visible),
                CFRange(location: 0, length: 0), nil, CGSize(width: flow.width, height: .greatestFiniteMagnitude), nil)
            rect.size.height = min(height, ceil(measured.height) + 1)
            fragments.append(Fragment(page: page, rect: rect,
                text: visible))
            start += visibleLength
            page += 1
        }
        cache.totalCostLimit = 8 * 1024 * 1024
        cache.setObject(Entry(fragments), forKey: key, cost: flow.source.utf8.count * 4)
        return fragments
    }

    static func requiredPageCount(_ document: NoteDocument) -> Int {
        document.textFlows.reduce(document.pageCount) { count, flow in
            max(count, (fragments(flow, pageHeight: document.pageHeight).last?.page ?? flow.anchorPageIndex) + 1)
        }
    }

    static func canFullyLayout(_ flow: NoteTextFlow, pageHeight: Double) -> Bool {
        let result = fragments(flow, pageHeight: pageHeight)
        if let compiled = CompiledTextCache.entry(flow) { return result.count == compiled.units.count }
        return result.reduce(0) { $0 + $1.text.length } == attributed(flow).length
    }

    static func draw(_ fragment: Fragment, in context: CGContext) {
        if let image = fragment.image {
            UIGraphicsPushContext(context)
            image.draw(in: fragment.rect)
            UIGraphicsPopContext()
            return
        }
        context.saveGState()
        context.translateBy(x: fragment.rect.minX, y: fragment.rect.maxY)
        context.scaleBy(x: 1, y: -1)
        context.textMatrix = .identity
        let frame = CTFramesetterCreateFrame(CTFramesetterCreateWithAttributedString(fragment.text),
            CFRange(location: 0, length: 0), CGPath(rect: CGRect(origin: .zero, size: fragment.rect.size), transform: nil), nil)
        CTFrameDraw(frame, context)
        context.restoreGState()
    }

    static func compiledFragments(_ units: [CompiledTextCache.Unit], flow: NoteTextFlow, pageHeight: Double) -> [Fragment] {
        var page = flow.anchorPageIndex
        var y = flow.anchorYInPage
        let fullHeight = max(1, pageHeight - 80)
        var fragments: [Fragment] = []
        for (index, unit) in units.enumerated() {
            let factor = min(1, fullHeight / max(1, unit.size.height))
            let height = unit.size.height * factor
            if y + height > pageHeight - 40 { page += 1; y = 40 }
            guard page < 500 else { break }
            fragments.append(Fragment(page: page,
                rect: CGRect(x: flow.anchorXInPage, y: y, width: unit.size.width * factor, height: height),
                text: NSAttributedString(string: index == 0 ? flow.source : ""), image: unit.image))
            y += height
        }
        return fragments
    }

    /// Returns the local string offset of a heading that would be left as the
    /// last visible paragraph. Blank separators after it do not count as body.
    private static func trailingOrphanHeadingStart(in text: NSAttributedString) -> Int? {
        guard text.length > 0 else { return nil }
        let string = text.string as NSString
        var cursor = string.length
        while cursor > 0 {
            let range = string.lineRange(for: NSRange(location: cursor - 1, length: 0))
            let content = string.substring(with: range).trimmingCharacters(in: .whitespacesAndNewlines)
            if !content.isEmpty {
                return text.attribute(headingAttribute, at: range.location, effectiveRange: nil) != nil ? range.location : nil
            }
            cursor = range.location
        }
        return nil
    }

    private static func attributed(_ flow: NoteTextFlow) -> NSAttributedString {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineHeightMultiple = max(1.1, min(2, flow.lineHeight))
        paragraph.paragraphSpacing = 4
        let baseFont = UIFont.systemFont(ofSize: flow.fontSizeSp)
        let attributes: [NSAttributedString.Key: Any] = [.font: baseFont, .paragraphStyle: paragraph,
            .foregroundColor: UIColor(red: 0.12, green: 0.16, blue: 0.20, alpha: 1)]
        let result = NSMutableAttributedString(string: "")
        var code = false
        for line in flow.source.components(separatedBy: "\n") {
            var content = line
            var font = baseFont
            if flow.format == "markdown" {
                if content.hasPrefix("```") { code.toggle(); continue }
                if code { font = .monospacedSystemFont(ofSize: flow.fontSizeSp, weight: .regular) }
                else if let match = content.range(of: "^#{1,3} +", options: .regularExpression) {
                    font = .boldSystemFont(ofSize: flow.fontSizeSp * (content.hasPrefix("# ") ? 1.3 : 1.12))
                    content.removeSubrange(match)
                } else if content.hasPrefix("- ") || content.hasPrefix("* ") {
                    content = "• " + content.dropFirst(2)
                }
            }
            let value = NSMutableAttributedString(string: content + "\n", attributes: attributes)
            value.addAttribute(.font, value: font, range: NSRange(location: 0, length: value.length))
            if !code, flow.format == "markdown", line.range(of: "^#{1,3} +", options: .regularExpression) != nil {
                value.addAttribute(headingAttribute, value: true, range: NSRange(location: 0, length: value.length))
            }
            if !code, flow.format == "markdown", let regex = try? NSRegularExpression(pattern: "\\*\\*(.+?)\\*\\*") {
                for match in regex.matches(in: value.string, range: NSRange(location: 0, length: value.length)).reversed() {
                    value.addAttribute(.font, value: UIFont.boldSystemFont(ofSize: flow.fontSizeSp), range: match.range(at: 1))
                    value.deleteCharacters(in: NSRange(location: match.range.location + match.range.length - 2, length: 2))
                    value.deleteCharacters(in: NSRange(location: match.range.location, length: 2))
                }
            }
            result.append(value)
        }
        return result
    }
}
