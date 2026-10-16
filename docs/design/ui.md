# Cassini Android 0.2: UI system

Status: this document is the binding specification for the `:ui` module (P7, P8) and for the Routes and ViewModels in `:app` (P10). The visual reference is `design/reference/png/*.png` and the markup with the exact inline styles in `design/reference/markup/*.html`. A phone board is 780 x 1688 px, that is 390 x 844 dp at 2x. The States board is 4540 x 2360 px, that is 2270 x 1180 dp. `features.md` gives the behavior and the copy.

## 1. Module and package layout (`:ui`, package `org.cassini.ui`)

```
theme/       Color.kt, RecordingColors.kt, Tone.kt, Type.kt, Shape.kt, Spacing.kt, Fonts.kt, Theme.kt, CassiniIcons.kt
components/  one file per component (section 6)
format/      Durations.kt, Dates.kt, Counts.kt, Spoken.kt, LocalFormat.kt (CompositionLocal: locale, is24Hour, zone)
banner/      BannerKind.kt (catalogue with tone, title, body and action resources), BannerSpec.kt
library/ recording/ note/ models/ settings/ voices/ trash/ licenses/
             XxxScreen.kt (stateless), XxxUiState.kt, XxxEvent.kt
res/         values/strings.xml, values-it/strings.xml, values/colors.xml + values-night/colors.xml (ui_window_bg),
             font/ui_*.ttf, drawable/ui_ic_logo.xml, drawable/ui_ic_stat_logo.xml,
             drawable/ui_ic_launcher_{foreground,background,monochrome}.xml, mipmap-anydpi/ui_ic_launcher.xml (+ _round)
assets/licenses/fonts/InstrumentSans-OFL.txt, IBMPlexMono-OFL.txt
src/test/    fixtures (BoardFixtures.kt, VariantFixtures.kt), BoardTest base, frames, screenshot tests, ContrastTest, FormatTest, FontAssetsTest
src/test/screenshots/{light,dark,it,fontscale,variants,components,states}/*.png   committed goldens
```

`ui/build.gradle.kts` sets `resourcePrefix = "ui_"`. With this setting, each resource name of `:ui` starts with `ui_`: the strings, plurals, fonts, drawables, mipmaps and colors. Thus the resources of `:app` cannot replace them. `:ui` has no XML theme. `Theme.Cassini` is in `:app` (architecture section 6), and it uses `@color/ui_window_bg`.

`:ui` depends on Compose (BOM 2026.09.00: ui, foundation, material3, ui-tooling-preview), on `activity-compose` (only for previews) and on `kotlinx-collections-immutable` 0.5.2. It never depends on `:core`, `:data`, `:engine` or `:app`.

## 2. Principles

- The screens are stateless: `XxxScreen(state: XxxUiState, onEvent: (XxxEvent) -> Unit, modifier)`. A screen holds only the interaction state (scroll, focus, the selection in a text field).
- `UiState` holds **semantic** values: enums, durations in milliseconds, `LocalDate`/`LocalTime`, counts, percent integers and user text (titles, names, words, snippets). `:ui` formats them with its own resources. Thus the same fixture shows in English and in Italian.
- The screens read only `CassiniTheme.colors`, `CassiniTheme.type` and `CassiniTheme.shapes`. No raw hex values are permitted outside `theme/`.
- The stock M3 components are permitted under the tokens (`ModalBottomSheet`, `BasicAlertDialog`, `DropdownMenu`, `Slider`). `CassiniCheckbox` replaces `Checkbox`. The tokens always give the style. `tonalElevation = 0.dp` everywhere.
- Each `UiState` class is `@Immutable` and uses `ImmutableList`.
- The ViewModel classifies the formats that depend on "now" (Today, Yesterday, "23 days left") with an injected `Clock`. It gives them as semantic values (`DayLabel.Today`, `daysLeft = 23`).

## 3. Fonts

| `res/font` file | Upstream | Path | SHA-256 |
|---|---|---|---|
| `ui_instrument_sans_regular.ttf` | github.com/Instrument/instrument-sans @ `7fa22308a3d0c94ee2b3cd537a1196b65db34a3e` | `fonts/ttf/InstrumentSans-Regular.ttf` | `69fd3f7c467c70c1f73b232812407f688f3d87dd7a801ea7281aa97d29cf53d5` |
| `ui_instrument_sans_medium.ttf` | same | `fonts/ttf/InstrumentSans-Medium.ttf` | `56ba599d12b7cf2fff4eebf46d29253231b6f49bc5b6ce7733dfbf3d7940bfae` |
| `ui_instrument_sans_semibold.ttf` | same | `fonts/ttf/InstrumentSans-SemiBold.ttf` | `7151cf505f897e17b4e9b956293b5a60046ec39da3923a8feba29ce86fd14e12` |
| `ui_instrument_sans_bold.ttf` | same | `fonts/ttf/InstrumentSans-Bold.ttf` | `735badeb8b2046cee6f5e1226412ab6c29db04accbca413af03d70e991dce10d` |
| `ui_ibm_plex_mono_regular.ttf` | github.com/IBM/plex tag `@ibm/plex-mono@2.5.0` | `packages/plex-mono/fonts/complete/ttf/IBMPlexMono-Regular.ttf` | `7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43` |
| `ui_ibm_plex_mono_medium.ttf` | same | `…/IBMPlexMono-Medium.ttf` | `98fbd727aae340b236955879dabed4d991aac9e8e90b3b2a67ce4a59221cc97c` |

The file names are only resource names. The font files are the same as upstream, byte for byte. The license texts are the `OFL.txt` of Instrument Sans and `packages/plex-mono/LICENSE.txt`, verbatim. These rules apply:

- Ship the unmodified static TTFs. Never commit the `.woff2` files of the design. They are web subsets of Google Fonts, a subset is a Modified Version under the OFL, and "Plex" is a Reserved Font Name. Do not make subsets, and do not rename the fonts inside the files.
- `third_party/fonts/fonts.json` is the source of truth. It has one entry for each file: `{file, family, weight, repo, ref, path, sha256, license}`. The license copies are `third_party/fonts/instrument-sans/OFL.txt` and `third_party/fonts/ibm-plex-mono/LICENSE.txt`.
- `scripts/fetch-fonts.sh` downloads the files of the manifest, makes sure that the SHA-256 is correct, and copies the files into `ui/src/main/res/font`. With `--check`, it only makes sure that the files agree with the manifest. The script passes ShellCheck.
- `ui/src/main/assets/licenses/fonts/*.txt` are the same as the copies in `third_party`, byte for byte. `THIRD_PARTY_NOTICES.md` has a "Fonts" section: the families, the copyright lines, the SIL Open Font License 1.1, the upstream URLs and refs, and "unmodified".
- `FontAssetsTest` (`:ui`, plain JVM) makes sure of these items: each TTF is in the manifest with the correct hash, and each manifest entry has a TTF. The license copies are equal. No `*.woff` or `*.woff2` file is under `*/src`.
- Plex Mono ships the weights 400 and 500. The "Processed" stat asks for 600. Compose makes 600 from 500 with the default `FontSynthesis.All`, as the browser did. Do not bundle a SemiBold.

```kotlin
val InstrumentSans = FontFamily(Font(R.font.ui_instrument_sans_regular, W400), Font(R.font.ui_instrument_sans_medium, W500),
    Font(R.font.ui_instrument_sans_semibold, W600), Font(R.font.ui_instrument_sans_bold, W700))
val PlexMono = FontFamily(Font(R.font.ui_ibm_plex_mono_regular, W400), Font(R.font.ui_ibm_plex_mono_medium, W500))
```

## 4. Tokens

### 4.1 Colors (`CassiniColors`, from `LocalCassiniColors`)

| Token | Light | Dark | Use |
|---|---|---|---|
| `ink` | #14171A | #ECEEF1 | Primary text |
| `bg` | #F5F6F7 | #0F1214 | Screen background (also `ui_window_bg`) |
| `surface` | #FFFFFF | #181C20 | Cards, sheets, transcript pane, player |
| `surfaceWash` | #F5F6F7 | #1F2429 | Highlighted sheet rows ("Cassini document", "Likely") |
| `boardBg` | #ECEEF0 | #1F2429 | Background of the States board, inactive track of the slider |
| `text2` | #4A525B | #A9B0B9 | Meta, captions, icons, section headers |
| `text3` | #3A4250 | #C3C9D0 | Card bodies, paragraphs of placeholder speakers, settled draft, column headings of the States board |
| `text4` | **#687180** (board #6B7480) | #8C95A0 | Unsettled draft words, placeholders |
| `line` | #E2E5E9 | #2A3036 | Hairlines, card borders |
| `line2` | #C9CED5 | #3A4048 | Outline buttons and chips, drag handle |
| `line3` | #9AA2AC | #5A626C | Dashed "Words may still change" tag, dot of the plain banner |
| `accent` | #2346C8 | #8FA6FF | Progress fill, slider, focused border, dots, checkbox |
| `accentFill` / `onAccentFill` | #2346C8 / #FFFFFF | #2346C8 / #FFFFFF | Primary buttons, active word |
| `accentInk` | #1A3596 | #B9C7FF | Text on the accent tint, blue text buttons, blue avatar initials |
| `accentTint` | #E8EDFC | #1C2647 | Info chip and banner |
| `accentTrack` | #C9D4F6 | #2B3A70 | Track of the Library banner |
| `teal` / `tealInk` / `tealTint` | #0F766E / #0B5A54 / #E3F2EF | #4FC7B8 / #8EDDD2 / #12302D | Ok tone, privacy shield |
| `amber` / `amberInk` / `amberTint` / `amberWash` | #B45309 / #8A3B07 / #FCEFE3 / #FCF6EE | #F0A35E / #F7C291 / #3A2614 / #2C2118 | Warn tone, unnamed speakers |
| `red` / `redInk` / `redTint` / `redLine` | #C2410C / #A3330B / #FBE7E1 / #E3B9A6 | #C2410C / #FFB08F / #3D1E14 / #6B3A2A | Record, error, destructive |
| `violet` / `violetTint` | #5B2BB5 / #EFEAFB | #C1A8FF / #2A2045 | Tint of the 3rd named speaker, 4th avatar tint |
| `inkFill` / `onInkFill` | #14171A / #FFFFFF | #ECEEF1 / #0F1214 | Ink pills, play/pause, selected segment |
| `snackbarBg` / `snackbarText` / `snackbarAction` | #14171A / #F2F3F5 / #9DB4FF | #ECEEF1 / #14171A / #2346C8 | Snackbar |
| `scrim` | #1F2A39 at 50 % | #000000 at 60 % | Sheets and dialogs (over `bg`, it gives the #8A9098 of the boards) |
| `link` | #2346C8, underlined | #8FA6FF | Inline links |

