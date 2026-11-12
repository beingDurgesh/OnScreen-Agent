-keep class com.onscreen.agent.** { *; }
-dontwarn org.bouncycastle.**
# Tink (pulled in by androidx.security:security-crypto) references ErrorProne
# annotations that are compile-time only and never present at runtime.
-dontwarn com.google.errorprone.annotations.**
