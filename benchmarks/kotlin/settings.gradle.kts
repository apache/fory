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

import org.gradle.util.GradleVersion

pluginManagement {
  val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
  val kspVersion = providers.gradleProperty("kspVersion").get()
  val jmhPluginVersion = providers.gradleProperty("jmhPluginVersion").get()
  plugins {
    id("org.jetbrains.kotlin.jvm") version kotlinVersion
    id("org.jetbrains.kotlin.plugin.serialization") version kotlinVersion
    id("com.google.devtools.ksp") version kspVersion
    id("me.champeau.jmh") version jmhPluginVersion
  }
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    providers.gradleProperty("foryMavenRepository").orNull?.let { maven { url = uri(it) } }
    mavenLocal()
    mavenCentral()
  }
}

val requiredGradle = providers.gradleProperty("gradleVersion").get()
check(GradleVersion.current() == GradleVersion.version(requiredGradle)) {
  "Kotlin JSON benchmarks require Gradle $requiredGradle, found ${GradleVersion.current()}"
}

rootProject.name = "fory-kotlin-json-benchmarks"
