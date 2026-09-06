package net.dexxicon.reader.core.serverapi.browse

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Reads a JSON array whose entries may be plain strings (`["Jane Doe"]`) **or** objects
 * with a `name` field (`[{"id":1,"name":"Jane Doe"}]`) — BookOrbit's list endpoints return
 * the former, its detail endpoint the latter.
 */
object FlexibleStringListSerializer : KSerializer<List<String>> {

    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        return json.decodeJsonElement().jsonArray.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
                is JsonObject -> (element["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) =
        delegate.serialize(encoder, value)
}
