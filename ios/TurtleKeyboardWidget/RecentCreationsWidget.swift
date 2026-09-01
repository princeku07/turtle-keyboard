import SwiftUI
import WidgetKit

// MARK: - Recent Creations widget
//
// Home Screen gallery of the last images produced by /cap and /org.
// Reads the App Group history that the keyboard extension writes
// (`ImageHistory`), so there is no network call and no backend hop.
//
// A widget cannot type into the field you're in — that stays the
// keyboard's job — so this surface is deliberately a *gallery*: look at
// what you made, tap to jump into the host app's History screen to
// re-share it.

// MARK: - Timeline

struct RecentCreationsEntry: TimelineEntry {
    let date: Date
    let items: [HistoryThumbnails.Item]
}

struct RecentCreationsProvider: TimelineProvider {

    /// How many thumbnails each size shows, and how big to decode them.
    /// Both numbers are deliberately conservative — the entry is archived
    /// by WidgetKit, and the widget process itself has ~30 MB to work in.
    static func layout(for family: WidgetFamily) -> (count: Int, maxPixel: CGFloat) {
        switch family {
        case .systemSmall:  return (1, 400)
        case .systemMedium: return (4, 220)
        case .systemLarge:  return (9, 200)
        default:            return (1, 400)
        }
    }

    func placeholder(in context: Context) -> RecentCreationsEntry {
        RecentCreationsEntry(date: Date(), items: [])
    }

    func getSnapshot(in context: Context,
                     completion: @escaping (RecentCreationsEntry) -> Void) {
        completion(loadEntry(for: context.family))
    }

    func getTimeline(in context: Context,
                     completion: @escaping (Timeline<RecentCreationsEntry>) -> Void) {
        let entry = loadEntry(for: context.family)
        // The keyboard calls `WidgetCenter.reloadTimelines` right after it
        // records a new image, so this refresh is only a safety net for the
        // case where that call is dropped (extension killed mid-write).
        // One hour keeps us well inside the system's daily reload budget.
        let next = Calendar.current.date(byAdding: .hour, value: 1, to: Date()) ?? Date().addingTimeInterval(3600)
        completion(Timeline(entries: [entry], policy: .after(next)))
    }

    private func loadEntry(for family: WidgetFamily) -> RecentCreationsEntry {
        let spec = Self.layout(for: family)
        return RecentCreationsEntry(
            date: Date(),
            items: HistoryThumbnails.recent(limit: spec.count, maxPixel: spec.maxPixel)
        )
    }
}

// MARK: - Views

struct RecentCreationsView: View {
    @Environment(\.widgetFamily) private var family
    let entry: RecentCreationsEntry

    var body: some View {
        Group {
            if entry.items.isEmpty {
                EmptyStateView()
            } else {
                switch family {
                case .systemSmall: SmallView(item: entry.items[0])
                case .systemLarge: GridView(items: entry.items, columns: 3)
                default:           GridView(items: entry.items, columns: 4)
                }
            }
        }
        .widgetURL(URL(string: "turtlekeyboard://history"))
        .turtleContainerBackground(WidgetPalette.background)
    }
}

/// Small: one image, full bleed, with the command that made it.
private struct SmallView: View {
    let item: HistoryThumbnails.Item

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Thumbnail(item: item, corner: 0)
            Text("/\(item.command)")
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .foregroundColor(.white)
                .padding(.horizontal, 7)
                .padding(.vertical, 3)
                .background(
                    Capsule().fill(WidgetPalette.green.opacity(0.92))
                )
                .padding(8)
        }
    }
}

/// Medium / large: a header strip plus an evenly-spaced thumbnail grid.
private struct GridView: View {
    let items: [HistoryThumbnails.Item]
    let columns: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HeaderView()
            LazyVGrid(
                columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: columns),
                spacing: 6
            ) {
                ForEach(items) { item in
                    LinkedThumbnail(item: item)
                }
            }
        }
        .padding(12)
    }
}

private struct HeaderView: View {
    var body: some View {
        HStack(spacing: 5) {
            Text("🐢")
                .font(.system(size: 12))
            Text("RECENT")
                .font(.system(size: 11, weight: .heavy, design: .rounded))
                .kerning(0.8)
                .foregroundColor(WidgetPalette.green)
            Spacer(minLength: 0)
        }
    }
}

/// A thumbnail that deep-links to its own entry. `Link` is only honoured
/// on medium and large — small widgets get a single tap target, which
/// `widgetURL` already provides.
private struct LinkedThumbnail: View {
    let item: HistoryThumbnails.Item

    var body: some View {
        if let url = item.deepLink {
            Link(destination: url) {
                Thumbnail(item: item, corner: 8)
            }
        } else {
            Thumbnail(item: item, corner: 8)
        }
    }
}

private struct Thumbnail: View {
    let item: HistoryThumbnails.Item
    let corner: CGFloat

    var body: some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay(
                Image(uiImage: item.image)
                    .resizable()
                    .scaledToFill()
            )
            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
            // Redacts the image when the device is locked, so generated
            // content isn't readable off a Lock Screen glance.
            .privacySensitive()
    }
}

private struct EmptyStateView: View {
    var body: some View {
        VStack(spacing: 6) {
            Text("🐢")
                .font(.system(size: 26))
            Text("No images yet")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(WidgetPalette.ink)
            Text("Type /cap in any chat")
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(WidgetPalette.subtle)
                .multilineTextAlignment(.center)
        }
        .padding(10)
    }
}

// MARK: - Widget

struct RecentCreationsWidget: Widget {
    private let kind = "TurtleRecentCreations"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: RecentCreationsProvider()) { entry in
            RecentCreationsView(entry: entry)
        }
        .configurationDisplayName("Recent Creations")
        .description("Your latest images from /cap and /org. Tap to open History.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
