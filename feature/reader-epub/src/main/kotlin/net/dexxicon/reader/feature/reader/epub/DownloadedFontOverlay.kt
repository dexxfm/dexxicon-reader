package net.dexxicon.reader.feature.reader.epub

import net.dexxicon.reader.core.data.font.BookOrbitFontLibrary
import net.dexxicon.reader.core.data.font.DownloadedFontFace
import net.dexxicon.reader.core.data.font.DownloadedFontFamily
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.CompositeContainer
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.file.FileResource
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import java.io.File

internal fun fontFamilyName(id: String): String = "dexxicon_$id"

private fun fontPath(family: DownloadedFontFamily, face: DownloadedFontFace): String =
    "dexxicon-fonts/${family.id}/${face.fileName}"

@OptIn(ExperimentalReadiumApi::class)
internal fun fontOverlay(
    library: BookOrbitFontLibrary,
    families: List<DownloadedFontFamily>,
): Publication.Builder.() -> Unit {
    var applied = false
    return overlay@{
        if (applied || families.isEmpty()) return@overlay
        val files = families.flatMap { family ->
            family.faces.map { face ->
                val href = requireNotNull(Url(fontPath(family, face)))
                check(href !in container.entries && manifest.linkWithHref(href) == null) {
                    "A downloaded font conflicts with a book resource."
                }
                href to (face to File(library.filePath(family, face)))
            }
        }.toMap()
        val fontContainer = object : Container<Resource> {
            override val entries = files.keys
            override fun get(url: Url): Resource? = files[url]?.second?.let(::FileResource)
            override fun close() = Unit
        }
        container = CompositeContainer(fontContainer, container)
        manifest = manifest.copy(resources = manifest.resources + files.map { (href, value) ->
            Link(href = href, mediaType = requireNotNull(MediaType("font/${value.first.format}")))
        })
        applied = true
    }
}

internal fun EpubNavigatorFragment.Configuration.addDownloadedFonts(families: List<DownloadedFontFamily>) {
    families.forEach { family ->
        addFontFamilyDeclaration(FontFamily(fontFamilyName(family.id))) {
            family.faces.forEach { face ->
                addFontFace {
                    addSource(requireNotNull(AbsoluteUrl("https://readium_package/${fontPath(family, face)}")))
                    setFontStyle(if (face.style == "italic") FontStyle.ITALIC else FontStyle.NORMAL)
                    setFontWeight((face.weightMin ?: face.weight)..(face.weightMax ?: face.weight))
                }
            }
        }
    }
}
