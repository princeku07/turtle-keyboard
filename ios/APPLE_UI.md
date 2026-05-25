# APPLE_UI.md — Designing UI to Apple's standard

A Claude-facing reference. When building or modifying any Apple-platform UI in this repo (host app, keyboard extension, future iPadOS / macOS / visionOS surfaces), read this file first. It encodes the rules Apple actually uses — pulled from the HIG, WWDC sessions, the SF font specs, and the iOS 26 Liquid Glass docs.

The goal: stop producing "good enough" UI and start producing UI that looks like it shipped from Cupertino.

---

## 0. The ten hard rules (read these even if you read nothing else)

1. **Never set a font name string.** Use `Font.system(...)` / `UIFont.preferredFont(forTextStyle:)`. You lose optical sizing, tracking tables, Dynamic Type, and disambiguation glyphs if you hardcode `"SFProDisplay-Regular"`.
2. **Always use semantic colors** (`Color.primary`, `UIColor.label`, `.secondaryLabel`, `systemBackground`, `secondarySystemFill`, …). Never hex literals for text on materials. Semantic colors handle dark mode, vibrancy, Increase Contrast, and Reduce Transparency for free.
3. **Two weights per screen, max.** Regular + Semibold is the safe default. If you reach for a third weight, ask whether you actually need it.
4. **44 × 44 pt minimum hit target** for any tap. Glyph can be smaller; the hit region cannot.
5. **8-pt rhythm on a 4-pt grid.** Spacing tokens: `4, 8, 12, 16, 20, 24, 32, 40, 44, 48, 56, 64`. Never `6, 10, 14, 18` unless you have an optical-alignment reason.
6. **Concentric corners.** When a rounded child sits inside a rounded parent, `inner_radius = outer_radius − padding`. Mismatched radii read as a defect.
7. **Continuous corner curve, always.** `RoundedRectangle(cornerRadius: r, style: .continuous)` / `layer.cornerCurve = .continuous`. Circular arcs only when the radius is half the shorter dimension (a true pill).
8. **Spring animations, not linear or ease-out.** iOS 17+ default is `.smooth`. Use `.snappy` for energetic UI, `.bouncy` for playful. Linear curves only for determinate progress.
9. **Haptics on meaningful state change only.** Not on every tap. `.selection` for picker scrolls, `.impact(.light)` for toggle, `.notification(.success)` for completion. Always `.prepare()` first.
10. **Test in both contrast modes and both tints.** Reduce Transparency, Increase Contrast, Reduce Motion, and (iOS 26.1+) Liquid Glass: Clear vs Tinted. If the UI breaks in any of those modes, it isn't done.

---

## 1. The design philosophy (why Apple UI feels different)

### 1.1 The original three themes

> **Clarity** — Text is legible at every size, icons are precise and lucid, adornments are subtle and appropriate.
> **Deference** — Content over chrome. UI helps people interact with content; it never competes with it.
> **Depth** — Distinct visual layers and realistic motion convey hierarchy and facilitate understanding.

Source: Apple HIG.

### 1.2 The iOS 26 / Liquid Glass additions

WWDC 2025 added three more themes that *augment* the originals, they do not replace them:

- **Hierarchy** — Liquid Glass material gives navigation a distinct, lifted plane above content.
- **Harmony** — The same design language flows across iOS, iPadOS, macOS Tahoe, watchOS, tvOS, visionOS — adapted to each hardware reality.
- **Consistency** — Elements adapt across contexts (size class, dark mode, accessibility) without losing identity.

### 1.3 Operationalizing "deference"

- Backgrounds default to `systemBackground` (pure white / pure black). Don't tint screens for the sake of tinting.
- Navigation bars are **borderless** by default in iOS 15+; Liquid Glass in iOS 26+. A hairline appears only when content scrolls under.
- Buttons are **text-only by default** (tinted with `accentColor`). Filled / prominent only for the single primary CTA per screen.
- Icons are line-weight by default. Filled variants signal a selected state.
- Depth comes from **materials and translucency**, not from skeuomorphic shadow stacks.

### 1.4 The hierarchy ladder

When differentiating primary vs secondary vs tertiary content, change properties in this order of preference:

1. **Opacity** (60% / 30% / 18% via `secondaryLabel` / `tertiaryLabel` / `quaternaryLabel`)
2. **Weight** (Semibold for emphasis, Regular for body)
3. **Size** (Body → Subheadline → Footnote → Caption)
4. **Color** (semantic colors only)
5. **Position** (lower in hierarchy = visually lower)

Reach for size last. Body and Headline are both 17 pt — the difference is weight.

---

## 2. Typography

### 2.1 Font family

Always SF Pro via system APIs. Never set a font name.

| Family | When |
|---|---|
| **SF Pro** | Default for everything on iOS / iPadOS / macOS / tvOS / visionOS |
| **SF Compact** | watchOS default; do not use on iOS |
| **SF Mono** | Code blocks, tabular numeric columns where the entire string is numeric (rare — prefer `monospacedDigit()`) |
| **SF Pro Rounded** | Quantitative HUDs (Activity, Health, Fitness). Not body. |
| **New York** | Editorial / long-form (Books-style). Not a system replacement. |

### 2.2 Optical sizing — Text ↔ Display

SF Pro is a variable font that interpolates the optical axis continuously between **17 pt and 28 pt**. Below ~19 pt it renders as Text (looser tracking, taller apertures, raised dot on the `i`); above ~20 pt as Display (tighter tracking, narrower apertures). The system handles this — never hardcode `"SFProDisplay"` or `"SFProText"`.

