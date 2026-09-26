# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.iamode.app.**$$serializer { *; }
-keepclassmembers class com.iamode.app.** { *** Companion; }
-keepclasseswithmembers class com.iamode.app.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# SQLCipher
-keep class net.zetetic.database.** { *; }

# pdfbox-android: optional JPEG2000 codec is not bundled
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder

# MSAL (Outlook sign-in) uses reflection/Gson for its config and cache
-keep class com.microsoft.identity.** { *; }
-dontwarn com.microsoft.identity.**
-dontwarn com.microsoft.device.display.**
