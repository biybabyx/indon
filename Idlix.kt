package com.Idlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class Idlix : MainAPI() {
    override var mainUrl     = "https://tv.idlix.asia"
    override var name        = "IDLIX"
    override val lang        = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/trending/page/%d/"           to "🔥 Trending",
        "$mainUrl/movie/page/%d/"              to "🎬 Film",
        "$mainUrl/tvseries/page/%d/"           to "📺 TV Series",
        "$mainUrl/genre/action/page/%d/"       to "Action",
        "$mainUrl/genre/anime/page/%d/"        to "Anime",
        "$mainUrl/genre/comedy/page/%d/"       to "Comedy",
        "$mainUrl/genre/drama/page/%d/"        to "Drama",
        "$mainUrl/genre/horror/page/%d/"       to "Horror",
        "$mainUrl/genre/romance/page/%d/"      to "Romance",
        "$mainUrl/genre/sci-fi/page/%d/"       to "Sci-Fi",
        "$mainUrl/genre/thriller/page/%d/"     to "Thriller",
        "$mainUrl/country/indonesia/page/%d/"  to "🇮🇩 Indonesia",
        "$mainUrl/country/korea/page/%d/"      to "🇰🇷 Korea",
        "$mainUrl/country/japan/page/%d/"      to "🇯🇵 Jepang",
        "$mainUrl/country/china/page/%d/"      to "🇨🇳 China",
        "$mainUrl/country/usa/page/%d/"        to "🇺🇸 Amerika",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc   = app.get(request.data.format(page)).document
        val items = doc.select(".data").mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, items)
    }

    private fun toResult(el: Element): SearchResponse? {
        val title  = el.selectFirst("h3 a")?.text() ?: return null
        val href   = el.selectFirst("a")?.attr("href") ?: return null
        val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        val type   = if (href.contains("/tvseries/")) TvType.TvSeries else TvType.Movie
        return if (type == TvType.TvSeries)
            newTvSeriesSearchResponse(title, href, type) { posterUrl = poster }
        else
            newMovieSearchResponse(title, href, type) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/?s=$query").document.select(".data").mapNotNull { toResult(it) }

    override suspend fun load(url: String): LoadResponse? {
        val doc    = app.get(url).document
        val title  = doc.selectFirst(".sheader h1, h1.title")?.text() ?: return null
        val poster = doc.selectFirst(".poster img")?.attr("src")
        val desc   = doc.selectFirst(".wp-content p, div[itemprop=description]")?.text()
        val year   = doc.selectFirst(".date")?.text()?.take(4)?.toIntOrNull()
        val rating = doc.selectFirst("[itemprop=ratingValue]")?.text()?.toFloatOrNull()
        val tags   = doc.select(".genres a").map { it.text() }
        val actors = doc.select(".persons .person").map {
            ActorData(Actor(it.selectFirst("img")?.attr("alt") ?: it.text()))
        }

        val isSeries = url.contains("/tvseries/")
        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            var seasonNum = 1
            doc.select(".se-c").forEach { season ->
                season.select(".episodios li").forEachIndexed { idx, ep ->
                    val epTitle = ep.selectFirst(".episodiotitle a")?.text() ?: "Episode ${idx + 1}"
                    val epHref  = ep.selectFirst("a")?.attr("href") ?: return@forEachIndexed
                    val epThumb = ep.selectFirst("img")?.attr("src")
                    val epNum   = ep.selectFirst(".numerando")?.text()
                        ?.split("-")?.lastOrNull()?.trim()?.toIntOrNull() ?: (idx + 1)
                    episodes.add(Episode(data = epHref, name = epTitle,
                        season = seasonNum, episode = epNum, posterUrl = epThumb))
                }
                seasonNum++
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster; plot = desc; this.year = year
                this.rating = rating; this.tags = tags
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; plot = desc; this.year = year
                this.rating = rating; this.tags = tags
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select(".dooplay_player_option").forEach { opt ->
            val post = opt.attr("data-post")
            val type = opt.attr("data-type")
            val nume = opt.attr("data-nume")
            if (post.isNotEmpty()) {
                val resp = app.post("$mainUrl/wp-admin/admin-ajax.php", data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post, "nume" to nume, "type" to type
                )).parsed<AjaxResponse>()
                resp?.embed_url?.let { loadExtractor(it, data, subtitleCallback, callback) }
            }
        }
        return true
    }

    data class AjaxResponse(val embed_url: String? = null)
}
