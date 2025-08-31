package eu.kanade.tachiyomi.extension.tr.uzaymanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.lib.randomua.getPrefCustomUA
import eu.kanade.tachiyomi.lib.randomua.getPrefUAType
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class UzayManga : HttpSource() {
    override val name = "Uzay Manga"

    override val baseUrl = "https://uzaymanga.com"

    override val lang = "tr"

    override val supportsLatest = true

    override val versionId = 3

    private val preferences = getPreferences()

    override fun headersBuilder() = super.headersBuilder()
        .set("referer", "$baseUrl/")
        .set("origin", baseUrl)

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(1)
        .setRandomUserAgent(
            preferences.getPrefUAType(),
            preferences.getPrefCustomUA(),
        )
        .build()

    private val json: Json by injectLazy()

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/search?page=$page&order=4", headers)

    override fun popularMangaNextPageSelector() =
        "section[aria-label='navigation'] li:has(a[aria-current='page']) + li > a:not([href='#'])"

    override fun popularMangaSelector() = "section[aria-label='series area'] .card"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val linkElement = element.selectFirst("a")
            ?: throw Exception("Link element not found")
        
        title = element.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("Manga title not found")
        
        thumbnail_url = element.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
        
        val href = linkElement.absUrl("href").takeIf { it.isNotBlank() }
            ?: throw Exception("Manga URL not found")
        setUrlWithoutDomain(href)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/search?page=$page&order=3", headers)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith(URL_SEARCH_PREFIX)) {
            val url = "$baseUrl/manga/${query.substringAfter(URL_SEARCH_PREFIX)}"
            return client.newCall(GET(url, headers)).asObservableSuccess().map { response ->
                val document = response.asJsoup()
                when {
                    isMangaPage(document) -> MangasPage(listOf(mangaDetailsParse(document)), false)
                    else -> MangasPage(emptyList(), false)
                }
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            "$CDN_URL/series/search/navbar".toHttpUrl().newBuilder()
                .addQueryParameter("search", query.trim())
                .build()
        } else {
            "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .apply {
                    filters.forEach { filter ->
                        when (filter) {
                            is GenreFilter -> {
                                if (filter.state != 0) {
                                    addQueryParameter("categories", filter.toUriPart())
                                }
                            }
                            is StatusFilter -> {
                                if (filter.state != 0) {
                                    addQueryParameter("publicStatus", filter.toUriPart())
                                }
                            }
                            is CountryFilter -> {
                                if (filter.state != 0) {
                                    addQueryParameter("country", filter.toUriPart())
                                }
                            }
                            is SortFilter -> {
                                addQueryParameter("order", filter.toUriPart())
                            }
                        }
                    }
                }
                .build()
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return if (response.request.url.toString().contains("/series/search/navbar")) {
            // JSON API response
            try {
                val responseBody = response.body.string()
                if (responseBody.isBlank()) return MangasPage(emptyList(), false)
                
                val dto = json.decodeFromString<List<SearchDto>>(responseBody)
                val mangas = dto.mapNotNull { searchItem ->
                    try {
                        SManga.create().apply {
                            title = searchItem.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            thumbnail_url = "$CDN_URL/${searchItem.image}".takeIf { searchItem.image.isNotBlank() }
                            url = "/manga/${searchItem.id}/${searchItem.name.toSlug()}"
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                MangasPage(mangas, false)
            } catch (e: Exception) {
                MangasPage(emptyList(), false)
            }
        } else {
            // HTML page response
            try {
                val document = response.asJsoup()
                val mangas = document.select(popularMangaSelector()).mapNotNull { element ->
                    try {
                        popularMangaFromElement(element)
                    } catch (e: Exception) {
                        null
                    }
                }
                val hasNextPage = document.selectFirst(popularMangaNextPageSelector()) != null
                MangasPage(mangas, hasNextPage)
            } catch (e: Exception) {
                MangasPage(emptyList(), false)
            }
        }
    }

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaSelector() = popularMangaSelector()

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        val contentElement = document.selectFirst("#content") ?: document.body()
        
        title = contentElement.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("Manga title not found")
        
        // Daha güvenilir thumbnail seçici
        thumbnail_url = contentElement.selectFirst("img[src*='thumbnail']")?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: contentElement.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
        
        // Genre'leri daha güvenli şekilde parse et
        val genreElements = contentElement.select("a[href*='categories'], .p-1\\.5, a[href*='search\\?categories']")
        genre = genreElements.eachText().filter { it.isNotBlank() }.joinToString(", ")
        
        // Description'ı farklı selector'larla dene
        description = contentElement.selectFirst(".summary p, .text-sm.xl\\:text-lg p, div.grid h2 + p, .whitespace-pre-wrap p")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        
        // Status parsing'i iyileştir
        val statusElement = contentElement.select("div.flex.justify-between").find { 
            it.selectFirst("span")?.text()?.contains("Durum", ignoreCase = true) == true 
        }
        val statusText = statusElement?.selectFirst("span:last-child")?.text() ?: ""
        
        status = when {
            statusText.contains("Devam Ediyor", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Tamamlandi", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("Ara Verildi", ignoreCase = true) -> SManga.ON_HIATUS
            statusText.contains("Bırakıldı", ignoreCase = true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        
        // Author bilgisini ekle
        author = contentElement.select("div.flex.justify-between").find {
            it.selectFirst("span")?.text()?.contains("Tarafından", ignoreCase = true) == true
        }?.selectFirst("span:last-child")?.text()?.trim()

        setUrlWithoutDomain(document.location())
    }

    override fun chapterListSelector() = "div.list-episode a"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        // Chapter name parsing iyileştirmesi
        name = element.selectFirst("h3, .chapternum, b")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: element.text().substringBefore("20").trim().takeIf { it.isNotBlank() }
            ?: "Bölüm"
        
        // Date parsing iyileştirmesi
        date_upload = element.selectFirst("span.text-slate-400, .text-slate-400, span")
            ?.text()?.toDate() ?: 0L
        
        val href = element.absUrl("href").takeIf { it.isNotBlank() }
            ?: throw Exception("Bölüm URL'i bulunamadı")
        setUrlWithoutDomain(href)
    }

    override fun pageListParse(document: Document): List<Page> {
        val script = document.select("script")
            .map { it.html() }.firstOrNull { pageRegex.find(it) != null }
            ?: return emptyList()

        return pageRegex.findAll(script).mapIndexed { index, result ->
            val url = result.groups.get(1)!!.value
            Page(index, document.location(), "$CDN_URL/$url")
        }.toList()
    }

    override fun imageUrlParse(document: Document) = ""

    private fun isMangaPage(document: Document): Boolean =
        document.selectFirst(".summary, .content-details, div.grid h2 + p") != null

    private fun String.toDate(): Long {
        return try {
            // Türkçe ay isimlerini İngilizce'ye çevir
            val normalizedDate = this.trim()
                .replace("Ocak", "Jan")
                .replace("Şubat", "Feb")
                .replace("Mart", "Mar")
                .replace("Nisan", "Apr")
                .replace("Mayıs", "May")
                .replace("Haziran", "Jun")
                .replace("Temmuz", "Jul")
                .replace("Ağustos", "Aug")
                .replace("Eylül", "Sep")
                .replace("Ekim", "Oct")
                .replace("Kasım", "Nov")
                .replace("Aralık", "Dec")
                .replace(",", "")
            
            dateFormat.parse(normalizedDate)?.time ?: 0L
        } catch (e: ParseException) {
            0L
        }
    }

    private fun String.contains(vararg fragment: String): Boolean =
        fragment.any { trim().contains(it, ignoreCase = true) }

    private fun String.toSlug(): String = 
        this.lowercase().trim()
            .replace(" ", "-")
            .replace("ş", "s")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ö", "o")
            .replace("ç", "c")
            .replace("ı", "i")
            .replace(Regex("[^a-z0-9-]"), "")

    // Filter sınıfları
    override fun getFilterList() = FilterList(
        Filter.Header("Arama filtreleri"),
        GenreFilter(),
        StatusFilter(),
        CountryFilter(),
        SortFilter()
    )

    private class GenreFilter : Filter.Select<String>(
        "Kategori",
        arrayOf(
            "Hepsi", "Aksiyon", "Avcı", "Büyü", "Canavar", "Çete", "Doğaüstü", 
            "Dövüş", "Dövüş-sanatları", "Dram", "Fantastik", "Geri-dönüş", 
            "Harem", "Komedi", "Macera", "Manhwa", "Murim", "Okul", "Reankarnasyon", 
            "Romantik", "Sistem", "Shounen"
        )
    ) {
        fun toUriPart() = values[state]
    }

    private class StatusFilter : Filter.Select<String>(
        "Durum",
        arrayOf("Hepsi", "Devam Ediyor", "Tamamlandı", "Ara Verdi")
    ) {
        fun toUriPart() = when (state) {
            1 -> "3" // Devam Ediyor
            2 -> "1" // Tamamlandı  
            3 -> "2" // Ara Verdi
            else -> ""
        }
    }

    private class CountryFilter : Filter.Select<String>(
        "Ülke",
        arrayOf("Hepsi", "Kore", "Japonya", "Çin")
    ) {
        fun toUriPart() = when (state) {
            1 -> "2" // Kore
            2 -> "3" // Japonya
            3 -> "1" // Çin
            else -> ""
        }
    }

    private class SortFilter : Filter.Select<String>(
        "Sıralama",
        arrayOf("Popülerlik", "Yeni Eklenen", "Alfabetik", "Puan")
    ) {
        fun toUriPart() = when (state) {
            0 -> "4" // Popülerlik
            1 -> "3" // Yeni Eklenen
            2 -> "1" // Alfabetik
            3 -> "2" // Puan
            else -> "4"
        }
    }

    companion object {
        const val CDN_URL = "https://manga2.efsaneler.can.re"
        const val URL_SEARCH_PREFIX = "slug:"
        private val dateFormat = SimpleDateFormat("MMM d ,yyyy", Locale("tr"))
        private val pageRegex = """\\"path\\":\\"([^"]+)\\""".trimIndent().toRegex()
    }
}

@Serializable
class SearchDto(val id: Int, val name: String, val image: String)
