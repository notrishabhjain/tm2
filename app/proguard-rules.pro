# Minification is off for release today; these rules exist so that turning it
# on later does not silently break serialization or Room.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations

# kotlinx.serialization
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.taskmind.**$$serializer { *; }
-keepclassmembers class com.taskmind.** {
    *** Companion;
    *** INSTANCE;
}

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
