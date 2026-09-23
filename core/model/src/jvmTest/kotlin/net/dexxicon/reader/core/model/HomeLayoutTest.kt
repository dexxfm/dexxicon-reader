package net.dexxicon.reader.core.model

import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.model.HomeSection.CONTINUE_LISTENING
import net.dexxicon.reader.core.model.HomeSection.CONTINUE_READING
import net.dexxicon.reader.core.model.HomeSection.DOWNLOADED
import net.dexxicon.reader.core.model.HomeSection.ON_DECK
import org.junit.Test

class HomeLayoutTest {

    @Test
    fun `default shows every section in declaration order`() {
        assertThat(HomeLayout().visible)
            .containsExactly(CONTINUE_READING, CONTINUE_LISTENING, ON_DECK, DOWNLOADED).inOrder()
    }

    @Test
    fun `encode and decode round-trip order and hidden sections`() {
        val layout = HomeLayout(
            order = listOf(DOWNLOADED, CONTINUE_READING, ON_DECK, CONTINUE_LISTENING),
            hidden = setOf(ON_DECK),
        )
        assertThat(layout.encode()).isEqualTo("DOWNLOADED,CONTINUE_READING,!ON_DECK,CONTINUE_LISTENING")
        assertThat(HomeLayout.decode(layout.encode())).isEqualTo(layout)
        assertThat(layout.visible).containsExactly(DOWNLOADED, CONTINUE_READING, CONTINUE_LISTENING).inOrder()
    }

    @Test
    fun `decode of nothing is the default`() {
        assertThat(HomeLayout.decode(null)).isEqualTo(HomeLayout())
        assertThat(HomeLayout.decode("")).isEqualTo(HomeLayout())
    }

    @Test
    fun `decode drops unknown and repeated names and appends missing sections`() {
        val layout = HomeLayout.decode("ON_DECK, BOGUS,ON_DECK,!DOWNLOADED")
        assertThat(layout.order).containsExactly(ON_DECK, DOWNLOADED, CONTINUE_READING, CONTINUE_LISTENING).inOrder()
        assertThat(layout.hidden).containsExactly(DOWNLOADED)
    }

    @Test
    fun `moved reorders and ignores out-of-range moves`() {
        val layout = HomeLayout().moved(from = 3, to = 0)
        assertThat(layout.order).containsExactly(DOWNLOADED, CONTINUE_READING, CONTINUE_LISTENING, ON_DECK).inOrder()
        assertThat(layout.moved(0, 9)).isEqualTo(layout)
    }

    @Test
    fun `withVisibility hides and shows a section`() {
        val hidden = HomeLayout().withVisibility(ON_DECK, visible = false)
        assertThat(hidden.visible).doesNotContain(ON_DECK)
        assertThat(hidden.withVisibility(ON_DECK, visible = true)).isEqualTo(HomeLayout())
    }
}
