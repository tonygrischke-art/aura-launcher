# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep Compose runtime and UI classes
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations

# Keep BuildConfig for HermesClient endpoint
-keep class com.aetheria.aura.BuildConfig { *; }

# Keep AuraAction sealed class hierarchy for JSON parsing
-keep class com.aetheria.aura.actions.AuraAction** { *; }

# Keep ActionExecutor for runtime execution
-keep class com.aetheria.aura.actions.ActionExecutor { *; }

# Keep HermesClient for network calls
-keep class com.aetheria.aura.net.HermesClient { *; }

# Keep WakeWordService for foreground service
-keep class com.aetheria.aura.wakeWord.WakeWordService { *; }

# Keep BootReceiver for boot completion
-keep class com.aetheria.aura.BootReceiver { *; }

# Keep AuraApplication
-keep class com.aetheria.aura.AuraApplication { *; }

# Keep MainActivity and AuraHomeScreen
-keep class com.aetheria.aura.MainActivity { *; }
-keep class com.aetheria.aura.avatar.AuraHomeScreen { *; }
-keep class com.aetheria.aura.avatar.AuraFigure { *; }
-keep class com.aetheria.aura.avatar.AuraMood { *; }
-keep class com.aetheria.aura.avatar.AvatarState { *; }
-keep class com.aetheria.aura.avatar.AvatarRenderer { *; }
-keep class com.aetheria.aura.avatar.OrbRenderer { *; }