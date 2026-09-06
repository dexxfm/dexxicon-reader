package net.dexxicon.reader.feature.reader.epub

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/**
 * Adds a "Highlight" item to the text-selection context menu. On tap it reads the current
 * selection from the navigator and hands the [Locator] to [onHighlight].
 */
class HighlightSelectionCallback(
    private val activity: FragmentActivity,
    private val navigator: () -> EpubNavigatorFragment?,
    private val onHighlight: (Locator) -> Unit,
) : ActionMode.Callback {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(0, MENU_HIGHLIGHT, 0, "Highlight")
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId != MENU_HIGHLIGHT) return false
        val nav = navigator() ?: return false
        activity.lifecycleScope.launch {
            val selection = nav.currentSelection()
            if (selection != null) onHighlight(selection.locator)
            nav.clearSelection()
        }
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {}

    private companion object {
        const val MENU_HIGHLIGHT = 0x7ADD
    }
}
