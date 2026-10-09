# Cassini Android 0.2: UI system

Status: binding specification for the `:ui` module (P7, P8) and for the Routes and ViewModels in `:app` (P10). The visual reference is `design/reference/png/*.png` (780 x 1688 px = 390 x 844 dp at 2x; the States board is 4540 x 2360 px = 2270 x 1180 dp) and the markup with exact inline styles in `design/reference/markup/*.html`. Behaviour and copy are in `features.md`.

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

`ui/build.gradle.kts` sets `resourcePrefix = "ui_"`: every `:ui` resource name starts with `ui_` (strings, plurals, fonts, drawables, mipmaps, colours), so `:app` resources can never override them. `:ui` has no XML theme; `Theme.Cassini` lives in `:app` (architecture section 6) and uses `@color/ui_window_bg`.

`:ui` depends on Compose (BOM 2026.09.00: ui, foundation, material3, ui-tooling-preview), `activity-compose` only for previews, and `kotlinx-collections-immutable` 0.5.2. It never depends on `:core`, `:data`, `:engine` or `:app`.

## 2. Principles

- Screens are stateless: `XxxScreen(state: XxxUiState, onEvent: (XxxEvent) -> Unit, modifier)`. Screens hold only interaction state (scroll, focus, text-field selection).
- `UiState` carries **semantic** values: enums, millisecond durations, `LocalDate`/`LocalTime`, counts, percent integers and user text (titles, names, words, snippets). `:ui` formats them with its own resources, so the same fixture renders in English and Italian.
- Screens read only `CassiniTheme.colors`, `CassiniTheme.type`, `CassiniTheme.shapes`; no raw hex outside `theme/`.
- Stock M3 components are allowed underneath (`ModalBottomSheet`, `BasicAlertDialog`, `DropdownMenu`, `Slider`; `Checkbox` replaced by `CassiniCheckbox`) but always styled through the tokens. `tonalElevation = 0.dp` everywhere.
- Every `UiState` class is `@Immutable` and uses `ImmutableList`.
- Formatting that depends on "now" (Today, Yesterday, "23 days left") is classified by the ViewModel with an injected `Clock` and passed as semantic values (`DayLabel.Today`, `daysLeft = 23`).

## 3. Fonts

| `res/font` file | Upstream | Path | SHA-256 |
|---|---|---|---|
| `ui_instrument_sans_regular.ttf` | github.com/Instrument/instrument-sans @ `7fa22308a3d0c94ee2b3cd537a1196b65db34a3e` | `fonts/ttf/InstrumentSans-Regular.ttf` | `69fd3f7c467c70c1f73b232812407f688f3d87dd7a801ea7281aa97d29cf53d5` |
| `ui_instrument_sans_medium.ttf` | same | `fonts/ttf/InstrumentSans-Medium.ttf` | `56ba599d12b7cf2fff4eebf46d29253231b6f49bc5b6ce7733dfbf3d7940bfae` |
| `ui_instrument_sans_semibold.ttf` | same | `fonts/ttf/InstrumentSans-SemiBold.ttf` | `7151cf505f897e17b4e9b956293b5a60046ec39da3923a8feba29ce86fd14e12` |
| `ui_instrument_sans_bold.ttf` | same | `fonts/ttf/InstrumentSans-Bold.ttf` | `735badeb8b2046cee6f5e1226412ab6c29db04accbca413af03d70e991dce10d` |
| `ui_ibm_plex_mono_regular.ttf` | github.com/IBM/plex tag `@ibm/plex-mono@2.5.0` | `packages/plex-mono/fonts/complete/ttf/IBMPlexMono-Regular.ttf` | `7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43` |
| `ui_ibm_plex_mono_medium.ttf` | same | `…/IBMPlexMono-Medium.ttf` | `98fbd727aae340b236955879dabed4d991aac9e8e90b3b2a67ce4a59221cc97c` |

The file names are resource names only; the font files themselves are byte-identical to upstream. Licence texts: Instrument Sans `OFL.txt` and `packages/plex-mono/LICENSE.txt`, verbatim. Rules:

- Ship unmodified static TTFs. Never commit the design's `.woff2` files (Google Fonts web subsets; a subset is a Modified Version under the OFL, and "Plex" is a Reserved Font Name). No subsetting, no renaming of fonts inside the files.
- `third_party/fonts/fonts.json` is the source of truth: one entry per file `{file, family, weight, repo, ref, path, sha256, license}`; `third_party/fonts/instrument-sans/OFL.txt`, `third_party/fonts/ibm-plex-mono/LICENSE.txt`.
- `scripts/fetch-fonts.sh` downloads from the manifest, checks SHA-256 and copies into `ui/src/main/res/font`; `--check` only verifies. Shellcheck-clean.
- `ui/src/main/assets/licenses/fonts/*.txt` are byte-identical to the `third_party` copies. `THIRD_PARTY_NOTICES.md` gets a "Fonts" section (families, copyright lines, SIL Open Font License 1.1, upstream URLs and refs, "unmodified").
- `FontAssetsTest` (`:ui`, plain JVM): each TTF is in the manifest with a matching hash and vice versa; licence copies equal; no `*.woff`/`*.woff2` anywhere under `*/src`.
- Plex Mono ships 400 and 500. The "Processed" stat asks for 600; Compose synthesises it from 500 with the default `FontSynthesis.All`, as the browser did. Do not bundle a SemiBold.

```kotlin
val InstrumentSans = FontFamily(Font(R.font.ui_instrument_sans_regular, W400), Font(R.font.ui_instrument_sans_medium, W500),
    Font(R.font.ui_instrument_sans_semibold, W600), Font(R.font.ui_instrument_sans_bold, W700))
val PlexMono = FontFamily(Font(R.font.ui_ibm_plex_mono_regular, W400), Font(R.font.ui_ibm_plex_mono_medium, W500))
```

## 4. Tokens

### 4.1 Colours (`CassiniColors`, provided by `LocalCassiniColors`)

