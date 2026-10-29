# JNI entry points are resolved by name from libmoto_jni
# (Java_top_maler_motogps_core_MotoNative_*).
-keep class top.maler.motogps.core.MotoNative { *; }

# Gateway, NavCore and map JSON models are decoded by kotlinx.serialization;
# keep their generated serializers and companion lookups.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class top.maler.motogps.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class top.maler.motogps.**$$serializer { *; }
-keepclassmembers class top.maler.motogps.** {
    *** Companion;
}

# The notification-listener and foreground services are referenced from the manifest.
-keep class top.maler.motogps.media.MediaNotificationListener
-keep class top.maler.motogps.service.NavigationForegroundService
