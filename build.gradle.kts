plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.detekt)
}

allprojects {
    apply(plugin = "dev.detekt")
    configure<dev.detekt.gradle.extensions.DetektExtension> {
        config.from(rootProject.files("detekt.yml"))
    }
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

val publishMultiPlatformImage = providers.gradleProperty("publishMultiPlatformImage")
    .map(String::toBoolean)
    .getOrElse(false)

jib {
    if (publishMultiPlatformImage) {
        from {
            platforms {
                platform {
                    architecture = "amd64"
                    os = "linux"
                }
                platform {
                    architecture = "arm64"
                    os = "linux"
                }
            }
        }
    }
    container {
        user = "65532:65532"
        ports = listOf("8080")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project("::core"))
    implementation(project("::client"))
    implementation(project("::domain"))
    implementation(project("::inflation-gus"))
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.kotlinx.datetime)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.swagger.parser)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    inputs.file(layout.projectDirectory.file("openapi/edo-calculator-v1.yaml"))
}
