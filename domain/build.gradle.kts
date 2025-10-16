plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.koin.core)
}

tasks.test {
    useJUnitPlatform()
}