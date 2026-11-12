# Release notes people read

Cassini keeps two texts. PR titles form the complete developer changelog, shown only in a folded section of the
GitHub release. The notes above it and Play’s “What’s new” describe what someone using the app will notice.

For each user-visible change, follow the [fragment rules](../release-notes.d/README.md). Do not add a fragment for
CI, refactoring, evidence collection or tests. Never generate user copy from PR titles. To review all fragments locally:

```sh
python3 -c 'import sys; sys.path.insert(0, "scripts"); import release_notes as n; from pathlib import Path; print(n.render(n.fragments(Path("."))))'
```

Scheduled builds compare fragments with the source commit of the last successful Play upload, using the existing
release deployment ledger. A failed upload keeps that baseline. New or changed fragments are announced once;
unchanged ones are omitted. This also works when the previous build predates fragments. English notes fit 500
UTF-16 units, preserving complete sentences and putting new features first. Empty changes use the agreed short
“under the hood” text. Only after Play succeeds does the workflow create a GitHub prerelease, with signed APKs,
user notes first and the developer list folded below. Its `android-internal-<versionCode>` tag points to the exact
main commit; the workspace version injected for that build is recorded in its title and release plan. These tags
are not manual version-tag releases. A GitHub-publication failure stays visible and does not forget a completed
Play upload. No stable release or production Play submission is automated.

Before a stable release, gather all user-visible fragments since the last stable version, including changes first
announced in test builds. Sort each entry into a highlight, improvement, fix, required action, or leave it out.
Collapse related changes around what you see, not around PRs. Omit internal work and bugs introduced and fixed
between published builds. Say what you can do now; use numbers only when they help you decide. Put changed
defaults, requirements and anything you must do under “Before you update”, with where the setting is and what
happens if you do nothing. Check control names against the version being released. Ask someone else to read it.

Write the curated English text in `release-notes/<version>.md`; keep this store copy under 500 UTF-16 units.
Optional headings include Highlights, Better, Fixed and Before you update; leave empty sections out. Check it with
`python3 -I scripts/release_notes.py v<version> --check`. The stable tag checks refuse a missing or empty file.
The tag build writes that copy into the Play changelog; GitHub shows it above the folded CHANGELOG section.
Manual beta tags may also provide curated notes; otherwise they render the available fragments. Stable notes
must always be curated. This pipeline does not authorize a production Play submission.
