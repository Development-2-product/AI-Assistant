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

# MSAL's Nimbus/YubiKey optional algorithms are selected only when their provider artifacts are
# present. IA Mode does not package those providers, so R8 must not require their absent classes.
-dontwarn com.google.crypto.tink.subtle.Ed25519Sign
-dontwarn com.google.crypto.tink.subtle.Ed25519Sign$KeyPair
-dontwarn com.google.crypto.tink.subtle.Ed25519Verify
-dontwarn com.google.crypto.tink.subtle.X25519
-dontwarn com.google.crypto.tink.subtle.XChaCha20Poly1305
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
