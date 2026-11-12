# User release-note fragments

A change that someone using Cassini can notice adds one `<short-slug>.md` file:

```text
kind: new
---
You can now export several notes together from Notes.
```

Use `new`, `better` or `fixed`. Write one or two short sentences, at most 450 UTF-16 units. Say what you can do,
what improves, or what stops going wrong. Name controls as they read on screen. Use English and sentence case;
use typographic apostrophes, and omit “please”. No PR numbers, decisions, filenames, developer terminology or links.
Most changes to tests, build scripts or internal code need no fragment.

Use a new slug for each change. Keep merged fragments: successful Play builds track their exact source commit.
An unchanged fragment already present at that commit is not announced again. Failed builds do not consume notes.
The renderer groups New, Better, Fixed; filenames order changes inside a group. Put the important changes first.
If the complete notes exceed 500 UTF-16 units, whole sentences are kept in that order, followed by “and smaller
fixes”. Those smaller changes count as announced. A changed fragment is announced again, so do not edit old
published fragments to add unrelated news.

See [the release-notes method](../docs/release-notes.md) for stable releases and previews.
