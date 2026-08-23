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

package org.apache.fory.json.kotlin

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.ExperimentalTime
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.apache.fory.json.ForyJson
import org.apache.fory.json.ForyJsonException

@OptIn(ExperimentalTime::class)
class KotlinUnsupportedRuntimeTest {
  @Test
  fun executableAndReflectionFamilies() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertRejected<() -> String>(json)
    assertRejectedClass(
      json,
      Class.forName("kotlin.coroutines.jvm.internal.SuspendFunction"),
    )
    assertRejected<KClass<String>>(json)
    assertRejected<KType>(json)
    assertRejected<Continuation<String>>(json)
    assertRejected<CoroutineContext>(json)
    assertRejected<ReadOnlyProperty<Any, String>>(json)
  }

  @Test
  fun lazyAndCursorFamilies() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertRejected<Result<String>>(json)
    assertRejected<Lazy<String>>(json)
    assertRejected<Iterable<Int>>(json)
    assertRejected<Iterator<Int>>(json)
    assertRejected<ListIterator<Int>>(json)
    assertRejected<Map.Entry<String, Int>>(json)
  }

  @Test
  fun abstractRangeAndTimeStateFamilies() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertRejected<ClosedRange<Double>>(json)
    assertRejected<OpenEndRange<Int>>(json)
    assertRejected<Clock>(json)
    assertRejected<TimeSource>(json)
    assertRejected<TimeMark>(json)
    assertRejected<ComparableTimeMark>(json)
  }

  private inline fun <reified T> assertRejected(json: ForyJson) {
    val failure = assertFailsWith<ForyJsonException> { json.fromJson("{}", jsonTypeRef<T>()) }
    assertTrue(
      failure.message?.contains("Unsupported Kotlin JSON type") == true,
      failure.message,
    )
  }

  @Suppress("UNCHECKED_CAST")
  private fun assertRejectedClass(json: ForyJson, type: Class<*>) {
    val failure = assertFailsWith<ForyJsonException> { json.fromJson("{}", type as Class<Any>) }
    assertTrue(
      failure.message?.contains("Unsupported Kotlin JSON type") == true,
      failure.message,
    )
  }
}
