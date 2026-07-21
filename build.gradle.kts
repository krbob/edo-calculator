plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.cyclonedx)
}

allprojects {
    group = "net.bobinski.edocalculator"
    version = "development"

    apply(plugin = "dev.detekt")

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    // The project version identifies SBOM components; published archive names stay backward-compatible.
    tasks.withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
        archiveVersion.set("")
    }

    configure<dev.detekt.gradle.extensions.DetektExtension> {
        config.from(rootProject.files("detekt.yml"))
    }

    tasks.withType<org.cyclonedx.gradle.BaseCyclonedxTask>().configureEach {
        componentGroup.set("net.bobinski.edocalculator")
        componentVersion.set("development")
        includeBomSerialNumber.set(false)
        includeBuildSystem.set(false)
        externalReferences.set(
            listOf(
                org.cyclonedx.model.ExternalReference().apply {
                    type = org.cyclonedx.model.ExternalReference.Type.VCS
                    url = "https://github.com/krbob/edo-calculator"
                },
            ),
        )
        xmlOutput.unsetConvention()
    }

    tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
        includeConfigs.set(listOf("runtimeClasspath"))
    }
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

val publishMultiPlatformImage = providers.gradleProperty("publishMultiPlatformImage")
    .map(String::toBoolean)
    .getOrElse(false)

val jibBaseImage =
    "gcr.io/distroless/java21-debian13:nonroot@sha256:c1ab839be0b871268e437a008e154be87f8fabca0202dcd393633c7b263b8e78"

jib {
    from {
        image = jibBaseImage
        if (publishMultiPlatformImage) {
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
        creationTime = "EPOCH"
        user = "65532:65532"
        ports = listOf("8080")
    }
}

tasks.named<org.cyclonedx.gradle.CyclonedxAggregateTask>("cyclonedxBom") {
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    componentName.set("edo-calculator")
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/edo-calculator.cdx.json"))
    doLast {
        val sbomFile = jsonOutput.get().asFile
        val sbom = sbomFile.readText(Charsets.UTF_8)
        val timestampPattern = Regex(""""timestamp"\s*:\s*"[^"]+"""")
        check(timestampPattern.findAll(sbom).count() == 1) {
            "Expected exactly one CycloneDX metadata timestamp in ${sbomFile.path}."
        }
        sbomFile.writeText(
            sbom.replace(timestampPattern, """"timestamp" : "1970-01-01T00:00:00Z""""),
            Charsets.UTF_8,
        )
    }
}

tasks.register("resolveAndLockAll") {
    group = "build setup"
    description = "Resolves every resolvable configuration and writes complete dependency lock state."
    doFirst {
        check(gradle.startParameter.isWriteDependencyLocks) {
            "Run this task with --write-locks."
        }
    }
    doLast {
        allprojects
            .flatMap { it.configurations }
            .filter { it.isCanBeResolved }
            .forEach { it.resolve() }
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
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.kotlinx.datetime)
    implementation(libs.micrometer.registry.prometheus)
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
