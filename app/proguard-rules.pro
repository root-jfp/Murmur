# Murmur ProGuard rules

# Keep Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.murmur.reader.**$$serializer { *; }
-keepclassmembers class com.murmur.reader.** {
    *** Companion;
}
-keepclasseswithmembers class com.murmur.reader.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Keep PDFBox
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Room entities
-keep class com.murmur.reader.data.local.** { *; }

# Avoid stripping data classes used for JSON parsing
-keep class com.murmur.reader.tts.EdgeVoice { *; }
-keep class com.murmur.reader.tts.MetadataMessage { *; }
-keep class com.murmur.reader.tts.MetadataItem { *; }
-keep class com.murmur.reader.tts.MetadataData { *; }
-keep class com.murmur.reader.tts.MetadataText { *; }
