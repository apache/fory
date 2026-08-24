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

val foryVersion = "1.7.0"
val scala213Version = "2.13.18"
val repositoryRoot = Def.setting((ThisBuild / baseDirectory).value.getParentFile)

ThisBuild / apacheSonatypeProjectProfile := "fory"
ThisBuild / version := foryVersion
ThisBuild / scalaVersion := scala213Version
ThisBuild / crossScalaVersions := Seq(scala213Version, "3.3.8")

val localForyResolver =
  sys.props
    .get("fory.maven.repo")
    .map(repo => "Local Fory Maven Repository" at repo)
    .getOrElse(Resolver.mavenLocal)

ThisBuild / externalResolvers := Seq(
  Resolver.mavenCentral,
  Resolver.ApacheMavenSnapshotsRepo,
  localForyResolver,
)

lazy val commonSettings = Seq(
  apacheSonatypeLicenseFile := repositoryRoot.value / "LICENSE",
  apacheSonatypeNoticeFile := repositoryRoot.value / "NOTICE",
  description := "Apache Fory™ is a blazingly fast multi-language serialization framework powered by JIT and zero-copy.",
  homepage := Some(url("https://fory.apache.org/")),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/apache/fory"),
      "scm:git:https://github.com/apache/fory.git",
      Some("scm:git:https://github.com/apache/fory.git")
    )
  ),
  startYear := Some(2024),
  developers := List(
    Developer(
      "fory-contributors",
      "Apache Fory™ Contributors",
      "dev@fory.apache.org",
      url("https://github.com/apache/fory/graphs/contributors")
    )
  ),
  Test / fork := true,
)

lazy val foryScala = Project(id = "fory-scala", base = file("fory-scala"))
  .settings(commonSettings)
  .settings(
    name := "fory-scala",
    Compile / javacOptions ++= Seq("--release", "8"),
    libraryDependencies ++= Seq(
      "org.apache.fory" % "fory-core" % foryVersion,
      "org.scalatest" %% "scalatest" % "3.2.20" % Test,
      "dev.zio" %% "zio" % "2.1.26" % Test,
    ),
  )

lazy val foryJsonScala = Project(id = "fory-json-scala", base = file("fory-json-scala"))
  .settings(commonSettings)
  .settings(
    name := "fory-json-scala",
    Compile / javacOptions ++= Seq("--release", "8"),
    libraryDependencies ++= {
      val reflect =
        if (scalaBinaryVersion.value == "2.13")
          Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided)
        else Nil
      Seq(
        "org.apache.fory" % "fory-json" % foryVersion,
        "org.scalatest" %% "scalatest" % "3.2.20" % Test,
      ) ++ reflect
    },
  )

lazy val writeTestClasspath = taskKey[File]("Writes the Scala test runtime classpath")

lazy val root = (project in file("."))
  .aggregate(foryScala, foryJsonScala)
  .settings(
    name := "fory-scala-parent",
    publish / skip := true,
    crossScalaVersions := Nil,
    apacheSonatypeLicenseFile := repositoryRoot.value / "LICENSE",
    apacheSonatypeNoticeFile := repositoryRoot.value / "NOTICE",
    writeTestClasspath := {
      val output = target.value / "scala-xlang-test-classpath"
      IO.write(
        output,
        (foryScala / Test / fullClasspath).value
          .map(_.data.getAbsolutePath)
          .mkString(java.io.File.pathSeparator),
      )
      output
    },
  )

commands := commands.value.filterNot { command =>
  command.nameOption.exists { name =>
    name.contains("sonatypeRelease") || name.contains("sonatypeBundleRelease")
  }
}
