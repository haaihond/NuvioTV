package com.nuvio.tv.core.usenet

import com.nuvio.tv.core.server.UsenetRankingPhone
import com.nuvio.tv.core.streams.StreamRankingKey
import com.nuvio.tv.core.streams.StreamRankingSort
import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UsenetSortStepsTest {
    private val gb = 1L shl 30
    private val first = UsenetIndexer(id = "first", name = "First", apiUrl = "https://first.test/api", apiKey = "key")
    private val second = first.copy(id = "second", name = "Second")
    private val config = UsenetSourceConfiguration(indexers = listOf(first, second))

    /** These keys on, in this order; the rest off. */
    private fun steps(vararg on: UsenetSortStep) =
        on.toList() + UsenetSortKey.entries.filter { key -> on.none { it.key == key } }.map { UsenetSortStep(it, enabled = false) }

    private fun steps(vararg on: UsenetSortKey) = steps(*on.map { UsenetSortStep(it) }.toTypedArray())

    /** The single sort as it was before steps: [sort], then indexer priority, resolution and largest size. */
    private fun before(releases: List<UsenetRelease>, sort: UsenetSort): List<UsenetRelease> {
        val priority = mapOf(first.id to 0, second.id to 1)
        val primary = when (sort) {
            UsenetSort.QUALITY -> compareByDescending<UsenetRelease> { it.resolution }
            UsenetSort.LARGEST -> compareByDescending<UsenetRelease> { it.size }
            UsenetSort.SMALLEST -> compareBy<UsenetRelease> { it.size.takeIf { size -> size > 0 } ?: Long.MAX_VALUE }
            UsenetSort.NEWEST -> compareByDescending<UsenetRelease> { it.publishedAt }
            UsenetSort.INDEXER -> compareBy<UsenetRelease> { priority[it.indexerId] ?: Int.MAX_VALUE }
        }
        return releases.sortedWith(primary.thenBy { priority[it.indexerId] ?: Int.MAX_VALUE }.thenByDescending { it.resolution }
            .thenByDescending { it.size }.thenBy { it.title }.thenBy { it.nzbUrl })
    }

    @Test fun `settings saved before steps keep their order`() {
        val random = Random(7)
        val tags = listOf("2160p.BluRay.REMUX", "2160p.WEB-DL", "1080p.BluRay", "1080p.WEBRip", "720p.HDTV", "")
        // Sizes differ, as real releases' do: only exactly equal sizes reach the keys the old sort lacked.
        val releases = (1..200).map { i ->
            UsenetRelease("Film.$i.${tags[random.nextInt(tags.size)]}-G", "https://a/$i", size = random.nextLong(1, 80 * gb),
                publishedAt = if (random.nextInt(8) == 0) 0 else random.nextLong(1, 1_000_000),
                indexerId = if (random.nextBoolean()) first.id else second.id)
        }
        for (sort in UsenetSort.entries) {
            val saved = Json.decodeFromString<UsenetSourceConfiguration>("""{"sort":"$sort","maxResults":200}""")
                .copy(indexers = config.indexers)
            assertNull(saved.sortOrder)
            assertEquals(sort.name, before(releases, sort), NewznabProtocol.arrange(releases, saved))
        }
    }

    @Test fun `each step orders what the steps above left tied`() {
        val web = UsenetRelease("Film.2160p.WEB-DL-A", "https://a/web", size = 15 * gb, indexerId = first.id)
        val remux = UsenetRelease("Film.2160p.BluRay.REMUX-B", "https://a/remux", size = 70 * gb, indexerId = second.id)
        val largerWeb = UsenetRelease("Film.2160p.WEB-DL-C", "https://a/larger", size = 20 * gb, indexerId = second.id)
        val hd = UsenetRelease("Film.1080p.BluRay.REMUX-D", "https://a/hd", size = 30 * gb, indexerId = first.id)
        val releases = listOf(web, remux, largerWeb, hd)
        fun order(steps: List<UsenetSortStep>) = NewznabProtocol.arrange(releases, config.copy(sortOrder = steps))
        // The old "best resolution first": the first indexer's WEB-DL before the second indexer's remux.
        assertEquals(listOf(web, remux, largerWeb, hd), order(steps(UsenetSortKey.RESOLUTION, UsenetSortKey.INDEXER)))
        assertEquals(listOf(remux, largerWeb, web, hd),
            order(steps(UsenetSortKey.RESOLUTION, UsenetSortKey.SOURCE, UsenetSortKey.SIZE)))
        assertEquals(listOf(remux, hd, web, largerWeb), order(steps(UsenetSortKey.SOURCE, UsenetSortKey.RESOLUTION)))
        assertEquals(listOf(web, largerWeb, hd, remux), order(steps(UsenetSortStep(UsenetSortKey.SIZE, descending = false))))
    }

    @Test fun `unknown values come last in either direction`() {
        val known = UsenetRelease("Film.2160p.BluRay.REMUX-A", "https://a/known", size = 9 * gb, publishedAt = 5, indexerId = first.id)
        val unknown = UsenetRelease("Film-B", "https://a/unknown", indexerId = first.id)
        for (step in listOf(UsenetSortStep(UsenetSortKey.SIZE), UsenetSortStep(UsenetSortKey.SIZE, descending = false),
            UsenetSortStep(UsenetSortKey.RESOLUTION), UsenetSortStep(UsenetSortKey.SOURCE), UsenetSortStep(UsenetSortKey.AGE))) {
            assertEquals(step.toString(), listOf(known, unknown),
                NewznabProtocol.arrange(listOf(unknown, known), config.copy(sortOrder = steps(step))))
        }
    }

    @Test fun `saved steps keep every key once and one of them on`() {
        val saved = config.copy(sortOrder = steps(UsenetSortKey.SOURCE, UsenetSortKey.SIZE))
        assertEquals(saved.sortSteps(), Json.decodeFromString<UsenetSourceConfiguration>(Json.encodeToString(saved)).sortSteps())
        // A key missing from saved steps, as one added later would be, joins at the end.
        assertEquals(UsenetSortKey.entries.toSet(),
            config.copy(sortOrder = listOf(UsenetSortStep(UsenetSortKey.AGE))).sortSteps().map { it.key }.toSet())
        assertThrows(IllegalArgumentException::class.java) {
            UsenetSourceConfiguration(sortOrder = listOf(UsenetSortStep(UsenetSortKey.SIZE), UsenetSortStep(UsenetSortKey.SIZE))).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsenetSourceConfiguration(sortOrder = UsenetSortKey.entries.map { UsenetSortStep(it, enabled = false) }).validate()
        }
    }

    @Test fun `the phone previews the same steps`() {
        val profile = UsenetRankingPhone.original(steps(UsenetSortStep(UsenetSortKey.SOURCE),
            UsenetSortStep(UsenetSortKey.SIZE, descending = false), UsenetSortStep(UsenetSortKey.INDEXER)))
        assertEquals(listOf(StreamRankingSort(StreamRankingKey.QUALITY), StreamRankingSort(StreamRankingKey.SIZE, false),
            StreamRankingSort(StreamRankingKey.INDEXER)), profile.sort)
        assertEquals(0, profile.topPerBucket)
    }
}
