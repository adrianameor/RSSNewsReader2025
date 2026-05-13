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

-dontwarn org.slf4j.impl.StaticLoggerBinder
# Keep Retrofit interfaces (VERY IMPORTANT)
-keep interface com.adriana.newscompanion.data.deepseek.** { *; }

# Keep model classes (DeepSeekResponse etc)
-keep class com.adriana.newscompanion.data.deepseek.** { *; }

# Keep generic signatures (CRITICAL)
-keepattributes Signature

# Keep RxJava
-keep class io.reactivex.rxjava3.** { *; }

# Keep Retrofit
-keep class retrofit2.** { *; }

# Keep Gson models
-keep class com.google.gson.** { *; }

# Protect WebViewClient callbacks from R8 stripping
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String);
    public boolean *(android.webkit.WebView, java.lang.String);
    public void *(android.webkit.WebView, android.webkit.WebResourceRequest);
}

# Protect TtsExtractor WebClient inner class
-keep class com.adriana.newscompanion.service.tts.TtsExtractor$* { *; }
-keep class com.adriana.newscompanion.service.tts.TtsExtractor { *; }

# Protect LoginWebViewActivity WebClient
-keep class com.adriana.newscompanion.ui.loginwebview.LoginWebViewActivity$* { *; }

# Protect WebViewActivity WebClient
-keep class com.adriana.newscompanion.ui.webview.WebViewActivity$* { *; }

# Keep Readability4J
-keep class net.dankito.readability4j.** { *; }
-keep public class org.jsoup.** { public *; }

-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable

-keep class net.dankito.** { *; }
-dontwarn net.dankito.**

-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, android.webkit.WebResourceRequest);
}

-keep class com.adriana.newscompanion.data.feed.Feed { *; }
-keep class com.adriana.newscompanion.data.entry.Entry { *; }
-keep class com.adriana.newscompanion.data.feed.FeedDao { *; }
-keep class com.adriana.newscompanion.data.entry.EntryDao { *; }