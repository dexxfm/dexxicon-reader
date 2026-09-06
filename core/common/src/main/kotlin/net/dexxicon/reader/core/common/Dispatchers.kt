package net.dexxicon.reader.core.common

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.RUNTIME

@Qualifier
@Retention(RUNTIME)
annotation class Dispatcher(val dispatcher: DexxiconDispatcher)

enum class DexxiconDispatcher { Default, IO }
