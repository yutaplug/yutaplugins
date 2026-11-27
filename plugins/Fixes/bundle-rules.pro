# Shrink-only pass over the plugin classes plus bundled BouncyCastle (see build.gradle.kts).
-dontobfuscate
-dontoptimize
-keepattributes *
# R8 in build-tools 35 can't read Kotlin 2.3 metadata; the plugin classes are kept as-is anyway.
-dontnote kotlin.Metadata
-dontwarn kotlin.Metadata

# The plugin's own classes are kept exactly as compiled; they're only here so R8 knows what BouncyCastle code is used.
-keep class com.github.yutaplug.fixes.** { *; }

# bcmls decodes every message type reflectively through its MLSInputStream constructor: keep all of it.
-keep class org.bouncycastle.mls.** { *; }
-keepclassmembers class org.bouncycastle.** { <init>(org.bouncycastle.mls.codec.MLSInputStream); }

# Provided at runtime by Discord / Aliucord, not passed to R8.
-dontwarn com.discord.**
-dontwarn com.aliucord.**
-dontwarn co.discord.**
-dontwarn de.robv.android.xposed.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn rx.**
-dontwarn b.**
-dontwarn d0.**
-dontwarn f0.**
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.google.gson.**
-dontwarn java.lang.invoke.**
-dontwarn androidx.**
-dontwarn com.google.**
-dontwarn com.lytefast.**
# Remaining unresolved references are Discord's own obfuscated packages, which exist at runtime.
-ignorewarnings
