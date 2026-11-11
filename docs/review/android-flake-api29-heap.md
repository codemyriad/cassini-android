# API 29 system-server heap failure

[Main run 42663561968](https://github.com/codemyriad/cassini-android/actions/runs/42663561968/job/129326963187)
passed all 27 instrumentation tests. Its `INSTRUMENTATION_CODE: -1` was normal completion, not a failed test.
After the published APK installed, `system_server` died in
`ConfigUpdateInstallReceiver.getAltContent` while growing a byte buffer by 4 MiB.
The retained logcat reports a **16 MiB growth limit**, followed by missing `package` and `settings` services.
This is a guest ART capacity failure, distinct from the host/Gradle OOMs in the inventory.
[Android 10's system server](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-10.0.0_r47/services/java/com/android/server/SystemServer.java)
clears the smaller growth limit at startup, making the guest maximum heap the relevant ceiling here.

Configure API 29's `vm.heapSize=512` before boot, leaving API 26/34/35 profiles unchanged.
This is a maximum heap allowance, not a 512 MiB initial allocation or a host RAM increase.
[Android's AVD documentation](https://developer.android.com/studio/run/managing-avds#avdproperties)
describes the VM heap override. The installed emulator hardware schema declares `vm.heapSize` as an integer in MiB.
The [pinned runner implementation](https://github.com/ReactiveCircus/android-emulator-runner/blob/a421e43855164a8197daf9d8d40fe71c6996bb0d/src/emulator-manager.ts#L43-L45)
writes `hw.heapSize` for its `heap-size` input, so this workflow uses the supported pre-launch hook to set the
actual emulator property directly.

After all instrumentation, FTS/codec checks and the published-APK launch check, verify the booted
`dalvik.vm.heapsize` and retain it in `emulator-results/heap.txt`. API 29 refuses a capacity below 512 MiB.
This makes an accidental return to the 16 MiB profile fail deterministically even when no configuration-update
broadcast arrives. The check runs after instrumentation, so it cannot enter the existing pre-test boot recovery
path. No test, API level, codec check or assertion is removed, retried or skipped.

Unit coverage includes duplicate/missing AVD properties, idempotent updates, byte-identical preservation of other
API profiles, Android size units, refusal of the observed 16 MiB value, and missing/unknown configuration.
Negative controls that write the wrong hardware key or accept the old capacity must fail these regressions.
Full Android CI must show the API 29 guest reporting at least `512m` and every existing device check passing.
