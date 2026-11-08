# Pinned CI download cache

The Gradle 9.8.2 distribution outage blocked PR85 before tests: the normal
services.gradle.org route and its GitHub browser-release redirect returned
HTTP504. Repeating builds cannot fix that dependency.

Every Gradle workflow now uses the local `pinned-gradle` composite action:

1. Read the distribution version and `distributionSha256Sum` from the committed
   wrapper properties. A missing SHA-256 pin fails.
2. Restore one ZIP through actions/cache with an exact version/SHA-256 key.
   There is no prefix fallback to another distribution or checksum.
3. Verify the ZIP's SHA-256 on every restore, including when setup-gradle
   restored an installed distribution. Corrupt cache contents fail immediately;
   they are not silently replaced or accepted through an installed marker.
4. Stage the verified ZIP in the wrapper's normal URL-addressed cache. The
   unmodified wrapper retains its own `distributionSha256Sum` check.
5. Save verified ZIPs only from GitHub-hosted runs of main. Pull requests,
   tags and self-hosted runners only restore this cache.

On a cache miss, the helper obtains the same vendor release asset through the
working official GitHub release API and checks the committed SHA-256 before
publication. It does not change the wrapper URL, Gradle version or checksum.
The project ZIP costs 151,620,138 cache bytes. The native-source build also
uses a separately keyed Gradle 8.6 ZIP with the official SHA-256 pin recorded
in ci-download-pins.json. The helper refuses an unexpected upstream wrapper
URL or existing wrong pin, adds that pin to the extracted source wrapper, and
stages its ZIP before building. Neither wrapper version changes. Only
one immutable cache entry is needed per distribution pin; the existing cache
retention/quota policy remains in place. The first hosted main run after merge
warms this shared cache. Until then, cold PR runs use the verified API route.
Once warm, a cache hit needs neither distribution download endpoint.
Cold API calls use the job-scoped read-only GitHub token to avoid shared
anonymous rate limits. Authorization is not forwarded to redirected asset
CDNs. Tokens are scoped to download steps; builds and tests do not receive them.

The helper also prefetches the exact pinned Kotlin compiler JAR into Gradle's
CI-only local Maven entry because Maven's large-JAR redirect had the same outage.
The vendor JAR and original published POM have independent SHA-256 pins. A CI
init script verifies both and routes only this exact module/version locally;
all transitive dependencies remain those in the original POM. The
native source build reuses the exact pinned ORT archive for both ABIs through
upstream's supported external-runtime directory. Native GitHub source archives,
including KissFFT and Kaldi decoder, are staged at upstream's supported
pre-download locations. Direct codeload bytes must match the existing source
SHA-256 pins; upstream CMake still enforces its original hashes. Versions and
bytes remain unchanged. The helper and its pins are included in the native source package.

The existing actionlint version/checksum is also fetched through the verified
API route; its analysis runs separately without the download credential. Native
main jobs reuse the already-verified ORT ZIP for their existing artifact output
instead of downloading it again.

There are no build, test or analysis retry loops. Only individual API network
operations can retry, at most four attempts with logged backoff. Permanent
HTTP errors and checksum/content failures stop immediately. Normal Gradle
analysis/tests run once; the wrapper's existing retry settings are unchanged.

Verification uses `python3 -m unittest discover -s tests/ci -v`. Tests refuse
corrupt ZIPs, missing pins and unsafe native archive paths, and stage a valid
cache hit with network calls forbidden. A real cached ZIP was also verified
and staged offline on the shared Mac; the local wrapper process was not run
because the memory admission gate refused it. CI also tests a fresh real-wrapper home with an unreachable network proxy:
the verified ZIP must start, and incorrect bytes must fail the wrapper checksum.
The existing full test gates still run. No assertion, golden or coverage is removed.
