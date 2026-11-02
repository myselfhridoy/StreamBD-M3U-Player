package com.streambd.iptv.data.ftp

/** ফোল্ডার/ফাইলের তালিকা সাজানোর জন্য: সংখ্যা-সচেতন (S01E2 < S01E10)। */
val NaturalOrder: Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return@Comparator na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return@Comparator c
            i = ei; j = ej
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return@Comparator c
            i++; j++
        }
    }
    (a.length - i) - (b.length - j)
}

fun sortEntries(entries: List<FtpEntry>): List<FtpEntry> =
    entries.sortedWith(compareBy<FtpEntry> { !it.isDir }.thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) })