| Token | Light | Dark | Use |
|---|---|---|---|
| `ink` | #14171A | #ECEEF1 | primary text |
| `bg` | #F5F6F7 | #0F1214 | screen background (also `ui_window_bg`) |
| `surface` | #FFFFFF | #181C20 | cards, sheets, transcript pane, player |
| `surfaceWash` | #F5F6F7 | #1F2429 | highlighted sheet rows ("Cassini document", "Likely") |
| `boardBg` | #ECEEF0 | #1F2429 | States board background, slider inactive track |
| `text2` | #4A525B | #A9B0B9 | meta, captions, icons, section headers |
| `text3` | #3A4250 | #C3C9D0 | card bodies, placeholder-speaker paragraphs, settled draft, States column headings |
| `text4` | **#687180** (board #6B7480) | #8C95A0 | unsettled draft words, placeholders |
| `line` | #E2E5E9 | #2A3036 | hairlines, card borders |
| `line2` | #C9CED5 | #3A4048 | outline buttons and chips, drag handle |
| `line3` | #9AA2AC | #5A626C | dashed "Words may still change" tag, plain-banner dot |
| `accent` | #2346C8 | #8FA6FF | progress fill, slider, focused border, dots, checkbox |
| `accentFill` / `onAccentFill` | #2346C8 / #FFFFFF | #2346C8 / #FFFFFF | primary buttons, active word |
| `accentInk` | #1A3596 | #B9C7FF | text on accent tint, blue text buttons, blue avatar initials |
| `accentTint` | #E8EDFC | #1C2647 | info chip and banner |
| `accentTrack` | #C9D4F6 | #2B3A70 | Library banner track |
| `teal` / `tealInk` / `tealTint` | #0F766E / #0B5A54 / #E3F2EF | #4FC7B8 / #8EDDD2 / #12302D | ok tone, privacy shield |
| `amber` / `amberInk` / `amberTint` / `amberWash` | #B45309 / #8A3B07 / #FCEFE3 / #FCF6EE | #F0A35E / #F7C291 / #3A2614 / #2C2118 | warn tone, unnamed speakers |
| `red` / `redInk` / `redTint` / `redLine` | #C2410C / #A3330B / #FBE7E1 / #E3B9A6 | #C2410C / #FFB08F / #3D1E14 / #6B3A2A | Record, error, destructive |
| `violet` / `violetTint` | #5B2BB5 / #EFEAFB | #C1A8FF / #2A2045 | 3rd named-speaker tint, 4th avatar tint |
| `inkFill` / `onInkFill` | #14171A / #FFFFFF | #ECEEF1 / #0F1214 | ink pills, play/pause, selected segment |
| `snackbarBg` / `snackbarText` / `snackbarAction` | #14171A / #F2F3F5 / #9DB4FF | #ECEEF1 / #14171A / #2346C8 | snackbar |
| `scrim` | #1F2A39 at 50 % | #000000 at 60 % | sheets and dialogs (over `bg` it gives the boards' #8A9098) |
| `link` | #2346C8, underlined | #8FA6FF | inline links |

`RecordingColors` (one set; the Recording screen is dark in both themes): `bg` #14171A, `text` #F2F3F5, `text2` #A9B0B9, `line` #3A4048, `barIdle` #4A525B, `barFresh` #FF8A55, `dot` #FF6A2B, `status` #FF9A6B, `pauseFill` #F2F3F5, `onPause` #14171A, `finish` #C2410C.

`Tone { Ok, Info, Warn, Error, Plain, Outline }` → `ToneColors(bg, fg, dot, border?)`: Ok (tealTint, tealInk, teal), Info (accentTint, accentInk, accent), Warn (amberTint, amberInk, amber), Error (redTint, redInk, red), Plain (surface, ink, line3, border line), Outline (transparent, ink, –, border line2). On the Recording screen, banners keep their light tones.

`SpeakerTone { Blue, Teal, Violet, Amber }` → (dot, label/ink, tint): Blue (accent, accentInk, accentTint), Teal (teal, tealInk, tealTint), Violet (violet, violet, violetTint), Amber (amber, amberInk, amberTint). The rule that picks a tone is in features.md section 0. Avatars use the same triples (tint fill, ink initial).

### 4.2 Material mapping

`CassiniTheme(darkTheme = isSystemInDarkTheme())` provides the locals and a `MaterialTheme`:

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

No dynamic colour. Ripples follow the content colour. The XML `Theme.Cassini` in `:app` sets `android:windowBackground` to `@color/ui_window_bg` (bg, with a `values-night` value) so the cold-start frame matches.

### 4.3 Typography (`CassiniType`, provided by `LocalCassiniType`)

CSS "normal" line height comes from the hhea metrics: Instrument Sans 1.22 em, Plex Mono 1.30 em. Every style sets `lineHeight` explicitly with `LineHeightStyle(Alignment.Center, Trim.None)`; letter spacing in em. `sans(size, weight = 400, lh = 1.22, ls = 0)` and `mono(size, weight = 400, lh = 1.30, ls = 0)`.

| Name | Style | Used by |
|---|---|---|
| `timer` | mono(72, ls −0.02), tabular | Recording timer |
| `percent` | sans(40, 600, lh 1.0, ls −0.02); "%" span 20 sp | progress card |
| `hero` | sans(28, 600, lh 1.15, ls −0.015) | Model download title |
| `libraryTitle` | sans(24, 600, ls −0.01) | "Notes" |
| `screenTitle` | sans(22, 600) | Settings, Saved voices, Trash; Recording title field |
| `sheetTitle` | sans(20, 600) | Name sheet, dialogs |
| `noteTitle` | sans(18, 600) | Note top bar, actions sheet |
| `transcript` | sans(17, 400, lh 1.6) | sealed transcript |
| `field` | sans(17) | name field |
| `fab` | sans(17, 600) | Record |
| `rowTitle` | sans(16, 600) | library rows, trash cards, voices |
| `body16` | sans(16) | search, sheet rows |
| `heroBody` | sans(16, lh 1.5) | Model download body |
| `draft` | sans(16, lh 1.55) | draft transcript |
| `cta` | sans(16, 600) | 56 dp primary |
| `avatar` | sans(16, 700) | avatar initials |
| `button` | sans(15, 600) | buttons, settings row titles, banner titles, stat values |
| `buttonRegular` | sans(15) | Cancel |
| `checkTitle` | sans(15, 600, lh 1.4) | Remember checkbox |
| `body15` | sans(15) | model size rows |
| `label14` | sans(14, 600) | small buttons, library banner title |
| `body14` | sans(14, lh 1.45) | banner body, intros, recording caption |
| `body14Plain` | sans(14) | secondary small buttons, snackbar |
| `about` | sans(14, lh 1.5) | About card, Saved voices privacy note |
| `snippet` | sans(14, lh 1.4) | library preview and snippet |
| `caption` | sans(13) | meta, helpers |
| `chip` | sans(13, 500) | status chips |
| `overline` | sans(13, 600, ls 0.06), uppercase | library day headers, States column headings (text3) |
| `speaker` | sans(13, 600) | turn header name |
| `fieldLabel` | sans(13, 600), text3 | "Name", "Saved voices, closest first" |
| `section` | sans(12, 600, ls 0.06), uppercase | settings and sheet sections |
| `caption12` | sans(12, lh 1.4) | progress footnote |
| `tag12` | sans(12, 600) / sans(12) | "Likely" / dashed tag |
| `stage` | sans(11); current 600 | stepper |
| `badge` | sans(11, 500) | "auto" |
| `skip` | sans(10, 700, lineHeight 12 sp) | "10" in skip buttons |
| `monoTime` | mono(13) | durations, timestamps, ranges |
| `monoSmall` | mono(12) | player times, format chip |
| `monoValue` | mono(15, 600 synthesised) | "Processed" |
| `monoSize` | mono(15) | model sizes |

A mono run on a row whose line box is set by a larger sans style gets that sans line height (`mono(13).copy(lineHeight = sans(16).lineHeight)`). Material `Typography`: displayLarge timer, displayMedium percent, displaySmall hero, headlineLarge libraryTitle, headlineMedium screenTitle, headlineSmall sheetTitle, titleLarge noteTitle, titleMedium rowTitle, titleSmall button, bodyLarge body16, bodyMedium body14, bodySmall caption, labelLarge button, labelMedium chip, labelSmall badge.

All sizes are sp and scale with the system font size. Fixed heights become `heightIn(min = …)`.

### 4.4 Shapes, spacing, elevation

