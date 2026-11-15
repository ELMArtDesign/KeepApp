/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    useTestNG()
}

dependencies {
    // Modulo Kotlin PURO: nessuna dipendenza dal framework Android.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Dipendenze di test unitari JVM (100% Apache License 2.0)
    testImplementation("org.jetbrains.kotlin:kotlin-test-testng:2.2.20")
    testImplementation("org.testng:testng:7.10.2")
}
