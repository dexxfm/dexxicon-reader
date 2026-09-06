package net.dexxicon.reader.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import net.dexxicon.reader.core.media.AudiobookPlayer
import net.dexxicon.reader.core.media.PlayerUiState
import javax.inject.Inject

@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val player: AudiobookPlayer,
) : ViewModel() {

    val playback: StateFlow<PlayerUiState> = player.state

    fun playPause() = player.playPause()
    fun dismiss() = player.stop()
}
