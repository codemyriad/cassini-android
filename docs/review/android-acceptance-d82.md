# D82 acceptance follow-up

The unmodified-main API 35 walk at `d17cdecf` failed four of its six test invocations. English light and dark real-model walks passed. Italian realModels failed to open Saved voices, while downloadScreens failed to reach download consent in all three variants. It collected 37 of 57 screenshots. This follow-up changes the acceptance fixture and selectors only.

## Saved voices and Trash

The Settings test matched the Saved voices title as a substring of any clickable row, then clicked the first match. In Italian, a model row's explanatory subtitle can contain that same text. The test now matches the exact localized row title and requires a unique matching node. Trash uses the same exact-title rule. Production Settings rows are unchanged.

## Optional model download

An empty downloaded-model root does not remove bundled Whistle. Transcribe on a note therefore does not promise to open model consent. Selecting an uninstalled Parakeet as the active recognizer is also not a valid fixture: active recognition falls back to the included model until the optional model is installed.

The walk now opens Settings and invokes its existing Parakeet download action. It verifies speech-only consent, with the size derived from the pinned catalog, and rejects speaker/voice parts in this optional plan. It retains the Downloading, Checking, No connection, No space and File check failed transitions, plus the assertions that the scripted fetcher wrote no files and every downloadable part remains missing. The scripted fetcher still supplies progress/failure events and never downloads a model. Included-model availability, device capability and production download code are unchanged.

The previous hard-coded 730 MB belonged to an older full-plan fixture. The optional speech plan is a different, actual user path. Full-plan composition and size coverage remains in the model-plan and Model download UI tests; this device walk exercises the installed app's current optional-download action.

## Validation

The default full walk still selects English light, English dark and Italian, with realModels and downloadScreens in each, and requires all 57 named screenshots. No failing variant, state or screenshot has been removed. Screenshot readiness still uses the existing hardware frame-commit acknowledgement on API 35. All waits remain driven by the existing UI/model events and bounded by hang guards.

Native rerun and route/selector mutation results are pending. The original 4/6 failures remain the baseline until replacement results are recorded. Run the walk with `scripts/acceptance-walk.sh --serial <owned API 35 emulator>` after preparing the committed public audio fixtures and installing the pinned public models. Use debug APKs with `adb install -r`; never uninstall or clear app data.
