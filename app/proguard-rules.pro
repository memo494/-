# R8 rules for the shared (release) build.

# onnxruntime's Java classes are created and called from its native library by name.
-keep class ai.onnxruntime.** { *; }

# Our own code is small and some of it is reached from the manifest and accessibility XML only; keep
# it whole so shrinking only trims the libraries (AppCompat, Material, coroutines, ML Kit).
-keep class com.clu.quickanswer.** { *; }

# Kotlin metadata is not needed at run time.
-dontwarn kotlin.**

# ML Kit finds its components through manifest metadata and reflection. Its own consumer rules
# should cover that, but this build cannot be tried on a phone before it is shared: keep it whole.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
