import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(rootProject)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets["main"].kotlin.srcDir("src")

// Pick the example with -PmainClass=com.tricoredb.kt.examples.QuickstartKt
application {
    mainClass.set(
        providers.gradleProperty("mainClass")
            .orElse("com.tricoredb.kt.examples.QuickstartKt")
    )
}
