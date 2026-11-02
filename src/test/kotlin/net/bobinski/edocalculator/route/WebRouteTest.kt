package net.bobinski.edocalculator.route

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.bobinski.edocalculator.module
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebRouteTest {
    @Test
    fun `versioned emission catalog uses the same snapshot as the UI`() = testApplication {
        application { module() }
        val response = client.get("/v1/edo/series")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        assertEquals(client.get("/ui/series.json").bodyAsText(), response.bodyAsText())
        val catalog = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("updatedAt", "series"), catalog.keys)
        catalog.getValue("series").jsonArray.forEach { entry ->
            assertEquals(setOf("code", "saleStart", "saleEnd", "firstPeriodRate", "margin", "sourceUrl", "verifiedAt"),
                entry.jsonObject.keys)
        }
        assertEquals(HttpStatusCode.MethodNotAllowed, client.post("/v1/edo/series").status)
    }

    @Test
    fun `web UI and its bundled assets are served without an external provider`() = testApplication {
        application { module() }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/html"))
        val html = page.bodyAsText()
        assertTrue(html.contains("lang=\"pl\""))

        val assets = Regex("(?:src|href)=\"(/ui/[^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(assets.isNotEmpty())
        (assets + listOf("/ui/model.js", "/ui/chart.js", "/ui/series.json")).forEach { path ->
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertTrue(response.bodyAsText().isNotBlank(), path)
        }
        val catalog = Json.parseToJsonElement(client.get("/ui/series.json").bodyAsText()).jsonObject
        assertFalse(catalog.getValue("series").jsonArray.isEmpty())
    }

    @Test
    fun `web routes do not swallow API errors or expose other classpath resources`() = testApplication {
        application { module() }

        val missing = client.get("/v1/missing")
        val method = client.post("/v1/edo/value")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(HttpStatusCode.MethodNotAllowed, method.status)
        assertEquals(ApiErrorCode.ROUTE_NOT_FOUND, Json.decodeFromString<ApiErrorResponse>(missing.bodyAsText()).errorCode)
        assertEquals(HttpStatusCode.NotFound, client.get("/ui/application.yaml").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/ui/missing.js").status)
    }
}
