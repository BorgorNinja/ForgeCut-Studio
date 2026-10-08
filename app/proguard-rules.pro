# ─── ForgeCut Studio ProGuard / R8 Rules ─────────────────────────────────

# Preserve Kotlin metadata and coroutines
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.coroutines.** { *; }

# Preserve Compose Runtime
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Preserve App Classes to avoid reflection or state issues
-keep class dev.forgecut.** { *; }
-keep class * extends android.app.Activity

# Strip verbose and debug logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

-dontwarn androidx.media3.**
