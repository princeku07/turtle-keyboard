import SwiftUI
import WidgetKit

// MARK: - Split Balance widget
//
// Glanceable view of the last bill split. Reads `SplitHistory` straight out
// of the App Group — the same newline-delimited `amount|people|timestampMs`
// log the keyboard's /split panel and the host app's Split screen read, so
// there is no second copy of the data and no format to keep in sync.
//
// Tapping deep-links to `turtlekeyboard://split-detail`, a route that
// already existed in `AppDelegate` before this widget did.

// MARK: - Timeline

struct SplitBalanceEntry: TimelineEntry {
    let date: Date
    let recent: [SplitHistory.Entry]   // most-recent first
    let total: Double                  // sum across the whole log

    var latest: SplitHistory.Entry? { recent.first }
}

struct SplitBalanceProvider: TimelineProvider {

    func placeholder(in context: Context) -> SplitBalanceEntry {
        SplitBalanceEntry(date: Date(), recent: [], total: 0)
    }

    func getSnapshot(in context: Context,
                     completion: @escaping (SplitBalanceEntry) -> Void) {
        completion(loadEntry())
    }

    func getTimeline(in context: Context,
                     completion: @escaping (Timeline<SplitBalanceEntry>) -> Void) {
        // Splits are only ever written while the user is actively in the
        // keyboard, and `SplitIntegration` reloads us at that moment. This
        // is the fallback for a dropped reload, same as the gallery widget.
        let next = Calendar.current.date(byAdding: .hour, value: 1, to: Date())
            ?? Date().addingTimeInterval(3600)
        completion(Timeline(entries: [loadEntry()], policy: .after(next)))
    }

    private func loadEntry() -> SplitBalanceEntry {
        let store = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)
        let all = SplitHistory(store: store).all()
        return SplitBalanceEntry(
            date: Date(),
            recent: Array(all.prefix(4)),
            total: all.reduce(0) { $0 + $1.amount }
        )
    }
}

// MARK: - Formatting

/// `₹` prefix matches every other Split surface (`SplitPanelView`,
/// `SplitHistoryView`, `SplitDetailViewController`). Amount formatting goes
/// through `SplitContract.formatAmount` for the same reason.
private func money(_ v: Double) -> String {
    "₹" + SplitContract.formatAmount(v)
}

private func perPerson(_ entry: SplitHistory.Entry) -> Double {
    guard entry.people > 0 else { return entry.amount }
    return entry.amount / Double(entry.people)
}

private func peopleNoun(_ n: Int) -> String {
    n == 1 ? "person" : "people"
}

private func shortDate(_ ms: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    let f = DateFormatter()
    f.dateFormat = "d MMM"
    return f.string(from: date)
}

// MARK: - Views

struct SplitBalanceView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SplitBalanceEntry

    var body: some View {
        content
            .widgetURL(URL(string: "turtlekeyboard://split-detail"))
    }

    @ViewBuilder
    private var content: some View {
        if #available(iOS 16.0, *), isAccessory {
            AccessoryBody(entry: entry, family: family)
                .turtleContainerBackground(.clear)
        } else if entry.latest == nil {
            SplitEmptyView()
                .turtleContainerBackground(WidgetPalette.background)
        } else if family == .systemSmall {
            SplitSmallView(entry: entry)
                .turtleContainerBackground(WidgetPalette.background)
        } else {
            SplitMediumView(entry: entry)
                .turtleContainerBackground(WidgetPalette.background)
        }
    }

    @available(iOS 16.0, *)
    private var isAccessory: Bool {
        family == .accessoryInline || family == .accessoryRectangular
    }
}

