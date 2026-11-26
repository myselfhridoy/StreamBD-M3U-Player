package com.streambd.iptv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamParsersTest {

    @Test fun seriesList() {
        val list = XtreamParsers.series(
            """[
              {"series_id":10,"name":"Show A","cover":"http://c/a.jpg","category_id":"3","rating":"7.5","releaseDate":"2019-04-01"},
              {"series_id":"11","name":"","category_id":null,"release_date":"2021-01-01"},
              {"name":"no id"},
              "garbage"
            ]""",
        )
        assertEquals(2, list.size)
        assertEquals(10, list[0].seriesId)
        assertEquals("Show A", list[0].name)
        assertEquals("2019", list[0].year)
        assertEquals("3", list[0].categoryId)
        assertEquals(11, list[1].seriesId)
        assertEquals("Series 11", list[1].name)
        assertNull(list[1].categoryId)
        assertEquals("2021", list[1].year)
    }

    @Test fun seriesListRejectsNonJson() {
        val failed = try { XtreamParsers.series("<html>"); false } catch (e: XtreamException) { true }
        assertTrue(failed)
    }

    @Test fun episodesAsObjectBySeason() {
        val eps = XtreamParsers.episodes(
            """{"episodes":{
              "2":[{"id":"22","title":"S2E2","episode_num":2,"container_extension":"mkv","info":{"movie_image":"http://i/2.jpg","duration":"45:00","rating":"8.1"}}],
              "1":[{"id":"12","title":"S1E2","episode_num":"2"},{"id":"11","title":"S1E1","episode_num":1,"season":1}]
            }}""",
        )
        assertEquals(listOf("11", "12", "22"), eps.map { it.id })
        assertEquals(listOf(1, 1, 2), eps.map { it.season })
        assertEquals(listOf(1, 2, 2), eps.map { it.episode })
        val last = eps.last()
        assertEquals("mkv", last.extension)
        assertEquals("http://i/2.jpg", last.image)
        assertEquals("45:00 · ★ 8.1", last.subtitle)
    }

    @Test fun episodesAsArrayOfArrays() {
        val eps = XtreamParsers.episodes(
            """{"episodes":[[{"id":"1","title":"a","episode_num":1}],[{"id":"2","title":"b","episode_num":1}]]}""",
        )
        assertEquals(listOf(1, 2), eps.map { it.season })
    }

    @Test fun episodesMissingOrEmpty() {
        assertTrue(XtreamParsers.episodes("""{"info":{}}""").isEmpty())
        assertTrue(XtreamParsers.episodes("""{"episodes":[]}""").isEmpty())
        assertTrue(XtreamParsers.episodes("""{"episodes":{}}""").isEmpty())
    }

    @Test fun episodeWithoutIdIsSkipped() {
        assertTrue(XtreamParsers.episodes("""{"episodes":{"1":[{"title":"no id"}]}}""").isEmpty())
    }

    @Test fun episodesRejectNonJsonObject() {
        val failed = try { XtreamParsers.episodes("[]"); false } catch (e: XtreamException) { true }
        assertTrue(failed)
    }
}
