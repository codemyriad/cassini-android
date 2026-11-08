The ONNX Runtime API 23 headers are fetched at build time from Microsoft's
v1.23.2 source tag. Their byte-size/SHA-256 pins are in whistle/worker-pins.json.
They match the headers from the official onnxruntime-osx-universal2-1.23.2.tgz
archive (SHA256 49ae8e3a66ccb18d98ad3fe7f5906b6d7887df8a5edd40f49eb2b14e20885809)
used by the Mac bridge. MIT license: ONNXRuntime-LICENSE. Cassini's JNI shim is
GPL-3.0-or-later. Runtime/model binaries and downloaded headers stay out of Git.

The private runtime ships its v1.23.2 ThirdPartyNotices.txt separately from
sherpa's newer runtime notices. The URL/size/SHA-256 is in the ortNotices pin;
package-whistle.py verifies the committed APK license copy before preparation.
