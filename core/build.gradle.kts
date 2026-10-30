plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.locationtech.jts:jts-core:1.20.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