### 2.3 The text style table (default Large Dynamic Type)

| Style | Size | Weight | Leading | Tracking |
|---|---:|---|---:|---:|
| Large Title | 34 | Regular | 41 | +0.37 |
| Title 1 | 28 | Regular | 34 | +0.36 |
| Title 2 | 22 | Regular | 28 | +0.35 |
| Title 3 | 20 | Regular | 25 | +0.38 |
| **Headline** | **17** | **Semibold** | **22** | **−0.43** |
| **Body** | **17** | **Regular** | **22** | **−0.43** |
| Callout | 16 | Regular | 21 | −0.31 |
| Subheadline | 15 | Regular | 20 | −0.23 |
| Footnote | 13 | Regular | 18 | −0.08 |
| Caption 1 | 12 | Regular | 16 | 0.00 |
| Caption 2 | 11 | Regular | 13 | +0.06 |

Never go below 11 pt even at the smallest Dynamic Type.

### 2.4 The Headline pattern

Headline and Body share size and leading (17 / 22) — only weight differs. This is deliberate: a Headline-weighted row title sits flush above a Body-weighted subtitle on the same baseline grid. Use Headline for the primary line in list cells, Subheadline (15 Regular) or Footnote (13 Regular) for the secondary line.

### 2.5 Tracking

Apple's tracking table is **negative at display sizes, positive at small sizes**. If you use system text styles, you get this for free.

| Size | Tracking | Size | Tracking |
|---:|---:|---:|---:|
| 11 | +6 | 17 | −24 |
| 12 | 0 | 20 | −16 |
| 13 | −6 | 24 | −19 |
| 15 | −16 | 28 | −20 |
| 16 | −20 | 36 | −24 |

If you must hand-set a font (e.g. for a custom logotype), apply matching tracking via `.tracking()` / `NSAttributedString.Key.kern`.

### 2.6 Dynamic Type

12 size categories from `.xSmall` to `.accessibility5` (AX5). Body scales **17 → 53 pt** across the full range. Rules:

- **Always enable scaling**: `adjustsFontForContentSizeCategory = true` (UIKit). SwiftUI auto-scales.
- **Do not scale**: tab/toolbar glyphs, hardware-mimicking controls, the keyboard keys themselves.
- For paddings and image sizes that *should* grow with text, use `@ScaledMetric(relativeTo: .body) var spacing: CGFloat = 8`.
- **Constrain max scale** only when layout truly breaks: `.dynamicTypeSize(.xSmall ... .accessibility1)`. Never clamp tighter than AX1 on essential reading text.

### 2.7 Text on materials and Liquid Glass

- Semantic colors only (`Color.primary`, `.secondary`, `.tertiary`, `.quaternary` / `UIColor.label`, `.secondaryLabel`, …). The system blends them correctly through vibrancy.
- **Semibold is the floor weight** for any label sitting directly on `.glassEffect()` smaller than 15 pt. iOS 26 bumped default weights for legibility against Liquid Glass.
- All-caps section headers were removed system-wide in iOS 26. Use sentence-case Semibold instead.
- Reduce Transparency and Increase Contrast handle themselves if you use semantic colors.

### 2.8 Numerics

SF uses **proportional digits by default**, which causes "wiggling" when numbers animate or align in columns. Use monospaced digits whenever:

- A number animates in place (timer, counter, score)
- Numbers align in a column (prices, durations)
- A live metric updates frequently

```swift
Text("12:34:56").monospacedDigit()
// or
UIFont.monospacedDigitSystemFont(ofSize: 17, weight: .regular)
```

Do not switch the whole font to SF Mono — `monospacedDigit()` keeps letters proportional and only fixes digit widths.

### 2.9 Truncation, leading, hyphenation

- Default `lineBreakMode`: `.byTruncatingTail` (single line), `.byWordWrapping` (multi-line).
- Enable `allowsDefaultTighteningForTruncation` / `.allowsTightening(true)` for nav titles and sheet headers — system shrinks tracking ~5% before truncating.
- Hyphenation is off by default; only enable for fully-justified long-form reading (Books, News).
- `.minimumScaleFactor(0.8)` for badges/chips that must fit. Never below `0.5`.
- Tight leading (−2 pt) for stacked display titles; loose leading (+2 pt) for long-form reading. SwiftUI: `.leading(.tight)` / `.leading(.loose)`.

---

## 3. Color

### 3.1 The system palette (light / dark hex)

| Color | Light | Dark |
|---|---|---|
| `systemRed` | `#FF3B30` | `#FF453A` |
| `systemOrange` | `#FF9500` | `#FF9F0A` |
| `systemYellow` | `#FFCC00` | `#FFD60A` |
| `systemGreen` | `#34C759` | `#30D158` |
| `systemMint` | `#00C7BE` | `#63E6E2` |
| `systemTeal` | `#30B0C7` | `#40C8E0` |
| `systemCyan` | `#32ADE6` | `#64D2FF` |
| `systemBlue` | `#007AFF` | `#0A84FF` |
| `systemIndigo` | `#5856D6` | `#5E5CE6` |
| `systemPurple` | `#AF52DE` | `#BF5AF2` |
| `systemPink` | `#FF2D55` | `#FF375F` |
| `systemBrown` | `#A2845E` | `#AC8E68` |

Grays:

| Token | Light | Dark |
|---|---|---|
| `systemGray` | `#8E8E93` | `#8E8E93` |
| `systemGray2` | `#AEAEB2` | `#636366` |
| `systemGray3` | `#C7C7CC` | `#48484A` |
| `systemGray4` | `#D1D1D6` | `#3A3A3C` |
| `systemGray5` | `#E5E5EA` | `#2C2C2E` |
| `systemGray6` | `#F2F2F7` | `#1C1C1E` |

