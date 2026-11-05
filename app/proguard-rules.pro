# Crash stacktrace পড়ার জন্য
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# Room entity/DAO
-keep class com.streambd.iptv.data.db.** { *; }

# OkHttp ঐচ্ছিক TLS প্রোভাইডার
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# app/proguard-rules.pro -এর শেষে এই লাইন যোগ করুন (FFmpeg রেন্ডারার রিফ্লেকশনে লোড হয়, R8 যেন মুছে না ফেলে)
-keep class androidx.media3.decoder.ffmpeg.** { *; }
