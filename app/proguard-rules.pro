# WebView bridges are called by name from JavaScript
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-dontwarn com.google.android.gms.**
# PDFBox (text extraction for selecting and copying PDF text); it loads fonts, filters and glyph lists by name
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
