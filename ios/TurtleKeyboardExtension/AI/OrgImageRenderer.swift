import UIKit

// MARK: - OrgImageRenderer
//
// Renders the JSON document returned by /org into a 500×500 PNG synchronously
// using Core Graphics. Replaces the WKWebView + html-to-image path that ran
// up against keyboard-extension memory limits.
//
// Schema (see commands/prompts/org.txt):
//   { "blocks": [ <block>, ... ] }
//   <block> = heading | paragraph | table | kv | stat
//           | grid    | checklist | list  | callout | badge

private extension UIColor {
    static let orgCream  = UIColor(red: 0.957, green: 0.937, blue: 0.894, alpha: 1)
    static let orgInk    = UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1)
    static let orgLime   = UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1)
    static let orgPink   = UIColor(red: 1.000, green: 0.310, blue: 0.639, alpha: 1)
    static let orgBlue   = UIColor(red: 0.357, green: 0.424, blue: 1.000, alpha: 1)
    static let orgOrange = UIColor(red: 1.000, green: 0.478, blue: 0.102, alpha: 1)
}

struct OrgDocument: Decodable {
    let blocks: [OrgBlock]
}

enum OrgBlock {
    case heading(level: Int, text: String)
    case paragraph(text: String)
    case table(headers: [String], rows: [[String]], footer: [String]?)
    case kv(rows: [[String]])
    case stat(value: String, label: String)
    case grid(cols: Int, cards: [Card])
    case checklist(items: [String])
    case list(ordered: Bool, items: [String])
    case callout(text: String)
    case badge(text: String, color: String)

    struct Card: Decodable { let title: String; let body: String }
}

extension OrgBlock: Decodable {
    private enum K: String, CodingKey {
        case type, level, text, headers, rows, footer, value, label
        case cols, cards, items, ordered, color
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        let type = try c.decode(String.self, forKey: .type)
        switch type {
        case "heading":
            self = .heading(
                level: (try? c.decode(Int.self, forKey: .level)) ?? 3,
                text: try c.decode(String.self, forKey: .text))
        case "paragraph":
            self = .paragraph(text: try c.decode(String.self, forKey: .text))
        case "table":
            self = .table(
                headers: try c.decode([String].self, forKey: .headers),
                rows: try c.decode([[String]].self, forKey: .rows),
                footer: try? c.decode([String].self, forKey: .footer))
        case "kv":
            self = .kv(rows: try c.decode([[String]].self, forKey: .rows))
        case "stat":
            self = .stat(
                value: try c.decode(String.self, forKey: .value),
                label: try c.decode(String.self, forKey: .label))
        case "grid":
            self = .grid(
                cols: (try? c.decode(Int.self, forKey: .cols)) ?? 2,
                cards: try c.decode([Card].self, forKey: .cards))
        case "checklist":
            self = .checklist(items: try c.decode([String].self, forKey: .items))
        case "list":
            self = .list(
                ordered: (try? c.decode(Bool.self, forKey: .ordered)) ?? false,
                items: try c.decode([String].self, forKey: .items))
        case "callout":
            self = .callout(text: try c.decode(String.self, forKey: .text))
        case "badge":
            self = .badge(
                text: try c.decode(String.self, forKey: .text),
                color: (try? c.decode(String.self, forKey: .color)) ?? "")
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type, in: c,
                debugDescription: "unknown block type \(type)")
        }
    }
}

enum OrgImageRenderer {
    private static let canvas: CGFloat = 500
    private static let pad: CGFloat = 28
    private static let blockSpacing: CGFloat = 14

    static func render(json: String) -> UIImage? {
        let trimmed = stripFences(json)
        guard let data = trimmed.data(using: .utf8) else { return nil }
        do {
            let doc = try JSONDecoder().decode(OrgDocument.self, from: data)
            return render(doc: doc)
        } catch {
            #if DEBUG
            NSLog("🐢[OrgRenderer] decode failed: %@", String(describing: error))
            #endif
            return nil
        }
    }