- Pill = `RoundedCornerShape(50)`. Radii: 2 (4 dp track), 3 (6 dp track), 4 (active word, Finish square, checkbox), 6 (auto badge), 8 (tags, format badge), 10 (status chip), 12 (segment), 14 (rows, inputs, inner sheet groups, suggestion card, snackbar, confirm box, menus), 16 (banners, settings groups, trash cards, library banner, 32 dp chips), 18 (Saved voices list), 20 (progress card), 28 (sheet top corners, dialogs). M3 `Shapes(extraSmall 4, small 8, medium 14, large 16, extraLarge 28)`.
- Spacing: `gutter` 16 dp (cards and fields from the edge), `textGutter` 20 dp (section headers, transcript, intros), `listInset` 12 + `rowPad` 10 (library rows), `topBarPad` 8. Other values are per component below.
- Flat design with 1 dp `line` borders. The only shadow is the Record button: `dropShadow(pill, Shadow(radius 18 dp, color red, alpha 0.28, offset 0/6 dp))`. Sheets, dialogs, menus and the snackbar have none.

### 4.5 Icons

No `material-icons-extended`. Every icon is an `ImageVector` built from the design's SVG path data (viewport 24; logo 28), stroked with round caps and joins, tinted by `Icon(tint)`. Stroke width is in viewport units. `enum class Glyph(paths, filled, viewport)` plus `CassiniIcon(glyph, size, stroke, tint, contentDescription)`, cached per (glyph, stroke); `addPathNodes` parses `d`; circles are two arcs; the logo ellipse is in `group(rotate = -20f, pivotX = 14f, pivotY = 14f)`.

| Glyph | Path data | Sizes / stroke |
|---|---|---|
| Logo (28) | circle(14,14,r5.5) + ellipse rx12 ry4.2 rotated −20° | 28/1.6 ink; 56/1.3 accent |
| Trash | `M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13` | 22/1.7, 24/1.7 |
| Settings | circle(12,12,3) + the gear path from board 00 | 22/1.7 |
| Search | circle(11,11,7) + `m20 20-3.5-3.5` | 20/1.8, 21/1.8 |
| Shield | `M12 3 4 6v6c0 4.5 3.4 8 8 9 4.6-1 8-4.5 8-9V6z` | 14/2, 18/2 teal |
| Import | `M12 4v11M7 10l5 5 5-5M5 20h14` | 20/1.8 |
| ChevronDown | `m6 9 6 6 6-6` | 22/1.8, 14/2 |
| ChevronRight | `m9 6 6 6-6 6` | 18/1.8 |
| ChevronUp | `m6 15 6-6 6 6` | 22/1.8 (search) |
| Back | `M15 6l-6 6 6 6` | 22/1.8 |
| Close | `M6 6l12 12M18 6 6 18` | 22/1.8; 20/1.8 banner dismiss |
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

The logo vector is also the adaptive launcher foreground (ink on bg, in `mipmap-anydpi/ui_ic_launcher.xml` with a monochrome layer) and the notification small icon `ui_ic_stat_logo`.

## 5. Touch targets

Every standalone control reports at least 48 x 48 dp. Where the board's visual is smaller (44 dp icon buttons, 40/36 dp pills, 44 dp segments, 32 dp version chip), the control is laid out at 48 dp with the visual centred, and the neighbouring padding is reduced by the difference so pixels do not move. Use an explicit `Modifier.touchTarget()` helper so the compensation is visible in code, not the silent `minimumInteractiveComponentSize()`. Compensated values are given per component ("visual 44 / layout 48").

Inline text buttons inside a line of text (the Library row suffixes "· Resume" and "· Try again") keep their text layout. Their touch area reaches 48 x 48 dp through Compose's extended hit testing: a `clickable` node smaller than `LocalViewConfiguration.current.minimumTouchTargetSize` receives taps within the 48 dp square centred on it, without a layout change. A test taps 14 dp above the text's centre and expects the event. The row also exposes the action as a TalkBack custom action. Inline transcript word links are exempt (WCAG 2.5.8 inline exception).

## 6. Components (`components/`, each with a `@Preview` and a component screenshot)

- **`CassiniTopBar(title, subtitle?, onBack, actions)`**: a plain Row. Board padding 12/8/4 (Read & listen bottom 0), gap 4 (2); compensated top 10, horizontal 6, bottom 2 (Read 0, and its chips row top 6 → 4); gap after back 2 (Read 0). Title starts at 56 dp (54 on Read). Title `noteTitle` (Note) or `screenTitle` (Settings, Saved voices, Trash), one line, ellipsis; subtitle `caption` text2 directly below, omitted when null (Read & listen). `IconButton44`: 48 dp box, 44 dp circular ripple, 22 dp ink icon, required contentDescription. Library header: padding 20/16/8 (compensated 18/14/6), logo 28 + 10 dp + "Notes"; Trash and Settings at the end. Trash header action "Empty" (redInk `button`, visual 44 / layout 48, padding 14).
- **`PillSearchField`**: height 52, pill, surface, 1 dp line, padding 16; Search 20/1.8 text2, 10 dp gap, `BasicTextField` body16 ink (2 dp extra start inset), placeholder in text4, cursor accent, `ImeAction.Search`; trailing 48 dp clear button "Clear search" when not empty.
- **`PrivacyLine`**: shield 14/2 teal, gap 6, caption text2; padding start/end 4, top 10. Saved voices variant: shield 18/2 top-aligned (2 dp down), gap 10, `about` text3, margins 8 top, 20 h, 16 bottom.
- **`ProgressBannerCard(title, percent?, progress?, line, onClick)`**: margin top 14, h 16; padding 12/14; accentTint; radius 16; gap 8. Row 1 title `label14` (one line) baseline-aligned with percent `monoTime`; 4 dp track (accentTrack, accent fill, radius 2); caption text3. Role Button, onClickLabel "Open note". Live region polite; its description changes only on stage change or 5 % steps.
- **`RecordingBannerCard(title, elapsedMs, paused, onClick)`** (no board): margin top 14, h 16; RecordingColors bg; radius 16; padding 12/14; Row gap 10: 10 dp rec.dot (paused: 1.5 dp rec.text2 ring), Column: "Recording · <title>" `label14` rec.text one line, caption "Tap to return" `caption` rec.text2; trailing elapsed `monoTime` rec.text2. Comes before the progress card, 8 dp apart. Role Button.
- **`DayHeader`**: `overline` text2; padding 20 h, bottom 6, top 14 (first) / 16; heading.
- **`NoteRow`** (list padding h 12, 2 dp between rows): radius 14; surface when highlighted else transparent; padding 12/10; gap 4. Title `rowTitle` (2 lines max) + 8 dp + duration `monoTime` text2 (sans16 line height). Preview `snippet` text3, 2 lines; leading and trailing ellipses from `Highlighted`; highlights W600 ink on accentTint. Meta row gap 8: time `caption`, `StatusChip`, suffix (`caption` text2; "· Resume" and "· Try again" are inline text buttons with extended hit areas, section 5). `mergeDescendants`; description "Weekly product sync, 48 minutes 13 seconds, today 10:02, transcribing"; custom actions; `combinedClickable(onLongClick)`.
- **`StatusChip(text, tone, dot?)`**: padding 8/2, radius 10, `chip`. Outline: 1 dp line2, ink. Not interactive.
- **Tags**: "Likely" (`tag12` 600, accentInk on accentTint, radius 8, padding 2/8). "Words may still change" (`tag12` 400, text2, 1 dp dashed line3 border via `drawBehind` with `dashPathEffect(3 dp, 2 dp)`, radius 8). "auto" badge (`badge`, text2, 1 dp line2, radius 6, padding 5/0). Format badge (`monoSmall` rec.text2, rec.line border, radius 8, padding 4/8).
- **Large chips** (height 32, radius 16): Verified (tealTint/tealInk, padding 12, gap 6, Check 14/2.2, 13/600); other trust chips the same with Warn/Error tones and the Warn icon; `VersionChip` (surface, 1 dp line2, padding 12/10, 13 ink + ChevronDown 14/2; visual 32 / layout 48); speaker count as plain caption. `FollowChip` (no board): the VersionChip style with "Follow playback" and Play 14, floating 16 dp above the player, centred.
- **Buttons** (pill, no elevation, ripple clipped, `Role.Button`; disabled = 38 % alpha; busy = 16 dp spinner, width kept):

  | Component | Visual / layout | Padding | Fill / border / text |
  |---|---|---|---|
  | `PrimaryButton` large | 56 / 56 | fill width | accentFill / – / `cta` onAccentFill |
  | `PrimaryButton` | 48 / 48 | 22 | accentFill / – / `button` |
  | `InkButton` small | 36 / 48 | 14–16 | inkFill / – / `label14` onInkFill |
  | `InkButton` medium | 40 / 48 | 14 | inkFill / – / `label14` |
  | `SecondaryButton` large | 52 / 52 | 20, icon gap 8 | surface / line2 / `button` ("Import audio") |
  | `SecondaryButton` | 48 / 48 | 18 | transparent / line2 / `buttonRegular` ("Cancel") |
  | `SecondaryButton` block | 44 / 48 | fill width | surface / line2 / `label14` ("Stop and keep progress") |
  | `SecondaryButton` medium | 40 / 48 | 16 or 14 | surface / line2 / `label14` or `body14Plain` |
  | `SecondaryButton` small | 36 / 48 | 14–16 | surface / line2 / `body14Plain` |
  | `TextButton` | 52 or 48 | 14 | – / – / `button` accentInk |
  | `DestructiveTextButton` | 44 or 40 / 48 | 14 or 12 | – / – / redInk |
  | `DestructiveOutlineButton` | 52 / 52 | fill − 32 | surface / redLine / `button` redInk |
  | destructive dialog confirm | 48 / 48 | 22 | redInk / – / white 15/600 |

  `PrimaryButton` has a `blockedReason: String?`: when set, the button looks enabled, a press calls `onBlocked` instead of `onClick`, and its stateDescription is the reason (Name sheet Save, features.md section 4).
