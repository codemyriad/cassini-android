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
The raw Gradle ZIP costs 151,620,138 cache bytes for the current version. Only
one immutable cache entry is needed per distribution pin; the existing cache
retention/quota policy remains in place. The first hosted main run after merge
warms this shared cache. Until then, cold PR runs use the verified API route.
Once warm, a cache hit needs neither distribution download endpoint.

The helper also prefetches the exact pinned Kotlin compiler JAR into Gradle's
artifact cache because Maven's large-JAR redirect had the same outage. The
native source build reuses the exact pinned ORT archive for both ABIs through
upstream's supported external-runtime directory. Versions and bytes remain
unchanged. The helper and its pins are included in the native source package.

There are no build, test or analysis retry loops. Only individual API network
operations can retry, at most four attempts with logged backoff. Permanent
HTTP errors and checksum/content failures stop immediately. Normal Gradle
analysis/tests run once; the wrapper's existing retry settings are unchanged.

Verification uses `python3 -m unittest discover -s tests/ci -v`. Tests refuse
corrupt ZIPs, missing pins and unsafe native archive paths, and stage a valid
cache hit with network calls forbidden. A real cached ZIP was also verified
and staged offline on the shared Mac; the local wrapper process was not run
because the memory admission gate refused it. CI runs the unmodified wrapper
and the existing full test gates. No assertion, golden or coverage is removed.