`RecordingColors` is one set, because the Recording screen is dark in the two themes: `bg` #14171A, `text` #F2F3F5, `text2` #A9B0B9, `line` #3A4048, `barIdle` #4A525B, `barFresh` #FF8A55, `dot` #FF6A2B, `status` #FF9A6B, `pauseFill` #F2F3F5, `onPause` #14171A and `finish` #C2410C.

`Tone { Ok, Info, Warn, Error, Plain, Outline }` gives `ToneColors(bg, fg, dot, border?)`:

- Ok: tealTint, tealInk, teal.
- Info: accentTint, accentInk, accent.
- Warn: amberTint, amberInk, amber.
- Error: redTint, redInk, red.
- Plain: surface, ink, line3, with the border line.
- Outline: transparent, ink, no dot, with the border line2.

On the Recording screen, the banners keep their light tones.

`SpeakerTone { Blue, Teal, Violet, Amber }` gives (dot, label/ink, tint):

- Blue: accent, accentInk, accentTint.
- Teal: teal, tealInk, tealTint.
- Violet: violet, violet, violetTint.
- Amber: amber, amberInk, amberTint.

features.md section 0 gives the rule that selects a tone. The avatars use the same three values: the tint as the fill and the ink color for the initial.

### 4.2 Material mapping

`CassiniTheme(darkTheme = isSystemInDarkTheme())` gives the locals and a `MaterialTheme`:

```kotlin
lightColorScheme(
  primary = accentFill, onPrimary = onAccentFill, primaryContainer = accentTint, onPrimaryContainer = accentInk,
  inversePrimary = Color(0xFF9DB4FF),
  secondary = teal, onSecondary = White, secondaryContainer = tealTint, onSecondaryContainer = tealInk,
  tertiary = amber, onTertiary = White, tertiaryContainer = amberTint, onTertiaryContainer = amberInk,
  error = redInk, onError = White, errorContainer = redTint, onErrorContainer = redInk,
  background = bg, onBackground = ink, surface = surface, onSurface = ink,
  surfaceVariant = bg, onSurfaceVariant = text2, surfaceTint = Color.Transparent,
  surfaceBright = surface, surfaceDim = boardBg,
  surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
  surfaceContainerHigh = surface, surfaceContainerHighest = surface,
  inverseSurface = ink, inverseOnSurface = Color(0xFFF2F3F5),
  outline = line2, outlineVariant = line, scrim = Color(0xFF1F2A39),
)
// dark: the same mapping from the dark column; inverseSurface #ECEEF1, inverseOnSurface #14171A.
```

There is no dynamic color. The ripples follow the content color. The XML `Theme.Cassini` in `:app` sets `android:windowBackground` to `@color/ui_window_bg` (bg, with a `values-night` value). Thus the first frame at a cold start has the correct color.

### 4.3 Typography (`CassiniType`, from `LocalCassiniType`)

The CSS "normal" line height comes from the hhea metrics: 1.22 em for Instrument Sans and 1.30 em for Plex Mono. Each style sets `lineHeight` explicitly, with `LineHeightStyle(Alignment.Center, Trim.None)`. The letter spacing is in em. The helpers are `sans(size, weight = 400, lh = 1.22, ls = 0)` and `mono(size, weight = 400, lh = 1.30, ls = 0)`.

| Name | Style | Used by |
|---|---|---|
| `timer` | mono(72, ls −0.02), tabular | Recording timer |
| `percent` | sans(40, 600, lh 1.0, ls −0.02), "%" span 20 sp | Progress card |
| `hero` | sans(28, 600, lh 1.15, ls −0.015) | Model download title |
| `libraryTitle` | sans(24, 600, ls −0.01) | "Notes" |
| `screenTitle` | sans(22, 600) | Settings, Saved voices, Trash, the title field of Recording |
| `sheetTitle` | sans(20, 600) | Name sheet, dialogs |
| `noteTitle` | sans(18, 600) | Note top bar, actions sheet |
| `transcript` | sans(17, 400, lh 1.6) | Sealed transcript |
| `field` | sans(17) | Name field |
| `fab` | sans(17, 600) | Record |
| `rowTitle` | sans(16, 600) | Library rows, trash cards, voices |
| `body16` | sans(16) | Search, sheet rows |
| `heroBody` | sans(16, lh 1.5) | Model download body |
| `draft` | sans(16, lh 1.55) | Draft transcript |
| `cta` | sans(16, 600) | 56 dp primary button |
| `avatar` | sans(16, 700) | Avatar initials |
| `button` | sans(15, 600) | Buttons, titles of settings rows, banner titles, stat values |
| `buttonRegular` | sans(15) | Cancel |
| `checkTitle` | sans(15, 600, lh 1.4) | Remember checkbox |
| `body15` | sans(15) | Rows with model sizes |
| `label14` | sans(14, 600) | Small buttons, title of the Library banner |
| `body14` | sans(14, lh 1.45) | Banner body, intros, recording caption |
| `body14Plain` | sans(14) | Secondary small buttons, snackbar |
| `about` | sans(14, lh 1.5) | About card, privacy note of Saved voices |
| `snippet` | sans(14, lh 1.4) | Library preview and snippet |
| `caption` | sans(13) | Meta, helpers |
| `chip` | sans(13, 500) | Status chips |
| `overline` | sans(13, 600, ls 0.06), uppercase | Library day headers, column headings of the States board (text3) |
| `speaker` | sans(13, 600) | Name in the turn header |
| `fieldLabel` | sans(13, 600), text3 | "Name", "Saved voices, closest first" |
| `section` | sans(12, 600, ls 0.06), uppercase | Settings and sheet sections |
| `caption12` | sans(12, lh 1.4) | Progress footnote |
| `tag12` | sans(12, 600) / sans(12) | "Likely" / dashed tag |
| `stage` | sans(11), current 600 | Stepper |
| `badge` | sans(11, 500) | "auto" |
| `skip` | sans(10, 700, lineHeight 12 sp) | "10" in the skip buttons |
| `monoTime` | mono(13) | Durations, timestamps, ranges |
| `monoSmall` | mono(12) | Player times, format chip |
| `monoValue` | mono(15, 600 synthesised) | "Processed" |
| `monoSize` | mono(15) | Model sizes |

A mono run on a row with a line box from a larger sans style gets the line height of that sans style (`mono(13).copy(lineHeight = sans(16).lineHeight)`).

The Material `Typography` uses these styles: displayLarge timer, displayMedium percent, displaySmall hero, headlineLarge libraryTitle, headlineMedium screenTitle, headlineSmall sheetTitle, titleLarge noteTitle, titleMedium rowTitle, titleSmall button, bodyLarge body16, bodyMedium body14, bodySmall caption, labelLarge button, labelMedium chip and labelSmall badge.

All sizes are in sp, and they change with the system font size. Fixed heights become `heightIn(min = …)`.

### 4.4 Shapes, spacing, elevation

- Pill = `RoundedCornerShape(50)`.
- Radii in dp: 2 (4 dp track), 3 (6 dp track), 4 (active word, Finish square, checkbox), 6 (auto badge), 8 (tags, format badge), 10 (status chip), 12 (segment).
- More radii in dp: 14 (rows, inputs, inner groups of sheets, suggestion card, snackbar, confirm box, menus), 16 (banners, settings groups, trash cards, Library banner, 32 dp chips), 18 (Saved voices list), 20 (progress card), 28 (top corners of sheets, dialogs).
- M3 `Shapes(extraSmall 4, small 8, medium 14, large 16, extraLarge 28)`.
- Spacing: `gutter` 16 dp (cards and fields from the edge), `textGutter` 20 dp (section headers, transcript, intros), `listInset` 12 + `rowPad` 10 (Library rows) and `topBarPad` 8. Section 6 gives the other values for each component.
- Flat design with 1 dp `line` borders. The only shadow is on the Record button: `dropShadow(pill, Shadow(radius 18 dp, color red, alpha 0.28, offset 0/6 dp))`. Sheets, dialogs, menus and the snackbar have no shadow.

