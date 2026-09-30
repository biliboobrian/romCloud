# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.romcloud.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.romcloud.app.**$$serializer { *; }

# LibretroDroid : classes appelées depuis le code natif (JNI).
-keep class com.swordfish.libretrodroid.** { *; }
