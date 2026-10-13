package com.streambd.iptv.data.vod

data class ParsedSeries(
    val series: String,
    val season: Int?,
    val episode: Int?,
    val title: String,
)

object SeriesTitleParser {

    private val patterns = listOf(
        Regex(
            """^(?<name>.+?)[\s._-]*[Ss](?<s>\d{1,2})[\s._-]*[Ee](?<e>\d{1,3})\b[\s._:-]*(?<title>.*)$""",
        ),
        Regex(
            """^(?<name>.+?)[\s._-]+(?<s>\d{1,2})[xX](?<e>\d{1,3})\b[\s._:-]*(?<title>.*)$""",
        ),
        Regex(
            """^(?<name>.+?)\s+[Ss]eason\s+(?<s>\d{1,2})\s+[Ee]pisode\s+(?<e>\d{1,3})\b[\s._:-]*(?<title>.*)$""",
            RegexOption.IGNORE_CASE,
        ),
    )

    fun parse(rawName: String): ParsedSeries {
        val name = rawName.trim()
        for (pattern in patterns) {
            val match = pattern.find(name) ?: continue
            val series = match.groups["name"]?.value
                ?.trim()
                ?.trimEnd('-', '.', '_', ' ')
                ?.trim()
                .orEmpty()
            if (series.isBlank()) continue

            val season = match.groups["s"]?.value?.toIntOrNull()
            val episode = match.groups["e"]?.value?.toIntOrNull()
            val title = match.groups["title"]?.value
                ?.trim()
                ?.trimStart('-', '.', ':', ' ')
                ?.trim()
                .orEmpty()
            return ParsedSeries(series, season, episode, title.ifBlank { name })
        }
        return ParsedSeries(name, null, null, name)
    }
}