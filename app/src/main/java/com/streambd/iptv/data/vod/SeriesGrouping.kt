package com.streambd.iptv.data.vod

import com.streambd.iptv.data.db.ChannelEntity

data class SeriesGroup(
    val name: String,
    val poster: String?,
    val episodeCount: Int,
    /** lazy সিরিজ (add-on `lazy:true`): এপিসোড এখনও তালিকায় নেই; এই URL দিয়ে add-on-কে জিজ্ঞেস করতে হবে। */
    val lazyRef: String? = null,
    val lazyGroupId: Long = 0L,
)

data class EpisodeEntry(
    val id: Long,
    val season: Int?,
    val episode: Int?,
    val displayName: String,
    val fullName: String,
    val streamUrl: String,
    val logoUrl: String?,
    val isLocked: Boolean,
    val isFavorite: Boolean,
    val subtitle: String? = null,
    /** lazy এপিসোডের জন্য ডেটাবেসে সারি নেই; প্লেয়ারের জন্য এই অস্থায়ী ChannelEntity। */
    val lazyEntity: ChannelEntity? = null,
)

object SeriesGrouping {

    const val LAZY_MARK = "@lazy"

    fun groups(items: List<ChannelEntity>): List<SeriesGroup> =
        items.groupBy { seriesOf(it) }
            .map { (name, episodes) ->
                val lazy = episodes.firstOrNull { it.serieId == LAZY_MARK }
                if (lazy != null) SeriesGroup(name, lazy.logoUrl, 0, lazy.streamUrl, lazy.groupId)
                else SeriesGroup(name, episodes.firstOrNull()?.logoUrl, episodes.size)
            }
            .sortedBy { it.name.lowercase() }

    fun episodes(items: List<ChannelEntity>, seriesName: String): List<EpisodeEntry> =
        items.mapNotNull { channel ->
            if (channel.serieId == LAZY_MARK) return@mapNotNull null // lazy সিরিজের স্থানধারক সারি, এপিসোড নয়
            val parsed = parsedOf(channel)
            if (parsed.series != seriesName) return@mapNotNull null
            EpisodeEntry(
                id = channel.id,
                season = parsed.season,
                episode = parsed.episode,
                displayName = buildDisplayName(parsed),
                fullName = channel.name,
                streamUrl = channel.streamUrl,
                logoUrl = channel.logoUrl,
                isLocked = channel.isLocked,
                isFavorite = channel.isFavorite,
                subtitle = channel.subtitle,
            )
        }.sortedWith(
            compareBy(
                { it.season ?: Int.MAX_VALUE },
                { it.episode ?: Int.MAX_VALUE },
                { it.fullName },
            ),
        )

    /** add-on/প্লেলিস্ট যদি সিরিজের নাম (serie-title) ও সিজন/এপিসোড নম্বর দেয়, সেটাই সত্য; না হলে নাম থেকে আন্দাজ। */
    private fun parsedOf(c: ChannelEntity): ParsedSeries {
        val byName = SeriesTitleParser.parse(c.name)
        val series = c.serieTitle?.trim()?.takeIf { it.isNotEmpty() } ?: byName.series
        val title = if (series != byName.series) c.name else byName.title
        return ParsedSeries(series, c.season ?: byName.season, c.episode ?: byName.episode, title)
    }

    private fun seriesOf(c: ChannelEntity): String = parsedOf(c).series

    private fun buildDisplayName(parsed: ParsedSeries): String {
        val marker = when {
            parsed.season != null && parsed.episode != null -> "S%02dE%02d".format(parsed.season, parsed.episode)
            parsed.season != null -> "S%02d".format(parsed.season)
            parsed.episode != null -> "E%02d".format(parsed.episode)
            else -> null
        }
        return when {
            marker == null -> parsed.title
            parsed.title.isBlank() || parsed.title == parsed.series -> marker
            else -> "$marker · ${parsed.title}"
        }
    }
}
