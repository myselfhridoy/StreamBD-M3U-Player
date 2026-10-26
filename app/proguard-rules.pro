# Crash stacktrace পড়ার জন্য
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# Room entity/DAO
-keep class com.streambd.iptv.data.db.** { *; }

# OkHttp ঐচ্ছিক TLS প্রোভাইডার
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