All system colors are defined in **Display P3** — hex above is the sRGB fallback. Author custom colors as P3 in Xcode asset catalogs.

### 3.2 Semantic label colors

| Token | Light alpha | Dark alpha | Use |
|---|---:|---:|---|
| `label` | 1.00 | 1.00 | Primary content |
| `secondaryLabel` | 0.60 | 0.60 | Subtitles, supporting copy |
| `tertiaryLabel` | 0.30 | 0.30 | Placeholders, disabled |
| `quaternaryLabel` | 0.18 | 0.16 | Watermarks, faintest hints |
| `placeholderText` | 0.30 | 0.30 | Text field placeholders |

### 3.3 Backgrounds — pick the right family

| Token | Light | Dark | Use |
|---|---|---|---|
| `systemBackground` | `#FFFFFF` | `#000000` | Plain screens (chat, feeds) |
| `secondarySystemBackground` | `#F2F2F7` | `#1C1C1E` | Cards on `systemBackground` |
| `tertiarySystemBackground` | `#FFFFFF` | `#2C2C2E` | Nested content inside cards |
| `systemGroupedBackground` | `#F2F2F7` | `#000000` | Settings-style screens (inset-grouped table backdrop) |
| `secondarySystemGroupedBackground` | `#FFFFFF` | `#1C1C1E` | Cells inside grouped tables |
| `tertiarySystemGroupedBackground` | `#F2F2F7` | `#2C2C2E` | Content nested in grouped cells |

**Picking the wrong family looks wrong in dark mode** because the inversion flips: `systemBackground` is white-then-black, `systemGroupedBackground` is light-gray-then-black, and the elevated tier inverts both.

### 3.4 Fills (translucent — sit on any background)

| Token | Light | Dark | Use |
|---|---|---|---|
| `systemFill` | `#787880` @ 20% | `#787880` @ 36% | Large shapes (sliders track) |
| `secondarySystemFill` | `#787880` @ 16% | `#787880` @ 32% | Medium shapes (segmented controls) |
| `tertiarySystemFill` | `#767680` @ 12% | `#767680` @ 24% | Small shapes (search bars) |
| `quaternarySystemFill` | `#747480` @ 8% | `#747480` @ 18% | Hairlines, pressed states |

### 3.5 Separators

- `separator`: translucent (29% light / 65% dark) — lets background bleed through. Default for table rows.
- `opaqueSeparator`: solid `#C6C6C8` / `#38383A` — for over photos or dynamic content where transparency would be unreadable.

### 3.6 Tint

**One tint per app.** Inherits through `UIView.tintColor` / SwiftUI `.tint(_:)`. Default is `systemBlue`. Do not introduce a second tint for "secondary" actions — use `systemGray` text instead.

### 3.7 Dark mode elevation

Apple ships two tiers of dark backgrounds for elevated surfaces. `systemBackground` returns pure black (`#000000`) at the base level, slightly lighter (`~#1C1C1E`) when traited as `.elevated`. Modally presented controllers get `.elevated` automatically.

**Never hardcode dark hex.** Let dynamic colors resolve.

### 3.8 Contrast minimums

- Body text (< 18 pt regular / < 14 pt bold): **4.5 : 1**
- Large text (≥ 18 pt regular / ≥ 14 pt bold): **3 : 1**
- UI components and icons: **3 : 1**

`secondaryLabel` on `systemBackground` lands ~7:1 (light) / ~9:1 (dark) — safe. `tertiaryLabel` (30%) is **only safe for non-essential text** (timestamps, glyphs with other meaning).

---

## 4. Spacing, layout, corners

### 4.1 Layout margins

| Context | Leading / trailing |
|---|---:|
| iPhone (compact width) | **16 pt** |
| iPad / Mac (regular width) | **20 pt** |
| Body text length cap (iPad) | **`readableContentGuide`** (~672 pt max) |

Multi-column iPad layouts use 20+ pt gutters. iOS does not ship a rigid 12-column grid — use the readable content guide for body text.

### 4.2 Safe area insets

| Device class | Top (portrait) | Bottom | Side (landscape) |
|---|---:|---:|---:|
| Pre-notch iPhone (SE) | 20 | 0 | 0 |
| Notch iPhone (X–14) | 44 | 34 | 44 |
| Dynamic Island (14 Pro, 15+) | 59 | 34 | 59–62 |
| iPad with home indicator | 24 | 20 | 0 |

Bottom is **34 pt** on Face ID iPhones (20 pt on iPads) — never place tap targets in the home-indicator strip. Keyboard area is **not** part of `safeAreaInsets`; use `keyboardLayoutGuide` (iOS 15+) or `UIResponder.keyboardWillShowNotification`.

### 4.3 The 4-pt grid

The atomic unit is **4 pt**; the design rhythm is **8 pt**. ~95% of Apple's published pt values are divisible by 8. Use the spacing scale:

`4, 8, 12, 16, 20, 24, 32, 40, 44, 48, 56, 64` pt

44 is the exception (touch target). SwiftUI `Spacer()` defaults to 8-pt rhythm; `.padding()` default is 16.

### 4.4 Touch targets

- **Minimum: 44 × 44 pt** for any tappable control.
- **≥ 8 pt gap** between adjacent tap targets (12 pt preferred).
- Navigation bar buttons get 44 × 44 implicit hit area even with 24 pt SF Symbols.

