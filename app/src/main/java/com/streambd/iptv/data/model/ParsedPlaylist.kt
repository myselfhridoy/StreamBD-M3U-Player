package com.streambd.iptv.data.model

import com.streambd.iptv.data.m3u.ParsedChannel

/**
 * যেকোনো প্লেলিস্ট ফরম্যাট (M3U/JSON/ভবিষ্যতে XSPF) থেকে পার্স করার পর এই একটি কমন অবজেক্ট পাওয়া যাবে।
 * এতে চ্যানেলের লিস্ট এবং প্লেলিস্ট হেডার থেকে পাওয়া EPG লিংক থাকবে।
 */
data class ParsedPlaylist(
    val channels: List<ParsedChannel>,
    val epgUrls: List<String> = emptyList(),
    /** `#EXTM3U x-sb-folders="1"`: প্লেলিস্ট চায় Live TV-তে `A › B › C` ফোল্ডার হয়ে দেখাক। */
    val folders: Boolean = false,
)

