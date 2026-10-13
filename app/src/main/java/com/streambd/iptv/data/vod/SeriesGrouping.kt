package com.streambd.iptv.data.vod

import com.streambd.iptv.data.db.ChannelEntity

data class SeriesGroup(
    val name: String,
    val poster: String?,
    val episodeCount: Int,
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
)

object SeriesGrouping {

    fun groups(items: List<ChannelEntity>): List<SeriesGroup> =
        items.groupBy { SeriesTitleParser.parse(it.name).series }
            .map { (name, episodes) ->
                SeriesGroup(name, episodes.firstOrNull()?.logoUrl, episodes.size)
            }
            .sortedBy { it.name.lowercase() }

    fun episodes(items: List<ChannelEntity>, seriesName: String): List<EpisodeEntry> =
        items.mapNotNull { channel ->
            val parsed = SeriesTitleParser.parse(channel.name)
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
            )
        }.sortedWith(
            compareBy(
                { it.season ?: Int.MAX_VALUE },
                { it.episode ?: Int.MAX_VALUE },
                { it.fullName },
            ),
        )

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