package net.dexxicon.reader.feature.reader.epub

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.dexxicon.reader.core.datastore.PlatformStorageContext
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
import net.dexxicon.reader.core.datastore.ReaderPreferencesStore
import net.dexxicon.reader.core.datastore.readerPreferencesFilePath
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderPreferencesMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `legacy fit seeds independent PDF and comic settings`() = runBlocking {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = temporaryFolder.root
        }
        val storageContext = PlatformStorageContext(context)
        val job = SupervisorJob()
        val legacy = PreferenceDataStoreFactory.createWithPath(
            scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { readerPreferencesFilePath(storageContext).toPath() },
        )
        legacy.edit { it[stringPreferencesKey("fit_mode")] = ReaderFitMode.PAGE_WIDTH.name }
        job.cancelAndJoin()

        val store = ReaderPreferencesStore(storageContext)
        val initial = store.preferences.first()
        assertThat(initial.pdfFitMode).isEqualTo(ReaderFitMode.PAGE_WIDTH)
        assertThat(initial.comicFitMode).isEqualTo(ReaderFitMode.PAGE_WIDTH)
        assertThat(initial.epubMarginTop).isEqualTo(0)
        assertThat(initial.epubMarginBottom).isEqualTo(0)
        assertThat(initial.epubMarginLeft).isEqualTo(5)
        assertThat(initial.epubMarginRight).isEqualTo(5)

        store.update {
            it.copy(pdfFitMode = ReaderFitMode.PAGE_FIT, epubMarginTop = 10, epubMarginLeft = 100, epubMarginRight = -1)
        }
        val changed = store.preferences.first()
        assertThat(changed.pdfFitMode).isEqualTo(ReaderFitMode.PAGE_FIT)
        assertThat(changed.comicFitMode).isEqualTo(ReaderFitMode.PAGE_WIDTH)
        assertThat(changed.epubMarginTop).isEqualTo(10)
        assertThat(changed.epubMarginLeft).isEqualTo(ReaderDisplayPreferences.MAX_EPUB_MARGIN)
        assertThat(changed.epubMarginRight).isEqualTo(0)

        store.update { it.copy(comicFitMode = ReaderFitMode.PAGE_HEIGHT) }
        val normalized = store.preferences.first()
        assertThat(normalized.pdfFitMode).isEqualTo(ReaderFitMode.PAGE_FIT)
        assertThat(normalized.comicFitMode).isEqualTo(ReaderFitMode.PAGE_FIT)
    }
}
