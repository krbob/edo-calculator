package net.bobinski.edocalculator

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.bobinski.edocalculator.domain.edo.EdoPeriodBreakdown
import net.bobinski.edocalculator.domain.edo.EdoStatus
import net.bobinski.edocalculator.domain.edo.EdoValue
import net.bobinski.edocalculator.domain.validation.CalculationLimits
import net.bobinski.edocalculator.route.ApiErrorCode
import net.bobinski.edocalculator.route.ApiErrorResponse
import net.bobinski.edocalculator.route.EdoHistoryPointResponse
import net.bobinski.edocalculator.route.EdoHistoryResponse
import net.bobinski.edocalculator.route.EdoResponse
import net.bobinski.edocalculator.route.InflationResponse
import net.bobinski.edocalculator.route.MonthlyInflationPointResponse
import net.bobinski.edocalculator.route.MonthlyInflationSeriesResponse
import net.bobinski.edocalculator.route.REGISTERED_GET_PATHS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class OpenApiContractTest {

    @Test
    fun `OpenAPI document is valid and completely describes canonical routes`() {
        val openApi = loadOpenApi()

        assertEquals("3.1.0", openApi.openapi)
        assertEquals(EXPECTED_PATHS, openApi.paths.keys)
        assertEquals(
            EXPECTED_PATHS + EXPECTED_PARAMETERS.keys.map { path -> path.removePrefix("/v1") },
            REGISTERED_GET_PATHS
        )
        assertEquals(
            mapOf(
                "404" to "#/components/responses/RouteNotFound",
                "405" to "#/components/responses/MethodNotAllowed"
            ),
            openApi.extensions["x-global-error-responses"]
        )

        val operationIds = openApi.paths.values.map { pathItem ->
            requireNotNull(pathItem.get) { "Every documented route must support GET" }.operationId
        }
        assertEquals(operationIds.size, operationIds.toSet().size, "operationId values must be unique")

        EXPECTED_PARAMETERS.forEach { (path, expectedParameters) ->
            val pathItem = openApi.path(path)
            val operation = requireNotNull(pathItem.get)
            val actualParameters = operation.parameters.orEmpty()
                .map { parameter -> openApi.resolve(parameter) }
                .associate { parameter -> parameter.name to (parameter.required == true) }

            assertEquals(expectedParameters, actualParameters, "Query parameter contract differs for $path")
            assertEquals(
                DOMAIN_RESPONSE_STATUSES,
                operation.responses.keys,
                "Response contract differs for $path"
            )
            assertEquals(path.removePrefix("/v1"), pathItem.extensions["x-legacy-alias"])
        }

        val healthOperation = requireNotNull(openApi.path("/healthz").get)
        val readinessOperation = requireNotNull(openApi.path("/readyz").get)
        assertTrue(healthOperation.parameters.isNullOrEmpty())
        assertEquals(setOf("200"), healthOperation.responses.keys)
        assertTrue(readinessOperation.parameters.isNullOrEmpty())
        assertEquals(setOf("200", "503"), readinessOperation.responses.keys)
    }

    @Test
    fun `OpenAPI response schemas match serialized Kotlin models and enums`() {
        val openApi = loadOpenApi()
        val serializers = mapOf(
            "EdoResponse" to EdoResponse.serializer(),
            "EdoValue" to EdoValue.serializer(),
            "EdoPeriodBreakdown" to EdoPeriodBreakdown.serializer(),
            "EdoHistoryResponse" to EdoHistoryResponse.serializer(),
            "EdoHistoryPointResponse" to EdoHistoryPointResponse.serializer(),
            "InflationResponse" to InflationResponse.serializer(),
            "MonthlyInflationSeriesResponse" to MonthlyInflationSeriesResponse.serializer(),
            "MonthlyInflationPointResponse" to MonthlyInflationPointResponse.serializer(),
            "ApiErrorResponse" to ApiErrorResponse.serializer()
        )

        serializers.forEach { (schemaName, serializer) ->
            val schema = requireNotNull(openApi.components.schemas[schemaName])
            val serializedFields = serializer.fieldNames()

            assertEquals(serializedFields, schema.properties.keys, "$schemaName properties differ")
            assertEquals(serializedFields, schema.required.toSet(), "$schemaName required fields differ")
            assertFalse(schema.additionalProperties as Boolean, "$schemaName must reject unknown response fields")
        }

        val edoStatusValues = requireNotNull(
            requireNotNull(openApi.components.schemas["EdoResponse"]).properties["status"]
        ).enum
            .map(Any::toString)
        val errorCodeValues = requireNotNull(
            requireNotNull(openApi.components.schemas["ApiErrorResponse"]).properties["errorCode"]
        ).enum
            .map(Any::toString)

        assertEquals(EdoStatus.entries.map { status -> status.name }, edoStatusValues)
        assertEquals(ApiErrorCode.entries.map { errorCode -> errorCode.name }, errorCodeValues)
    }

    @Test
    fun `OpenAPI decimal constraints match domain limits`() {
        val openApi = loadOpenApi()
        val parameters = openApi.components.parameters

        assertEquals("#/components/schemas/RateParameter", parameters["FirstPeriodRate"]?.schema?.`$ref`)
        assertEquals("#/components/schemas/RateParameter", parameters["Margin"]?.schema?.`$ref`)
        assertEquals(
            "#/components/schemas/PrincipalParameter",
            parameters["Principal"]?.schema?.allOf?.singleOrNull()?.`$ref`
        )

        openApi.assertRuntimeDecimalConstraints(
            schemaName = "RateParameter",
            maximum = CalculationLimits.MAX_RATE_PERCENT.toPlainString()
        )
        openApi.assertRuntimeDecimalConstraints(
            schemaName = "PrincipalParameter",
            maximum = CalculationLimits.MAX_PRINCIPAL.toPlainString()
        )
    }

    @Test
    fun `static OpenAPI examples match deterministic first-period responses`() = testApplication {
        application { module() }
        val openApi = loadOpenApi()
        val examples = listOf(
            StaticExample(
                responseName = "EdoValue",
                exampleName = "firstPeriod",
                requestPath = "/v1/edo/value/at?purchaseYear=2020&purchaseMonth=1&purchaseDay=1" +
                    "&asOfYear=2020&asOfMonth=1&asOfDay=2" +
                    "&firstPeriodRate=5.00&margin=2.00&principal=100.00"
            ),
            StaticExample(
                responseName = "EdoHistory",
                exampleName = "firstPeriod",
                requestPath = "/v1/edo/history?purchaseYear=2020&purchaseMonth=1&purchaseDay=1" +
                    "&fromYear=2020&fromMonth=1&fromDay=1" +
                    "&toYear=2020&toMonth=1&toDay=3" +
                    "&firstPeriodRate=5.00&margin=2.00&principal=100.00"
            )
        )

        examples.forEach { example ->
            val documented = requireNotNull(
                openApi.components.responses[example.responseName]
                    ?.content
                    ?.get("application/json")
                    ?.examples
                    ?.get(example.exampleName)
                    ?.value
            ) { "Missing ${example.responseName}/${example.exampleName} OpenAPI example" }
            val response = client.get(example.requestPath)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                documented.toJsonElement().withoutObjectNulls(),
                Json.parseToJsonElement(response.bodyAsText()).withoutObjectNulls(),
                "Static OpenAPI example differs for ${example.requestPath}"
            )
        }
    }

    @Test
    fun `every documented path is registered and returns a request id`() {
        val openApi = loadOpenApi()

        testApplication {
            application { module() }

            openApi.paths.keys.forEach { path ->
                val response = client.get(path) {
                    header(HttpHeaders.XRequestId, CONTRACT_REQUEST_ID)
                }

                assertNotEquals(HttpStatusCode.NotFound, response.status, "$path is not registered")
                assertNotEquals(HttpStatusCode.MethodNotAllowed, response.status, "$path does not support GET")
                assertEquals(CONTRACT_REQUEST_ID, response.headers[HttpHeaders.XRequestId])
                assertEquals(
                    if (path.startsWith("/v1/")) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                    response.status,
                    "Unexpected no-parameter response for $path"
                )
            }
        }
    }

    @Test
    fun `legacy aliases preserve all versioned domain error contracts`() {
        val openApi = loadOpenApi()

        testApplication {
            application { module() }

            EXPECTED_PARAMETERS.keys.forEach { versionedPath ->
                val legacyPath = openApi.path(versionedPath).extensions["x-legacy-alias"].toString()
                val versioned = client.get(versionedPath) {
                    header(HttpHeaders.XRequestId, CONTRACT_REQUEST_ID)
                }
                val legacy = client.get(legacyPath) {
                    header(HttpHeaders.XRequestId, CONTRACT_REQUEST_ID)
                }

                assertEquals(HttpStatusCode.BadRequest, versioned.status)
                assertEquals(versioned.status, legacy.status, "Status differs for $legacyPath")
                assertEquals(versioned.bodyAsText(), legacy.bodyAsText(), "Body differs for $legacyPath")
                assertEquals(versioned.headers[HttpHeaders.ContentType], legacy.headers[HttpHeaders.ContentType])
                assertEquals(CONTRACT_REQUEST_ID, legacy.headers[HttpHeaders.XRequestId])
            }
        }
    }

    @Test
    fun `global runtime errors implement the documented stable error schema`() = testApplication {
        application { module() }

        val responses = listOf(
            client.get("/v1/does-not-exist") {
                header(HttpHeaders.XRequestId, CONTRACT_REQUEST_ID)
            } to ApiErrorCode.ROUTE_NOT_FOUND,
            client.post("/v1/edo/value") {
                header(HttpHeaders.XRequestId, CONTRACT_REQUEST_ID)
            } to ApiErrorCode.METHOD_NOT_ALLOWED
        )

        responses.forEach { (response, expectedCode) ->
            val body = response.bodyAsText()
            val error = Json.decodeFromString<ApiErrorResponse>(body)

            assertEquals(API_ERROR_FIELDS, Json.parseToJsonElement(body).jsonObject.keys)
            assertEquals(expectedCode, error.errorCode)
            assertFalse(error.retryable)
            assertEquals(CONTRACT_REQUEST_ID, error.requestId)
            assertEquals(CONTRACT_REQUEST_ID, response.headers[HttpHeaders.XRequestId])
        }
    }

    private fun loadOpenApi(): OpenAPI {
        val result = OpenAPIV3Parser().readLocation(
            OPEN_API_PATH.toAbsolutePath().toString(),
            null,
            ParseOptions().apply { isResolve = true }
        )

        assertTrue(result.messages.isNullOrEmpty(), result.messages.orEmpty().joinToString("\n"))
        return requireNotNull(result.openAPI) { "Swagger parser did not return an OpenAPI model" }
    }

    private fun OpenAPI.resolve(parameter: Parameter): Parameter {
        val reference = parameter.`$ref` ?: return parameter
        return requireNotNull(components.parameters[reference.substringAfterLast('/')]) {
            "Unresolved parameter reference: $reference"
        }
    }

    private fun OpenAPI.path(path: String) =
        requireNotNull(paths[path]) { "OpenAPI path is missing: $path" }

    private fun OpenAPI.assertRuntimeDecimalConstraints(schemaName: String, maximum: String) {
        val schema = requireNotNull(components.schemas[schemaName])
        val constraints = requireNotNull(schema.extensions?.get("x-runtime-constraints") as? Map<*, *>) {
            "$schemaName must declare x-runtime-constraints"
        }

        assertEquals("0", constraints["minimum"]?.toString())
        assertEquals(maximum, constraints["maximum"]?.toString())
        assertEquals(CalculationLimits.MAX_DECIMAL_PRECISION.toString(), constraints["maximumPrecision"]?.toString())
        assertEquals(CalculationLimits.MAX_DECIMAL_SCALE.toString(), constraints["maximumScale"]?.toString())
        assertEquals(CalculationLimits.MIN_DECIMAL_SCALE.toString(), constraints["minimumScale"]?.toString())
    }

    private fun KSerializer<*>.fieldNames(): Set<String> =
        (0 until descriptor.elementsCount)
            .map(descriptor::getElementName)
            .toSet()

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(
            entries.associate { (key, value) ->
                requireNotNull(key).toString() to value.toJsonElement()
            }
        )
        is Iterable<*> -> JsonArray(map { value -> value.toJsonElement() })
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    // Swagger Parser omits explicit YAML nulls from example maps. The response schema test
    // separately guarantees that nullable fields such as inflationPercent remain required.
    private fun JsonElement.withoutObjectNulls(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            entries
                .filter { (_, value) -> value != JsonNull }
                .associate { (key, value) -> key to value.withoutObjectNulls() }
        )
        is JsonArray -> JsonArray(map { value -> value.withoutObjectNulls() })
        else -> this
    }

    private data class StaticExample(
        val responseName: String,
        val exampleName: String,
        val requestPath: String
    )

    private companion object {
        val OPEN_API_PATH: Path = Path.of("openapi", "edo-calculator-v1.yaml")
        const val CONTRACT_REQUEST_ID = "openapi-contract-test"

        val DOMAIN_RESPONSE_STATUSES = setOf("200", "400", "503", "500")
        val API_ERROR_FIELDS = setOf("error", "errorCode", "retryable", "requestId")
        val EXPECTED_PATHS = setOf(
            "/v1/edo/value",
            "/v1/edo/value/at",
            "/v1/edo/history",
            "/v1/inflation/since",
            "/v1/inflation/between",
            "/v1/inflation/monthly",
            "/healthz",
            "/readyz",
            "/metrics"
        )
        val EXPECTED_PARAMETERS = mapOf(
            "/v1/edo/value" to requiredAndOptional(
                required = setOf("purchaseYear", "purchaseMonth", "purchaseDay", "firstPeriodRate", "margin"),
                optional = setOf("principal")
            ),
            "/v1/edo/value/at" to requiredAndOptional(
                required = setOf(
                    "purchaseYear",
                    "purchaseMonth",
                    "purchaseDay",
                    "asOfYear",
                    "asOfMonth",
                    "asOfDay",
                    "firstPeriodRate",
                    "margin"
                ),
                optional = setOf("principal")
            ),
            "/v1/edo/history" to requiredAndOptional(
                required = setOf("purchaseYear", "purchaseMonth", "purchaseDay", "firstPeriodRate", "margin"),
                optional = setOf(
                    "fromYear",
                    "fromMonth",
                    "fromDay",
                    "toYear",
                    "toMonth",
                    "toDay",
                    "principal"
                )
            ),
            "/v1/inflation/since" to requiredAndOptional(
                required = setOf("year", "month")
            ),
            "/v1/inflation/between" to requiredAndOptional(
                required = setOf("startYear", "startMonth", "endYear", "endMonth")
            ),
            "/v1/inflation/monthly" to requiredAndOptional(
                required = setOf("startYear", "startMonth", "endYear", "endMonth")
            )
        )

        fun requiredAndOptional(
            required: Set<String>,
            optional: Set<String> = emptySet()
        ): Map<String, Boolean> =
            required.associateWith { true } + optional.associateWith { false }
    }
}
