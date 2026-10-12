# The keep rules for sherpa-onnx, zstd-jni, NativeOpus and native methods come with :engine
# (engine/proguard-consumer-rules.pro). Add rules here only for code in this module.

# The navigation keys (nav/Dest.kt). Navigation 3 saves the back stack with kotlinx-serialization: it looks up each
# key's serializer by reflection and stores the class name, so the keys, their companions and serializers stay.
-keep @kotlinx.serialization.Serializable class org.cassini.android.nav.** { *; }
-keepclassmembers class org.cassini.android.nav.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
