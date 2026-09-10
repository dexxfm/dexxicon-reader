package net.dexxicon.reader.core.common

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.RUNTIME

/** Selects which [DexxiconDispatcher] to inject. */
@Qualifier
@Retention(RUNTIME)
annotation class Dispatcher(val dispatcher: DexxiconDispatcher)
