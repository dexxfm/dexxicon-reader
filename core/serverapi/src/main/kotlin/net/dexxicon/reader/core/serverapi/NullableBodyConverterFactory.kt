package net.dexxicon.reader.core.serverapi

import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

/**
 * Wraps another converter factory so an empty response body — or the literal JSON `null`
 * that some servers return when a resource has no data yet (e.g. BookOrbit's
 * `/audio-progress`) — deserialises to `null` instead of throwing.
 */
class NullableBodyConverterFactory(
    private val delegate: Converter.Factory,
) : Converter.Factory() {

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *>? {
        val inner = delegate.responseBodyConverter(type, annotations, retrofit) ?: return null
        return Converter<ResponseBody, Any?> { body ->
            body.use {
                val text = it.string().trim()
                if (text.isEmpty() || text == "null") {
                    null
                } else {
                    inner.convert(text.toResponseBody(it.contentType()))
                }
            }
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody>? =
        delegate.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)

    override fun stringConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, String>? = delegate.stringConverter(type, annotations, retrofit)
}