- **`LibraryBottomBar`**: bottom-aligned; padding 16 h, top 40, bottom 24 + navigation-bar inset; `verticalGradient(0 → bg@0, 0.4 → bg)`; Import (left) and **`RecordFab`** (right: height 64, radius 32, padding 22/26, red, gap 10, 18 dp white circle, "Record" `fab` white, the drop shadow; description "Record a new note"; recording variant "Recording · 24:07" with the live dot, description "Return to recording"). The list gets `contentPadding(bottom = 128 dp + navigation bars)`.
- **`SegmentedControl`**: surface, radius 16, height 52; three equal segments with full-height click areas; the visual is a 44 dp rect (radius 12) inset 4 dp (2 dp between neighbours). Selected inkFill + `label14` onInkFill; unselected `body14Plain` ink. `selectableGroup()`; each `selectable(role = RadioButton)`.
- **Settings**: `SectionHeader` (`section` text2, padding 20 h, top 22 / 16 first, bottom 6, heading); `GroupCard` (margin h 16, surface, radius 16, optional 1 dp line border; 1 dp dividers between rows); `SettingsRow` (padding 14/16, gap 12; title `button`, subtitle `caption` text2; trailing status text 13/600 in the tone's ink colour, ChevronRight 18/1.8, or nothing); `HelperText` (`caption` text2 lh 1.4, margin top 6, h 20); `AboutCard` (padding 14/16, `about` text3, bold version, `LinkAnnotation.Clickable` "Source code and notices").
- **`CassiniSheet`**: `ModalBottomSheet(skipPartiallyExpanded = true, shape top 28, containerColor surface, scrimColor scrim, dragHandle = DragHandle (36 x 4, radius 2, line2, 10 dp above), contentWindowInsets = navigationBars)`; IME padding. `SheetBoardFrame` renders sheet content over the scrim for screenshots.
- **`SheetActionRow`**: featured (surfaceWash, radius 14, padding 12, gap 14, top-aligned; Music 24/1.7 accent; title `rowTitle`; caption with a `monoTime` extension) and plain (min 52, padding 12, gap 14; icon 24/1.7; `body16` label + mono extension; optional subtitle). `SheetSection` (`section`, margin top 8, h 12, bottom 4). Divider (1 dp line, margin 8/12).
- **`CassiniDialog`** (`BasicAlertDialog`): surface, radius 28, padding 24, gap 16; title `sheetTitle`; body `body14` text3 or a custom slot (text field, key/value rows); end-aligned actions gap 8. Its content is a separate `CassiniDialogContent` composable so `DialogBoardFrame` can render it inline for screenshots.
- **`CassiniMenu`** (`DropdownMenu`): surface, radius 14, 1 dp line, items min 48 dp, padding 16 h, `body16`; destructive items redInk. Content separate (`CassiniMenuContent`) for `MenuBoardFrame`.
- **`CassiniSnackbarHost`**: bottom, margin 16 h / 24 bottom above insets and the bottom bar; snackbarBg; radius 14; padding 6/16/6; message `body14Plain`; action 14/600 snackbarAction (visual 44 / layout 48). Live region polite; the action is also a custom action. Duration is a parameter computed by the caller from `AccessibilityManager.getRecommendedTimeoutMillis(10_000, …)`.
- **`ProcessingCard`** (margin top 8, h 16; surface; 1 dp line; radius 20; padding 16; gap 12): header row bottom-aligned (headline `caption` text2; range `monoTime` text3, 2 dp above; percent); `LinearTrack` 6 dp (line track, accent fill, radius 3); `StageTracker` (5 equal columns, gap 4, 2 dp top rule accent for done/current and line2 for pending, padding 4, `stage` text2, current W600 ink, labels may wrap; one node per step with stateDescription "done" / "in progress" / "not started" / "skipped"); `StatGrid` (2 columns, row gap 10, column gap 12, label `caption` text2, 2 dp, value `button` ink; "Processed" in `monoValue`; pending values "Calculating…" / "Measuring…" in text2 W400); block "Stop and keep progress"; footnote `caption12` text2. Low-memory hint as a `caption` amberInk line.
- **`QueuedCard`** (no board): the plain `Banner` tone with the title "Queued", body "2 ahead of this note." and a `SecondaryButton` small "Remove from queue"; margin 8/16.
- **`TurnHeader(name, tone, timeMs, auto, onClick?, mode)`**: Row gap 6: 8 dp dot, name `speaker` in the tone label colour, time `monoTime` text2, optional auto badge. Read mode: a full-width 48 dp clickable row with the text line 26 dp from the top and 6 dp below; turns stacked with 0 gap. Draft mode: the same 48 dp row (adds about 6 dp per turn; accepted deviation). `Role.Button`, onClickLabel "Name this speaker"; clickable only when renaming is allowed.
- **`TurnParagraph(words, activeIndex?, unsettledFrom?, matches, onWordTap, mode)`**: one `Text` per turn. Build the `AnnotatedString` once per (turn, matches, unsettled range); each word is `LinkAnnotation.Clickable` with `TextLinkStyles(style = SpanStyle(), pressedStyle = SpanStyle(background = accentTint))` (explicit empty style, no underline); separators belong to the word. The active word: `SpanStyle(color = onAccentFill)` on that word and a rounded accentFill rect (radius 4, outset 3 dp left/right, full line height) drawn in `drawBehind` from `TextLayoutResult` (`getPathForRange` when it wraps), so text never reflows. Read: `transcript`, ink for named speakers, text3 for placeholders. Draft: `draft`, text3 settled, text4 unsettled. Search: matches on amberTint; current match amberTint + W600. Paragraph top margin 2. `LazyColumn` items keyed by turn id; only the turn with the old or new active word recomposes. Auto-follow keeps the active line at 30 % of the viewport, pausing while the user scrolls (resume after 4 s idle or a word tap). Custom action "Play from start of turn".
- **`SuggestionCard(speaker, tone, timeMs, name, onYes, onNo, onSpeaker)`**: surface, 1 dp line, radius 14, padding 10/12; the header is a `TurnHeader` (48 dp button, opens the Name sheet); "Is this **Luca**?" 14 sp (bold W700), margins 6/8 (compensated 2); row gap 8: `InkButton` small "Yes, Luca", `SecondaryButton` small "Not Luca". Following paragraph top margin 6.
- **`PlaybackBar`**: surface, 1 dp line top border; padding top 10 (compensated 0), h 16, bottom 22 (compensated 18). `CassiniSlider` (M3 `Slider` with custom thumb and track; 48 dp layout; 8 dp track radius 4, active accent, inactive boardBg with 1 dp line2 border; 16 dp accent thumb; contentDescription "Position", stateDescription "11:44 of 23:50", steps of 10 s for accessibility). Times row `monoSmall` text2, offset −4 dp. Controls centred, gap 28: `SkipButton` (52 dp transparent circle, Back10/Fwd10 28/1.6 ink, "10" in `skip` 2 dp below centre), `PlayPauseButton` (64 dp inkFill, Play/Pause 26 onInkFill; stateDescription "Playing"/"Paused"). The bar is a traversal group.
- **`Waveform(levels: ImmutableList<Float>, freshCount = 7)`**: Canvas, full width, 96 dp; bar width (W − 55 × 3 dp)/56, gap 3 dp, height max(4 dp, round(v × 92) dp), centred, radius 2; last 7 bars barFresh, others barIdle. One image node. Fixture `v_i = 0.18 + 0.82·|sin(0.61 i)·cos(0.23 i + 1.1)|`.
- **`RecordingTimer`**: `timer` rec.text centred; spoken contentDescription; not live. **`RecordingStatus`**: 10 dp dot (paused: hollow 10 dp ring, 1.5 dp rec.text2 stroke) + 14/600 text ("Recording" rec.status / "Paused" rec.text2); live region polite. **`RecordingControls`**: 3 equal columns; Library 56 dp circle 1 dp rec.line + List 22/1.8 + label "Library" 13 sp 6 dp below; Pause/Resume 88 dp pauseFill with Pause 30 (paused: 26 dp rec.dot filled circle; descriptions "Pause recording" / "Resume recording"); Finish 56 dp rec.finish with an 18 dp white square (radius 4) + label "Finish".
- **`Avatar(text, tone: SpeakerTone, size = 40)`**: circle in the tone's tint, initial in the tone's ink colour (accentInk, tealInk, amberInk, violet), `avatar` style (16 sp W700), as boards 04 and 07.
- **`Banner(spec: BannerSpec, onAction, onDismiss?)`**: fills width (screens give 16 dp margins); padding 14/16 (bottom 8 with actions); radius 16; tone bg (Plain adds a 1 dp line border on surface); Row gap 10 top-aligned: 10 dp dot (6 dp top margin), Column: title 15/600 tone fg (Plain ink), body `body14` text3 (3 dp above), actions in a `FlowRow` (gap 8, top 10 → 4 compensated; first `InkButton` small, others `SecondaryButton` small). When `onDismiss` is set, a trailing 48 dp close button (Close 20/1.8 text2, a11y "Dismiss") sits top-right, overlapping the padding (right padding 16 → 4, title row end inset 32). The title is a heading; `announce = true` makes the banner a polite live region. On Recording, banners keep their light tones. No progress track (the Library `ProgressBannerCard` has its own).
- **`CassiniTextField(value, onValueChange, label, error, showFocus: Boolean? = null)`**: height 52, radius 14, padding 14, `field`; 2 dp accent border when focused, 1 dp line2 otherwise, 2 dp redInk on error; label `fieldLabel` 6 dp above. `showFocus = true` forces the focused look without a caret (fixtures; board 04), `null` follows real focus. **`CassiniCheckbox`**: 22 dp, radius 4; checked accent fill + white Check 2.2; unchecked 2 dp text2 border; the whole row is `toggleable(role = Checkbox)`.

## 7. Screen contracts (`UiState` and events)

Common types:

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

`ProgressHeadline` strings are in features.md section 3.1.

Per screen:

- **Library**: `LibraryUiState(loading, query, recording: RecordingPillUi?(title, elapsedMs, paused), progress: ProgressBannerUi?(noteId?, title?, percent, progress, line: ProgressLine /* TimeLeft(TimeLeftUi) | Waiting | Downloading(part, percent) | Checking(percent) */, queued: Int), banners, sections: ImmutableList<DaySection(label, rows)>, results: ImmutableList<NoteRowUi>?, snackbar, sheet: NoteActionsUi?, dialog: LibraryDialog? /* Rename(id, title) */)`. Events: Query, ClearQuery, OpenNote, LongPress, Record, ReturnToRecording, Import, OpenTrash, OpenSettings, OpenProgress, Resume(id), TryAgain(id), BannerAction(id, action), DismissBanner(id), Undo, SheetAction(…), DismissSheet, RenameConfirmed.
- **Recording**: `RecordingUiState(phase: NeedsPermission | Starting | Recording | Paused | Stopping, title, elapsedMs, levels, format: OPUS | M4A + sampleRateHz, hint: RecordingHint? /* Space(ms) | Cap(ms) */, banner?)`. Events: Title, PauseResume, Finish, Minimise, OpenLibrary, OpenSettings, BannerAction.
- **Note**: `NoteUiState(title, subtitle: NoteSubtitleUi?(day: DayLabel, time: LocalTime, durationMs) /* null in Reading mode */, mode: NoteMode, readOnlyReason: ReadOnlyReason? /* JOB_PENDING, TRUST */, chips: NoteChipsUi?(trust, version: VersionChipUi?(index, count, kind), speakers), banners, turns, activeWord: WordRef?, playback: PlaybackUi?(positionMs, durationMs, playing, following), search: TranscriptSearchUi?(query, current, total), sheet: NoteSheet? /* Actions | NameSpeaker | Merge | Versions | SubtitleFormat | Info */, dialog: NoteDialog?, notificationsOff)`. `NoteMode = Loading | AudioOnly | Queued(ahead, overDocument) | Processing(ProcessingCardUi(headline, rangeStartMs, rangeEndMs, percent, stage, skippedMatching, timeLeft, doneMs, totalMs, battery, words, lowMemory, download: DownloadUi?)) | Paused(doneMs, totalMs, hasCheckpoint) | Failed(overDocument) | Reading | Unplayable`. The top bar's More label follows the mode (Reading "Share and more", else "More actions"). Events per features.md section 3 (Back, More, Stop, Resume, StartOver, RemoveFromQueue, WordTap, SpeakerTap, AcceptSuggestion, RejectSuggestion, PlayPause, Seek, Skip, FollowPlayback, OpenVersions, PickVersion, Search…, Export(kind), Rename, Transcribe, TranscribeAgain, Trash, NameSheet(event), BannerAction, DismissSheet, DismissDialog).
- **Name a speaker**: `NameSheetUi(speakerId, label: SpeakerNameUi, tone, speakingMs, canSample, sampling, name, voices: ImmutableList<VoiceOptionUi(id, name, likely)>, showAll, ranked: Boolean, samePerson: String?, renameSaved: String?, notName: String?, remember, rememberEnabled, rememberReason: TOO_LITTLE_SPEECH | NO_MODEL | null, canMerge, save: SaveState /* ENABLED | DISABLED | BLOCKED_BY_QUESTION */, nameFieldFocused: Boolean, draft: Boolean)`. Merge state: `MergeUi(targets: (speakerId, label, tone, speakingMs), confirm: target?, sealedVersion: Int?)`.
- **Note actions**: `NoteActionsUi(title, recordedAt, durationMs, speakers, documentKind: DOCUMENT | PLAIN(ext), textExportEnabled, transcribeRow: TRANSCRIBE | TRANSCRIBE_AGAIN(nextVersion, namesCarry) | STOP | REMOVE_FROM_QUEUE | RESUME_OR_START_OVER, busyRow?, recording: Boolean)`.
- **Model download**: `ModelDownloadUiState(parts: ImmutableList<(ModelPartUi, bytes)>, totalBytes /* sum of rounded parts */, requiredFreeBytes, withNote: Boolean, busy, banner?)`. Never rendered with an empty part list (features.md section 5).
- **Settings**: `SettingsUiState(language: PHONE | ENGLISH | ITALIAN, models: ImmutableList<ModelRowUi(part, bytes, status: Ready | Downloading(pct) | Checking | Pending | NotDownloaded | Failed)>, voices: Int, trash: Int, version: String, notificationsOff)`.
- **Saved voices**: `SavedVoicesUiState(people: ImmutableList<PersonUi(id, name, notes, confirmedSeconds, stale)>, readOnly, menuFor: String?, dialog)`.
- **Trash**: `TrashUiState(items: ImmutableList<TrashItemUi(id, title, durationMs, deletedOn: DayLabel, daysLeft)> /* oldest deletion first */, backTo: LIBRARY | SETTINGS, snackbar /* fixture-only for board 09 */, dialog)`.
- **Licenses**: `LicensesUiState(entries, open: LicenseText?)`.

## 8. Screens (layout, board references)

- **Library (00)**: `Box(bg)` with a `LazyColumn` (header; search padded 16 h, top 4; privacy line; recording card; progress card margin top 14; banners; day sections; list padding top 6) and the bottom bar; snackbar above the bar. Search mode replaces the sections with the flat results list.
- **Recording (01)**: Column(rec.bg, padding 16/16/28 + safe drawing insets): header row 48 (collapse 44/48, status centred, format badge); title block margin top 28 (label "Title" 13 rec.text2, 4 dp, Row gap 8 with 1 dp rec.line bottom border and padding bottom 6: `BasicTextField` `screenTitle` rec.text, cursor rec.status, Pencil 18/1.8 rec.text2); banner slot 8 dp below; centre Column (weight 1, gap 28): timer, waveform, caption `body14` rec.text2 centred, max width 300; controls.
- **Transcribing (02)**: top bar with subtitle; `ProcessingCard`; header row (padding top 16, h 20, bottom 6: "Draft transcript" `button` + dashed tag); draft `LazyColumn` (padding top 4, h 20) with suggestion cards. Other non-Reading Note modes use the same top bar with their banner or `QueuedCard` at the card's place.
- **Read & listen (03)**: top bar without subtitle (Search 21/1.8 "Search in transcript", More "Share and more"); chips `FlowRow` (padding top 4, h 16, bottom 10, gap 8, centre-aligned); banners (margin h 16, bottom 10); transcript pane (weight 1, surface, 1 dp line top border, padding 0/20/16); `PlaybackBar`; `FollowChip` when following is paused.
- **In-note search header** (no board): replaces the top bar at the same height: back 48 (a11y "Close search"), a `PillSearchField` variant of height 44 (visual) / 48 (layout) with placeholder "Search in transcript", the counter "2 of 7" `monoTime` text2 (or "No matches"), ChevronUp and ChevronDown 22/1.8 (48 dp each, a11y "Previous match" / "Next match").
- **Name a speaker (04)**: sheet padding top 10, h 20, bottom 24, gap 16: header row (avatar 40, title `sheetTitle`, caption, play-sample 44/48 circle with 1 dp line2), name field (`showFocus` in the board fixture), "Saved voices, closest first" list card (1 dp line, radius 14; rows min 52, padding 14, gap 10; the Likely row on surfaceWash; "Show all" row when more exist), conflict box (amberWash, radius 14, padding 12/14, `body14`, buttons gap 8; compensated bottom 8), Remember row, footer (top 4, SpaceBetween: "Merge into…" left; Cancel + Save name right, gap 8).
- **Merge sheet** (no board): the `CassiniSheet` with title "Merge <A> into…" `sheetTitle`, then one 56 dp row per other speaker (8 dp dot in its tone, label `body16`, speaking time `monoTime` text2 at the end); tapping a row opens the confirmation `CassiniDialog` with the copy of features.md section 4 and a primary "Merge".
- **Versions sheet** (no board): title "Versions"; one row per variant: radio (accent, 22 dp) at the start, "Version N" `rowTitle`, detail `caption` text2 (date, words or "Speakers merged · from version 1", language); rows min 64, padding 12/16; disabled rows at 38 % with "Can't be read".
- **Subtitle chooser** (no board): a sheet with two `SheetActionRow` plain rows (Subtitles icon): "SubRip (.srt)" + "works in most video players", "WebVTT (.vtt)" + "for web players".
- **Document info** (no board): a `CassiniDialog` with key/value rows (key `caption` text2, value `body14` ink; the meeting id `monoSmall`, selectable), a trust line with the trust chip, and a single "Close" `TextButton`.
- **Model download (05)**: Column(bg, padding 16/20/24): Close (start offset −12 with 48 layout); content (weight 1, gap 20, top 24): Logo 56/1.3 accent, `hero`, `heroBody` text3 with the bold size, `GroupCard` with 1 dp line (rows 14/16, `body15` label, `monoSize` text2 value), footnote `body14` text2 exactly as the board, then the added `TextButton` row "Where the models come from · Privacy" (48 dp); bottom Column gap 10: large primary, `TextButton` "Not now — keep the audio".
- **Note actions (06)**: sheet padding top 10, h 12, bottom 20; title margin 12 h / 2 bottom, meta margin bottom 12; "Save a copy" section; featured document row; text and subtitle rows; divider; Rename, Transcribe again (subtitle), Document info, Move to trash (redInk, subtitle text2).
- **Saved voices (07)**: top bar; privacy line variant; GroupCard radius 18 with 1 dp line (rows padding 12/14/8, compensated 10/14/6; avatar; name; meta; stale line amberInk; options button MoreSmall 20 "Options for <name>" → `CassiniMenu` with Rename and Forget in redInk); helper (13 text2 lh 1.45, margin top 16, h 20); spacer; "Forget everyone…" (margin 16, bottom 24).
- **Settings (08)**: "App language" segmented + helper; optional notifications banner; "On-device models" GroupCard + helper; "Privacy" rows with chevrons; "About" card.
- **Trash (09)**: top bar with "Empty"; intro `body14` text3 (margin top 6, h 20, bottom 14); cards (gap 8, margin h 16; surface, 1 dp line, radius 16, padding 14/14/10 compensated 6; title + duration; meta; Restore + Delete now), oldest deletion first.
- **States (10)**: not a screen; the banner catalogue rendered by `Banner` (section 10.2).
- **Licenses** (no board): top bar "Source code and notices"; `GroupCard` of `SettingsRow`s (title, subtitle with the licence name, ChevronRight); a row opens a full-screen text pane with its own top bar (`monoSmall` text3 for licence texts, `body14` for notices and `PRIVACY.md` rendered as plain paragraphs; selectable; padding 20); "Source code on GitHub" opens the browser.

## 9. Navigation, transitions, system bars

- Keys and back behaviour: architecture section 6.
- Default push: slide in from 30 % right with fade, 250 ms FastOutSlowIn; pop reverses; predictive pop uses NavDisplay's default.
- Recording: `slideInVertically { it } togetherWith fadeOut()`; pop and predictive pop `fadeIn() togetherWith slideOutVertically { it }`. Back, the chevron and the Library button minimise; Finish replaces the entry with `NoteKey(id)`. A restored `RecordingKey` with no active recording pops itself.
- Deep links:

  | Source | Intent | Back stack |
  |---|---|---|
  | Recording notification | `open = recording` | pop to `RecordingKey` if present, else push |
  | Processing, results notifications | `open = note`, `noteId` | nothing if on top; pop to it if in the stack; else push (Library at the bottom) |
  | ACTION_VIEW | data uri | import on IO with the "Importing…" banner, then push `NoteKey(id)` |

- `SystemBarsAppearance(darkIcons)`: light screens use dark icons in the light theme; Recording always light icons; sheets do not change the bars.
- Edge to edge: `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)`; design paddings are added on top of insets. Screenshot tests run with zero insets.

## 10. Screenshot testing

### 10.1 Tool and setup

Roborazzi 1.76.0 with Robolectric 4.17 native graphics (SDK 35), proven in a spike with this toolchain in an application module: `@Config(qualifiers = "w390dp-h844dp-xhdpi")` + `@GraphicsMode(NATIVE)` produces 780 x 1688 px, the boards' size; Library differed in 4.9 % and Read & listen in 4.6 % of pixels by more than 32 levels, almost all text anti-aliasing. The P2 scaffold test in `:ui` is the proof for an AGP 9 library module with `android.newDsl=false`; it also renders a `LinkAnnotation` paragraph with a drawn active-word rect (`getPathForRange`), and runs one `enableAccessibilityChecks()` test. Paparazzi (alpha, layoutlib-bound) and Compose Preview Screenshot Testing (alpha, preview-only) are rejected.

- Catalog: `roborazzi = "1.76.0"`; `roborazzi`, `roborazzi-compose`, `roborazzi-junit-rule` (group `io.github.takahirom.roborazzi`); plugin `io.github.takahirom.roborazzi`. BOM-managed `compose-ui-test-junit4` and `compose-ui-test-junit4-accessibility`.
- `ui/build.gradle.kts`: the plugin; `roborazzi { outputDir.set(file("src/test/screenshots")) }`; `isIncludeAndroidResources = true`; `src/test/screenshots` declared as a test input; `resourcePrefix = "ui_"`; a lint block like `:data`'s.
- Base class:

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

- Determinism: fixtures carry fixed values relative to a fixed now of 2026-10-09 10:30 Europe/Rome; `ProvideFixedFormat` pins locale, 24-hour time and the zone. Focused-looking fields use `CassiniTextField(showFocus = true)` (no caret). Sheets render inside `SheetBoardFrame`, dialogs inside `DialogBoardFrame`, the person menu inside `MenuBoardFrame` (scrim #8A9098 light or the dark equivalent, content placed as the real window places it), all captured with `onRoot()`. One extra test per window type (a real `ModalBottomSheet`, a real `BasicAlertDialog`, a real `DropdownMenu`) uses `window = true` (`captureScreenRoboImage`) as a golden only.
- Goldens are recorded and verified on Linux x86_64 only (this machine and the ubuntu CI runner).

### 10.2 What is captured

- `BoardScreenshotTest` (light; compared with the design): goldens named after the design slugs in `light/`: `00-library`, `01-recording`, `02-transcribing`, `03-read-and-listen`, `04-name-a-speaker`, `05-model-download`, `06-note-actions-and-export`, `07-saved-voices`, `08-settings`, `09-trash`. Fixtures (`BoardFixtures.kt`) copy the boards' content word for word (Library: Today — Weekly product sync 48:13 10:02 Transcribing highlighted, Interview — Marta 1:12:05 08:40 Queued; Yesterday — Call with supplier 23:50 with the excerpt preview "…quindi la consegna slitta a giovedì, but the price stays as agreed in the March order…", 15:10 Transcribed · 3 speakers; Board review prep 34:12 11:00 Paused at 12:40 · Resume; Voice note 8 Oct, 08:14 02:41 Audio only; banner 62 %, "About 6–9 min left · 1 more in queue". Trash: the board's snackbar is set in the fixture).
- `StatesBoardTest`: five images `states/10-states-col1.png` … `col5.png`. Geometry: each image is one board column without outer margin: 358 dp wide (the banners' width), `boardBg`, the column heading (`overline`, text3, 4 dp bottom margin), then the banners with 12 dp gaps (also 12 dp between heading and first banner), height = content height (rendered with qualifiers `w358dp-h1200dp-xhdpi` and captured from the column node with `onNodeWithTag("states-column")`). References: `tools/ui-fidelity/crop_states.py` cuts the board PNG (4540 x 2360 px) at x = round(2 × (48 + i × 442.8)) px, width 716 px; y from the first row at or below 2 × 100 px whose pixels in that x range differ from boardBg (the heading top) to the last such row. `compare.py` requires equal widths, pads the shorter image with boardBg and reports the height difference in dp.
- `DarkScreenshotTest`: the 10 boards and the 5 columns in dark (`dark/`, goldens only).
- `LocaleScreenshotTest` (`+it`, `Locale.ITALIAN`): Library, Transcribing, ReadAndListen, NameSpeaker, Settings, NoteActions (`it/`).
- `FontScaleScreenshotTest` (font scale 1.5 via `RuntimeEnvironment.setFontScale`): Library, ReadAndListen, NameSpeaker, Settings (`fontscale/`).
- `StateVariantsTest` (goldens only, `variants/`): every variant listed in features.md acceptance sections, each dialog, the person menu, the no-board specs of section 8, Model download with No connection.
- `ComponentScreenshotTest`: each component in all tones and states on a 390 dp strip (`components/`).

Tasks: `:ui:recordRoborazziDebug` (update), `:ui:verifyRoborazziDebug` (CI, through `ciTest`), `:ui:compareRoborazziDebug` (diffs in `ui/build/outputs/roborazzi`). CI uploads the diffs on failure.

### 10.3 Comparing with the design

- `tools/ui-fidelity/compare.py --design design/reference/png --golden ui/src/test/screenshots/light` (Pillow only, run with `python3 -I`): pairs files by slug; per board a 50/50 blend PNG and an absolute-difference heat map; prints the mean difference, the percentage of pixels differing by more than 32 levels, and a table per 52 dp band. `rows.py` reports vertical offsets; `crop_states.py` cuts the five column references; `contrast.py` computes WCAG ratios.
- Reviewer protocol (P8, P12): read the design PNG, the golden and the blend for each board and record in `docs/ui-fidelity.md`: layout and spacing within 2 dp except the accepted deviations (section 11); colours equal to the tokens; typography (family, size, weight); icons; copy. Advisory bar: at most 8 % of pixels above 32 per phone board and no 52 dp band above 25 %; anything above gets a written explanation. CI gates only on `verifyRoborazziDebug` against committed goldens, which a reviewer approves when they change.

## 11. Accessibility and contrast

Contrast (WCAG 2.x), enforced by `ContrastTest` for both themes (≥ 4.5 for text, ≥ 3.0 for large text and non-text):

| Pair | Ratio |
|---|---|
| ink on bg / surface | 16.63 / 17.99 |
| text2 on bg / surface / boardBg | 7.33 / 7.93 / 6.82 |
| text3 on bg / accentTint / boardBg | 9.35 / 8.65 / (computed, ≥ 4.5) |
| text4 #687180 on bg / surface | 4.55 / 4.93 (the board's #6B7480 is 4.38 and fails AA) |
| accentInk on accentTint | 9.00 |
| tealInk on tealTint / surface | 6.99 / 8.06 |
| amberInk on amberTint / amberWash / surface | 6.86 / 7.22 / 7.75 |
| redInk on redTint / surface / bg | 5.80 / 6.92 / 6.39 |
| violet on violetTint | 7.26 |
| white on accentFill; accent on surface | 7.55 |
| white on red (Record) | 5.18 |
| onInkFill on inkFill | 17.99 |
| rec.text / text2 / status / dot / barFresh on rec.bg | 16.20 / 8.22 / 8.64 / 6.30 / 7.73 |
| snackbar action on snackbar | 8.92 |
| accent on accentTrack / line (non-text) | 5.12 / 5.97 |
| teal / amber / red dots on surface (non-text) | 5.47 / 5.02 / 5.18 |
| Dark: ink on bg 16.18; text2 on surface 7.83; text4 on bg 6.20; accentInk on accentTint 8.94; tealInk on tealTint 9.03; amberInk on amberTint 8.91; redInk on redTint 8.51; violet on violetTint 7.41; accent on surface 7.40 | AA |

Allowed below 3:1 because text or icons identify the control (WCAG 1.4.11): line2 outlines (1.58), rec.line circle and barIdle (decorative), line3 dashed tag and plain dot, the slider's inactive track (thumb and active track carry the state). The unchecked checkbox uses text2 (7.93).

Accepted deviations from the boards (each one is checked by the reviewer, not hidden by the tolerance):

1. `text4` #687180 instead of #6B7480.
2. 48 dp layout for every control; in the draft transcript this adds about 6 dp per turn.
3. The active-word background is drawn, not padded, so "giovedì," may wrap one word later than on the board.
4. Sub-pixel text advance differences (a snippet line may wrap one word earlier).
5. Plex Mono 600 is synthesised from 500.
6. Turn 1 of Read & listen sits 2 dp lower than on the board.
7. Board 05: the added "Where the models come from · Privacy" row below the footnote (no other pixel moves).
8. Board 10: the download banner drops "Keep Cassini open." (downloads survive leaving the app); the NO_ENCODER body drops "(M4A)" when the capture format is Opus (the board's copy is used otherwise and in the fixture).
9. Board 10: TX_CALCULATING and TX_BATTERY are catalogue-only cards; the app shows these states in the progress card's stat cells.
10. Dismissible banners (RECOVERED, NO_ENCODER) have a close button the board does not show; the catalogue column renders them without it, as the board.
11. Board 09: the "moved to trash" snackbar over Trash is a fixture-only state; the app shows that snackbar on the Library.
12. Board 04: Save name keeps its enabled look while the same-person question is open; a press is blocked and announced instead of saving (features.md section 4).
13. Library row suffixes keep their board size; their 48 dp touch area comes from extended hit testing (section 5), which ATF may report and the tests allow.
14. UI without a board (section 8 "no board" items, the dark theme, the extra banners marked "not on the board" in features.md section 13) follows this document and waits for the owner's review.

Mapping (features.md section 14 is the acceptance list): word links with onClick label "Play from here"; speaker labels as buttons with full spoken descriptions; live regions throttled; state descriptions on recording status, stepper, play/pause, slider and segments; headings; traversal order Note = top bar, chips, banners, card, transcript, player (player is a traversal group), Library = header, search, banners, rows, Import, Record; reduced motion respected.

## 12. Strings

All UI strings live in `ui/src/main/res/values{,-it}/strings.xml` with the `ui_` prefix; notification and service strings live in `:app` with the prefix `notif_`. `MissingTranslation` is an error. Plurals and format resources as in features.md section 0. The placeholder speaker label is `ui_speaker_label` ("Speaker %1$d" / "Voce %1$d"), created in P7; in P10 the job's placeholder function and `DefaultLabelsTest` use it, and the test checks it against `DefaultLabels.PREFIXES`.

Key Italian copy (the implementer completes the rest in the same register): Notes → Note; Search titles and what was said → Cerca nei titoli e in ciò che è stato detto; Transcribed on this phone. Audio never leaves it. → Trascritto su questo telefono. L’audio non lo lascia mai.; Today / Yesterday → Oggi / Ieri; Import audio → Importa audio; Record → Registra; Recording → Registrazione in corso; Title → Titolo; Library → Libreria; Finish → Termina; Transcribing → In trascrizione; Queued → In coda; Transcribed → Trascritta; Paused at %s → In pausa a %s; Audio only → Solo audio; Finding words and speakers → Ricerca di parole e voci; Finding words → Ricerca delle parole; Reading / Preparing / Words & voices / Matching / Saving → Lettura / Preparazione / Parole e voci / Abbinamento / Salvataggio; Time left → Tempo rimasto; Processed → Elaborato; Battery at finish → Batteria alla fine; Words so far → Parole finora; Stop and keep progress → Ferma e conserva i progressi; Draft transcript → Bozza della trascrizione; Words may still change → Le parole possono ancora cambiare; Verified → Verificata; Is this %s? → È %s?; Yes, %s → Sì, %s; Not %s → Non è %s; Save a copy → Salva una copia; Cassini document → Documento Cassini; Transcript as text → Trascrizione come testo; Subtitles → Sottotitoli; Rename → Rinomina; Transcribe again → Trascrivi di nuovo; Document info → Informazioni sul documento; Move to trash → Sposta nel cestino; Kept for 30 days, then deleted. → Conservata per 30 giorni, poi eliminata.; Name → Nome; Saved voices, closest first → Voci salvate, dalla più simile; Likely → Probabile; Same person → Stessa persona; Someone else → Un’altra persona; Answer the question above first → Prima rispondi alla domanda qui sopra; Remember this voice on this phone → Ricorda questa voce su questo telefono; Merge into… → Unisci a…; Cancel → Annulla; Save name → Salva il nome; Transcribe on this phone → Trascrivi su questo telefono; Download and transcribe → Scarica e trascrivi; Not now — keep the audio → Non ora — tieni l’audio; App language → Lingua dell’app; Phone → Telefono; On-device models → Modelli sul dispositivo; Ready → Pronto; Saved voices → Voci salvate; Trash → Cestino; Empty → Svuota; Restore → Ripristina; Delete now → Elimina ora; Undo → Annulla; Dismiss → Chiudi; Forget everyone… → Dimentica tutti…; About → Informazioni.
