# Android model download completion audit

This audit follows the report of an intermittent iPad download that reached
100%, stalled and failed, then succeeded on retry. These findings concern
Android code and local fake-server tests; they do not establish the iPad cause.

## Transfer and installation

The path is `ArtifactFetcher` → `NetworkFetcher` → `ModelRepository` →
`ModelInstaller` / `ModelInstallOwner` → `ProcessingService`.

| Boundary | Required behavior |
| --- | --- |
| Manifest | Match the pinned model ID, source revision, file sizes and SHA-256 values; enforce the redirect allow-list. |
| HTTP body | Validate known length against the pinned whole response or declared range; bound range data at its declared end. |
| Compressed file | Check exact size and SHA-256, fsync, then rename. |
| Extraction | Stream to a separate partial file; bound output size; fsync; verify extracted size and SHA-256; rename. |
| Model readiness | Write the source marker only after every artifact has verified; fsync the temporary marker and replace atomically. Directory sync is best effort. |
| Completion | Wait for the install task and readiness refresh, independently of the last percentage. |

Existing size and hash checks already prevent truncated or wrong weights from
becoming ready. The audit found these additional gaps:

- A lengthless response ending early was treated as a hash failure and its
  prefix discarded. It now reports an interrupted transfer and preserves the
  prefix for a Range retry.
- A resumed response could declare an earlier range end but deliver the whole
  remaining pinned file. It passed the final hash despite inconsistent framing.
  The copy is now bounded by the declared end; inconsistent known lengths are
  refused from the headers.
- An inconsistent declared length could leave the client waiting for more body
  bytes. It is now rejected before waiting for that body.
- Local output errors escaped as ordinary I/O errors and could appear as
  connection failures. Local open/write/flush/close and digest errors now keep
  their INSTALL or SPACE classification and original cause. ENOSPC from a
  fetcher or marker write also remains SPACE at the repository boundary.
- A DOWNLOAD wrapper hid the transport exception from the installer. Interrupted
  socket I/O with the interrupt flag set now becomes CANCELLED; ordinary timeout,
  DNS, connect and route errors remain NO_CONNECTION even on validated Wi-Fi.
  Cancellation and error mapping also inspect the cause of DOWNLOAD wrappers.
- Ignored directory-creation failures could appear as insufficient storage when
  the missing path reported zero free bytes. Directory creation now uses
  `Files.createDirectories`; its errors are INSTALL or SPACE before fetching.
  Both plain and compressed artifact entry points ensure their directories.

A full verified partial remains reusable after a failed final move. A failed
marker write leaves the model unavailable; a retry can complete it. A verified
compressed download is retained on cancellation or local extraction/install
errors, and discarded for invalid decoded content.

## Timeouts, foreground service and storage

The 30-second connect/read timeouts apply to HTTP requests, including redirect
hops. There is no whole-install or large-file hashing deadline. Hashing and
extraction use streaming buffers and check interruption between blocks.
An unterminated body can still wait for the HTTP read timeout after delivering
bytes: a complete HTTP response is required before verification and install.

Model downloads use the foreground service and install owner, not WorkManager.
The service joins outstanding download jobs before stopping. The new
Robolectric test gates verification at 100%: the model is unavailable, the
install remains active and the service stays foreground until the gate is
released and the readiness marker is written. It then verifies readiness,
owner idle state and service shutdown.

Space checks cover compressed input, extracted output and 32 MiB slack.
The plan-level preflight deliberately uses the full missing-model budget even
on resume; per-file checks also account for retained download bytes. This
conservative preflight can refuse a low-space resume. Its existing budget
policy is unchanged. Local space errors must still report insufficient
storage rather than a network problem.

## Regression coverage

Fast loopback tests cover a truncated response without length and its Range
retry, range over-delivery, inconsistent length with a gated slow body, a valid
gated slow response, local output failure, and a failed final move followed by
a retry without another network request. Existing hash-mismatch, truncated
fixed-length, capped-range, decompression and cancellation tests remain.

Repository tests cover ENOSPC after transfer, failed marker creation at 100%
verification, unavailable readiness on failure and successful retries. The
foreground-service test covers the 100%-checking completion boundary. Model
files in Android tests are sparse fakes; no large model download, device or
private audio is used. Gates and completion events provide synchronization;
wait timeouts are upper bounds, not fixed delays.

Review regressions also cover interruption classification at the repository and
installer boundaries, cancellation without a failed banner through the real
install owner, all four transport failures through that owner while online,
and directory creation/refusal for plain and compressed downloads. Obstructing
files remain unchanged, and no fetch starts after a directory failure.
