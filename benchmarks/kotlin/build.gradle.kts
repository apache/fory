/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.net.URLClassLoader

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.kotlin.plugin.serialization")
  id("com.google.devtools.ksp")
  id("me.champeau.jmh")
}

group = "org.apache.fory.benchmark"
version = "1.0-SNAPSHOT"

val foryVersion: String by project
val kotlinxSerializationVersion: String by project
val moshiVersion: String by project
val jacksonVersion: String by project
val benchmarkJmhVersion = providers.gradleProperty("jmhVersion").get()

dependencies {
  implementation("org.apache.fory:fory-json-kotlin:$foryVersion")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
  implementation("com.squareup.moshi:moshi:$moshiVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

  ksp("com.squareup.moshi:moshi-kotlin-codegen:$moshiVersion")

  testImplementation("org.junit.jupiter:junit-jupiter:5.14.1")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.1")
}

kotlin {
  jvmToolchain(17)
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
  }
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.test {
  useJUnitPlatform()
}

jmh {
  jmhVersion = benchmarkJmhVersion
  benchmarkMode = listOf("thrpt")
  timeUnit = "s"
  warmupIterations = 3
  iterations = 5
  fork = 1
  threads = 1
  timeOnIteration = "2s"
  warmup = "2s"
  resultFormat = "JSON"
}

val verifyGeneratedJsonArtifacts = tasks.register("verifyGeneratedJsonArtifacts") {
  dependsOn(tasks.named("classes"))
  doLast {
    val runtimeFiles = sourceSets.main.get().runtimeClasspath.files
    URLClassLoader(runtimeFiles.map { it.toURI().toURL() }.toTypedArray(), null).use { loader ->
      val modelNames =
        listOf(
          "org.apache.fory.benchmark.json.MediaContent",
          "org.apache.fory.benchmark.json.Media",
          "org.apache.fory.benchmark.json.Image",
        )
      for (modelName in modelNames) {
        loader.loadClass(modelName + "JsonAdapter")
      }
    }
  }
}

tasks.named("check") {
  dependsOn(verifyGeneratedJsonArtifacts)
}

tasks.matching { it.name == "compileJmhKotlin" || it.name == "jmhClasses" }.configureEach {
  dependsOn(verifyGeneratedJsonArtifacts)
}
