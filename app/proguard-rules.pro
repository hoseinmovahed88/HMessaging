# Keep Room entities / DAOs metadata
-keep class com.hmessaging.data.db.entity.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.hmessaging.** {
    *** Companion;
}
-keepclasseswithmembers class com.hmessaging.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.hmessaging.**$$serializer { *; }
