plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project("::core"))
    implementation(libs.koin.core)
    implementation(libs.kotlinx.datetime)
}

tasks.test {
    useJUnitPlatform()
}