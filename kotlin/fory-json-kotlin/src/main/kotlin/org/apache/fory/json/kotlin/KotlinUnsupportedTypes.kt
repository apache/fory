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

import kotlin.random.Random
import kotlin.ranges.ClosedRange
import kotlin.ranges.OpenEndRange
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.apache.fory.json.resolver.UnsupportedJsonTypeException

/** Closed rejection table for Kotlin carriers that do not define portable value schemas. */
internal object KotlinUnsupportedTypes {
  /** Returns whether this exact Kotlin class has a fixed automatic-rejection owner. */
  fun rejects(type: Class<*>): Boolean = reason(type) != null

  fun reject(type: Class<*>) {
    val reason = reason(type) ?: return
    throw UnsupportedJsonTypeException("Unsupported Kotlin JSON type ${type.name}: $reason")
  }

  private fun reason(type: Class<*>): String? {
    val name = type.name
    return when {
      isPrivateCollection(type, name) ->
        "implementation-private collections must be declared through a public collection type"
      type == Result::class.java -> "Result may retain failure execution state"
      Lazy::class.java.isAssignableFrom(type) -> "Lazy values have deferred executable state"
      Map.Entry::class.java.isAssignableFrom(type) -> "map entries have no declared result identity"
      (Iterable::class.java.isAssignableFrom(type) &&
        !Collection::class.java.isAssignableFrom(type)) ||
        Sequence::class.java.isAssignableFrom(type) ->
        "iterables and sequences may be lazy, infinite, or one-shot"
      Iterator::class.java.isAssignableFrom(type) -> "iterators are live destructive cursors"
      ClosedRange::class.java.isAssignableFrom(type) ||
        OpenEndRange::class.java.isAssignableFrom(type) ->
        "range interfaces have no single constructible concrete schema"
      Clock::class.java.isAssignableFrom(type) ||
        TimeSource::class.java.isAssignableFrom(type) ||
        TimeMark::class.java.isAssignableFrom(type) ||
        ComparableTimeMark::class.java.isAssignableFrom(type) ->
        "time sources and marks retain ambient process state"
      type == Regex::class.java ||
        name.startsWith("kotlin.text.MatcherMatchResult") ||
        name.startsWith("kotlin.text.MatchResult") ||
        name.startsWith("kotlin.text.MatchGroup") ->
        "regular expressions and match state require an application-owned resource policy"
      Random::class.java.isAssignableFrom(type) -> "random generators retain mutable entropy state"
      kotlin.Function::class.java.isAssignableFrom(type) ||
        name.startsWith("kotlin.jvm.functions.") -> "function values retain executable state"
      name.startsWith("kotlin.reflect.") -> "reflection values are class or callable authority"
      name.startsWith("kotlin.coroutines.") || name.startsWith("kotlinx.coroutines.") ->
        "coroutine values retain scheduler or continuation state"
      name.startsWith("kotlin.properties.") -> "property delegates retain executable state"
      name.startsWith("kotlin.sequences.") -> "sequences may be lazy, infinite, or one-shot"
      else -> null
    }
  }

  private fun isPrivateCollection(type: Class<*>, name: String): Boolean {
    if (!Collection::class.java.isAssignableFrom(type) && !Map::class.java.isAssignableFrom(type)) {
      return false
    }
    if (type == ArrayDeque::class.java) return false
    return name == "kotlin.collections.EmptyList" ||
      name == "kotlin.collections.EmptySet" ||
      name == "kotlin.collections.EmptyMap" ||
      name.startsWith("kotlin.collections.builders.") ||
      name.startsWith("kotlin.collections.ReversedList") ||
      name.startsWith("kotlin.enums.EnumEntries")
  }
}
