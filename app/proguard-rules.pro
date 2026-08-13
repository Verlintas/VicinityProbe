# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vicinityprobe.**$$serializer { *; }
-keepclassmembers class com.vicinityprobe.** {
    *** Companion;
}
-keepclasseswithmembers class com.vicinityprobe.** {
    kotlinx.serialization.KSerializer serializer(...);
}
