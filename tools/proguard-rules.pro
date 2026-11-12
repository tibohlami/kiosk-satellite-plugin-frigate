# Keep plugin classes intact without obfuscation so reflection / host entry works cleanly
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Plugin entry point & public plugin classes (non-shaded)
-keep class me.jxl.kiosk.plugins.frigate.FrigatePlugin { *; }
-keep class me.jxl.kiosk.plugins.frigate.api.** { *; }
-keep class me.jxl.kiosk.plugins.frigate.config.** { *; }
-keep class me.jxl.kiosk.plugins.frigate.model.** { *; }
-keep class me.jxl.kiosk.plugins.frigate.ui.** { *; }

# Keep shaded AndroidX / Media3 runtime reflection targets
-keepclassmembernames,allowobfuscation,allowshrinking class me.jxl.kiosk.plugins.frigate.shaded.androidx.core.view.ViewCompat$Api* { <methods>; }
-keepclassmembernames,allowobfuscation,allowshrinking class me.jxl.kiosk.plugins.frigate.shaded.androidx.core.view.WindowInsetsCompat$*Impl* { <methods>; }
-keepclassmembernames,allowobfuscation,allowshrinking class me.jxl.kiosk.plugins.frigate.shaded.androidx.core.app.NotificationCompat$*$Api*Impl { <methods>; }
-keepclassmembernames,allowobfuscation,allowshrinking class me.jxl.kiosk.plugins.frigate.shaded.androidx.core.os.UserHandleCompat$Api*Impl { <methods>; }
-keepclassmembernames,allowobfuscation,allowshrinking class me.jxl.kiosk.plugins.frigate.shaded.androidx.core.widget.EdgeEffectCompat$Api*Impl { <methods>; }

-keepclassmembers enum me.jxl.kiosk.plugins.frigate.shaded.androidx.lifecycle.Lifecycle$Event { <fields>; }
-keep !interface * implements me.jxl.kiosk.plugins.frigate.shaded.androidx.lifecycle.LifecycleObserver { }
-keepclassmembers class me.jxl.kiosk.plugins.frigate.shaded.androidx.lifecycle.ReportFragment$LifecycleCallbacks { *; }

-keep class * implements me.jxl.kiosk.plugins.frigate.shaded.androidx.versionedparcelable.VersionedParcelable
-keep public class me.jxl.kiosk.plugins.frigate.shaded.androidx.versionedparcelable.ParcelImpl

-keepclassmembernames class me.jxl.kiosk.plugins.frigate.shaded.guava.base.Function { *; }
-keepclassmembers class me.jxl.kiosk.plugins.frigate.shaded.androidx.media3.datasource.RawResourceDataSource {
  public static android.net.Uri buildRawResourceUri(int);
}
-keepclasseswithmembers class me.jxl.kiosk.plugins.frigate.shaded.androidx.media3.exoplayer.hls.HlsMediaSource$Factory {
  <init>(me.jxl.kiosk.plugins.frigate.shaded.androidx.media3.datasource.DataSource$Factory);
}

# Suppress warnings / notes for optional compile-only annotations and unused decoders/sources
-dontnote me.jxl.kiosk.plugins.frigate.shaded.androidx.media3.**
-dontwarn org.checkerframework.**
-dontwarn kotlin.annotations.jvm.**
-dontwarn javax.annotation.**
-dontwarn java.lang.ClassValue
-dontwarn java.lang.SafeVarargs
-dontwarn javax.lang.model.element.Modifier
-dontwarn sun.misc.Unsafe
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn me.jxl.kiosk.plugins.frigate.shaded.**
-dontwarn me.jxl.kiosk.plugin.sdk.**
