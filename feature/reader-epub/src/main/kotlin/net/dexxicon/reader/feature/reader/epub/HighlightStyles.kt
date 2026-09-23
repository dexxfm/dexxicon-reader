package net.dexxicon.reader.feature.reader.epub

import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderTheme
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplates

/** Readium's own highlight opacity — right on a light page. */
private const val LIGHT_PAGE_ALPHA = 0.3

/** issue #279 — on a black page the 30% tint all but vanished (yellow read as dim olive). */
private const val DARK_PAGE_ALPHA = 0.5

/**
 * issue #279 — a highlight painted at [DARK_PAGE_ALPHA], for dark reader pages. A style of its
 * own (rather than a different alpha for the one highlight template) because Readium fixes the
 * templates when the navigator is created, while the reader's theme can change mid-book:
 * switching style re-applies the decorations with the right tint straight away.
 */
internal data class StrongHighlight(
    @ColorInt override val tint: Int,
    override val isActive: Boolean = false,
) : Decoration.Style, Decoration.Style.Tinted, Decoration.Style.Activable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tint)
        dest.writeInt(if (isActive) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<StrongHighlight> {
        override fun createFromParcel(source: Parcel): StrongHighlight =
            StrongHighlight(source.readInt(), source.readInt() == 1)

        override fun newArray(size: Int): Array<StrongHighlight?> = arrayOfNulls(size)
    }
}

/** Readium's default decoration templates, plus [StrongHighlight] — the same highlight template
 *  at [DARK_PAGE_ALPHA]. */
internal fun highlightDecorationTemplates(): HtmlDecorationTemplates =
    HtmlDecorationTemplates.defaultTemplates(alpha = LIGHT_PAGE_ALPHA).apply {
        HtmlDecorationTemplates.defaultTemplates(alpha = DARK_PAGE_ALPHA)[Decoration.Style.Highlight::class]
            ?.let { set(StrongHighlight::class, it) }
    }

/** issue #279 — the highlight style for a highlight of [tint] on this reader page. */
internal fun highlightStyle(@ColorInt tint: Int, darkPage: Boolean): Decoration.Style =
    if (darkPage) StrongHighlight(tint) else Decoration.Style.Highlight(tint = tint, isActive = false)

/** Whether the reader page is dark — same resolution as [toEpubPreferences]'s theme. */
internal fun ReaderDisplayPreferences.hasDarkPage(systemInDark: Boolean): Boolean =
    theme == ReaderTheme.DARK || (theme == ReaderTheme.SYSTEM && systemInDark)