### 4.5 Corner radii — when to use which

| Radius | Use |
|---:|---|
| 6 | Tiny chips, badges |
| 8 | Small buttons, list cells in custom UI |
| 10 | Default alert; sheet bottom-sheet handle area; `UISheetPresentationController.preferredCornerRadius` default |
| 12 | Standard card / control |
| 14 | Inset-grouped table row corner |
| 16 | Large cards, sheet content card |
| 20 | Modal sheet outer corner |
| 22 | App-icon inner concentric cue |
| 38.5 | iPhone X-class display corner |
| 53–55 | iPhone 14 Pro+ display corner |

### 4.6 Continuous vs circular curve

- **Continuous** (squircle, superellipse n≈5) is Apple's standard. `RoundedRectangle(..., style: .continuous)` / `CALayer.cornerCurve = .continuous`. No tangent discontinuity at the corner — visually smoother. Use everywhere.
- **Circular** (Bézier arc, n=2) only for true pills/circles where `radius = height/2`, or for sub-4 pt micro-radii where the difference is invisible.

**Default to `.continuous`. Always.**

### 4.7 Concentric corners — the math

```
inner_radius = outer_radius − padding
```

Worked examples:

- Card outer r = 16, internal padding = 12 → inner thumbnail r = **4**
- Button r = 8 inside card with 12 padding → card r = **20**
- Content at 16 padding inside sheet r = 20 → inner card r = **4** (or skip the card — too tight)
- If inner radius computes to **< 4, drop to a rectangle** (no rounding). Sub-4 curves read as a manufacturing defect.

iOS 26 ships `ConcentricRectangle` and `.containerConcentric` that derive child radii from parent shape automatically. **Default container shape across iOS 26 is now Capsule** — that's why Liquid Glass UIs feel pill-shaped.

### 4.8 Inset-grouped table defaults

- Section horizontal inset: **20 pt** from screen edge (iPhone)
- Cell `layoutMargins`: **16 / 16** leading/trailing, **11 pt** vertical
- Cell minimum height: **44 pt** (subtitle style ≥ 58 pt)
- Section corner radius: **10 pt** (top of first cell, bottom of last cell)
- Separator inset: **20 pt** leading from cell, **60 pt** leading if cell has an icon
- Inter-section gap: **35 pt** with header, **22 pt** without

### 4.9 Sheets

- `.medium()` detent ≈ 50% screen height
- `.large()` detent: full minus top safe area + ~10 pt parent visible behind
- Grabber handle: **36 × 5 pt**, centered, 5 pt from top
- `preferredCornerRadius`: **10 pt** system look, **20 pt** for a more modern feel

### 4.10 Grouping ladder — use the lightest tool that works

1. **Whitespace alone** — for loosely related content
2. **Section header** (uppercase footnote, `secondaryLabel`) — when content shares a category
3. **Hairline separator** (`separator`, 1px / 0.33 pt physical) — between rows of the same kind
4. **Inset grouped card** (16 pt corner radius, `secondarySystemGroupedBackground`) — when items must be visually unified
5. **Sheet / modal** — only when truly modal

**Never combine** a card with internal dividers AND outer shadow AND a header rule. Pick one level of containment.

---

## 5. Liquid Glass (iOS 26+)

### 5.1 What it is

A translucent "digital meta-material" that dynamically bends and shapes light, with real-time lensing, specular highlights that respond to device orientation and touch, content-aware shadows, and adaptive tinting. It is **rendered per-frame from the scene below it** — not composited from a pre-blurred snapshot like the old `UIBlurEffect` materials.

### 5.2 The two variants

| Variant | When |
|---|---|
| **Regular** | Default. Full adaptive stack (tint, shadow opacity, light/dark flip for small chrome). Use over any content. |
| **Clear** | Permanent transparency, no adaptive behavior. Requires three conditions together: (1) over media-rich content, (2) a dimming layer is acceptable, (3) content above the glass is bold and bright. |

**Never mix Regular and Clear in the same interface.**

### 5.3 Where it belongs — and where it doesn't

**Navigation layer only.** Use it on:

- Navigation bars, tab bars, toolbars and their buttons
- Sidebars, menus, popovers
- Alerts, Control Center, notifications
- Lock Screen clock and widgets

**Never on the content layer** — lists, tables, media views, hero cards. Glass is for chrome that floats above content; it is not a card decoration.

### 5.4 Pitfalls Apple specifically warns against

- **Glass on glass.** Glass cannot sample other glass. Adjacent glass shapes must be wrapped in `GlassEffectContainer` / `UIGlassContainerEffect(spacing: 20)` so they sample the scene, not each other.
- **Clear glass on busy content without dimming.** Always pair Clear with a dimming layer.
- **Glass on the content layer.** Never apply to tables, lists, hero media.
- **Custom bar backgrounds.** Remove legacy `UIBarAppearance` and `.presentationBackground()` overrides when adopting iOS 26.
- **Over-tinting.** Toolbar icons render monochrome by default; only tint to convey meaning (green phone, red destructive). When everything is tinted, nothing stands out.
- **Steady-state intersections.** At rest, content should not sit half-under chrome. Reposition or scale.

### 5.5 API quick reference

**SwiftUI:**

```swift
.glassEffect()                                            // capsule by default
.glassEffect(.regular, in: .rect(cornerRadius: 12))
.glassEffect().interactive()                              // scale/bounce/shimmer on touch
.glassEffect().tint(.blue)                                // only when color carries meaning
GlassEffectContainer { ... }                              // group adjacent glass
.glassEffectID("identifier", in: namespace)               // coordinate morph between states
Button(...).buttonStyle(.glass)                           // .glass or .glassProminent
ToolbarSpacer(spacing: .fixed(16))
.scrollEdgeEffectStyle(.automatic | .denser | .hard)
.tabBarMinimizeBehavior(.onScrollDown)
```

