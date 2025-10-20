plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.koin.core)
    implementation(libs.kotlinx.datetime)
}

tasks.test {
    useJUnitPlatform()
}