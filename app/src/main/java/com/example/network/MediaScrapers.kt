package com.example.network

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.regex.Pattern

data class ScrapedGalleryResult(
    val title: String,
    val coverImage: String?,
    val images: List<String>
)

data class CoomerPostData(
    val id: String,
    val urls: List<String>,
    val thumbUrls: List<String>,
    val caption: String? = null,
    val mediaTypes: List<String> = emptyList(),
    val sourceService: String = "OnlyFans"
)

data class ScrapedCreatorResult(
    val name: String,
    val avatarUrl: String,
    val posts: List<CoomerPostData>,
    val service: String
)

data class ScrapedTorrent(
    val title: String,
    val magnetUrl: String,
    val size: String,
    val seeders: Int,
    val leechers: Int,
    val siteName: String
)

object MediaScrapers {
    private const val TAG = "MediaScrapers"

    // 1. Photoset Scraper (AdultPhotoSets, PornBox, FreeOnes, direct image list)
    suspend fun scrapeGallery(url: String): ScrapedGalleryResult {
        return try {
            val html = NetworkClient.getHtml(url)
            val doc = Jsoup.parse(html)
            val title = doc.title().replace(" - AdultPhotoSets", "").replace(" - FreeOnes", "").trim()
            val images = mutableListOf<String>()

            // Extract all image links and fullsize img tags
            val elements = doc.select("a[href~=(?i)\\.(png|jpe?g|webp)], img[src~=(?i)\\.(png|jpe?g|webp)], img[data-src~=(?i)\\.(png|jpe?g|webp)]")
            for (el in elements) {
                var imgUrl = el.attr("href")
                if (imgUrl.isEmpty() || !imgUrl.matches(Regex(".*\\.(jpg|jpeg|png|webp).*", RegexOption.IGNORE_CASE))) {
                    imgUrl = el.attr("data-src")
                }
                if (imgUrl.isEmpty()) {
                    imgUrl = el.attr("src")
                }
                if (imgUrl.isNotEmpty() && !imgUrl.contains("logo") && !imgUrl.contains("banner") && !imgUrl.contains("icon")) {
                    val fullUrl = if (imgUrl.startsWith("//")) "https:$imgUrl"
                    else if (imgUrl.startsWith("/")) {
                        val base = url.substringBefore("/", url.removePrefix("https://"))
                        "https://$base$imgUrl"
                    } else imgUrl

                    if (!images.contains(fullUrl)) {
                        images.add(fullUrl)
                    }
                }
            }

            val cover = images.firstOrNull()
            ScrapedGalleryResult(title = title.ifEmpty { "Photoset Gallery" }, coverImage = cover, images = images)
        } catch (e: Exception) {
            Log.e(TAG, "scrapeGallery error", e)
            ScrapedGalleryResult("Photoset", null, emptyList())
        }
    }

