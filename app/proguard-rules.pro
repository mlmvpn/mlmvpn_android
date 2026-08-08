# ============================================================================
# R8 / ProGuard rules — conservative: shrink unused LIBRARY code (androidx,
# compose, okhttp, gson internals, etc.) while keeping ALL app code and the
# native Xray core fully intact, so runtime behavior is unchanged.
# ============================================================================

# --- Keep the entire app (no obfuscation/removal of our own classes) --------
# App code is small compared to libraries; keeping it removes almost all risk
# of reflection/JNI/Gson breakage while libraries still get shrunk.
-keep class com.mlmvpn.** { *; }
-keep class com.therealaleph.** { *; }
-keepclassmembers class com.mlmvpn.** { *; }

# --- Native Xray core (also declared by the AAR's own consumer rules) -------
-keep class go.** { *; }
-keep class libv2ray.** { *; }

# --- OpenVPN core (VPN Gate feature) ----------------------------------------
# net.openvpn.ovpn3.* is SWIG-generated: libovpn3.so instantiates these classes
# and invokes their methods by name from C++, so R8 must not rename or drop any
# of it. com.tim.** carries the service/config/Parcelable layer, plus
# DefaultVpnServiceNotification which is constructed reflectively from the class
# name string VpnGateEngine passes across to the :openvpn process.
-keep class net.openvpn.ovpn3.** { *; }
-keep class com.tim.** { *; }
-dontwarn com.tim.**

# --- JNI native methods -----------------------------------------------------
-keepclasseswithmembernames class * { native <methods>; }

# --- SoftEther SSL-VPN client (vendored kittoku.mvc, Apache-2.0) ------------
# Kept unobfuscated so its stack traces stay readable: this is protocol code where the only
# practical diagnosis is the exact class and line an exception came from, and R8's renaming
# turned those into "h2.b.a(SourceFile:640)".
-keep class kittoku.** { *; }

# --- Reflection-friendly attributes (Gson, coroutines, generics) ------------
# SourceFile + LineNumberTable keep release stack traces usable.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, Exceptions, SourceFile, LineNumberTable

# --- Enums (valueOf / values used reflectively) -----------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Parcelable / Serializable ----------------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable { *; }

# --- Gson (uses generic type info + no-arg construction) --------------------
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# --- OkHttp / Okio (ship their own rules; silence platform warnings) --------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- ZXing (QR) -------------------------------------------------------------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# --- Misc: don't fail on unresolved optional references ---------------------
-dontwarn go.**
-dontwarn libv2ray.**
-dontwarn javax.annotation.**