**UIKit:**

```swift
UIVisualEffectView(effect: UIGlassEffect())               // effect = nil to dematerialize
UIGlassContainerEffect(spacing: 20)                       // siblings closer than spacing morph
view.cornerConfiguration = .containerRelative()           // or .fixed(8)
UIButton.Configuration.glass()                            // or .prominentGlass()
scrollView.topEdgeEffect.style = .hard
tabBarController.bottomAccessory = UITabAccessory(contentView:)
```

Recompiling against the iOS 26 SDK auto-upgrades `UITabBarController`, `UINavigationController`, `UISplitViewController`, `UIToolbar`, `UIAlertController`.

### 5.6 Accessibility — all automatic

- **Reduce Transparency** → glass becomes frostier and more opaque.
- **Increase Contrast** → elements become predominantly black/white with contrasting borders.
- **Reduce Motion** → elastic / dynamic responses disabled, lensing intensity drops.
- **Settings → Display & Brightness → Liquid Glass: Clear | Tinted** (iOS 26.1+) — user-facing toggle that raises opacity and contrast globally. Test in both modes.

### 5.7 Apple does not publish blur radii or opacity percentages

Values are computed per-frame from scene content. If you see a number quoted for Liquid Glass blur radius or opacity, it is inferred — not from Apple. Use the APIs and trust the system.

### 5.8 Generic glassmorphism (for non-Apple surfaces only)

If you are styling a web surface or a non-glass Apple build, the community baseline:

- `backdrop-filter: blur(8–24px)` (start at 8)
- Fill `rgba(255,255,255,0.10–0.40)`
- **1 px inner stroke** `rgba(255,255,255,0.25–0.30)` to define the edge
- Subtle saturation boost (`saturate(120–180%)`)
- Soft outer shadow for lift

CSS `backdrop-filter` cannot reproduce per-frame lensing, environmental specular highlights, or adaptive tinting — do not market a web component as "Liquid Glass."

---

## 6. Motion & animation

### 6.1 Curves and durations

| Curve / API | Duration | Use |
|---|---:|---|
| `linear` | — | Determinate progress only |
| `easeIn` | 0.20 s | Element leaving screen |
| `easeOut` | 0.25 s | Element entering / appearing |
| `easeInOut` | 0.25–0.30 s | General-purpose state change |

### 6.2 SwiftUI springs (iOS 17+)

| Preset | Duration | Bounce | Use |
|---|---:|---:|---|
| `.smooth` | 0.5 s | 0.00 | Critically damped, no overshoot — system default |
| `.snappy` | 0.5 s | 0.15 | Energetic, small overshoot |
| `.bouncy` | 0.5 s | 0.30 | Playful, noticeable bounce |

Tunable: `.smooth(duration: 0.4, extraBounce: 0.1)`, etc.

### 6.3 Legacy spring parameters

- `.spring()`: `response: 0.55, dampingFraction: 0.825`
- `.interactiveSpring()`: `response: 0.15, dampingFraction: 0.86, blendDuration: 0.25`

Use `interactiveSpring` for gesture-driven animations that need to redirect mid-flight (sheet drag, swipe-to-dismiss).

### 6.4 Standard durations

| Duration | Use |
|---:|---|
| 0.15 s | Tap-down state, chip selection |
| 0.20 s | Quick fade |
| 0.25 s | Default content swap, button press release |
| 0.30 s | Deliberate transition, sheet content swap |
| 0.35 s | Push navigation |
| 0.50 s | Sheet present / dismiss |
| 0.60+ s | Hero / shared element transition |

### 6.5 Transition standards

| Transition | Duration | Curve |
|---|---:|---|
| `UINavigationController` push/pop | 0.35 s | Apple custom ease-in-out |
| Modal sheet (`.pageSheet`) | 0.50 s | spring (response 0.5, damping 0.85) |
| Full-screen modal | 0.40 s | ease-out slide |
| Popover | 0.20 s | ease-out fade + 95→100% scale |
| Alert / Action Sheet | 0.25 s present, 0.20 s dismiss | spring with subtle scale |
| Tab bar switch | 0.0 s | (no animation by default) |
| Keyboard show/hide | 0.25 s | private "keyboard" curve — read from `UIResponder.keyboardAnimationDurationUserInfoKey` and `…CurveUserInfoKey` |

**Always synchronize keyboard-extension chrome animations to the keyboard curve** — read it from the notification user info, do not hardcode 0.25 + easeInOut.

### 6.6 Reduce Motion

Query `UIAccessibility.isReduceMotionEnabled` or `@Environment(\.accessibilityReduceMotion)`.

**Disable:**
- Parallax / depth simulation
- Spring oscillation (use `.linear` or short ease)
- Slide / zoom / scale transitions ≥ 200 pt distance
- Auto-playing video, looping decorative motion

**Keep:**
- State changes (replace slides with **cross-dissolve** at same duration)
- Loading indicators (essential feedback)
- Haptics (separate setting)

Apple's pattern: replace push with cross-dissolve, replace modal slide-up with fade-in, keep duration the same.

### 6.7 Animations are interruptible

Never block user input while an animation is running. Springs in iOS 17+ are interruptible by default.

---

## 7. Haptics

### 7.1 The three generators