### 4.5 Icons

The app does not use `material-icons-extended`. Each icon is an `ImageVector` that comes from the SVG path data of the design (viewport 24, and 28 for the logo). The strokes have round caps and joins, and `Icon(tint)` gives the color. The stroke width is in viewport units.

The code has `enum class Glyph(paths, filled, viewport)` and `CassiniIcon(glyph, size, stroke, tint, contentDescription)`, with a cache for each (glyph, stroke). `addPathNodes` parses `d`. A circle is two arcs. The ellipse of the logo is in `group(rotate = -20f, pivotX = 14f, pivotY = 14f)`.

| Glyph | Path data | Sizes / stroke |
|---|---|---|
| Logo (28) | circle(14,14,r5.5) + ellipse rx12 ry4.2 rotated −20° | 28/1.6 ink, 56/1.3 accent |
| Trash | `M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13` | 22/1.7, 24/1.7 |
| Settings | circle(12,12,3) + the gear path of board 00 | 22/1.7 |
| Search | circle(11,11,7) + `m20 20-3.5-3.5` | 20/1.8, 21/1.8 |
| Shield | `M12 3 4 6v6c0 4.5 3.4 8 8 9 4.6-1 8-4.5 8-9V6z` | 14/2, 18/2 teal |
| Import | `M12 4v11M7 10l5 5 5-5M5 20h14` | 20/1.8 |
| ChevronDown | `m6 9 6 6 6-6` | 22/1.8, 14/2 |
| ChevronRight | `m9 6 6 6-6 6` | 18/1.8 |
| ChevronUp | `m6 15 6-6 6 6` | 22/1.8 (search) |
| Back | `M15 6l-6 6 6 6` | 22/1.8 |
| Close | `M6 6l12 12M18 6 6 18` | 22/1.8, 20/1.8 banner dismiss |
| Pencil | `M4 20h4L19 9l-4-4L4 16z` | 18/1.8, 24/1.7 |
| List | `M3 6h18M3 12h18M3 18h12` | 22/1.8 |
| Pause (filled) | rect(6,5,4,14,rx1) + rect(14,5,4,14,rx1) | 30, 26 |
| Play (filled) | `M7 5v14l12-7z` | 18, 26 |
| More (filled) | circles (12,5),(12,12),(12,19) r1.7 | 22 |
| MoreSmall (filled) | circles (12,5),(12,12),(12,19) r1.6 | 20 (Saved voices options) |
| Check | `m5 12 5 5 9-10` | 14/2.2 |
| Back10 | `M4 12a8 8 0 1 0 2.3-5.6` + `M4 4v4h4` | 28/1.6 |
| Fwd10 / Retranscribe | `M20 12a8 8 0 1 1-2.3-5.6` + `M20 4v4h-4` | 28/1.6, 24/1.7 |
| Music | `M9 18V5l11-2v13` + circle(6,18,3) + circle(17,16,3) | 24/1.7 accent |
| TextFile | `M6 3h9l4 4v14H6z` + `M9 11h7M9 15h7` | 24/1.7 |
| Subtitles | rect(3,5,18,14,rx2) + `M7 14h4M13 14h4M7 10h10` | 24/1.7 |
| Info | circle(12,12,9) + `M12 11v6M12 7.5v.5` | 24/1.7 |
| Warn | `M12 4 3 20h18z` + `M12 10v4M12 17v.5` | 14/2 (trust chips) |

The logo vector is also the adaptive launcher foreground (ink on bg, in `mipmap-anydpi/ui_ic_launcher.xml` with a monochrome layer) and the small notification icon `ui_ic_stat_logo`.

## 5. Touch targets

Each standalone control has a touch area of at least 48 x 48 dp. Some visuals of the boards are smaller: 44 dp icon buttons, 40/36 dp pills, 44 dp segments and the 32 dp version chip. For these controls, the layout is 48 dp, with the visual at the center. The padding next to the control is less by the difference. Thus the pixels do not move. Use an explicit `Modifier.touchTarget()` helper, so that the code shows the compensation. Do not use the silent `minimumInteractiveComponentSize()`. Section 6 gives the compensated values for each component ("visual 44 / layout 48").

The inline text buttons inside a line of text (the Library row suffixes "· Resume" and "· Try again") keep their text layout. Their touch area gets to 48 x 48 dp through the extended hit testing of Compose. A `clickable` node that is smaller than `LocalViewConfiguration.current.minimumTouchTargetSize` receives the taps in the 48 dp square around its center, without a change of the layout. A test taps 14 dp above the center of the text and expects the event. The row also gives the action as a TalkBack custom action. The inline word links of the transcript are an exception (the inline exception of WCAG 2.5.8).

## 6. Components (`components/`, each with a `@Preview` and a component screenshot)

The values in this section are in dp, unless the text gives a different unit.

### `CassiniTopBar(title, subtitle?, onBack, actions)`

- Layout: a plain Row.
- Board padding: 12/8/4 (bottom 0 on Read & listen). Gap: 4 (2).
- Compensated padding: top 10, horizontal 6, bottom 2. On Read, the bottom is 0, and the top of its chips row changes from 6 to 4. The gap after back is 2 (0 on Read).
- The title starts at 56 dp (54 on Read).
- Title: `noteTitle` (Note) or `screenTitle` (Settings, Saved voices, Trash), one line, with an ellipsis.
- Subtitle: `caption` text2, directly under the title. It is not there when it is null (Read & listen).
- `IconButton44`: a 48 dp box, a 44 dp circular ripple and a 22 dp ink icon. The contentDescription is mandatory.
- Library header: padding 20/16/8 (compensated 18/14/6), then logo 28 + 10 dp + "Notes", with Trash and Settings at the end.
- Trash header action: "Empty" (redInk `button`, visual 44 / layout 48, padding 14).

### `PillSearchField`

- Height 52, pill, surface, 1 dp line, padding 16.
- The Search icon 20/1.8 text2, then a 10 dp gap.
- `BasicTextField`: body16 ink (2 dp more inset at the start), the placeholder in text4, the cursor in accent, `ImeAction.Search`.
- At the end: a 48 dp clear button "Clear search" when the field is not empty.

### `PrivacyLine`

- The shield 14/2 teal, gap 6, `caption` text2. Padding: start/end 4, top 10.
- Saved voices variant: the shield 18/2 at the top (2 dp down), gap 10, `about` text3. Margins: top 8, horizontal 20, bottom 16.

### `ProgressBannerCard(title, percent?, progress?, line, onClick)`

- Margin: top 14, horizontal 16. Padding 12/14. accentTint, radius 16, gap 8.
- Row 1: the title in `label14` (one line), on the same baseline as the percent in `monoTime`.
- A 4 dp track (accentTrack, accent fill, radius 2).
- The caption in text3.
- Role Button, onClickLabel "Open note".
- A polite live region. Its description changes only at a change of stage or at 5 % steps.

### `RecordingBannerCard(title, elapsedMs, paused, onClick)` (no board)

- Margin: top 14, horizontal 16. `RecordingColors` bg, radius 16, padding 12/14.
- A Row with gap 10. First a 10 dp rec.dot (paused: a 1.5 dp rec.text2 ring). Then a Column with "Recording · <title>" (`label14` rec.text, one line) and the caption "Tap to return" (`caption` rec.text2). At the end: the elapsed time in `monoTime` rec.text2.
- The card is before the progress card, with 8 dp between them. Role Button.

### `DayHeader`

- `overline` text2. Padding: horizontal 20, bottom 6, top 14 (first header) or 16. It is a heading.

### `NoteRow`

- List padding: horizontal 12, with 2 dp between the rows.
- Radius 14. surface when highlighted, else transparent. Padding 12/10, gap 4.
- Title: `rowTitle` (at most 2 lines), then 8 dp, then the duration in `monoTime` text2 (with the line height of sans16).
- Preview: `snippet` text3, 2 lines. `Highlighted` gives the ellipses at the start and at the end. The highlights are W600 ink on accentTint.
- Meta row with gap 8: the time in `caption`, the `StatusChip` and the suffix (`caption` text2). "· Resume" and "· Try again" are inline text buttons with extended hit areas (section 5).
- `mergeDescendants`, with the description "Weekly product sync, 48 minutes 13 seconds, today 10:02, transcribing". Custom actions. `combinedClickable(onLongClick)`.

### `StatusChip(text, tone, dot?)`

- Padding 8/2, radius 10, `chip`. Outline: 1 dp line2, ink. It is not interactive.

### Tags

- "Likely": `tag12` 600, accentInk on accentTint, radius 8, padding 2/8.
- "Words may still change": `tag12` 400, text2, and a 1 dp dashed line3 border with `drawBehind` and `dashPathEffect(3 dp, 2 dp)`. Radius 8.
- "auto" badge: `badge`, text2, 1 dp line2, radius 6, padding 5/0.
- Format badge: `monoSmall` rec.text2, rec.line border, radius 8, padding 4/8.

### Large chips (height 32, radius 16)

- Verified: tealTint/tealInk, padding 12, gap 6, Check 14/2.2, 13/600.
- The other trust chips: the same, with the Warn/Error tones and the Warn icon.
- `VersionChip`: surface, 1 dp line2, padding 12/10, 13 ink and ChevronDown 14/2. Visual 32 / layout 48.
- The speaker count is a plain caption.
- `FollowChip` (no board): the style of VersionChip with "Follow playback" and Play 14. It floats 16 dp above the player, at the center.

