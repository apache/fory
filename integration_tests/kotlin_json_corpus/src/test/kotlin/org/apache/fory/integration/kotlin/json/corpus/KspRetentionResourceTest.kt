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

package org.apache.fory.integration.kotlin.json.corpus

import java.nio.file.Files
import java.nio.file.Path
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

public class KspRetentionResourceTest {
  @Test
  public fun emitsOnlyExactResources(): Unit {
    val generatedResources = Path.of("target/generated-resources/ksp")
    val outputs = regularFiles(generatedResources)
    val relative = outputs.map { generatedResources.relativize(it).toString() }.toSet()
    assertTrue(relative == EXPECTED_RULES, relative.toString())
    assertTrue(regularFiles(Path.of("target/generated-sources/ksp")).isEmpty())
    assertTrue(regularFiles(Path.of("target/ksp-classes")).isEmpty())

    val account = rules("PlatformAccount")
    assertTrue(account.contains("kotlin.jvm.internal.DefaultConstructorMarker"), account)
    val value = rules("PlatformId")
    assertTrue(value.contains("constructor-impl"), value)
    assertTrue(value.contains("box-impl"), value)
    assertTrue(value.contains("unbox-impl"), value)
    val sealed = rules("PlatformShape")
    assertTrue(sealed.contains("class $PACKAGE.PlatformCircle"), sealed)
    assertTrue(sealed.contains("class $PACKAGE.PlatformMarker"), sealed)
    assertTrue(sealed.contains("class $PACKAGE.PlatformSquare"), sealed)
    assertTrue(sealed.contains("class $PACKAGE.PlatformOpen"), sealed)
    assertFalse(sealed.contains("class $PACKAGE.PlatformOpenDescendant"), sealed)
    val root = rules("PlatformRoot")
    assertConstructor(root, "$PACKAGE.PlatformTokenCodec")
    assertTrue(
      root.contains("@interface org.apache.fory.json.annotation.JsonByteArray"),
      root,
    )
    assertConstructor(root, "org.apache.fory.json.codec.Base64ByteArrayCodec")
    assertConstructor(root, "org.apache.fory.json.codec.ArrayCodec\$SignedByteArrayCodec")
    assertConstructor(
      rules("PlatformDirectOverride"),
      "$PACKAGE.PlatformDirectOverrideCodec",
    )
    val mixin = mixinRules("PlatformJavaProfileMixin")
    assertTrue(mixin.contains("class $PACKAGE.PlatformJavaProfile"), mixin)
    assertTrue(mixin.contains("class $PACKAGE.PlatformJavaProfileMixin"), mixin)
    assertConstructor(
      mixinRules("PlatformMixinOverrideAnnotations"),
      "$PACKAGE.PlatformMixinOverrideCodec",
    )

    outputs.forEach { output ->
      val text = Files.readString(output)
      assertFalse(text.contains('*'), output.toString())
      assertFalse(text.contains("JsonCodec\$NoJsonValueCodec"), output.toString())
      assertFalse(text.contains("JsonCodec\$NoMapKeyCodec"), output.toString())
    }
  }

  private fun rules(model: String): String =
    resourceText("META-INF/proguard/fory-json-$PACKAGE.$model.pro")

  private fun mixinRules(model: String): String =
    resourceText("META-INF/proguard/fory-json-mixin-$PACKAGE.$model.pro")

  private fun resourceText(path: String): String =
    checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "Missing KSP resource $path" }
      .bufferedReader()
      .use { it.readText() }

  private fun assertConstructor(rules: String, type: String) {
    assertTrue(
      rules.contains("-keep,allowoptimization,allowobfuscation class $type"),
      rules,
    )
    assertTrue(rules.contains("-keepclassmembers class $type {\n  public <init>();\n}"), rules)
  }

  private fun regularFiles(root: Path): List<Path> {
    if (!Files.exists(root)) return emptyList()
    return Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }
  }

  private companion object {
    const val PACKAGE = "org.apache.fory.integration.kotlin.json.corpus"
    val EXPECTED_RULES: Set<String> =
      setOf(
        "META-INF/proguard/fory-json-$PACKAGE.PlatformAccount.pro",
        "META-INF/proguard/fory-json-$PACKAGE.PlatformBox.pro",
        "META-INF/proguard/fory-json-$PACKAGE.PlatformCircle.pro",
        "META-INF/proguard/fory-json-$PACKAGE.PlatformDirectOverride.pro",
        "META-INF/proguard/fory-json-$PACKAGE.PlatformId.pro",
        "META-INF/proguard/fory-json-$PACKAGE.PlatformMarker.pro",
        "META-INF/proguard/fory-json-$PACKAGE.PlatformOpen.pro",
        "META-INF/proguard/fory-json-$PACKAGE.PlatformRoot.pro",
        "META-INF/proguard/fory-json-$PACKAGE.PlatformShape.pro",
        "META-INF/proguard/fory-json-$PACKAGE.PlatformSquare.pro",
        "META-INF/proguard/fory-json-mixin-$PACKAGE.PlatformJavaProfileMixin.pro",
        "META-INF/proguard/fory-json-mixin-$PACKAGE.PlatformMixinOverrideAnnotations.pro",
      )
  }
}
