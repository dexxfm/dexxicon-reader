package net.dexxicon.reader.core.common.di

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.RUNTIME

/** A process-lifetime [kotlinx.coroutines.CoroutineScope] for fire-and-forget work.
 *  Provided by the app's `DispatchersModule`. */
@Qualifier
@Retention(RUNTIME)
annotation class ApplicationScope
