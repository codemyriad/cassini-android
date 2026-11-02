# Applied to every app that depends on :engine (consumerProguardFiles). The rules keep members, not only
# class names: native code reads fields and calls methods by name.
# sherpa-onnx's JNI code reads Kotlin config fields and calls methods by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# zstd-jni looks up its classes and fields from native code.
-keep class com.github.luben.zstd.** { *; }
# libcassini_opus binds Java_io_codemyriad_cassini_NativeOpus_* by name.
-keep class io.codemyriad.cassini.NativeOpus { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
