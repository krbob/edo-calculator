plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project("::domain"))
    implementation(libs.koin.core)
}

tasks.test {
    useJUnitPlatform()
}