### Buttons

All buttons are pills, with no elevation, a clipped ripple and `Role.Button`. A disabled button has 38 % alpha. A busy button shows a 16 dp spinner and keeps its width.

| Component | Visual / layout | Padding | Fill / border / text |
|---|---|---|---|
| `PrimaryButton` large | 56 / 56 | Fill width | accentFill / – / `cta` onAccentFill |
| `PrimaryButton` | 48 / 48 | 22 | accentFill / – / `button` |
| `InkButton` small | 36 / 48 | 14–16 | inkFill / – / `label14` onInkFill |
| `InkButton` medium | 40 / 48 | 14 | inkFill / – / `label14` |
| `SecondaryButton` large | 52 / 52 | 20, icon gap 8 | surface / line2 / `button` ("Import audio") |
| `SecondaryButton` | 48 / 48 | 18 | transparent / line2 / `buttonRegular` ("Cancel") |
| `SecondaryButton` block | 44 / 48 | Fill width | surface / line2 / `label14` ("Stop and keep progress") |
| `SecondaryButton` medium | 40 / 48 | 16 or 14 | surface / line2 / `label14` or `body14Plain` |
| `SecondaryButton` small | 36 / 48 | 14–16 | surface / line2 / `body14Plain` |
| `TextButton` | 52 or 48 | 14 | – / – / `button` accentInk |
| `DestructiveTextButton` | 44 or 40 / 48 | 14 or 12 | – / – / redInk |
| `DestructiveOutlineButton` | 52 / 52 | Fill − 32 | surface / redLine / `button` redInk |
| Destructive dialog confirm | 48 / 48 | 22 | redInk / – / white 15/600 |

`PrimaryButton` has a `blockedReason: String?`. When it is set, the button looks enabled, a push calls `onBlocked` and not `onClick`, and its stateDescription is the reason. The Save button of the Name sheet uses it (features.md section 4).

### `LibraryBottomBar`

- At the bottom. Padding: horizontal 16, top 40, bottom 24 + the navigation-bar inset.
- `verticalGradient(0 → bg@0, 0.4 → bg)`.
- Import at the left and **`RecordFab`** at the right.
- `RecordFab`: height 64, radius 32, padding 22/26, red, gap 10, an 18 dp white circle, "Record" in `fab` white, and the drop shadow. The description is "Record a new note".
- The recording variant of `RecordFab`: "Recording · 24:07" with the live dot. The description is "Return to recording".
- The list gets `contentPadding(bottom = 128 dp + navigation bars)`.

### `SegmentedControl`

- surface, radius 16, height 52.
- Three equal segments with click areas of the full height. The visual is a 44 dp rect (radius 12) with an inset of 4 dp (2 dp between neighbors).
- Selected: inkFill and `label14` onInkFill. Not selected: `body14Plain` ink.
- `selectableGroup()`, and each segment is `selectable(role = RadioButton)`.

### Settings components

- `SectionHeader`: `section` text2. Padding: horizontal 20, top 22 (16 for the first), bottom 6. It is a heading.
- `GroupCard`: horizontal margin 16, surface, radius 16, an optional 1 dp line border, and 1 dp dividers between the rows.
- `SettingsRow`: padding 14/16, gap 12. The title is `button`, and the subtitle is `caption` text2. At the end: a status text 13/600 in the ink color of the tone, ChevronRight 18/1.8, or nothing.
- `HelperText`: `caption` text2 with lh 1.4. Margin: top 6, horizontal 20.
- `AboutCard`: padding 14/16, `about` text3, the version in bold, and `LinkAnnotation.Clickable` "Source code and notices".

### `CassiniSheet`

- `ModalBottomSheet(skipPartiallyExpanded = true, shape top 28, containerColor surface, scrimColor scrim, dragHandle = DragHandle (36 x 4, radius 2, line2, 10 dp above), contentWindowInsets = navigationBars)`.
- IME padding.
- For the screenshots, `SheetBoardFrame` draws the content of the sheet over the scrim.

### `SheetActionRow`

- Featured: surfaceWash, radius 14, padding 12, gap 14, aligned at the top. Music 24/1.7 accent. The title is `rowTitle`. The caption has an extension in `monoTime`.
- Plain: minimum 52, padding 12, gap 14. Icon 24/1.7. The label is `body16` with a mono extension. An optional subtitle.
- `SheetSection`: `section`. Margin: top 8, horizontal 12, bottom 4.
- Divider: 1 dp line, margin 8/12.

### `CassiniDialog` (`BasicAlertDialog`)

- surface, radius 28, padding 24, gap 16.
- The title is `sheetTitle`. The body is `body14` text3 or a custom slot (a text field, key/value rows).
- The actions are at the end, with gap 8.
- Its content is a separate composable, `CassiniDialogContent`. Thus `DialogBoardFrame` can draw it inline for the screenshots.

### `CassiniMenu` (`DropdownMenu`)

- surface, radius 14, 1 dp line. The items have a minimum of 48 dp, horizontal padding 16 and `body16`. The destructive items are redInk.
- The content is separate (`CassiniMenuContent`), for `MenuBoardFrame`.

### `CassiniSnackbarHost`

- At the bottom. Margin: horizontal 16, and 24 at the bottom, above the insets and the bottom bar.
- snackbarBg, radius 14, padding 6/16/6. The message is `body14Plain`. The action is 14/600 snackbarAction (visual 44 / layout 48).
- A polite live region. The action is also a custom action.
- The duration is a parameter. The caller calculates it from `AccessibilityManager.getRecommendedTimeoutMillis(10_000, …)`.

### `ProcessingCard`

- Margin: top 8, horizontal 16. surface, 1 dp line, radius 20, padding 16, gap 12.
- Header row, aligned at the bottom: the headline (`caption` text2), the range (`monoTime` text3, 2 dp above) and the percent.
- `LinearTrack`: 6 dp (line track, accent fill, radius 3). The fill moves in the draw phase only. It glides to each new value. While words are found, it also moves at the measured pace toward the end of the current window, and never past it. The percent and the stats change only at each update. With reduced motion, the fill goes to each value at once.
- `StageTracker`: 5 equal columns with gap 4. A 2 dp rule at the top: accent for done and current steps, line2 for the next steps. Padding 4, `stage` text2, and the current step in W600 ink. The labels can wrap. There is one node for each step, with the stateDescription "done", "in progress", "not started" or "skipped".
- `StatGrid`: 2 columns, row gap 10, column gap 12. The label is `caption` text2, then 2 dp, then the value in `button` ink. "Processed" uses `monoValue`. The values that are not available yet ("Calculating…", "Measuring…") are text2 W400.
- The block "Stop and keep progress".
- The footnote in `caption12` text2.
- The low-memory hint is a `caption` amberInk line.

### `QueuedCard` (no board)

- The plain `Banner` tone, with the title "Queued", the body "2 ahead of this note." and a small `SecondaryButton` "Remove from queue". Margin 8/16.

### `TurnHeader(name, tone, timeMs, auto, onClick?, mode)`

- A Row with gap 6: an 8 dp dot, the name in `speaker` in the label color of the tone, the time in `monoTime` text2, and an optional auto badge.
- Read mode: a clickable row of 48 dp and full width. The text line is 26 dp from the top, with 6 dp under it. The turns have no gap between them.
- Draft mode: the same 48 dp row. It adds about 6 dp for each turn (an accepted deviation).
- `Role.Button`, onClickLabel "Name this speaker". The row is clickable only when a rename is permitted.

### `TurnParagraph(words, activeIndex?, unsettledFrom?, matches, onWordTap, mode)`

