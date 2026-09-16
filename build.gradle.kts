import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.21"
    `java-library`
    id("org.jetbrains.dokka") version "2.0.0"
    id("org.jetbrains.dokka-javadoc") version "2.0.0"
    `maven-publish`
    signing
}

val sdkVersion = version.toString()

base {
    archivesName.set("tricoredb-kotlin")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    // `api`, not `implementation`: every call in this SDK is a suspend function, so a
    // consumer needs coroutines on its own compile classpath to call one at all.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    val v = sdkVersion
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/tricoredb/kt/SdkVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.tricoredb.kt
            |
            |/** The version of this SDK, as published to Maven Central. */
            |public const val SDK_VERSION: String = "$v"
            |""".trimMargin()
        )
    }
}

sourceSets["main"].kotlin.srcDir(generateVersion)

tasks.test {
    useJUnitPlatform {
        excludeTags("live")
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs the live tests against a private tricore-server."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("live")
    }
    System.getenv("TRICORE_SERVER_BIN")?.let { environment("TRICORE_SERVER_BIN", it) }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    shouldRunAfter(tasks.test)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dokka {
    moduleName.set("tricoredb-kotlin")
    dokkaSourceSets.configureEach {
        reportUndocumented.set(true)
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/trinesh14/tricoredb-sdk-kotlin/tree/main/src/main/kotlin")
        }
    }
}

val javadocJar by tasks.registering(Jar::class) {
    description = "A javadoc jar built by Dokka, as Maven Central requires."
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationJavadoc"))
}

publishing {
    repositories {
        // A local staging repository laid out exactly as Maven Central wants it.
        // `./gradlew centralBundle` zips it for the Central Portal's upload page, so
        // no portal token ever has to live in a Gradle file.
        maven {
            name = "centralBundle"
            url = layout.buildDirectory.dir("central-bundle").get().asFile.toURI()
        }
    }

    publications {
        create<MavenPublication>("maven") {
            artifactId = "tricoredb-kotlin"
            from(components["java"])
            artifact(javadocJar)
            pom {
                name.set("TriCoreDB Kotlin SDK")
                description.set(
                    "A native Kotlin client for TriCoreDB: coroutines over the tricore wire protocol, " +
                        "covering SQL, documents, cache, vectors, graphs and LLM context export."
                )
                url.set("https://github.com/trinesh14/tricoredb-sdk-kotlin")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("trinesh14")
                        name.set("Trinesh Kumar")
                        email.set("trinesh14kumar@gmail.com")
                        url.set("https://github.com/trinesh14")
                    }
                }
                scm {
                    url.set("https://github.com/trinesh14/tricoredb-sdk-kotlin")
                    connection.set("scm:git:https://github.com/trinesh14/tricoredb-sdk-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/trinesh14/tricoredb-sdk-kotlin.git")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/trinesh14/tricoredb-sdk-kotlin/issues")
                }
            }
        }
    }
}

// Signing is opt-in: pass -Ptricoredb.sign=true to sign. A plain build and
// publishToMavenLocal never sign.
val signingEnabled = providers.gradleProperty("tricoredb.sign").map { it.toBoolean() }.getOrElse(false)

signing {
    isRequired = signingEnabled
    val key = providers.gradleProperty("signingInMemoryKey").orNull
    val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    if (signingEnabled) {
        if (key != null) {
            // An armored key passed in directly, parsed by Bouncy Castle.
            useInMemoryPgpKeys(key, password)
        } else {
            // The `gpg` binary signs instead. This is the default because Bouncy
            // Castle cannot read the secret-key format GnuPG 2.4 writes, so an
            // exported key fails with "Could not read PGP secret key" — while gpg
            // itself has no trouble with its own keys. Name the key and pass its
            // passphrase with:
            //
            //   -Psigning.gnupg.keyName=KEYID -Psigning.gnupg.passphrase=...
            useGpgCmd()
        }
        sign(publishing.publications["maven"])
    }
}

/**
 * Builds the zip the Central Portal accepts: the signed artifacts, their checksums and
 * the POM, under `com/tricoredb/tricoredb-kotlin/<version>/`.
 *
 * Signing is required for Maven Central, so this task refuses to run unsigned:
 *
 *     ./gradlew centralBundle -Ptricoredb.sign=true -PsigningInMemoryKey=... -PsigningInMemoryKeyPassword=...
 */
val centralBundle by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Zips the signed artifacts for upload to the Central Portal."
    dependsOn("publishMavenPublicationToCentralBundleRepository")
    doFirst {
        require(signingEnabled) {
            "a Central Portal bundle must be signed: rerun with -Ptricoredb.sign=true and the signing key properties"
        }
    }
    from(layout.buildDirectory.dir("central-bundle")) {
        // Central takes the artifacts themselves; repository metadata is not part of a bundle.
        exclude("**/maven-metadata*")
    }
    archiveFileName.set("tricoredb-kotlin-$sdkVersion-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
