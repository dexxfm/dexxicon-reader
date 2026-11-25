package net.dexxicon.reader.feature.reader.epub

import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderPageLayout
import net.dexxicon.reader.core.datastore.ReaderScrollMode
import net.dexxicon.reader.shared.reader.epub.EpubMarginGesture
import net.dexxicon.reader.shared.reader.epub.classifyMarginGesture
import net.dexxicon.reader.shared.reader.epub.epubMarginGeometry
import org.junit.Test

class EpubMarginGeometryTest {
    @Test
    fun `single page margins use the reading area dimensions independently`() {
        val prefs = ReaderDisplayPreferences(
            pageLayout = ReaderPageLayout.SINGLE,
            epubMarginLeft = 0,
            epubMarginRight = 25,
            epubMarginTop = 10,
            epubMarginBottom = 20,
        )
        val geometry = epubMarginGeometry(prefs, 800f, 600f, reflowable = true)
        assertThat(geometry.left).isEqualTo(0f)
        assertThat(geometry.right).isEqualTo(200f)
        assertThat(geometry.top).isEqualTo(60f)
        assertThat(geometry.bottom).isEqualTo(120f)
        assertThat(geometry.spreadPageMargin).isEqualTo(0.0)
    }

    @Test
    fun `spread averages horizontal margins per page and keeps a minimum gutter`() {
        val prefs = ReaderDisplayPreferences(
            pageLayout = ReaderPageLayout.DOUBLE,
            epubMarginLeft = 10,
            epubMarginRight = 20,
        )
        val geometry = epubMarginGeometry(prefs, 800f, 600f, reflowable = true)
        assertThat(geometry.left).isEqualTo(0f)
        assertThat(geometry.right).isEqualTo(0f)
        assertThat(geometry.spreadPageMargin).isEqualTo(60.0)
        assertThat(epubMarginGeometry(prefs.copy(epubMarginLeft = 0, epubMarginRight = 0), 800f, 600f, true)
            .spreadPageMargin).isEqualTo(12.0)
        assertThat(epubMarginGeometry(prefs, 400f, 600f, true).spreadPageMargin).isEqualTo(30.0)
        assertThat(epubMarginGeometry(prefs.copy(epubMarginLeft = 25, epubMarginRight = 25), 800f, 600f, true)
            .spreadPageMargin).isEqualTo(100.0)
    }

    @Test
    fun `scrolling and fixed layout do not use spread margins`() {
        val prefs = ReaderDisplayPreferences(pageLayout = ReaderPageLayout.DOUBLE)
        val scrolling = epubMarginGeometry(prefs.copy(scrollMode = ReaderScrollMode.SCROLL), 800f, 600f, true)
        assertThat(scrolling.spread).isFalse()
        assertThat(scrolling.left).isEqualTo(40f)
        val fixed = epubMarginGeometry(prefs, 800f, 600f, false)
        assertThat(fixed.left).isEqualTo(0f)
        assertThat(fixed.top).isEqualTo(0f)
        assertThat(fixed.spreadPageMargin).isEqualTo(0.0)
        val vertical = epubMarginGeometry(prefs, 800f, 600f, true, verticalText = true)
        assertThat(vertical.spread).isFalse()
        assertThat(vertical.left).isEqualTo(40f)
    }

    @Test
    fun `automatic layout uses two pages only in landscape`() {
        val prefs = ReaderDisplayPreferences()
        val narrow = epubMarginGeometry(prefs, 800f, 1200f, true)
        val wide = epubMarginGeometry(prefs, 720f, 600f, true)
        assertThat(narrow.spread).isFalse()
        assertThat(narrow.left).isWithin(0.01f).of(40f)
        assertThat(wide.spread).isTrue()
        assertThat(wide.spreadPageMargin).isEqualTo(18.0)
    }

    @Test
    fun `margin gestures distinguish taps from horizontal and vertical drags`() {
        fun classify(dx: Float, dy: Float, distance: Float) =
            classifyMarginGesture(dx, dy, distance, touchSlop = 8f, viewportWidth = 1000f)

        assertThat(classify(3f, 4f, 5f)).isEqualTo(EpubMarginGesture.TAP)
        assertThat(classify(-180f, 30f, 183f)).isEqualTo(EpubMarginGesture.FORWARD)
        assertThat(classify(180f, 30f, 183f)).isEqualTo(EpubMarginGesture.BACKWARD)
        assertThat(classify(30f, -300f, 302f)).isEqualTo(EpubMarginGesture.NONE)
        assertThat(classify(179f, 0f, 179f)).isEqualTo(EpubMarginGesture.NONE)
        assertThat(classify(0f, 0f, 200f)).isEqualTo(EpubMarginGesture.NONE)
    }
}
