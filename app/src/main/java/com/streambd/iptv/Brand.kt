package com.streambd.iptv

/**
 * অ্যাপের ব্র্যান্ডের একমাত্র উৎস। নাম বা User-Agent বদলাতে শুধু এই ফাইল বদলান।
 *
 * এখানে নেই (ইচ্ছাকৃত): applicationId, Kotlin প্যাকেজ, ডাটাবেস ফাইলের নাম, সার্ভার/ওয়ার্কার।
 * লঞ্চারে যে নাম দেখায় সেটা res/values/strings.xml এর app_name থেকে আসে (আলাদা, ম্যানিফেস্ট পড়ে)।
 */
object Brand {
    /** UI-তে দেখানোর পুরো নাম */
    const val NAME = "StreamBD IPTV Player"

    /** ছোট নাম (User-Agent ইত্যাদিতে) */
    const val SHORT_NAME = "StreamBD Play"

    /** Settings > About এ দেখানো ভার্সন */
    const val VERSION = "1.0.0"

    /** প্লেয়ার ও Xtream অনুরোধের ডিফল্ট User-Agent */
    const val USER_AGENT = "$SHORT_NAME/1.0"
}
