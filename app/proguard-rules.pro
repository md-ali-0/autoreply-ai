# Release builds are minified (isMinifyEnabled = true), so the rules below matter.
# Debug builds ignore this file entirely, which is why forgetting one of these shows
# up only in release — the classic "works on my machine, crashes when shipped".

-dontwarn okhttp3.**
-dontwarn okio.**

# ---------------------------------------------------------------- Android entry points
# These are instantiated by the framework from the manifest, by name. R8 cannot see
# that, so it renames or removes them and the app dies at launch with a
# ClassNotFoundException.
-keep class com.claw.autoreplyai.App { *; }
-keep class com.claw.autoreplyai.MainActivity { *; }
-keep class com.claw.autoreplyai.SendAccessibilityService { *; }
-keep class com.claw.autoreplyai.WhatsAppNotificationListener { *; }
-keep class com.claw.autoreplyai.KeepAliveService { *; }
-keep class com.claw.autoreplyai.BootReceiver { *; }

# Accessibility services are described entirely by XML metadata, so the framework only
# ever touches these members reflectively.
-keep class * extends android.accessibilityservice.AccessibilityService {
    public <init>(...);
}
-keep class * extends android.service.notification.NotificationListenerService {
    public <init>(...);
}
-keep class * extends android.app.Service { public <init>(...); }
-keep class * extends android.content.BroadcastReceiver { public <init>(...); }
-keep class * extends android.app.Application { public <init>(...); }
-keep class * extends android.app.Activity { public <init>(...); }

# ViewBinding inflates layouts by the generated binding class name.
-keep class com.claw.autoreplyai.databinding.** { *; }

# ---------------------------------------------------------------- state serialisation
# Everything persisted or restored goes through org.json, which reads and writes fields
# reflectively. Renaming a field here silently changes the on-disk format, so the app
# would appear to "forget" all its state after an update.
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*

# Kotlin metadata, needed for reflection over data classes and coroutines.
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Enum values are looked up by name when parsing stored state.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
