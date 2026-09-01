import SwiftUI
import WidgetKit

// MARK: - Setup Status widget
//
// "Is Turtle actually working?" on the Home Screen. Turtle needs two manual
// steps in Settings that the app cannot perform for the user — add the
// keyboard, then grant Full Access — and until both are done every AI
// command silently does nothing. This widget makes that state visible
// instead of leaving the user to discover it in a chat.
//
// Deliberately self-retiring: once everything is green it reduces to a quiet
// confirmation line, and the user can remove it.
//
// The state itself is NOT re-derived here. `KeyboardHomeState.current()` is
// the host app's own rule (heartbeat vs. legacy seen-at, 30-day staleness,
// failure-newer-than-success) and it was moved to its own file specifically
// so this target could compile it. A second copy would drift.

// MARK: - Timeline

struct SetupStatusEntry: TimelineEntry {
    let date: Date
    let state: KeyboardHomeState
}

struct SetupStatusProvider: TimelineProvider {

    func placeholder(in context: Context) -> SetupStatusEntry {
        SetupStatusEntry(date: Date(), state: .notConfigured)
    }

    func getSnapshot(in context: Context,
                     completion: @escaping (SetupStatusEntry) -> Void) {
        completion(SetupStatusEntry(date: Date(), state: KeyboardHomeState.current()))
    }

    func getTimeline(in context: Context,
                     completion: @escaping (Timeline<SetupStatusEntry>) -> Void) {
        let entry = SetupStatusEntry(date: Date(), state: KeyboardHomeState.current())
        // Settings toggles happen outside any Turtle process, so there is no
        // event to hang a reload on. The host app refreshes us when it comes
        // to the foreground — which is where the user lands right after
        // flipping those switches — and this is the backstop for the user who
        // enables the keyboard and never reopens the app.
        let next = Calendar.current.date(byAdding: .minute, value: 30, to: Date())
            ?? Date().addingTimeInterval(1800)
        completion(Timeline(entries: [entry], policy: .after(next)))
    }
}

// MARK: - Presentation

/// Display shape for a `KeyboardHomeState`. Keeping the mapping in one place
/// means the small and medium layouts can't disagree about what a state means.
private struct StatusDisplay {
    let headline: String
    let detail: String
    let accent: Color
    let keyboardDone: Bool
    let fullAccessDone: Bool

    init(_ state: KeyboardHomeState) {
        switch state {
        case .notConfigured:
            headline = "Add Turtle Keyboard"
            detail = "Settings → General → Keyboard → Keyboards"
            accent = WidgetPalette.warn
            keyboardDone = false
            fullAccessDone = false
        case .keyboardEnabled:
            headline = "Turn on Full Access"
            detail = "AI commands stay off until you do"
            accent = WidgetPalette.warn
            keyboardDone = true
            fullAccessDone = false
        case .fullAccessRequired:
            headline = "Full Access is off"
            detail = "AI commands can't reach the model"
            accent = WidgetPalette.warn
            keyboardDone = true
            fullAccessDone = false
        case .actionNeeded(let message):
            headline = "Needs attention"
            detail = message
            accent = WidgetPalette.warn
            keyboardDone = true
            fullAccessDone = true
        case .ready(let lastCommand):
            headline = "Turtle is ready"
            detail = lastCommand.map { "Last used /\($0)" } ?? "Type / in any text field"
            accent = WidgetPalette.green
            keyboardDone = true
            fullAccessDone = true
        }
    }

    var isReady: Bool { keyboardDone && fullAccessDone }
}

// MARK: - Views

struct SetupStatusView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SetupStatusEntry

    var body: some View {
        let display = StatusDisplay(entry.state)
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 5) {
                Text("🐢").font(.system(size: 11))
                Text("SETUP")
                    .font(.system(size: 10, weight: .heavy, design: .rounded))
                    .kerning(0.8)
                    .foregroundColor(display.accent)
                Spacer(minLength: 0)
            }

            Spacer(minLength: 6)

            Text(display.headline)
                .font(.system(size: family == .systemSmall ? 15 : 17,
                              weight: .bold, design: .rounded))
                .foregroundColor(WidgetPalette.ink)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
                .fixedSize(horizontal: false, vertical: true)

            Text(display.detail)
                .font(.system(size: 11))
                .foregroundColor(WidgetPalette.subtle)
                .lineLimit(family == .systemSmall ? 2 : 3)
                .minimumScaleFactor(0.8)
                .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: 6)

            // The checklist is the point of the widget while setup is
            // incomplete; once ready it's just noise, so it's dropped.
            if !display.isReady {
                VStack(alignment: .leading, spacing: 3) {
                    ChecklistRow(label: "Keyboard added", done: display.keyboardDone)
                    ChecklistRow(label: "Full Access on", done: display.fullAccessDone)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .padding(12)
        .turtleContainerBackground(WidgetPalette.background)
        // No widgetURL — a plain tap opens the app at its root, which is
        // already the "Finish setting up Turtle" card.
    }
}

private struct ChecklistRow: View {
    let label: String
    let done: Bool

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: done ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(done ? WidgetPalette.green : WidgetPalette.subtle)
            Text(label)
                .font(.system(size: 11, weight: done ? .regular : .semibold))
                .foregroundColor(done ? WidgetPalette.subtle : WidgetPalette.ink)
                .lineLimit(1)
        }
    }
}

// MARK: - Widget

struct SetupStatusWidget: Widget {
    private let kind = "TurtleSetupStatus"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: SetupStatusProvider()) { entry in
            SetupStatusView(entry: entry)
        }
        .configurationDisplayName("Turtle Setup")
        .description("Whether the keyboard is enabled and Full Access is on.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
