# Keyboard performance budgets

Turtle emits local `os_signpost` events under subsystem
`com.samarth.turtlekeyboard.keyboard`, category `Performance`. Signposts contain
only fixed operation names. They never include typed text, prompts, URLs,
clipboard values, generated content, account identifiers, or server responses.

| Metric | Budget | Signpost |
| --- | ---: | --- |
| Warm keyboard presentation | < 100 ms | `KeyboardInitialization` |
| Cold keyboard presentation | < 250 ms | `KeyboardInitialization` |
| Key-down to insertion | < 16 ms | `KeyDownToInsertion` |
| Suggestion calculation | < 30 ms | `SuggestionLookup` |
| Main-thread stalls | none > 50 ms | Instruments Hangs / Time Profiler |
| Normal typing memory | < 25 MB | Instruments Allocations during typing interval |
| Peak image workflow memory | < 40 MB | image signpost intervals + Allocations |
| Layout switch | < 50 ms | `LayoutSwitch` |

Additional signposts: `FirstKeyGridRendered`, `FirstKeypress`,
`SuggestionUIUpdate`, `CommandRouting`, `ImageDecode`, `ImageRender`, and
`ImageEncode`.

Profile a physical device with Instruments → Points of Interest, Time Profiler,
Hangs, and Allocations. Measure cold presentation after terminating the host app
and keyboard process; measure warm presentation by switching away and back while
the extension remains resident. Test sustained two-thumb typing and all three
key layouts before recording results.
