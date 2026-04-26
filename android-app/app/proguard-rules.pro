# Keep Room generated classes
-keep class androidx.room.RoomDatabase { *; }

# Hilt
-keep class dagger.hilt.android.internal.managers.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

# Kotlin coroutines - avoid stripping debug metadata
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
