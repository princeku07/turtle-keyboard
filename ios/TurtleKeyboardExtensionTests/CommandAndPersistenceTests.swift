import XCTest
@testable import TurtleKeyboardExtension

final class CommandAndPersistenceTests: XCTestCase {
    func testEverySlashCommandHasUniqueNameAndPresentation() {
        XCTAssertEqual(Set(SlashCommand.allCases.map(\.rawValue)).count, SlashCommand.allCases.count)
        for command in SlashCommand.allCases {
            XCTAssertFalse(command.emoji.isEmpty)
            XCTAssertFalse(command.buttonTitle.isEmpty)
            XCTAssertFalse(command.loadingMessage.isEmpty)
        }
    }

    func testCommandRoutingBoundarySeparatesLocalAndRemoteCommands() {
        XCTAssertTrue(SlashCommand.split.isLocal)
        XCTAssertTrue(SlashCommand.notion.isLocal)
        XCTAssertTrue(SlashCommand.slack.isLocal)
        XCTAssertFalse(SlashCommand.fix.isLocal)
        XCTAssertFalse(SlashCommand.cap.isLocal)
    }

    func testSuggestionDecoderHandlesJSONFencesAndFallback() {
        XCTAssertEqual(parseSuggestionsJSON("```json\n[\"One\",\"Two\",\"Three\",\"Four\"]\n```"),
                       ["One", "Two", "Three"])
        XCTAssertEqual(parseSuggestionsJSON("A normal reply"), ["A normal reply"])
        XCTAssertEqual(parseSuggestionsJSON(""), [])
    }

    func testAppGroupCompatiblePersistenceRoundTrip() {
        let suite = "TurtleKeyboardTests.\(UUID().uuidString)"
        defer { UserDefaults.standard.removePersistentDomain(forName: suite) }
        let store = UserDefaultsSplitStore(suiteName: suite)
        store.setString("value", forKey: "key")
        store.setInt(4, forKey: "count")
        XCTAssertEqual(store.string(forKey: "key", fallback: ""), "value")
        XCTAssertEqual(store.int(forKey: "count", fallback: 0), 4)
    }

    func testSplitHistorySkipsMalformedRowsAndKeepsNewestFirst() {
        let store = MemorySplitStore()
        store.setString("100|2|200\nbad row\n45.5|3|100", forKey: SplitKeys.history)
        let entries = SplitHistory(store: store).all()
        XCTAssertEqual(entries.count, 2)
        XCTAssertEqual(entries[0].amount, 100)
        XCTAssertEqual(entries[0].people, 2)
        XCTAssertEqual(SplitContract.formatAmount(entries[1].amount / Double(entries[1].people)), "15.17")
    }

    func testAmountWatcherValidation() {
        XCTAssertTrue(AmountWatcher.isAmount("12.50"))
        XCTAssertTrue(AmountWatcher.isAmount("9999999"))
        XCTAssertFalse(AmountWatcher.isAmount("0"))
        XCTAssertFalse(AmountWatcher.isAmount("1.234"))
        XCTAssertFalse(AmountWatcher.isAmount("10000000"))
    }

    func testNetworkErrorsMapToSafeMessages() {
        let offline = APIError.network(URLError(.notConnectedToInternet)).errorDescription
        let timeout = APIError.network(URLError(.timedOut)).errorDescription
        XCTAssertEqual(offline, "No internet connection")
        XCTAssertEqual(timeout, "Request timed out — try again")
        XCTAssertFalse(APIError.server(500).errorDescription?.contains("500") ?? true)
    }

    func testTelemetrySchemaCannotStoreContentProperties() throws {
        let event = PrivacySafeTelemetry.Event(
            name: .commandCompleted, timestamp: 1,
            category: PrivacySafeTelemetry.CommandCategory.writing.rawValue,
            durationMs: 120)
        let json = String(decoding: try JSONEncoder().encode(event), as: UTF8.self)
        XCTAssertFalse(json.contains("prompt"))
        XCTAssertFalse(json.contains("clipboard"))
        XCTAssertFalse(json.contains("content"))
    }
}

private final class MemorySplitStore: SplitStore {
    private var values: [String: String] = [:]
    func string(forKey key: String, fallback: String) -> String { values[key] ?? fallback }
    func int(forKey key: String, fallback: Int) -> Int { Int(values[key] ?? "") ?? fallback }
    func setString(_ value: String, forKey key: String) { values[key] = value }
    func setInt(_ value: Int, forKey key: String) { values[key] = String(value) }
}
