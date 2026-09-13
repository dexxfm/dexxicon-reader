package net.dexxicon.reader.feature.reader.pdf.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.dexxicon.reader.feature.reader.pdf.PdfReaderScreen

@Serializable
data class PdfReaderRoute(val serverId: String, val bookId: String)

fun NavController.navigateToPdfReader(serverId: String, bookId: String) =
    navigate(PdfReaderRoute(serverId, bookId))

/** See `comicReaderSection`'s doc comment (issue #155) for why [onExit] exists. */
fun NavGraphBuilder.pdfReaderSection(navController: NavController, onExit: () -> Unit) {
    composable<PdfReaderRoute> {
        PdfReaderScreen(onBack = { if (!navController.popBackStack()) onExit() })
    }
}
