package net.bobinski.edocalculator.core.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import kotlinx.serialization.modules.SerializersModule
import java.math.BigDecimal

internal object AppJsonFactory {
    val serializersModule: SerializersModule = SerializersModule {
        contextual(BigDecimal::class, BigDecimalSerializer)
    }

    fun create(configure: JsonBuilder.() -> Unit = {}): Json = Json {
        serializersModule = AppJsonFactory.serializersModule
        isLenient = true
        prettyPrint = true
        ignoreUnknownKeys = true
        configure()
    }
}

private object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        return BigDecimal(decoder.decodeString())
    }
}