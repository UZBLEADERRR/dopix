# Add project specific ProGuard rules here.
# For OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# For JSON
-keepattributes Signature
-keepattributes *Annotation*
-keep class org.json.** { *; }

# For Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep Dopix state classes
-keep class com.dopix.app.DopixState { *; }
-keep class com.dopix.app.services.** { *; }
