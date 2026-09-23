package net.dexxicon.reader.core.model

import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.model.HomeSection.CONTINUE_LISTENING
import net.dexxicon.reader.core.model.HomeSection.CONTINUE_READING
import net.dexxicon.reader.core.model.HomeSection.DOWNLOADED
import net.dexxicon.reader.core.model.HomeSection.ON_DECK
import org.junit.Test

class HomeLayoutTest {

    private fun sections(vararg s: HomeSection) = s.map { HomeShelf.Section(it) }
    private val favourites = BookGroup("srv1", BookGroupKind.COLLECTION, "12", "Favourites, re-reads | 2026 %")

    @Test
    fun `default shows every section in declaration order`() {
        assertThat(HomeLayout().visible)
            .containsExactlyElementsIn(sections(CONTINUE_READING, CONTINUE_LISTENING, ON_DECK, DOWNLOADED)).inOrder()
    }

    @Test
    fun `encode and decode round-trip order and hidden sections`() {
        val layout = HomeLayout(
            order = sections(DOWNLOADED, CONTINUE_READING, ON_DECK, CONTINUE_LISTENING),
            hidden = setOf(ON_DECK.name),
        )
        assertThat(layout.encode()).isEqualTo("DOWNLOADED,CONTINUE_READING,!ON_DECK,CONTINUE_LISTENING")
        assertThat(HomeLayout.decode(layout.encode())).isEqualTo(layout)
        assertThat(layout.visible)
            .containsExactlyElementsIn(sections(DOWNLOADED, CONTINUE_READING, CONTINUE_LISTENING)).inOrder()
    }

    @Test
    fun `decode of nothing is the default`() {
        assertThat(HomeLayout.decode(null)).isEqualTo(HomeLayout())
        assertThat(HomeLayout.decode("")).isEqualTo(HomeLayout())
    }

    @Test
    fun `decode drops unknown and repeated names and appends missing sections`() {
        val layout = HomeLayout.decode("ON_DECK, BOGUS,ON_DECK,!DOWNLOADED")
        assertThat(layout.order)
            .containsExactlyElementsIn(sections(ON_DECK, DOWNLOADED, CONTINUE_READING, CONTINUE_LISTENING)).inOrder()
        assertThat(layout.hidden).containsExactly(DOWNLOADED.name)
    }

    @Test
    fun `moved reorders and ignores out-of-range moves`() {
        val layout = HomeLayout().moved(from = 3, to = 0)
        assertThat(layout.order)
            .containsExactlyElementsIn(sections(DOWNLOADED, CONTINUE_READING, CONTINUE_LISTENING, ON_DECK)).inOrder()
        assertThat(layout.moved(0, 9)).isEqualTo(layout)
    }

    @Test
    fun `withVisibility hides and shows a section`() {
        val hidden = HomeLayout().withVisibility(ON_DECK, visible = false)
        assertThat(hidden.visible).doesNotContain(HomeShelf.Section(ON_DECK))
        assertThat(hidden.withVisibility(ON_DECK, visible = true)).isEqualTo(HomeLayout())
    }

    // --- issue #254: pinned groups ---------------------------------------------------------

    @Test
    fun `pinning appends once and survives an encode round-trip, awkward name included`() {
        val layout = HomeLayout().pinned(favourites).pinned(favourites)
        assertThat(layout.pinned).containsExactly(favourites)
        assertThat(layout.visible.last()).isEqualTo(HomeShelf.Pinned(favourites))

        val decoded = HomeLayout.decode(layout.encode())
        assertThat(decoded.pinned.single().name).isEqualTo("Favourites, re-reads | 2026 %")
        assertThat(decoded.isPinned(favourites)).isTrue()
    }

    @Test
    fun `a pinned shelf hides, reorders and unpins by its key`() {
        val key = HomeShelf.Pinned(favourites).key
        val layout = HomeLayout().pinned(favourites).moved(from = 4, to = 0).withVisibility(key, visible = false)
        assertThat(layout.order.first()).isEqualTo(HomeShelf.Pinned(favourites))
        assertThat(HomeLayout.decode(layout.encode()).hidden).containsExactly(key)

        val unpinned = layout.unpinned(favourites)
        assertThat(unpinned.isPinned(favourites)).isFalse()
        assertThat(unpinned.hidden).isEmpty()
        assertThat(unpinned).isEqualTo(HomeLayout())
    }

    @Test
    fun `malformed pins are dropped rather than crashing the layout`() {
        val layout = HomeLayout.decode("PIN|only|three,PIN|s|NOT_A_KIND|1|x,PIN||COLLECTION|1|x,CONTINUE_READING")
        assertThat(layout.pinned).isEmpty()
        assertThat(layout.order.first()).isEqualTo(HomeShelf.Section(CONTINUE_READING))
    }

    @Test
    fun `forServers drops only pins from servers that are gone`() {
        val other = BookGroup("srv2", BookGroupKind.SMART, "3", "Unread")
        val layout = HomeLayout().pinned(favourites).pinned(other)
            .withVisibility(HomeShelf.Pinned(other).key, visible = false)
        val kept = layout.forServers(setOf("srv1"))
        assertThat(kept.pinned).containsExactly(favourites)
        assertThat(kept.hidden).isEmpty()
        assertThat(layout.forServers(setOf("srv1", "srv2"))).isSameInstanceAs(layout)
    }
}
