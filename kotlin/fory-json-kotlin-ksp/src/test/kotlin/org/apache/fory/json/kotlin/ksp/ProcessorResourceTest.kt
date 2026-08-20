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

package org.apache.fory.json.kotlin.ksp

import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProcessorResourceTest {
  @Test
  fun discoversProvider() {
    val providers =
      ServiceLoader.load(SymbolProcessorProvider::class.java)
        .filterIsInstance<ForyJsonKotlinSymbolProcessorProvider>()

    assertEquals(1, providers.size)
  }

  @Test
  fun writesOwnedResources() {
    val directSource = sourceFile("Profile.kt")
    val sealedSource = sourceFile("Shape.kt")
    val mixinSource = sourceFile("ProfileMixin.kt")
    val mixinTargetSource = sourceFile("ExternalProfile.java")
    val directCodecTypes = codecTypes("example.direct")
    val mixinCodecTypes = codecTypes("example.mixin")
    val models =
      listOf(
        jsonModel(
          targetBinaryName = "example.Profile",
          originatingFiles = listOf(directSource),
          retainedAnnotations = setOf("org.apache.fory.json.annotation.JsonType"),
          codecTypes = directCodecTypes,
        ),
        jsonModel(
          targetBinaryName = "example.Shape",
          originatingFiles = listOf(sealedSource),
          retainedAnnotations = setOf("org.apache.fory.json.annotation.JsonSubTypes"),
          retainedTypes = setOf("example.Circle"),
        ),
        jsonModel(
          targetBinaryName = "example.ExternalProfile",
          mixinBinaryName = "mixins.ProfileMixin",
          originatingFiles = listOf(mixinSource, mixinTargetSource),
          codecTypes = mixinCodecTypes,
          members =
            listOf(
              JvmMember(
                MemberKind.FIELD,
                "mixins.ProfileMixin",
                "renamed",
                "Ljava/lang/String;",
              )
            ),
        ),
      )
    val generator = RecordingCodeGenerator()
    val processor =
      ForyJsonKotlinSymbolProcessorProvider()
        .create(
          SymbolProcessorEnvironment(
            emptyMap(),
            KotlinVersion.CURRENT,
            generator,
            SilentLogger,
          )
        )
    val write = processor.javaClass.getDeclaredMethod("write", JsonModel::class.java)
    write.isAccessible = true

    models.forEach { write.invoke(processor, it) }

    assertEquals(
      setOf(
        "META-INF/proguard/fory-json-example.Profile.pro",
        "META-INF/proguard/fory-json-example.Shape.pro",
        "META-INF/proguard/fory-json-mixin-mixins.ProfileMixin.pro",
      ),
      generator.outputs.keys,
    )
    models.forEach { model ->
      val path = R8RulesWriter.resourcePath(model)
      assertEquals(R8RulesWriter.write(model), generator.text(path))
      val dependencies = generator.dependenciesByPath.getValue(path)
      assertFalse(dependencies.aggregating, path)
      assertFalse(dependencies.isAllSources, path)
      assertEquals(model.originatingFiles.size, dependencies.originatingFiles.size, path)
      model.originatingFiles.forEachIndexed { index, source ->
        assertSame(source, dependencies.originatingFiles[index], path)
      }
    }
    assertTrue(
      generator.text("META-INF/proguard/fory-json-example.Shape.pro").contains("example.Circle")
    )
    assertCodecRules(
      generator.text("META-INF/proguard/fory-json-example.Profile.pro"),
      directCodecTypes,
    )
    assertCodecRules(
      generator.text("META-INF/proguard/fory-json-mixin-mixins.ProfileMixin.pro"),
      mixinCodecTypes,
    )
  }

  private fun codecTypes(packageName: String): Set<String> =
    linkedSetOf(
      "$packageName.MapValueCodec",
      "$packageName.WholeValueCodec",
      "$packageName.KeyCodec",
      "$packageName.ContentCodec",
      "$packageName.ElementCodec",
    )

  private fun assertCodecRules(rules: String, codecTypes: Set<String>) {
    codecTypes.forEach { codecType ->
      val exactRule =
        """-keep,allowoptimization,allowobfuscation class $codecType
-keepclassmembers class $codecType {
  public <init>();
}"""
      assertEquals(1, rules.split(exactRule).size - 1, codecType)
    }
    assertEquals(codecTypes.size, rules.lineSequence().count { it == "  public <init>();" })
    assertFalse(rules.contains('*'), rules)
    assertFalse(rules.contains("JsonCodec\$NoJsonValueCodec"), rules)
    assertFalse(rules.contains("JsonCodec\$NoMapKeyCodec"), rules)
  }
}
