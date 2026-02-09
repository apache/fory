/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

val foryVersion = "0.16.0-SNAPSHOT"
val scala213Version = "2.13.15"
ThisBuild / apacheSonatypeProjectProfile := "fory"
version := foryVersion
scalaVersion := scala213Version
crossScalaVersions := Seq(scala213Version, "3.3.1")

lazy val root = Project(id = "fory-scala", base = file("."))
  .settings(
    name := "fory-scala",
    apacheSonatypeLicenseFile := baseDirectory.value / ".." / "LICENSE",
    apacheSonatypeNoticeFile := baseDirectory.value / ".." / "NOTICE",
    description := "Apache Fory™ is a blazingly fast multi-language serialization framework powered by JIT and zero-copy.",
    homepage := Some(url("https://fory.apache.org/")),
    startYear := Some(2024),
    developers := List(
      Developer(
        "fory-contributors",
        "Apache Fory™ Contributors",
        "dev@fory.apache.org",
        url("https://github.com/apache/fory/graphs/contributors"))))

resolvers += Resolver.mavenLocal
resolvers += Resolver.ApacheMavenSnapshotsRepo

libraryDependencies ++= Seq(
  "org.apache.fory" % "fory-core" % foryVersion,
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  "dev.zio" %% "zio" % "2.1.7" % Test,
)

// Exclude sonatypeRelease and sonatypeBundleRelease commands because we
// don't want to release this project to Maven Central without having
// to complete the release using the repository.apache.org web site.
commands := commands.value.filterNot { command =>
  command.nameOption.exists { name =>
    name.contains("sonatypeRelease") || name.contains("sonatypeBundleRelease")
  }
}