    static func render(doc: OrgDocument) -> UIImage {
        let trace = KeyboardPerformance.begin("ImageRender")
        defer { KeyboardPerformance.end("ImageRender", trace) }
        let size = CGSize(width: canvas, height: canvas)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { ctx in
            UIColor.orgCream.setFill()
            ctx.cgContext.fill(CGRect(origin: .zero, size: size))

            let contentW = canvas - 2 * pad
            let heights = doc.blocks.map { height(of: $0, width: contentW) }
            let total = heights.reduce(0, +)
                      + blockSpacing * CGFloat(max(0, doc.blocks.count - 1))
            var y = max(pad, (canvas - total) / 2)
            for (i, block) in doc.blocks.enumerated() {
                let r = CGRect(x: pad, y: y, width: contentW, height: heights[i])
                draw(block, in: r)
                y += heights[i] + blockSpacing
            }
        }
    }

    // MARK: - Helpers

    private static func stripFences(_ s: String) -> String {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("```") {
            if let nl = t.firstIndex(of: "\n") {
                t = String(t[t.index(after: nl)...])
            }
            if t.hasSuffix("```") { t = String(t.dropLast(3)) }
        }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func textHeight(_ text: String, width: CGFloat, font: UIFont) -> CGFloat {
        let attrs: [NSAttributedString.Key: Any] = [.font: font]
        let bbox = (text as NSString).boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin],
            attributes: attrs, context: nil)
        return ceil(bbox.height)
    }

    // MARK: - Heights

    private static func height(of block: OrgBlock, width: CGFloat) -> CGFloat {
        switch block {
        case .heading(let level, _):
            return level == 1 ? 36 : (level == 2 ? 30 : 28)
        case .paragraph(let text):
            return textHeight(text, width: width, font: .systemFont(ofSize: 15))
        case .table(_, let rows, let footer):
            let n = 1 + rows.count + (footer == nil ? 0 : 1)
            return CGFloat(n) * 34 + 4
        case .kv(let rows):
            return CGFloat(rows.count) * 28
        case .stat:
            return 110
        case .grid(let cols, let cards):
            let cols = max(1, min(3, cols))
            let rows = max(1, Int(ceil(Double(cards.count) / Double(cols))))
            return CGFloat(rows) * 70 + CGFloat(rows - 1) * 8
        case .checklist(let items):
            return CGFloat(items.count) * 28
        case .list(_, let items):
            return CGFloat(items.count) * 28
        case .callout(let text):
            return textHeight(text, width: width - 36, font: .systemFont(ofSize: 14)) + 24
        case .badge:
            return 28
        }
    }

    // MARK: - Dispatch

    private static func draw(_ block: OrgBlock, in rect: CGRect) {
        switch block {
        case .heading(let l, let t):     drawHeading(t, level: l, in: rect)
        case .paragraph(let t):          drawParagraph(t, in: rect)
        case .table(let h, let r, let f): drawTable(headers: h, rows: r, footer: f, in: rect)
        case .kv(let rows):              drawKV(rows: rows, in: rect)
        case .stat(let v, let l):        drawStat(value: v, label: l, in: rect)
        case .grid(let c, let cards):    drawGrid(cols: c, cards: cards, in: rect)
        case .checklist(let items):      drawChecklist(items, in: rect)
        case .list(let ord, let items):  drawList(items, ordered: ord, in: rect)
        case .callout(let t):            drawCallout(t, in: rect)
        case .badge(let t, let c):       drawBadge(t, color: c, in: rect)
        }
    }

    // MARK: - Block painters

    private static func drawHeading(_ text: String, level: Int, in rect: CGRect) {
        let size: CGFloat = level == 1 ? 26 : (level == 2 ? 22 : 20)
        let p = NSMutableParagraphStyle(); p.alignment = .center
        let attrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.boldSystemFont(ofSize: size),
            .foregroundColor: UIColor.orgInk,
            .paragraphStyle: p
        ]
        (text as NSString).draw(in: rect, withAttributes: attrs)
    }

    private static func drawParagraph(_ text: String, in rect: CGRect) {
        let attrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 15),
            .foregroundColor: UIColor.orgInk
        ]
        (text as NSString).draw(in: rect, withAttributes: attrs)
    }

    private static func drawTable(headers: [String], rows: [[String]],
                                  footer: [String]?, in rect: CGRect) {
        let cols = max(1, headers.count)
        let colW = rect.width / CGFloat(cols)
        let rowH: CGFloat = 34

        UIColor.white.setFill()
        UIBezierPath(rect: rect).fill()
        UIColor.orgInk.setStroke()
        let border = UIBezierPath(rect: rect); border.lineWidth = 2; border.stroke()

        // Header
        let headerR = CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: rowH)
        UIColor.orgLime.setFill()
        UIBezierPath(rect: headerR).fill()
        for (i, h) in headers.enumerated() {
            drawCell(h, x: rect.minX + CGFloat(i) * colW, y: rect.minY,
                     w: colW, h: rowH, color: .white, bold: true)
        }

        var y = rect.minY + rowH
        for r in rows {
            for (i, cell) in r.enumerated() where i < cols {
                drawCell(cell, x: rect.minX + CGFloat(i) * colW, y: y,
                         w: colW, h: rowH, color: .orgInk, bold: false)
            }
            y += rowH
            UIColor.orgInk.withAlphaComponent(0.15).setStroke()
            let sep = UIBezierPath()
            sep.move(to: CGPoint(x: rect.minX, y: y))
            sep.addLine(to: CGPoint(x: rect.maxX, y: y))
            sep.lineWidth = 0.5; sep.stroke()
        }

        if let f = footer {
            let footerR = CGRect(x: rect.minX, y: y, width: rect.width, height: rowH)
            UIColor.orgCream.setFill()
            UIBezierPath(rect: footerR).fill()
            UIColor.orgInk.setStroke()
            let topSep = UIBezierPath()
            topSep.move(to: CGPoint(x: rect.minX, y: y))
            topSep.addLine(to: CGPoint(x: rect.maxX, y: y))
            topSep.lineWidth = 2; topSep.stroke()
            for (i, cell) in f.enumerated() where i < cols {
                drawCell(cell, x: rect.minX + CGFloat(i) * colW, y: y,
                         w: colW, h: rowH, color: .orgInk, bold: true)
            }
        }
    }

    private static func drawCell(_ text: String, x: CGFloat, y: CGFloat,
                                 w: CGFloat, h: CGFloat, color: UIColor, bold: Bool) {
        let p = NSMutableParagraphStyle()
        p.lineBreakMode = .byTruncatingTail
        let font: UIFont = bold ? .boldSystemFont(ofSize: 14) : .systemFont(ofSize: 14)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font, .foregroundColor: color, .paragraphStyle: p
        ]
        let textY = y + (h - font.lineHeight) / 2
        (text as NSString).draw(
            in: CGRect(x: x + 10, y: textY, width: w - 20, height: h),
            withAttributes: attrs)
    }

    private static func drawKV(rows: [[String]], in rect: CGRect) {
        let rowH: CGFloat = 28
        var y = rect.minY
        let keyW = rect.width * 0.42
        for row in rows where row.count >= 2 {
            let kAttr: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: 15),
                .foregroundColor: UIColor.orgInk
            ]
            let vAttr: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 15),
                .foregroundColor: UIColor.orgInk
            ]
            (row[0] as NSString).draw(at: CGPoint(x: rect.minX, y: y + 5),
                                      withAttributes: kAttr)
            (row[1] as NSString).draw(at: CGPoint(x: rect.minX + keyW + 8, y: y + 5),
                                      withAttributes: vAttr)
            y += rowH
        }
    }

    private static func drawStat(value: String, label: String, in rect: CGRect) {
        let valFont = UIFont.systemFont(ofSize: 64, weight: .heavy)
        let labFont = UIFont.systemFont(ofSize: 16)
        let p = NSMutableParagraphStyle(); p.alignment = .center
        let valAttr: [NSAttributedString.Key: Any] = [
            .font: valFont, .foregroundColor: UIColor.orgLime, .paragraphStyle: p
        ]
        let labAttr: [NSAttributedString.Key: Any] = [
            .font: labFont,
            .foregroundColor: UIColor.orgInk.withAlphaComponent(0.7),
            .paragraphStyle: p
        ]
        (value as NSString).draw(
            in: CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: 76),
            withAttributes: valAttr)
        (label as NSString).draw(
            in: CGRect(x: rect.minX, y: rect.minY + 80, width: rect.width, height: 22),
            withAttributes: labAttr)
    }

    private static func drawGrid(cols: Int, cards: [OrgBlock.Card], in rect: CGRect) {
        let cols = max(1, min(3, cols))
        let gap: CGFloat = 8
        let cardW = (rect.width - gap * CGFloat(cols - 1)) / CGFloat(cols)
        let cardH: CGFloat = 70
        for (i, card) in cards.enumerated() {
            let r = i / cols, c = i % cols
            let x = rect.minX + CGFloat(c) * (cardW + gap)
            let y = rect.minY + CGFloat(r) * (cardH + gap)
            let cardR = CGRect(x: x, y: y, width: cardW, height: cardH)

            UIColor.orgInk.setFill()
            UIBezierPath(rect: cardR.offsetBy(dx: 4, dy: 4)).fill()
            UIColor.white.setFill()
            UIBezierPath(rect: cardR).fill()
            UIColor.orgInk.setStroke()
            let border = UIBezierPath(rect: cardR); border.lineWidth = 2; border.stroke()

            let tAttr: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: 14),
                .foregroundColor: UIColor.orgInk
            ]
            (card.title as NSString).draw(
                in: CGRect(x: x + 10, y: y + 10, width: cardW - 20, height: 18),
                withAttributes: tAttr)
            let bAttr: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 13),
                .foregroundColor: UIColor.orgInk.withAlphaComponent(0.75)
            ]
            (card.body as NSString).draw(
                in: CGRect(x: x + 10, y: y + 32, width: cardW - 20, height: cardH - 38),
                withAttributes: bAttr)
        }
    }

    private static func drawChecklist(_ items: [String], in rect: CGRect) {
        let rowH: CGFloat = 28
        var y = rect.minY
        for item in items {
            let box = CGRect(x: rect.minX, y: y + 6, width: 14, height: 14)
            UIColor.orgInk.setStroke()
            let p = UIBezierPath(roundedRect: box, cornerRadius: 2)
            p.lineWidth = 2; p.stroke()
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 15),
                .foregroundColor: UIColor.orgInk
            ]
            (item as NSString).draw(
                in: CGRect(x: rect.minX + 24, y: y + 5, width: rect.width - 24, height: rowH),
                withAttributes: attrs)
            y += rowH
        }
    }

    private static func drawList(_ items: [String], ordered: Bool, in rect: CGRect) {
        let rowH: CGFloat = 28
        var y = rect.minY
        for (i, item) in items.enumerated() {
            let bullet = ordered ? "\(i + 1)." : "•"
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 15),
                .foregroundColor: UIColor.orgInk
            ]
            (bullet as NSString).draw(at: CGPoint(x: rect.minX, y: y + 5),
                                      withAttributes: attrs)
            (item as NSString).draw(
                in: CGRect(x: rect.minX + 24, y: y + 5, width: rect.width - 24, height: rowH),
                withAttributes: attrs)
            y += rowH
        }
    }

    private static func drawCallout(_ text: String, in rect: CGRect) {
        UIColor.white.setFill()
        UIBezierPath(rect: rect).fill()
        UIColor.orgInk.setStroke()
        let border = UIBezierPath(rect: rect); border.lineWidth = 2; border.stroke()
        UIColor.orgLime.setFill()
        UIBezierPath(rect: CGRect(x: rect.minX, y: rect.minY, width: 8, height: rect.height)).fill()
        let attrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 14),
            .foregroundColor: UIColor.orgInk
        ]
        (text as NSString).draw(
            in: CGRect(x: rect.minX + 20, y: rect.minY + 12,
                       width: rect.width - 32, height: rect.height - 24),
            withAttributes: attrs)
    }

    private static func drawBadge(_ text: String, color: String, in rect: CGRect) {
        let bg: UIColor
        switch color {
        case "green":  bg = .orgLime
        case "pink":   bg = .orgPink
        case "blue":   bg = .orgBlue
        case "orange": bg = .orgOrange
        default:       bg = .orgInk
        }
        let font = UIFont.systemFont(ofSize: 13, weight: .bold)
        let textW = (text as NSString).size(withAttributes: [.font: font]).width
        let badgeR = CGRect(x: rect.minX, y: rect.minY, width: textW + 24, height: 26)
        bg.setFill()
        UIBezierPath(roundedRect: badgeR, cornerRadius: 13).fill()
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font, .foregroundColor: UIColor.white
        ]
        (text as NSString).draw(at: CGPoint(x: badgeR.minX + 12, y: badgeR.minY + 5),
                                withAttributes: attrs)
    }
}