/// Small: the headline number, then the number that actually matters —
/// what each person owes.
private struct SplitSmallView: View {
    let entry: SplitBalanceEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SplitHeader()
            Spacer(minLength: 4)
            if let latest = entry.latest {
                Text(money(perPerson(latest)))
                    .font(.system(size: 30, weight: .heavy, design: .rounded))
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .foregroundColor(WidgetPalette.greenDeep)
                Text("each")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(WidgetPalette.subtle)
                Spacer(minLength: 4)
                Text("\(money(latest.amount)) · \(latest.people) \(peopleNoun(latest.people))")
                    .font(.system(size: 11))
                    .foregroundColor(WidgetPalette.subtle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .padding(12)
    }
}

/// Medium: latest split on the left, the run of recent ones on the right.
private struct SplitMediumView: View {
    let entry: SplitBalanceEntry

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 0) {
                SplitHeader()
                Spacer(minLength: 6)
                if let latest = entry.latest {
                    Text(money(perPerson(latest)))
                        .font(.system(size: 28, weight: .heavy, design: .rounded))
                        .minimumScaleFactor(0.5)
                        .lineLimit(1)
                        .foregroundColor(WidgetPalette.greenDeep)
                    Text("each · \(latest.people) \(peopleNoun(latest.people))")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(WidgetPalette.subtle)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Text("\(money(entry.total)) split so far")
                    .font(.system(size: 10))
                    .foregroundColor(WidgetPalette.subtle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .leading, spacing: 5) {
                ForEach(Array(entry.recent.prefix(4).enumerated()), id: \.offset) { _, item in
                    HStack(spacing: 6) {
                        Text(money(item.amount))
                            .font(.system(size: 12, weight: .semibold, design: .rounded))
                            .foregroundColor(WidgetPalette.ink)
                        Spacer(minLength: 0)
                        Text(shortDate(item.timestampMs))
                            .font(.system(size: 10))
                            .foregroundColor(WidgetPalette.subtle)
                    }
                    .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
    }
}

private struct SplitHeader: View {
    var body: some View {
        HStack(spacing: 5) {
            Text("🐢").font(.system(size: 11))
            Text("SPLIT")
                .font(.system(size: 10, weight: .heavy, design: .rounded))
                .kerning(0.8)
                .foregroundColor(WidgetPalette.green)
            Spacer(minLength: 0)
        }
    }
}

private struct SplitEmptyView: View {
    var body: some View {
        VStack(spacing: 5) {
            Text("🐢").font(.system(size: 22))
            Text("No splits yet")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(WidgetPalette.ink)
            Text("Type /split in any chat")
                .font(.system(size: 10, design: .monospaced))
                .foregroundColor(WidgetPalette.subtle)
                .multilineTextAlignment(.center)
        }
        .padding(10)
    }
}

/// Lock Screen. Inline sits beside the clock, so it gets one line; the
/// rectangular family gets the same two numbers stacked.
@available(iOS 16.0, *)
private struct AccessoryBody: View {
    let entry: SplitBalanceEntry
    let family: WidgetFamily

    var body: some View {
        if family == .accessoryInline {
            if let latest = entry.latest {
                Text("🐢 \(money(latest.amount)) · \(money(perPerson(latest))) each")
            } else {
                Text("🐢 No splits yet")
            }
        } else {
            VStack(alignment: .leading, spacing: 1) {
                Text("SPLIT")
                    .font(.system(size: 10, weight: .heavy, design: .rounded))
                if let latest = entry.latest {
                    Text(money(perPerson(latest)))
                        .font(.system(size: 22, weight: .heavy, design: .rounded))
                        .minimumScaleFactor(0.5)
                        .lineLimit(1)
                    Text("each · \(latest.people) \(peopleNoun(latest.people))")
                        .font(.system(size: 11))
                        .lineLimit(1)
                } else {
                    Text("No splits yet").font(.system(size: 13))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Widget

struct SplitBalanceWidget: Widget {
    private let kind = "TurtleSplitBalance"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: SplitBalanceProvider()) { entry in
            SplitBalanceView(entry: entry)
        }
        .configurationDisplayName("Split Balance")
        .description("Your last bill split and what each person owes.")
        .supportedFamilies(Self.families)
    }

    /// Lock Screen families only exist on iOS 16+, and the target builds at
    /// 15.0, so the list is assembled rather than written as a literal.
    private static var families: [WidgetFamily] {
        var all: [WidgetFamily] = [.systemSmall, .systemMedium]
        if #available(iOS 16.0, *) {
            all.append(contentsOf: [.accessoryInline, .accessoryRectangular])
        }
        return all
    }
}