**`UIImpactFeedbackGenerator(style:)`** — physical contact / collision feel:

| Style | Use |
|---|---|
| `.light` | Toggle on, small UI element settling |
| `.medium` | Default impact, button confirmation |
| `.heavy` | Big snap, drag-and-drop drop |
| `.soft` (iOS 13+) | Rounded, padded — picker landing |
| `.rigid` (iOS 13+) | Sharp, mechanical — switch flip |

**`UISelectionFeedbackGenerator.selectionChanged()`** — continuous-selection events only (picker scroll, segmented control passes a segment, slider crossing tick marks). Not for one-shot taps.

**`UINotificationFeedbackGenerator.notificationOccurred(_:)`** — completion of an async task:

| Type | Use |
|---|---|
| `.success` | Payment confirmed, form saved, task completed |
| `.warning` | Validation issue, "are you sure" |
| `.error` | Failed network call, denied auth |

### 7.2 Restraint rules

- Don't fire haptics on every button tap. Reserve for **state changes with semantic weight**.
- Don't pair multiple haptics within **< 150 ms** of each other — feels broken.
- Don't haptic on incoming events (notifications) — the system handles that.
- Always call `.prepare()` **before** the trigger for < 100 ms latency. Re-prepare after each fire if you'll trigger again soon.
- Respect `Settings → Sounds & Haptics → System Haptics` (no API to query; the generator silently no-ops if disabled).
- Standard pattern: haptic **+** visual change **+** (optional) sound. Never haptic alone for critical info.

---

## 8. Canonical component patterns

### 8.1 Buttons

| Style | Use |
|---|---|
| **Plain / text-only** | Default. Tinted with `accentColor`. Use for most actions. |
| **Bordered** | Secondary actions, when text-only lacks affordance |
| **Bordered prominent / filled** | The **single primary CTA per screen**. Never two filled buttons on one screen. |
| **Glass** (iOS 26+) | Floating chrome on Liquid Glass surfaces |
| **Glass prominent** (iOS 26+) | Primary CTA on a Liquid Glass surface |
| **Destructive** | Delete / remove / log-out. Tinted `systemRed`. |

Standard heights: **44 pt** (default). Standard radius: capsule (height/2) is iOS 26 default; **8 pt** for older / non-Liquid-Glass contexts.

### 8.2 Lists

- **Inset grouped** for settings-style screens (`UITableView.Style.insetGrouped` / SwiftUI `List` with `.listStyle(.insetGrouped)`).
- **Plain** for feeds, search results, chronological data.
- **Grouped** (legacy edge-to-edge): avoid in new UI.
- Row height: **44 pt** minimum; **58 pt** for subtitle style.
- Disclosure indicator: chevron right (`chevron.right`, `tertiaryLabel`).

### 8.3 Sheets

- **Bottom sheet** (`.medium` / `.large` detents) for transient, focused tasks.
- **Full-screen cover** only when truly modal and immersive.
- **Popover** on iPad / regular size class; converts to sheet on iPhone automatically.
- Always include a Cancel and a primary action. Cancel on leading; primary on trailing (or both centered on iOS depending on context).

### 8.4 Alerts

- **One sentence title** (verb-first if asking a question). No period.
- **Optional one-sentence body** explaining the consequence.
- **Two buttons max** for confirmation. Three for "Save / Don't Save / Cancel" pattern.
- **Cancel on the left**, primary on the right (iOS convention).
- Destructive actions get red tint (`role: .destructive`).

### 8.5 Navigation bars

- Large titles for top-level screens; inline titles for nested.
- **At most one trailing accessory** (typically one icon button). Two reads as cluttered.
- Title and tab text get extra weight automatically on Liquid Glass.

### 8.6 Empty / loading / error states

`UIContentUnavailableConfiguration` (iOS 17+) is the canonical pattern.

- **Empty**: centered SF Symbol (≥ 48 pt, `tertiaryLabel`) + Title 3 headline + secondary-label one-line description + optional tinted button.
- **Loading**: skeleton placeholders for ≥ 400 ms loads; spinner only for ≥ 1 s; full-screen progress only for ≥ 3 s. "The best content-loading experience finishes before people become aware of it."
- **Error**: same structure as empty, symbol tinted `systemRed`; body explains *what* and *why*; primary button is "Try Again" or specific recovery. Never just "Error."

### 8.7 SF Symbols

- **Default size 17 pt** (matches Body).
- 9 weights × 3 scales (Small / Medium / Large), Medium default.
- **Weight-matched to text**: a Semibold 17 pt label pairs with a Semibold 17 pt symbol so strokes visually match.
- Use `imageScale(.small/.medium/.large)` to adjust emphasis without breaking weight match.
- Vertically optically center to **cap height**, not bounding box.
- iOS 26 animated symbols: `.symbolEffect(.bounce / .pulse / .variableColor)` — sparingly, for state feedback.

---

## 9. Accessibility (non-negotiable)

- **Dynamic Type** — support `.xSmall` through `.accessibility5`. Constrain only when layout truly breaks.
- **VoiceOver** — every interactive element has an `accessibilityLabel`. Decorative views are `.accessibilityHidden(true)`.
- **Reduce Motion** — see §6.6.
- **Reduce Transparency** — automatic for semantic colors and materials.
- **Increase Contrast** — automatic for semantic colors.
- **Bold Text** — automatic for system text styles.
- **Hit targets** ≥ 44 × 44 pt — see §4.4.
- **Color is never the sole signal** — pair red text with an icon, pair green status with a checkmark glyph.
- **Contrast** ≥ 4.5:1 body, 3:1 large text and icons — see §3.8.

