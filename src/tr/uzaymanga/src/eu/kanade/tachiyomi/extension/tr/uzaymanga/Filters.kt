package eu.kanade.tachiyomi.extension.tr.uzaymanga

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

abstract class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
    defaultValue: String? = null,
) : Filter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
    options.indexOfFirst { it.second == defaultValue }.coerceAtLeast(0),
) {
    val selected get() = options[state].second.takeUnless { it.isBlank() }
}

class StatusFilter : SelectFilter(
    "Status",
    listOf(
        "",
        "Devam Ediyor",
        "Tamamlandi",
        "Bırakıldı",
        "Ara Verildi",
    ).map { it to it },
)

class OrderFilter(default: String? = null) : SelectFilter(
    "Order by",
    listOf(
        "" to "",
        "A-Z" to "az",
        "Z-A" to "za",
        "Yeni Eklenenler" to "latest",
        "Popülerlik" to "popular",
    ),
    default,
) {
    companion object {
        val LATEST = FilterList(OrderFilter("latest"))
        val POPULAR = FilterList(OrderFilter("popular")) 
    }
}

fun getFilters() = FilterList(
    StatusFilter(),
    OrderFilter(),
)
