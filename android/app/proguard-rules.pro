# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.sharptalons.obsdirector.**$$serializer { *; }
-keepclassmembers class dev.sharptalons.obsdirector.** { *** Companion; }
-keepclasseswithmembers class dev.sharptalons.obsdirector.** { kotlinx.serialization.KSerializer serializer(...); }