---

## 10. The "smell test" — what makes UI feel amateur vs Apple-grade

A checklist to run on any UI you've built before declaring it done:

| Smell | Symptom | Fix |
|---|---|---|
| Inconsistent radii | Mixing 10 and 12, or 8 and 10, on the same screen | Pick a radius scale (e.g. 8/12/16/20) and never deviate |
| Three+ weights | Bold title, Medium label, Regular body, Semibold caption | Reduce to two weights per screen |
| Two tints | Blue primary buttons and orange secondary buttons | Use one tint; demote secondary to `systemGray` text |
| Heavy drop shadows | `shadowOpacity ≥ 0.3, shadowRadius ≥ 8` on flat chrome | Depth comes from materials and translucency, not shadow stacks |
| Hard-coded hex | `Color(red: 0.2, green: 0.2, blue: 0.2)` in dark mode | Replace with semantic color |
| Linear or ease-out everywhere | Animation feels flat or "Material Design"-ish | Use springs (`.smooth` / `.snappy` / `.bouncy`) |
| Haptics on every tap | Phone constantly buzzing | Reserve for meaningful state changes |
| All-caps section headers | Pre-iOS 26 pattern | Sentence-case Semibold |
| Sub-4 pt corner radii | Inner element rounds to 2 or 3 | Drop to a rectangle |
| Tap targets < 44 pt | User has to aim | Expand hit region (visual glyph can stay small) |
| Mismatched concentric corners | Outer 16, inner 12 with 4 padding (should be 12, not 12) — wait, that one matches. Bad example: outer 16, inner 8 with 4 padding | `inner = outer − padding`, always |
| Custom font that "looks like SF" | Inter, Helvetica, Roboto, a "geometric sans" | Use the system font — you lose optical sizing, tracking, disambiguation, Dynamic Type |
| Glass on glass | Two `.glassEffect()` surfaces adjacent | Wrap in `GlassEffectContainer` |
| Wiggling numbers | Timer or counter digits shift width as they animate | `.monospacedDigit()` |
| Animation blocks input | Can't tap during a 0.5 s push | Make animations interruptible |
| Empty / error / loading state missing | Blank screen on first load | `UIContentUnavailableConfiguration` |

---

## 11. Applying this to the keyboard extension (project-specific)

The keyboard extension has unique constraints — read these in addition to the general rules.

### 11.1 Memory and rendering ceiling

The keyboard extension has a ~50 MB memory ceiling. Avoid:
- Multiple full-screen `UIVisualEffectView` instances stacked
- High-resolution shadow rasterization (use `shadowPath` always — see `LiquidGlassBackingView.layoutSubviews()`)
- Re-creating glass backing views on every state change — reuse them

### 11.2 Liquid Glass at key scale — use the real APIs

**Canonical path on iOS 26+: `UIGlassEffect` (UIKit) or `.glassEffect()` (SwiftUI).** Hand-rolled blur+tint+specular+rim stacks are an iOS 15–25 fallback only. Faked glass cannot reproduce per-frame lensing, environmental specular highlights that track device motion and the fingertip, content-aware shadow opacity, adaptive tinting against scene brightness, or the morph behavior between sibling shapes. At key scale those cues are still visible — the keyboard catching real light as the phone tilts is exactly the iOS 26 "feel" the keys should ship with.

**Required pattern on iOS 26+:**

```swift
let glass = UIGlassEffect(style: .regular)
glass.tintColor = tintColor                 // forwards to scene-brightness-mapped tonal range
glass.isInteractive = true                  // touch lensing + bounce (only fires when
                                            // touches reach the effect view itself)
let view = UIVisualEffectView(effect: glass)
view.cornerConfiguration = .uniformCorners(radius: .fixed(keyCornerRadius))
```

Group adjacent keys in a `UIGlassContainerEffect(spacing: rowGap)` so neighboring keys morph together instead of sampling each other (glass cannot sample other glass — §5.4).

**Solve the corner-radius bleed properly.** The earlier workaround avoided `UIGlassEffect` because its intrinsic capsule shape ignored `layer.cornerRadius + masksToBounds` — the Metal layer underneath renders outside the standard clipping pipeline, so high-opacity tints (caps-lock latched ~95% white) bled past the rounded corners. The correct fix is **`view.cornerConfiguration = .uniformCorners(radius: .fixed(r))`** — the official API for constraining a glass effect to a fixed shape. Ships in the iOS 26 SDK. Use it.

**Version branch — required because the deployment target is iOS 15:**

```swift
if #available(iOS 26.0, *) {
    // Real UIGlassEffect path (above). This is the default.
} else {
    // Fallback: UIBlurEffect + tint + specular gradient + rim.
    // Lives in LiquidGlassBackingView; reads as the closest available
    // approximation on iOS 15–25 where no real glass material exists.
}
```

**Fallback stack parameters (iOS 15–25 only)** — kept here so the approximation stays consistent with the real material:

- `UIBlurEffect` + tint overlay inside `contentView` so it inherits the rounded clip
- Top-edge specular gradient: white 32% → 6% → clear at 0 / 30 / 85% locations (mimics light catching a curved glass top)
- 0.5 pt rim at 22% white alpha (edge legibility on similar-tone backgrounds)
- Whisper-thin shadow: opacity 0.18, offset (0, 1), radius 1 — anything heavier reads as Material Design elevation rather than glass

**What not to do, either path:**