- One `Text` for each turn. Build the `AnnotatedString` one time for each (turn, matches, unsettled range).
- The words are not links (one child node for each word made long notes too slow). One `pointerInput` with `detectTapGestures` covers the paragraph. `getOffsetForPosition` of the stored `TextLayoutResult` and a binary search over the word starts find the tapped word. A tap on the space between two words of a line goes to the nearer word. A drag cancels the tap, so the list scrolls.
- The pressed word: a rounded accentTint rect, drawn from the text layout like the active word. As with `clickable` in a scrolling list, it shows after the tap timeout, so a scroll that starts on a word does not flash it. A shorter tap shows it for one frame.
- The active word: a rounded accentFill rect (radius 4, 3 dp outset at the left and right, the height of the font's content area). The draw phase draws it from `TextLayoutResult`, one rect for each line of the word (bounds of `getPathForRange`, cached for each layout and word). After the text, the draw phase clips to these rects and draws the same `TextLayoutResult` again in onAccentFill. Thus the text never moves, and a new active word does not lay out the text again.
- Read: `transcript`, ink for named speakers, text3 for placeholders.
- Draft: `draft`, text3 for settled words, text4 for unsettled words.
- Search: the matches on amberTint. The current match is amberTint and W600.
- The top margin of the paragraph is 2.
- The `LazyColumn` items have the turn ID as the key. Only the turn with the old or the new active word composes again.
- The automatic follow keeps the active line at 30 % of the viewport. It pauses while the user scrolls, and it starts again after 4 s with no scroll or after a word tap.
- Custom action "Play from start of turn".

### `SuggestionCard(speaker, tone, timeMs, name, onYes, onNo, onSpeaker)`

- surface, 1 dp line, radius 14, padding 10/12.
- The header is a `TurnHeader` (a 48 dp button that opens the Name sheet).
- "Is this **Luca**?" in 14 sp (bold W700). Margins 6/8 (compensated 2).
- A row with gap 8: a small `InkButton` "Yes, Luca" and a small `SecondaryButton` "Not Luca".
- The top margin of the next paragraph is 6.

### `PlaybackBar`

- surface, with a 1 dp line border at the top. Padding: top 10 (compensated 0), horizontal 16, bottom 22 (compensated 18).
- `CassiniSlider`: an M3 `Slider` with a custom thumb and track, and a 48 dp layout. The track is 8 dp with radius 4: accent for the active part, and boardBg with a 1 dp line2 border for the inactive part. The thumb is 16 dp accent. The contentDescription is "Position", and the stateDescription is "11:44 of 23:50". For accessibility, the steps are 10 s.
- The times row: `monoSmall` text2, with an offset of −4 dp.
- The controls are at the center, with gap 28.
- `SkipButton`: a transparent 52 dp circle, Back10/Fwd10 28/1.6 ink, and "10" in `skip`, 2 dp under the center.
- `PlayPauseButton`: 64 dp inkFill, Play/Pause 26 onInkFill. The stateDescription is "Playing" or "Paused".
- The bar is a traversal group.

### `Waveform(levels: WaveformLevels, running = false, freshCount = 7)`

- A Canvas, full width, 96 dp.
- Bar width (W − 55 × 3 dp)/56, gap 3 dp, height max(4 dp, round(v × 92) dp), at the vertical center, radius 2.
- The last 7 bars are barFresh, and the other bars are barIdle.
- `WaveformLevels` holds the bars and the captured time of the newest sample. It also holds the constants: 56 bars of 125 ms, one sample each 100 ms.
- When `running` is false (paused, previews, tests), the newest bar is at the right edge. When `running` is true, the bars move to the left at the display rate. A frame loop moves a playhead from the captured time of the newest sample plus the time since it arrived (at most two samples more). The playhead does not move back. Only the draw phase reads it. The newest bar grows to its level in a few frames. With reduced motion, the bars do not move.
- One image node.
- The fixture is `v_i = 0.18 + 0.82·|sin(0.61 i)·cos(0.23 i + 1.1)|`.

### Recording components

- **`RecordingTimer`**: `timer` rec.text, at the center. A spoken contentDescription. It is not a live region.
- **`RecordingStatus`**: a 10 dp dot (paused: a hollow 10 dp ring with a 1.5 dp rec.text2 stroke) and a 14/600 text ("Recording" in rec.status, "Paused" in rec.text2). A polite live region.
- **`RecordingControls`**: 3 equal columns, with the Library, Pause/Resume and Finish controls.
- `RecordingControls`, Library: a 56 dp circle with 1 dp rec.line, List 22/1.8, and the label "Library" (13 sp) 6 dp under the circle.
- `RecordingControls`, Pause/Resume: 88 dp pauseFill with Pause 30. When paused, a filled 26 dp rec.dot circle. The descriptions are "Pause recording" and "Resume recording".
- `RecordingControls`, Finish: 56 dp rec.finish with an 18 dp white square (radius 4) and the label "Finish".

### `Avatar(text, tone: SpeakerTone, size = 40)`

- A circle in the tint of the tone. The initial is in the ink color of the tone (accentInk, tealInk, amberInk, violet), in the `avatar` style (16 sp W700), as on boards 04 and 07.

### `Banner(spec: BannerSpec, onAction, onDismiss?)`

- It fills the width. The screens give 16 dp margins.
- Padding 14/16 (8 at the bottom with actions), radius 16, the bg of the tone. Plain adds a 1 dp line border on surface.
- A Row with gap 10, aligned at the top. First a 10 dp dot (6 dp top margin).
- Then a Column: the title in 15/600 in the fg of the tone (ink for Plain), the body in `body14` text3 (3 dp above), and the actions in a `FlowRow` (gap 8, top 10 → 4 compensated). The first action is a small `InkButton`, and the other actions are small `SecondaryButton`s.
- When `onDismiss` is set, a 48 dp close button (Close 20/1.8 text2, a11y "Dismiss") is at the top right, over the padding. The right padding changes from 16 to 4, and the title row has an end inset of 32.
- The title is a heading. With `announce = true`, the banner is a polite live region.
- On Recording, the banners keep their light tones.
- No progress track. The `ProgressBannerCard` of the Library has its own track.

### `CassiniTextField(value, onValueChange, label, error, showFocus: Boolean? = null)` and `CassiniCheckbox`

- `CassiniTextField`: height 52, radius 14, padding 14, `field`. A 2 dp accent border when focused, 1 dp line2 at other times, and 2 dp redInk for an error. The label is `fieldLabel`, 6 dp above.
- `showFocus = true` gives the focused look without a caret (fixtures, board 04). `null` follows the real focus.
- `CassiniCheckbox`: 22 dp, radius 4. Checked: accent fill with a white Check 2.2. Not checked: a 2 dp text2 border. The full row is `toggleable(role = Checkbox)`.

## 7. Screen contracts (`UiState` and events)

These are the common types:

```kotlin
@Immutable data class BannerSpec(val kind: BannerKind, val args: BannerArgs = BannerArgs(),
    val actions: ImmutableList<BannerAction>, val announce: Boolean = false, val dismissible: Boolean = false, val id: String = kind.name)
@Immutable data class BannerArgs(val count: Int = 0, val text: String? = null, val ms1: Long = 0, val ms2: Long = 0,
    val bytes: Long = 0, val bytes2: Long = 0, val percent: Int? = null, val m4a: Boolean = false)
enum class BannerKind { /* every kind of features.md section 13, each with tone + title/body resources; catalogue-only kinds flagged */ }
enum class BannerAction { RESUME, START_OVER, TRY_AGAIN, TRANSCRIBE, TRANSCRIBE_AGAIN, PLAY_ANYWAY, OPEN, OPEN_NOTE,
    OPEN_SETTINGS, TURN_ON, DOWNLOAD, DOWNLOAD_AGAIN, RETRY, CANCEL, CHOOSE_ANOTHER, RECORD, IMPORT, CONTINUE }
sealed interface DayLabel { data object Today; data object Yesterday; data class Date(val date: LocalDate, val withYear: Boolean) }
sealed interface NoteStatusUi { Recording; Transcribing; Queued; Paused(atMs); Failed; Unplayable; Unreadable; NewerFormat;
    Transcribed(speakers: Int, words: Int); AudioOnly }
data class Highlighted(val text: String, val ranges: ImmutableList<IntRange>, val leadingEllipsis: Boolean, val trailingEllipsis: Boolean)
data class NoteRowUi(id, title, titleRanges, durationMs, time: LocalTime, date: LocalDate?, status: NoteStatusUi,
    preview: Highlighted?, highlighted: Boolean)
sealed interface TimeLeftUi { Calculating; Finishing; Range(lowMs, highMs) }
sealed interface BatteryUi { Hidden; Measuring(now); PluggedIn(now); Projected(now, atFinish) }
enum class StageUi { READING, PREPARING, WORDS_AND_VOICES, MATCHING, SAVING }
enum class ProgressHeadline { STARTING, WAITING_FOR_PREVIOUS, READING_AUDIO, PREPARING, FINDING_WORDS_AND_SPEAKERS, FINDING_WORDS, MATCHING_VOICES, SAVING }
data class TurnUi(id, speakerId, name: SpeakerNameUi /* Named(text) | Placeholder(n) */, tone: SpeakerTone, startMs,
    auto: Boolean, renamable: Boolean, words: ImmutableList<WordUi(text, startMs)>, unsettledFrom: Int?, suggestion: SuggestionUi?)
data class SnackbarSpec(kind: SnackbarKind /* MOVED_TO_TRASH, SAVED_FILE, SAVE_FAILED */, text: String?, undo: Boolean, durationMs: Long)
```

features.md section 3.1 gives the `ProgressHeadline` strings.

These are the contracts of the screens:

- **Library**: `LibraryUiState(loading, query, recording: RecordingPillUi?(title, elapsedMs, paused), progress: ProgressBannerUi?(noteId?, title?, percent, progress, line: ProgressLine /* TimeLeft(TimeLeftUi) | Waiting | Downloading(part, percent) | Checking(percent) */, queued: Int), banners, sections: ImmutableList<DaySection(label, rows)>, results: ImmutableList<NoteRowUi>?, snackbar, sheet: NoteActionsUi?, dialog: LibraryDialog? /* Rename(id, title) */)`. Events: Query, ClearQuery, OpenNote, LongPress, Record, ReturnToRecording, Import, OpenTrash, OpenSettings, OpenProgress, Resume(id), TryAgain(id), BannerAction(id, action), DismissBanner(id), Undo, SheetAction(…), DismissSheet, RenameConfirmed.
- **Recording**: `RecordingUiState(phase: NeedsPermission | Starting | Recording | Paused | Stopping, title, elapsedMs, levels, format: OPUS | M4A + sampleRateHz, hint: RecordingHint? /* Space(ms): a recording has no time limit */, banner?)`. Events: Title, PauseResume, Finish, Minimise, OpenLibrary, OpenSettings, BannerAction.
- **Note**: `NoteUiState(title, subtitle: NoteSubtitleUi?(day: DayLabel, time: LocalTime, durationMs) /* null in Reading mode */, mode: NoteMode, readOnlyReason: ReadOnlyReason? /* JOB_PENDING, TRUST */, chips: NoteChipsUi?(trust, version: VersionChipUi?(index, count, kind), speakers), banners, turns, activeWord: WordRef?, playback: PlaybackUi?(positionMs, durationMs, playing, following), search: TranscriptSearchUi?(query, current, total), sheet: NoteSheet? /* Actions | NameSpeaker | Merge | Versions | SubtitleFormat | Info */, dialog: NoteDialog?, notificationsOff)`.
- **Note** modes: `NoteMode = Loading | AudioOnly | Queued(ahead, overDocument) | Processing(ProcessingCardUi(headline, rangeStartMs, rangeEndMs, percent, stage, skippedMatching, timeLeft, doneMs, totalMs, battery, words, lowMemory, download: DownloadUi?, anchor: TrackAnchor?)) | Paused(doneMs, totalMs, hasCheckpoint) | Failed(overDocument) | Reading | Unplayable`. The More label of the top bar follows the mode: "Share and more" in Reading, else "More actions".
- **Note** events: they follow features.md section 3. Back, More, Stop, Resume, StartOver, RemoveFromQueue, WordTap, SpeakerTap, AcceptSuggestion, RejectSuggestion, PlayPause, Seek, Skip, FollowPlayback, OpenVersions, PickVersion, Search…, Export(kind), Rename, Transcribe, TranscribeAgain, Trash, NameSheet(event), BannerAction, DismissSheet, DismissDialog.
- **Name a speaker**: `NameSheetUi(speakerId, label: SpeakerNameUi, tone, speakingMs, canSample, sampling, name, voices: ImmutableList<VoiceOptionUi(id, name, likely)>, showAll, ranked: Boolean, samePerson: String?, renameSaved: String?, notName: String?, remember, rememberEnabled, rememberReason: TOO_LITTLE_SPEECH | NO_MODEL | null, canMerge, save: SaveState /* ENABLED | DISABLED | BLOCKED_BY_QUESTION */, nameFieldFocused: Boolean, draft: Boolean)`. The merge state is `MergeUi(targets: (speakerId, label, tone, speakingMs), confirm: target?, sealedVersion: Int?)`.
- **Note actions**: `NoteActionsUi(title, recordedAt, durationMs, speakers, documentKind: DOCUMENT | PLAIN(ext), textExportEnabled, transcribeRow: TRANSCRIBE | TRANSCRIBE_AGAIN(nextVersion, namesCarry) | STOP | REMOVE_FROM_QUEUE | RESUME_OR_START_OVER, busyRow?, recording: Boolean)`.
- **Model download**: `ModelDownloadUiState(parts: ImmutableList<(ModelPartUi, bytes)>, totalBytes /* sum of rounded parts */, requiredFreeBytes, withNote: Boolean, busy, banner?)`. The screen never shows an empty list of parts (features.md section 5).
- **Settings**: `SettingsUiState(language: PHONE | ENGLISH | ITALIAN, models: ImmutableList<ModelRowUi(part, bytes, status: Ready | Downloading(pct) | Checking | Pending | NotDownloaded | Failed)>, voices: Int, trash: Int, version: String, notificationsOff)`.
- **Saved voices**: `SavedVoicesUiState(people: ImmutableList<PersonUi(id, name, notes, confirmedSeconds, stale)>, readOnly, menuFor: String?, dialog)`.
- **Trash**: `TrashUiState(items: ImmutableList<TrashItemUi(id, title, durationMs, deletedOn: DayLabel, daysLeft)> /* oldest deletion first */, backTo: LIBRARY | SETTINGS, snackbar /* fixture-only for board 09 */, dialog)`.
- **Licenses**: `LicensesUiState(entries, open: LicenseText?)`.

## 8. Screens (layout, board references)

### Library (00)

- A `Box(bg)` with a `LazyColumn` and the bottom bar. The snackbar is above the bar.
- The `LazyColumn` has, in this sequence: the header, the search (padding: horizontal 16, top 4), the privacy line, the recording card, the progress card (top margin 14), the banners and the day sections. The list has a top padding of 6.
- In search mode, the flat list of results replaces the sections.

### Recording (01)

- A `Column(rec.bg)` with padding 16/16/28 and the safe drawing insets.
- Header row 48: collapse (44/48), the status at the center, and the format badge.
- Title block, top margin 28: the label "Title" (13, rec.text2), 4 dp, then a Row with gap 8, a 1 dp rec.line border at the bottom and a bottom padding of 6. The Row has a `BasicTextField` (`screenTitle` rec.text, cursor rec.status) and Pencil 18/1.8 rec.text2.
- The banner slot is 8 dp under the title block.
- A center Column (weight 1, gap 28): the timer, the waveform, the caption (`body14` rec.text2, at the center, maximum width 300) and the controls.

### Transcribing (02)

- A top bar with a subtitle, then the `ProcessingCard`.
- A header row (padding: top 16, horizontal 20, bottom 6) with "Draft transcript" in `button` and the dashed tag.
- The draft `LazyColumn` (padding: top 4, horizontal 20), with the suggestion cards.
- The other Note modes (not Reading) use the same top bar. Their banner or the `QueuedCard` is in the position of the card.

### Read & listen (03)

- A top bar without a subtitle: Search 21/1.8 ("Search in transcript") and More ("Share and more").
- The chips in a `FlowRow` (padding: top 4, horizontal 16, bottom 10, gap 8, aligned at the center).
- The banners (margin: horizontal 16, bottom 10).
- The transcript pane: weight 1, surface, a 1 dp line border at the top, padding 0/20/16.
- A document with no words (features.md section 3.3): the pane shows a `BannerCard` with `Tone.Plain`, with a top margin of 16. The title is `ui_note_no_words_title` and the body is `ui_note_no_words_body`. The golden is `variants/read-no-words.png` (no board).
- The `PlaybackBar`, and the `FollowChip` when the follow mode is paused.

### In-note search header (no board)

- It replaces the top bar, at the same height.
- Back 48 (a11y "Close search").
- A `PillSearchField` variant with a height of 44 (visual) / 48 (layout) and the placeholder "Search in transcript".
- The counter "2 of 7" in `monoTime` text2, or "No matches".
- ChevronUp and ChevronDown 22/1.8, 48 dp each (a11y "Previous match" / "Next match").

### Name a speaker (04)

- Sheet padding: top 10, horizontal 20, bottom 24. Gap 16.
- Header row: the avatar 40, the title in `sheetTitle`, the caption, and the play-sample button (a 44/48 circle with 1 dp line2).
- The name field (`showFocus` in the board fixture).
- The list card "Saved voices, closest first": 1 dp line, radius 14. The rows have a minimum of 52, padding 14 and gap 10. The Likely row is on surfaceWash. A "Show all" row shows when more voices exist.
- The conflict box: amberWash, radius 14, padding 12/14, `body14`, buttons with gap 8. The compensated bottom is 8.
- The Remember row.
- The footer: top 4, SpaceBetween. "Merge into…" at the left. Cancel and Save name at the right, with gap 8.

### Merge sheet (no board)

- The `CassiniSheet` with the title "Merge <A> into…" in `sheetTitle`.
- One 56 dp row for each other speaker: an 8 dp dot in its tone, the label in `body16`, and the speaking time in `monoTime` text2 at the end.
- A tap on a row opens the confirmation `CassiniDialog`, with the copy of features.md section 4 and a primary "Merge".

### Versions sheet (no board)

- The title "Versions".
- One row for each variant: a radio button (accent, 22 dp) at the start, "Version N" in `rowTitle`, and the detail in `caption` text2 (the date, the words or "Speakers merged · from version 1", the language).
- The rows have a minimum of 64 and padding 12/16. The disabled rows are at 38 %, with "Can't be read".

### Subtitle chooser (no board)

- A sheet with two plain `SheetActionRow` rows (Subtitles icon): "SubRip (.srt)" with "works in most video players", and "WebVTT (.vtt)" with "for web players".

### Document info (no board)

- A `CassiniDialog` with key/value rows: the key in `caption` text2, the value in `body14` ink, and the meeting ID in `monoSmall` (selectable).
- A trust line with the trust chip.
- One "Close" `TextButton`.

### Model download (05)

- A `Column(bg)` with padding 16/20/24.
- Close (start offset −12, with a 48 layout).
- The content: weight 1, gap 20, top 24. It has the logo 56/1.3 accent, `hero`, `heroBody` text3 with the size in bold, and a `GroupCard` with a 1 dp line. The rows of the card are 14/16, with the label in `body15` and the value in `monoSize` text2.
- Then the footnote in `body14` text2, exactly as on the board, and the added `TextButton` row "Where the models come from · Privacy" (48 dp).
- At the bottom, a Column with gap 10: the large primary button and the `TextButton` "Not now — keep the audio".

### Note actions (06)

- Sheet padding: top 10, horizontal 12, bottom 20.
- The title (margin: horizontal 12, bottom 2) and the meta line (bottom margin 12).
- The section "Save a copy": the featured document row, then the text and subtitle rows.
- A divider.
- Rename, Transcribe again (with a subtitle), Document info, and Move to trash (redInk, subtitle text2).

### Saved voices (07)

- The top bar and the privacy line variant.
- A `GroupCard` with radius 18 and a 1 dp line. The rows have padding 12/14/8 (compensated 10/14/6), the avatar, the name, the meta line and the stale line (amberInk).
- The options button of a row: MoreSmall 20, "Options for <name>". It opens a `CassiniMenu` with Rename, and Forget in redInk.
- The helper: 13 text2, lh 1.45, top margin 16, horizontal margin 20.
- A spacer, then "Forget everyone…" (margin 16, bottom 24).

### Settings (08)

- "App language": the segmented control and the helper.
- An optional notifications banner.
- "On-device models": a `GroupCard` and the helper.
- "Privacy": rows with chevrons.
- The "About" card.

### Trash (09)

- The top bar with "Empty".
- The intro: `body14` text3 (margin: top 6, horizontal 20, bottom 14).
- The cards: gap 8, horizontal margin 16, surface, 1 dp line, radius 16, padding 14/14/10 (compensated 6). Each card has the title and the duration, the meta line, and Restore and Delete now.
- The oldest deletion is first.

### States (10)

- This is not a screen. It is the banner catalogue, drawn by `Banner` (section 10.2).

### Licenses (no board)

- The top bar "Source code and notices".
- A `GroupCard` of `SettingsRow`s: the title, a subtitle with the name of the license, and ChevronRight.
- A row opens a full-screen text pane with its own top bar. The pane uses `monoSmall` text3 for the license texts, and `body14` for the notices and for `PRIVACY.md`, as plain paragraphs. The text is selectable, and the padding is 20.
- "Source code on GitHub" opens the browser.

## 9. Navigation, transitions, system bars

- Keys and back behavior: architecture section 6.
- Default push: a slide in from 30 % at the right with a fade, 250 ms FastOutSlowIn. A pop does the opposite. The predictive pop uses the default of NavDisplay.
- Recording: `slideInVertically { it } togetherWith fadeOut()`. The pop and the predictive pop use `fadeIn() togetherWith slideOutVertically { it }`. Back, the chevron and the Library button minimise the screen. Finish replaces the entry with `NoteKey(id)`. A restored `RecordingKey` with no active recording removes itself from the stack.
- Deep links: the table that follows gives them.

| Source | Intent | Back stack |
|---|---|---|
| Recording notification | `open = recording` | Go back to `RecordingKey` if it is in the stack, else push it |
| Processing and results notifications | `open = note`, `noteId` | Nothing if the note is on top. Go back to it if it is in the stack. Else push it, with the Library at the bottom. |
| ACTION_VIEW | Data URI | Import on IO with the "Importing…" banner, then push `NoteKey(id)` |

- `SystemBarsAppearance(darkIcons)`: in the light theme, the light screens use dark icons. Recording always uses light icons. The sheets do not change the bars.
- Edge to edge: `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)`. The design paddings are in addition to the insets. The screenshot tests use zero insets.

## 10. Screenshot testing

### 10.1 Tool and setup

The tool is Roborazzi 1.76.0 with the native graphics of Robolectric 4.17 (SDK 35). A spike with this toolchain in an application module showed that it operates. `@Config(qualifiers = "w390dp-h844dp-xhdpi")` + `@GraphicsMode(NATIVE)` gives 780 x 1688 px, the size of the boards. In the spike, 4.9 % of the pixels of Library and 4.6 % of the pixels of Read & listen had a difference of more than 32 levels. Almost all of these pixels were text anti-aliasing.

The P2 scaffold test in `:ui` shows that the tool operates in an AGP 9 library module with `android.newDsl=false`. It also draws a `LinkAnnotation` paragraph with a drawn rect for the active word (`getPathForRange`). P2 found that `enableAccessibilityChecks()` does not operate under Robolectric. Thus the scaffold has no accessibility test, and the checks are in the device tests (features.md section 14). We rejected Paparazzi (alpha, bound to layoutlib) and Compose Preview Screenshot Testing (alpha, previews only).

- Catalog: `roborazzi = "1.76.0"`, with `roborazzi`, `roborazzi-compose` and `roborazzi-junit-rule` (group `io.github.takahirom.roborazzi`) and the plugin `io.github.takahirom.roborazzi`. The BOM manages `compose-ui-test-junit4` and `compose-ui-test-junit4-accessibility`.
- `ui/build.gradle.kts`: the plugin, `roborazzi { outputDir.set(file("src/test/screenshots")) }`, `isIncludeAndroidResources = true`, `src/test/screenshots` as a test input, `resourcePrefix = "ui_"`, and a lint block as in `:data`.
- The base class is in the code block that follows.

```kotlin
@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")       // dark: "+night"; Italian: "+it"
abstract class BoardTest {
  @get:Rule val rule = createComposeRule()
  open val locale: Locale = Locale.ENGLISH                    // LocaleScreenshotTest: Locale.ITALIAN (with the +it qualifier)
  open val fontScale: Float = 1f                              // FontScaleScreenshotTest: 1.5f
  fun board(dir: String, name: String, dark: Boolean = false, window: Boolean = false, content: @Composable () -> Unit) {
    if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
    rule.mainClock.autoAdvance = false
    rule.setContent { CassiniTheme(darkTheme = dark) {
      ProvideFixedFormat(locale = locale, is24Hour = true, zone = ZoneId.of("Europe/Rome")) { content() } } }
    rule.mainClock.advanceTimeBy(1_000)
    val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.0005f))
    val path = "src/test/screenshots/$dir/$name.png"
    if (window) captureScreenRoboImage(path, roborazziOptions = options)
    else rule.onRoot().captureRoboImage(path, roborazziOptions = options)
  }
}
```

- Determinism: the fixtures have fixed values, relative to a fixed "now" of 2026-10-09 10:30 Europe/Rome. `ProvideFixedFormat` fixes the locale, the 24-hour time and the zone. The fields with the focused look use `CassiniTextField(showFocus = true)` (no caret).
- The sheets show inside `SheetBoardFrame`, the dialogs inside `DialogBoardFrame`, and the person menu inside `MenuBoardFrame`. The frames have the scrim (#8A9098 in light, or the dark equivalent) and put the content where the real window puts it. The tests capture all of them with `onRoot()`.
- For each window type, one more test (a real `ModalBottomSheet`, a real `BasicAlertDialog`, a real `DropdownMenu`) uses `window = true` (`captureScreenRoboImage`), only as a golden.
- We record and compare the goldens only on Linux x86_64 (this machine and the ubuntu CI runner).

### 10.2 What the tests capture

- `BoardScreenshotTest` (light, compared with the design): the goldens in `light/`, with the names of the design slugs: `00-library`, `01-recording`, `02-transcribing`, `03-read-and-listen`, `04-name-a-speaker`, `05-model-download`, `06-note-actions-and-export`, `07-saved-voices`, `08-settings` and `09-trash`.
- The fixtures (`BoardFixtures.kt`) copy the content of the boards word for word. The Trash fixture sets the snackbar of the board. The list "Library fixture" after this list gives the rows of the Library.
- `StatesBoardTest`: five images, `states/10-states-col1.png` … `col5.png`. The paragraphs after this list give the geometry.
- `DarkScreenshotTest`: the 10 boards and the 5 columns in dark (`dark/`, goldens only).
- `LocaleScreenshotTest` (`+it`, `Locale.ITALIAN`): Library, Transcribing, ReadAndListen, NameSpeaker, Settings and NoteActions (`it/`).
- `FontScaleScreenshotTest` (font scale 1.5 through `RuntimeEnvironment.setFontScale`): Library, ReadAndListen, NameSpeaker and Settings (`fontscale/`).
- `StateVariantsTest` (goldens only, `variants/`): each variant in the acceptance sections of features.md, each dialog, the person menu, the items of section 8 with no board, and Model download with No connection.
- `ComponentScreenshotTest`: each component in all tones and states, on a 390 dp strip (`components/`).

Library fixture:

- Today: Weekly product sync, 48:13, 10:02, Transcribing, highlighted.
- Today: Interview — Marta, 1:12:05, 08:40, Queued.
- Yesterday: Call with supplier, 23:50, with the excerpt preview "…quindi la consegna slitta a giovedì, but the price stays as agreed in the March order…", 15:10, Transcribed · 3 speakers.
- Yesterday: Board review prep, 34:12, 11:00, Paused at 12:40 · Resume.
- Yesterday: Voice note 8 Oct, 08:14, 02:41, Audio only.
- The banner: 62 %, "About 6–9 min left · 1 more in queue".

The geometry of `StatesBoardTest`: each image is one column of the board, without the outer margin. It is 358 dp wide (the width of the banners), on `boardBg`. It has the column heading (`overline`, text3, 4 dp bottom margin), then the banners with gaps of 12 dp (also 12 dp between the heading and the first banner). The height is the content height. The test uses the qualifiers `w358dp-h1200dp-xhdpi` and captures the column node with `onNodeWithTag("states-column")`.

The references of the States columns: `tools/ui-fidelity/crop_states.py` cuts the board PNG (4540 x 2360 px) at x = round(2 × (48 + i × 442.8)) px, with a width of 716 px. The crop starts at the first row at y = 2 × 100 px or more whose pixels in that x range are different from boardBg (the top of the heading). It ends at the last such row. `compare.py` requires equal widths. It adds boardBg to the shorter image and gives the difference of the heights in dp.

The tasks are `:ui:recordRoborazziDebug` (update), `:ui:verifyRoborazziDebug` (CI, through `ciTest`) and `:ui:compareRoborazziDebug` (diffs in `ui/build/outputs/roborazzi`). If the check fails, CI uploads the diffs.

### 10.3 Comparison with the design

- `tools/ui-fidelity/compare.py --design design/reference/png --golden ui/src/test/screenshots/light` (only Pillow, run with `python3 -I`) pairs the files by slug. For each board, it writes a 50/50 blend PNG and a heat map of the absolute difference. It prints the mean difference, the percentage of pixels with a difference of more than 32 levels, and a table for each 52 dp band.
- `rows.py` gives the vertical offsets. `crop_states.py` cuts the five column references. `contrast.py` calculates the WCAG ratios.
- Reviewer protocol (P8, P12): for each board, read the design PNG, the golden and the blend, and record the results in `docs/ui-fidelity.md`. Examine these items: layout and spacing within 2 dp (except the accepted deviations of section 11), colors equal to the tokens, typography (family, size, weight), icons and copy.
- Advisory limits: at most 8 % of pixels above 32 for each phone board, and no 52 dp band with more than 25 %. A board above a limit needs a written explanation.
- CI uses only `verifyRoborazziDebug` against the committed goldens as a gate. A reviewer approves each change to the goldens.

## 11. Accessibility and contrast

`ContrastTest` enforces the contrast (WCAG 2.x) for the two themes: 4.5 or more for text, and 3.0 or more for large text and for non-text items.

| Pair | Ratio |
|---|---|
| ink on bg / surface | 16.63 / 17.99 |
| text2 on bg / surface / boardBg | 7.33 / 7.93 / 6.82 |
| text3 on bg / accentTint / boardBg | 9.35 / 8.65 / (calculated, ≥ 4.5) |
| text4 #687180 on bg / surface | 4.55 / 4.93 (the #6B7480 of the board is 4.38 and fails AA) |
| accentInk on accentTint | 9.00 |
| tealInk on tealTint / surface | 6.99 / 8.06 |
| amberInk on amberTint / amberWash / surface | 6.86 / 7.22 / 7.75 |
| redInk on redTint / surface / bg | 5.80 / 6.92 / 6.39 |
| violet on violetTint | 7.26 |
| white on accentFill, accent on surface | 7.55 |
| white on red (Record) | 5.18 |
| onInkFill on inkFill | 17.99 |
| rec.text / text2 / status / dot / barFresh on rec.bg | 16.20 / 8.22 / 8.64 / 6.30 / 7.73 |
| snackbar action on snackbar | 8.92 |
| accent on accentTrack / line (non-text) | 5.12 / 5.97 |
| teal / amber / red dots on surface (non-text) | 5.47 / 5.02 / 5.18 |
| Dark: ink on bg 16.18, text2 on surface 7.83, text4 on bg 6.20, accentInk on accentTint 8.94, tealInk on tealTint 9.03, amberInk on amberTint 8.91, redInk on redTint 8.51, violet on violetTint 7.41, accent on surface 7.40 | AA |

These items can have less than 3:1, because text or icons identify the control (WCAG 1.4.11): the line2 outlines (1.58), the rec.line circle and barIdle (decorative), the line3 dashed tag and plain dot, and the inactive track of the slider (the thumb and the active track show the state). The checkbox that is not checked uses text2 (7.93).

Accepted deviations from the boards. The reviewer examines each one, and the tolerance does not hide them:

1. `text4` #687180 in place of #6B7480.
2. A 48 dp layout for each control. In the draft transcript, this adds about 6 dp for each turn.
3. The app draws the background of the active word and does not pad the word. Thus "giovedì," can wrap one word later than on the board.
4. Sub-pixel differences of the text advance (a snippet line can wrap one word earlier).
5. Compose makes Plex Mono 600 from 500.
6. Turn 1 of Read & listen is 2 dp lower than on the board.
7. Board 05: the added row "Where the models come from · Privacy" under the footnote (no other pixel moves).
8. Board 10: the download banner does not have "Keep Cassini open.", because downloads continue when the user leaves the app. The NO_ENCODER body does not have "(M4A)" when the capture format is Opus. In the other cases and in the fixture, the app uses the copy of the board.
9. Board 10: TX_CALCULATING and TX_BATTERY are cards of the catalogue only. The app shows these states in the stat cells of the progress card.
10. The banners that the user can dismiss (RECOVERED, NO_ENCODER) have a close button that the board does not show. The catalogue column draws them without it, as on the board.
11. Board 09: the "moved to trash" snackbar over Trash is a state of the fixture only. The app shows that snackbar on the Library.
12. Board 04: Save name keeps its enabled look while the same-person question is open. The app blocks a push and announces the reason. It does not save (features.md section 4).
13. The Library row suffixes keep their board size. Their 48 dp touch area comes from extended hit testing (section 5). ATF can report them, and the tests permit this.
14. The UI without a board (the "no board" items of section 8, the dark theme, and the extra banners marked "not on the board" in features.md section 13) follows this document and waits for the review of the owner.
15. Board 10: IMPORT_TOO_LARGE says "Choose a file under 4 GB." The board says 2 GB. The import limit changed on 2026-10-11 (architecture.md section 3.7).

Mapping to features.md section 14 (the acceptance list):

- The transcript words are not links, so TalkBack has no action for each word (a deviation for the owner's review, section 6 `TurnParagraph`). Each turn has the custom action "Play from start of turn".
- The speaker labels are buttons with full spoken descriptions.
- The live regions have a limited update rate.
- The recording status, the stepper, play/pause, the slider and the segments have state descriptions.
- Headings.
- Traversal order. Note: the top bar, the chips, the banners, the card, the transcript and the player (the player is a traversal group). Library: the header, the search, the banners, the rows, Import and Record.
- The app obeys reduced motion.

## 12. Strings

All UI strings are in `ui/src/main/res/values{,-it}/strings.xml`, with the prefix `ui_`. The notification and service strings are in `:app`, with the prefix `notif_`. `MissingTranslation` is an error. features.md section 0 gives the plurals and the format resources. The placeholder speaker label is `ui_speaker_label` ("Speaker %1$d" / "Voce %1$d"). P7 made it. In P10, the placeholder function of the job and `DefaultLabelsTest` use it, and the test compares it with `DefaultLabels.PREFIXES`.

The table gives the key Italian copy. The implementer writes the other strings in the same register.

| English | Italian |
|---|---|
| Notes | Note |
| Search titles and what was said | Cerca nei titoli e in ciò che è stato detto |
| Transcribed on this phone. Audio never leaves it. | Trascritto su questo telefono. L’audio non lo lascia mai. |
| Today / Yesterday | Oggi / Ieri |
| Import audio | Importa audio |
| Record | Registra |
| Recording | Registrazione in corso |
| Title | Titolo |
| Library | Libreria |
| Finish | Termina |
| Transcribing | In trascrizione |
| Queued | In coda |
| Transcribed | Trascritta |
| Paused at %s | In pausa a %s |
| Audio only | Solo audio |
| Finding words and speakers | Ricerca di parole e voci |
| Finding words | Ricerca delle parole |
| Reading / Preparing / Words & voices / Matching / Saving | Lettura / Preparazione / Parole e voci / Abbinamento / Salvataggio |
| Time left | Tempo rimasto |
| Processed | Elaborato |
| Battery at finish | Batteria alla fine |
| Words so far | Parole finora |
| Stop and keep progress | Ferma e conserva i progressi |
| Draft transcript | Bozza della trascrizione |
| Words may still change | Le parole possono ancora cambiare |
| Verified | Verificata |
| Is this %s? | È %s? |
| Yes, %s | Sì, %s |
| Not %s | Non è %s |
| No speech | Nessun parlato |
| No words were found in this recording. | Non sono state trovate parole in questa registrazione. |
| Save a copy | Salva una copia |
| Cassini document | Documento Cassini |
| Transcript as text | Trascrizione come testo |
| Subtitles | Sottotitoli |
| Rename | Rinomina |
| Transcribe again | Trascrivi di nuovo |
| Document info | Informazioni sul documento |
| Move to trash | Sposta nel cestino |
| Kept for 30 days, then deleted. | Conservata per 30 giorni, poi eliminata. |
| Name | Nome |
| Saved voices, closest first | Voci salvate, dalla più simile |
| Likely | Probabile |
| Same person | Stessa persona |
| Someone else | Un’altra persona |
| Answer the question above first | Prima rispondi alla domanda qui sopra |
| Remember this voice on this phone | Ricorda questa voce su questo telefono |
| Merge into… | Unisci a… |
| Cancel | Annulla |
| Save name | Salva il nome |
| Transcribe on this phone | Trascrivi su questo telefono |
| Download and transcribe | Scarica e trascrivi |
| Android stopped the download. Try again to continue where it stopped. | Android ha interrotto il download. Riprova per continuare da dove si è fermato. |
| Not now — keep the audio | Non ora — tieni l’audio |
| App language | Lingua dell’app |
| Phone | Telefono |
| On-device models | Modelli sul dispositivo |
| Ready | Pronto |
| Saved voices | Voci salvate |
| Trash | Cestino |
| Empty | Svuota |
| Restore | Ripristina |
| Delete now | Elimina ora |
| Undo | Annulla |
| Dismiss | Chiudi |
| Forget everyone… | Dimentica tutti… |
| About | Informazioni |
