# Phase 1 WP3: Android meeting view

Based on the approved Phase 1 Android Meeting, Find, Scrolled and Player boards and L43/L55/L56. Android keeps the title in its native M3 top bar, as specified in the brief. Back says Back to all meetings; Search, Share Cassini document and More actions use existing navigation/export actions. The compact scrolled bar keeps Android’s hairline. No summary/tab/bookmark features are added.

Recording date/duration, inline speaker names and existing trust/version controls are one scrolling transcript header. Stored names and first-speech palette slots come from the existing mapped transcript, cached when it is built (including overlap-only speakers). Naming delegates to the existing SpeakerTap validation. Unnamed names have a soft underline; each speaker remains a 48dp target. No writer/schema/colour allocation changes.

The find bar is native M3, retaining the existing query acknowledgement, match counter, next/previous actions and IME policy. A selected turn scrolls the metadata away. Header item offsets apply to both playback-follow and match jumps, with stable turn keys. Tests use a short viewport and tall turns so an off-by-one target cannot accidentally be visible.

The player retains its Material 3 Slider, transport semantics and callbacks in a Material 3 Surface. Speed uses the board’s leading outline pill. Both compact and upright layouts measure its actual localized text; larger text gets a separate leading row when needed instead of covering transport targets.
