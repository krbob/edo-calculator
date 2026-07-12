package net.bobinski.edocalculator.core.serialization

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppJsonFactoryTest {

    @Test
    fun `uses compact JSON by default`() {
        val payload = buildJsonObject { put("value", "ok") }
        val encoded = AppJsonFactory.create().encodeToString(JsonElement.serializer(), payload)

        assertEquals("{\"value\":\"ok\"}", encoded)
    }
}
