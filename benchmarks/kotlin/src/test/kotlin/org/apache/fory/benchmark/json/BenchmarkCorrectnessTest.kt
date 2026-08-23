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

package org.apache.fory.benchmark.json

import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class BenchmarkCorrectnessTest {
  @Test
  fun modelsHaveNoPublicZeroArgConstructor() {
    for (type in listOf(MediaContent::class.java, Media::class.java, Image::class.java)) {
      assertFalse(
        type.constructors.any { Modifier.isPublic(it.modifiers) && it.parameterCount == 0 },
        "${type.name} must not regress to a mutable Java-style model",
      )
    }
  }

  @Test
  fun allLibrariesUseEquivalentShapes() {
    val codecs = BenchmarkCodecs()
    val expected = MediaContentFixture.expected()
    val fixtureBytes = MediaContentFixture.bytes()
    val fixtureString = MediaContentFixture.text(fixtureBytes)

    assertEquals(expected, codecs.foryFromString(fixtureString))
    assertEquals(expected, codecs.foryFromBytes(fixtureBytes))
    assertEquals(expected, codecs.kotlinxFromString(fixtureString))
    assertEquals(expected, codecs.kotlinxFromBytes(fixtureBytes))
    assertEquals(expected, codecs.moshiFromString(fixtureString))
    assertEquals(expected, codecs.moshiFromBytes(fixtureBytes))
    assertEquals(expected, codecs.jacksonFromString(fixtureString))
    assertEquals(expected, codecs.jacksonFromBytes(fixtureBytes))

    val foryString = codecs.foryToString(expected)
    val foryBytes = codecs.foryToBytes(expected)
    val kotlinxString = codecs.kotlinxToString(expected)
    val kotlinxBytes = codecs.kotlinxToBytes(expected)
    val moshiString = codecs.moshiToString(expected)
    val moshiBytes = codecs.moshiToBytes(expected)
    val jacksonString = codecs.jacksonToString(expected)
    val jacksonBytes = codecs.jacksonToBytes(expected)
    val tree = codecs.tree(foryString)

    for (actual in
      listOf(
        codecs.tree(foryBytes),
        codecs.tree(kotlinxString),
        codecs.tree(kotlinxBytes),
        codecs.tree(moshiString),
        codecs.tree(moshiBytes),
        codecs.tree(jacksonString),
        codecs.tree(jacksonBytes)
      )) {
      assertEquals(tree, actual)
    }

    assertEquals(expected, codecs.foryFromString(foryString))
    assertEquals(expected, codecs.foryFromBytes(foryBytes))
    assertEquals(expected, codecs.kotlinxFromString(kotlinxString))
    assertEquals(expected, codecs.kotlinxFromBytes(kotlinxBytes))
    assertEquals(expected, codecs.moshiFromString(moshiString))
    assertEquals(expected, codecs.moshiFromBytes(moshiBytes))
    assertEquals(expected, codecs.jacksonFromString(jacksonString))
    assertEquals(expected, codecs.jacksonFromBytes(jacksonBytes))
  }
}