    // 2. Coomer / OnlyFans / Fansly Creator Scraper
    suspend fun scrapeCreatorProfile(profileUrl: String): ScrapedCreatorResult {
        var name = "Creator"
        var avatarUrl = ""
        val posts = mutableListOf<CoomerPostData>()
        var service = "OnlyFans"

        try {
            // e.g. https://coomer.su/onlyfans/user/username
            val match = Pattern.compile("coomer\\.su/([a-zA-Z0-9]+)/user/([a-zA-Z0-9_.-]+)").matcher(profileUrl)
            val detectedService = if (match.find()) match.group(1) ?: "onlyfans" else "onlyfans"
            val detectedUser = if (match.groupCount() >= 2) match.group(2) ?: "" else ""
            service = detectedService.replaceFirstChar { it.uppercase() }
            name = detectedUser.ifEmpty { "Creator" }

            val html = NetworkClient.getHtml(profileUrl)
            val doc = Jsoup.parse(html)

            val avatarEl = doc.select(".user-header__avatar img, .fancy-image__image").firstOrNull()
            if (avatarEl != null) {
                avatarUrl = avatarEl.attr("src")
                if (avatarUrl.startsWith("//")) avatarUrl = "https:$avatarUrl"
            }

            val postElements = doc.select("article.post-card")
            for ((idx, el) in postElements.withIndex()) {
                val postId = el.attr("data-id").ifEmpty { "post_$idx" }
                val caption = el.select(".post-card__header").text()
                val mediaLinks = el.select("a.post-card__image-link, a.fileThumb")
                val urls = mutableListOf<String>()
                val thumbs = mutableListOf<String>()
                val mediaTypes = mutableListOf<String>()

                for (link in mediaLinks) {
                    val href = link.attr("href")
                    val thumb = link.select("img").attr("src")
                    if (href.isNotEmpty()) {
                        val fullMedia = if (href.startsWith("//")) "https:$href" else href
                        urls.add(fullMedia)
                        thumbs.add(if (thumb.startsWith("//")) "https:$thumb" else thumb)
                        mediaTypes.add(if (href.endsWith(".mp4") || href.endsWith(".m4v")) "video" else "image")
                    }
                }

                if (urls.isNotEmpty()) {
                    posts.add(
                        CoomerPostData(
                            id = postId,
                            urls = urls,
                            thumbUrls = thumbs,
                            caption = caption.ifEmpty { null },
                            mediaTypes = mediaTypes,
                            sourceService = service
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scrapeCreatorProfile error", e)
        }

        return ScrapedCreatorResult(name = name, avatarUrl = avatarUrl, posts = posts, service = service)
    }

    // 3. Sukebei / Torrent Scraper
    suspend fun scrapeSukebei(query: String): List<ScrapedTorrent> {
        val list = mutableListOf<ScrapedTorrent>()
        try {
            val encoded = query.trim().replace(" ", "+")
            val searchUrl = "https://sukebei.nyaa.si/?f=0&c=0_0&q=$encoded&s=seeders&o=desc"
            val html = NetworkClient.getHtml(searchUrl)
            val doc = Jsoup.parse(html)
            val rows = doc.select("table.torrent-list tbody tr, tr.default, tr.success, tr.danger")

            for (row in rows) {
                val titleEl = row.select("td:nth-child(2) a:not(.comments)").lastOrNull()
                    ?: row.select("a[href*=/view/]:not(.comments)").firstOrNull()
                val magnetEl = row.select("a[href^=magnet:]").firstOrNull()
                val sizeEl = row.select("td:nth-child(4)").firstOrNull()
                val seedersEl = row.select("td:nth-child(6)").firstOrNull()
                val leechersEl = row.select("td:nth-child(7)").firstOrNull()

                if (titleEl != null && magnetEl != null) {
                    list.add(
                        ScrapedTorrent(
                            title = titleEl.text().trim(),
                            magnetUrl = magnetEl.attr("href"),
                            size = sizeEl?.text()?.trim() ?: "Unknown",
                            seeders = seedersEl?.text()?.trim()?.toIntOrNull() ?: 0,
                            leechers = leechersEl?.text()?.trim()?.toIntOrNull() ?: 0,
                            siteName = "Sukebei Nyaa"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scrapeSukebei error", e)
        }
        return list
    }

    // 4. XXXClub / Adult Trackers Scraper
    suspend fun scrapeXxxClub(query: String): List<ScrapedTorrent> {
        val list = mutableListOf<ScrapedTorrent>()
        try {
            val encoded = query.trim().replace(" ", "+")
            val searchUrl = "https://xxxclub.to/torrents/browse?search=$encoded&sort=seeders&order=desc"
            val html = NetworkClient.getHtml(searchUrl)
            val doc = Jsoup.parse(html)
            val rows = doc.select("table tbody tr, .table-responsive table tr")

            for (row in rows.take(15)) {
                val titleEl = row.select("a.torrent-title, a[href*=/torrents/details/]").firstOrNull()
                    ?: row.select("td:nth-child(2) a").firstOrNull()
                var magnet = row.select("a[href^=magnet:]").firstOrNull()?.attr("href")
                val sizeEl = row.select("td:nth-child(4), .torrent-size").firstOrNull()
                val seedersEl = row.select("td:nth-child(5), .text-success").firstOrNull()
                val leechersEl = row.select("td:nth-child(6), .text-danger").firstOrNull()

                // If magnet is not in table directly, fetch detail page if high match
                if (magnet.isNullOrBlank() && titleEl != null) {
                    val detailHref = titleEl.attr("href")
                    if (detailHref.contains("/torrents/details/")) {
                        try {
                            val detailUrl = if (detailHref.startsWith("http")) detailHref else "https://xxxclub.to$detailHref"
                            val detailHtml = NetworkClient.getHtml(detailUrl)
                            val detailDoc = Jsoup.parse(detailHtml)
                            magnet = detailDoc.select("a[href^=magnet:]").firstOrNull()?.attr("href")
                        } catch (_: Exception) {}
                    }
                }

                if (titleEl != null && !magnet.isNullOrBlank()) {
                    val rawSeeders = seedersEl?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 1
                    val rawLeechers = leechersEl?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
                    list.add(
                        ScrapedTorrent(
                            title = titleEl.text().trim(),
                            magnetUrl = magnet,
                            size = sizeEl?.text()?.trim() ?: "Unknown",
                            seeders = rawSeeders,
                            leechers = rawLeechers,
                            siteName = "XXXClub"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scrapeXxxClub error", e)
        }
        return list
    }

    // 5. BitSearch Trackers Scraper (High Speed & Reliability Fallback)
    suspend fun scrapeBitSearch(query: String): List<ScrapedTorrent> {
        val list = mutableListOf<ScrapedTorrent>()
        try {
            val encoded = query.trim().replace(" ", "+")
            val searchUrl = "https://bitsearch.to/search?q=$encoded&sort=seeders"
            val html = NetworkClient.getHtml(searchUrl)
            val doc = Jsoup.parse(html)
            val cards = doc.select(".card.search-result, li.search-result, div.search-result")

            for (card in cards.take(15)) {
                val titleEl = card.select("h5.title a, .card-title a, a[href*=/torrents/]").firstOrNull()
                val magnetEl = card.select("a[href^=magnet:], a.dl-magnet").firstOrNull()
                val sizeEl = card.select(".stats div:contains(GB), .stats div:contains(MB), .stats .size").firstOrNull()
                val seedersEl = card.select(".stats .seeders, font[color=green], div:contains(Seeders)").firstOrNull()
                val leechersEl = card.select(".stats .leechers, font[color=red], div:contains(Leechers)").firstOrNull()

                if (titleEl != null && magnetEl != null) {
                    val rawSeeders = seedersEl?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
                    val rawLeechers = leechersEl?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
                    list.add(
                        ScrapedTorrent(
                            title = titleEl.text().trim(),
                            magnetUrl = magnetEl.attr("href"),
                            size = sizeEl?.text()?.trim() ?: "Unknown",
                            seeders = rawSeeders,
                            leechers = rawLeechers,
                            siteName = "BitSearch"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scrapeBitSearch error", e)
        }
        return list
    }

    // 6. Studio Normalization & Smart Query Formulator
    fun buildSmartTorrentQueries(title: String, studio: String?): List<String> {
        val queries = mutableListOf<String>()
        val cleanTitle = title.replace(Regex("[\\[\\]()|:\"'/,]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (!studio.isNullOrBlank()) {
            val cleanStudio = studio.replace(Regex("(?i)\\b(studios?|network|productions?|media|group|films?|hd|\\.com)\\b"), "")
                .replace(Regex("[\\[\\]()|:\"'/,]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val collapsedStudio = cleanStudio.replace(" ", "")

            if (cleanTitle.isNotBlank()) {
                queries.add("$cleanStudio $cleanTitle")
                if (collapsedStudio != cleanStudio && collapsedStudio.isNotBlank()) {
                    queries.add("$collapsedStudio $cleanTitle")
                }
            } else {
                queries.add(cleanStudio)
                if (collapsedStudio != cleanStudio && collapsedStudio.isNotBlank()) {
                    queries.add(collapsedStudio)
                }
            }
        }

        if (cleanTitle.isNotBlank() && !queries.contains(cleanTitle)) {
            queries.add(cleanTitle)
        }

        return queries.distinct().filter { it.isNotBlank() }
    }

    // 7. Unified Torrent Search across All Adult Trackers
    suspend fun searchTorrentsUnified(
        query: String,
        studio: String? = null,
        provider: String = "ALL"
    ): List<ScrapedTorrent> {
        val results = mutableListOf<ScrapedTorrent>()
        val searchQueries = buildSmartTorrentQueries(query, studio)
        val primaryQuery = searchQueries.firstOrNull() ?: query

        if (provider == "ALL" || provider == "SUKEBEI") {
            val sukebeiResults = scrapeSukebei(primaryQuery)
            results.addAll(sukebeiResults)
            if (sukebeiResults.size < 3 && searchQueries.size > 1) {
                val secondaryResults = scrapeSukebei(searchQueries[1])
                for (sr in secondaryResults) {
                    if (results.none { it.magnetUrl.equals(sr.magnetUrl, ignoreCase = true) }) {
                        results.add(sr)
                    }
                }
            }
        }

        if (provider == "ALL" || provider == "XXXCLUB") {
            val xxxResults = scrapeXxxClub(primaryQuery)
            for (xr in xxxResults) {
                if (results.none { it.magnetUrl.equals(xr.magnetUrl, ignoreCase = true) }) {
                    results.add(xr)
                }
            }
        }

        if (provider == "ALL" || provider == "BITSEARCH") {
            val bitResults = scrapeBitSearch(primaryQuery)
            for (br in bitResults) {
                if (results.none { it.magnetUrl.equals(br.magnetUrl, ignoreCase = true) }) {
                    results.add(br)
                }
            }
        }

        return results.distinctBy { it.magnetUrl }.sortedByDescending { it.seeders }
    }

    // 8. SubtitleCat Scraper
    suspend fun scrapeSubtitleCat(code: String): String? {
        return try {
            val url = "https://www.subtitlecat.com/index.php?search=${code.trim()}"
            val html = NetworkClient.getHtml(url)
            val doc = Jsoup.parse(html)
            val subLink = doc.select("a[href*=/subtitles/]").firstOrNull()?.attr("href")
            if (subLink != null) {
                val fullUrl = if (subLink.startsWith("http")) subLink else "https://www.subtitlecat.com$subLink"
                val pageHtml = NetworkClient.getHtml(fullUrl)
                val dlDoc = Jsoup.parse(pageHtml)
                val downloadLink = dlDoc.select("a[href*=/download/]").firstOrNull()?.attr("href")
                if (downloadLink != null) {
                    if (downloadLink.startsWith("http")) downloadLink else "https://www.subtitlecat.com$downloadLink"
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "scrapeSubtitleCat error", e)
            null
        }
    }
}
