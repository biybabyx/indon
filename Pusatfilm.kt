package com.Pusatfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Pusatfilm : MainAPI() {
    override var mainUrl     = "https://pf21.net"
    override var name        = "Pusatfilm"
    override val lang        = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/film-terbaru/page/%d/"           to "🎬 Film Terbaru",
        "$mainUrl/trending/page/%d/"               to "🔥 Trending",
        "$mainUrl/series-terbaru/page/%d/"         to "📺 TV Series",
        "$mainUrl/genre/action/page/%d/"           to "Action",
        "$mainUrl/genre/animation/page/%d/"        to "Animation",
        "$mainUrl/genre/comedy/page/%d/"           to "Comedy",
        "$mainUrl/genre/drama/page/%d/"            to "Drama",
        "$mainUrl/genre/fantasy/page/%d/"          to "Fantasy",
        "$mainUrl/genre/horror/page/%d/"           to "Horror",
        "$mainUrl/genre/romance/page/%d/"          to "Romance",
        "$mainUrl/genre/sci-fi/page/%d/"           to "Sci-Fi",
        "$mainUrl/genre/thriller/page/%d/"         to "Thriller",
        "$mainUrl/country/indonesia/page/%d/"      to "🇮🇩 Indonesia",
        "$mainUrl/country/south-korea/page/%d/"    to "🇰🇷 Korea",
        "$mainUrl/country/japan/page/%d/"          to "🇯🇵 Jepang",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc   = app.get(request.data.format(page)).document
        val items = doc.select(".item, article.item").mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, items)
    }

    private fun toResult(el: Element): SearchResponse? {
        val title  = el.selectFirst("h2 a, .title a")?.text() ?: return null
        val href   = el.selectFirst("a")?.attr("href") ?: return null
        val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        val type   = if (href.contains("/series/") || href.contains("/tv/")) TvType.TvSeries else TvType.Movie
        return if (type == TvType.TvSeries)
            newTvSeriesSearchResponse(title, href, type) { posterUrl = poster }
        else
            newMovieSearchResponse(title, href, type) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/?s=$query").document.select(".item").mapNotNull { toResult(it) }

    override suspend fun load(url: String): LoadResponse? {
        val doc    = app.get(url).document
        val title  = doc.selectFirst("h1, .entry-title")?.text() ?: return null
        val poster = doc.selectFirst(".poster img, .thumb img")?.attr("src")
        val desc   = doc.selectFirst(".synopsis, .entry-content p")?.text()
        val year   = doc.selectFirst(".year, .date")?.text()?.take(4)?.toIntOrNull()
        val tags   = doc.select(".genres a, .category a").map { it.text() }

        val epLinks = doc.select(".episodelist a, .eps a")
        return if (epLinks.isNotEmpty()) {
            var season = 1
            val episodes = mutableListOf<Episode>()
            doc.select(".season-list, .episodelist").forEachIndexed { sIdx, seasonEl ->
                seasonEl.select("a").forEachIndexed { eIdx, ep ->
                    episodes.add(Episode(
                        data    = ep.attr("href"),
                        name    = ep.text().ifEmpty { "Episode ${eIdx + 1}" },
                        season  = sIdx + 1,
                        episode = eIdx + 1
                    ))
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster; plot = desc; this.year = year; this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; plot = desc; this.year = year; this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        // Server dropdown
        doc.select(".server-list a, ul#dropdown-server li a").forEach { a ->
            val encoded = a.attr("data-frame")
            if (encoded.isNotEmpty()) {
                // decode base64 lalu load extractor (sama seperti Pusatfilm asli)
                val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
                loadExtractor(decoded, data, subtitleCallback, callback)
            } else {
                val href = a.attr("href")
                if (href.isNotEmpty()) loadExtractor(href, data, subtitleCallback, callback)
            }
        }
        // Fallback iframe langsung
        doc.select("iframe[src]").forEach {
            loadExtractor(it.attr("src"), data, subtitleCallback, callback)
        }
        return true
    }
}
