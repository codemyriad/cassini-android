# sherpa-onnx's JNI code reads Kotlin config fields and calls methods by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# zstd-jni looks up its classes and fields from native code.
-keep class com.github.luben.zstd.** { *; }
# libcassini_opus binds Java_org_cassini_android_NativeOpus_* by name.
-keep class org.cassini.android.NativeOpus { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
