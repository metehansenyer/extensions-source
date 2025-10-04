package eu.kanade.tachiyomi.extension.tr.koreliscans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import java.text.SimpleDateFormat
import java.util.Locale

class KoreliScans : MangaThemesia(
    "Koreli Scans",
    "https://koreliscans.net",
    "tr",
    dateFormat = SimpleDateFormat("d MMMM yyyy", Locale("tr")),
) {
    open val pageListImageSelector = "div#readerarea img"

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        return document.select(pageListImageSelector).mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("src"))
        }
    }
}