- Don't stack glass on glass without a container — §5.4. Two glass keys sitting on a glass command bar must share a `UIGlassContainerEffect` / `GlassEffectContainer`.
- Don't tint every key. Tints on glass are for keys that carry meaning (return-key accent, destructive backspace if you ever style one). Letter keys stay un-tinted on iOS 26+ and let the material adapt.
- Don't hardcode blur radius or material opacity on the real path. Apple computes those per-frame from scene content (§5.7).

### 11.3 Keyboard-specific motion

- The keyboard show/hide curve is a **private "keyboard" curve** (`UIView.AnimationCurve(rawValue: 7)`), not `easeInOut`. Always read it from `UIResponder.keyboardAnimationDurationUserInfoKey` and `…CurveUserInfoKey`.
- Banner, command bar, preview overlay, and Quick Panel animations must sync to this curve or the user sees visual desync.
- Keep press feedback short: **0.15 s alpha bounce** is the existing convention (see `QuickPanelView.buildTile`).

### 11.4 Theme palette discipline

The theme system (`KeyboardPalette`, `KeyboardThemeManager`) sets `keyText` to match the active theme — dark ink on light tiles, white on dark tiles. Always read text/icon colors from the palette, never hardcode. The same applies to chip strips, quick panel headers, listening overlay text.

### 11.5 Touch targets on the keyboard

iOS keyboard keys are tighter than the general 44 × 44 pt rule allows — system keyboard keys are ~37–42 pt tall on iPhone. Match the system. But for **non-key controls** (mic button, slash button, command bar buttons, Quick Panel tiles), respect the 44 pt minimum.

### 11.6 Dynamic Type in the keyboard

Keyboard keys themselves do **not** scale with Dynamic Type — they're a hardware-mimicking control. But any text that appears in the command bar, preview, Quick Panel, history panel, or integration panels **must** scale (via `preferredFont(forTextStyle:)`).

### 11.7 Liquid Glass do-not in the keyboard

- Don't apply glass to the entire keyboard backdrop on themes where `KeyboardPalette.bg` is `.clear` (it's already floating glass via the host). The Quick Panel's opaque `UIBlurEffect(style: .systemChromeMaterial)` backdrop is the canonical pattern — it guarantees taps don't fall through.
- Don't stack glass keys on top of a glass command bar without a `GlassEffectContainer`-equivalent. The existing implementation works because the command bar is opaque-backed.

---

## 12. References

Primary (read before deep work):

- [Apple HIG (root)](https://developer.apple.com/design/human-interface-guidelines/)
- [Apple HIG — Materials](https://developer.apple.com/design/human-interface-guidelines/materials)
- [Apple HIG — Typography](https://developer.apple.com/design/human-interface-guidelines/typography)
- [Apple HIG — Color](https://developer.apple.com/design/human-interface-guidelines/color)
- [Apple HIG — Layout](https://developer.apple.com/design/human-interface-guidelines/layout)
- [Apple HIG — Motion](https://developer.apple.com/design/human-interface-guidelines/motion)
- [Apple HIG — Playing Haptics](https://developer.apple.com/design/human-interface-guidelines/playing-haptics)
- [Apple Developer — Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass)
- [Apple Newsroom — Liquid Glass announcement (Jun 9 2025)](https://www.apple.com/newsroom/2025/06/apple-introduces-a-delightful-and-elegant-new-software-design/)

WWDC sessions (for depth):

- [WWDC25 #219 — Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/)
- [WWDC25 #356 — Get to know the new design system](https://developer.apple.com/videos/play/wwdc2025/356/)
- [WWDC25 #323 — Build a SwiftUI app with the new design](https://developer.apple.com/videos/play/wwdc2025/323/)
- [WWDC25 #284 — Build a UIKit app with the new design](https://developer.apple.com/videos/play/wwdc2025/284/)
- [WWDC23 #10158 — Animate with springs](https://developer.apple.com/videos/play/wwdc2023/10158/)
- [WWDC22 #110381 — Meet the expanded San Francisco family](https://developer.apple.com/videos/play/wwdc2022/110381/)
- [WWDC20 #10175 — The details of UI typography](https://developer.apple.com/videos/play/wwdc2020/10175/)
- [WWDC19 #206 — Introducing SF Symbols](https://developer.apple.com/videos/play/wwdc2019/206/)

Editorial / critical:

- [NN/g — "Liquid Glass Is Cracked, and Usability Suffers in iOS 26"](https://www.nngroup.com/articles/liquid-glass/)
- [Daring Fireball — iOS 26.1 Beta 4 Liquid Glass: Clear or Tinted toggle](https://daringfireball.net/linked/2025/10/21/ios-26-1-beta-4-liquid-glass-tinted-option)
- [Pimp my Type — Liquid Glass typography analysis](https://pimpmytype.com/liquid-glass/)
- [Kyle Bashour — Finding the real iPhone X corner radius](https://kylebashour.com/posts/finding-the-real-iphone-x-corner-radius)
- [Squircle.js — How Apple Uses Squircles](https://squircle.js.org/blog/squircles-in-apple-design)
- [Frank Rausch — Modern iOS Navigation Patterns](https://frankrausch.com/ios-navigation/)
- [Sarunw — Dark color cheat sheet](https://sarunw.com/posts/dark-color-cheat-sheet/)
- [Noah Gilmore — iOS system colors with hex](https://noahgilmore.com/blog/dark-mode-uicolor-compatibility)
- [LearnUI — iOS Font Size Guidelines](https://www.learnui.design/blog/ios-font-size-guidelines.html)
- [Use Your Loaf — Monospace Digits](https://useyourloaf.com/blog/monospace-digits/)
