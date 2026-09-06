# Readium uses reflection for some JSON models; keep its model classes.
-keep class org.readium.r2.shared.** { *; }
-keep class org.readium.r2.streamer.** { *; }
-keep class org.readium.r2.navigator.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Media3
-keep class androidx.media3.** { *; }
