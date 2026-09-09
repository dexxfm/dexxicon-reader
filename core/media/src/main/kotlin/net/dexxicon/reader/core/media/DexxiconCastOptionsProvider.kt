package net.dexxicon.reader.core.media

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Wired to the Cast framework via the `OPTIONS_PROVIDER_CLASS_NAME` manifest meta-data.
 * We use the Default Media Receiver — the app has no custom Cast receiver — so the Cast
 * device fetches the stream URL itself. Authenticated streams are handed a `?token=` in
 * the URL (see [MediaLibraryContentSource]); servers that don't accept that will fail to
 * load on the receiver, surfaced as a playback error.
 */
class DexxiconCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Cast selector category for the Default Media Receiver — used to discover Cast routes. */
val CAST_RECEIVER_APP_ID: String = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
