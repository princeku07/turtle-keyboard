import SwiftUI
import WidgetKit

@main
struct TurtleKeyboardWidgetBundle: WidgetBundle {
    var body: some Widget {
        RecentCreationsWidget()
        SplitBalanceWidget()
        SetupStatusWidget()
    }
}
