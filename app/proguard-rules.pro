# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Media3
-keep class androidx.media3.** { *; }

# ── Release logging hardening (WD-04) ─────────────────────────────────────────
# Strip verbose/debug/info logging from release builds. proguard-android-optimize
# enables the optimization pass that honours -assumenosideeffects, so these calls
# are removed entirely (arguments included) rather than merely no-op'd.
# Log.w / Log.e / Log.wtf are intentionally preserved for release diagnostics.
# Sensitive-metadata logs (SAF URIs, filenames, counts) are ALSO gated behind
# BuildConfig.DEBUG in source so they never execute in debug either — this rule
# is defence-in-depth, not the sole guard.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
