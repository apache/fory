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

package org.apache.fory.serializer.kotlin

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import org.apache.fory.Fory
import org.apache.fory.memory.MemoryBuffer
import org.apache.fory.serializer.ImmutableSerializer
import org.apache.fory.serializer.Serializer

public class DurationSerializer(fory: Fory, needToWriteRef: Boolean) :
  ImmutableSerializer<Duration>(fory, Duration::class.java, needToWriteRef) {

  public constructor(
    fory: Fory,
  ) : this(fory, fory.config.isTimeRefIgnored)

  private val durationUnitSerializer: Serializer<DurationUnit> by lazy {
    fory.typeResolver.getSerializer(DurationUnit::class.java)
  }

  override fun write(buffer: MemoryBuffer, value: Duration) {
    val unit = computeDurationUnitPrecision(value)
    durationUnitSerializer.write(buffer, unit)

    val rawValue: Long =
      when (unit) {
        DurationUnit.NANOSECONDS -> value.inWholeNanoseconds
        DurationUnit.MICROSECONDS -> value.inWholeMicroseconds
        DurationUnit.MILLISECONDS -> value.inWholeMilliseconds
        DurationUnit.SECONDS -> value.inWholeSeconds
        DurationUnit.MINUTES -> value.inWholeMinutes
        DurationUnit.HOURS -> value.inWholeHours
        DurationUnit.DAYS -> value.inWholeDays
      }

    buffer.writeInt64(rawValue)
  }

  override fun read(buffer: MemoryBuffer): Duration {
    val unit = durationUnitSerializer.read(buffer)
    val rawValue = buffer.readInt64()
    return when (unit) {
      DurationUnit.NANOSECONDS -> rawValue.nanoseconds
      DurationUnit.MICROSECONDS -> rawValue.microseconds
      DurationUnit.MILLISECONDS -> rawValue.milliseconds
      DurationUnit.SECONDS -> rawValue.seconds
      DurationUnit.MINUTES -> rawValue.minutes
      DurationUnit.HOURS -> rawValue.hours
      DurationUnit.DAYS -> rawValue.days
    }
  }

  private fun computeDurationUnitPrecision(value: Duration): DurationUnit {
    if (value == Duration.ZERO) {
      return DurationUnit.NANOSECONDS
    }
    if (value.isInfinite()) {
      return DurationUnit.MILLISECONDS
    }

    value.absoluteValue.toComponents { days, hours, minutes, seconds, nanoseconds ->
      val millisecondPrecision = nanoseconds >= 1_000_000 && nanoseconds % 1_000_000 == 0
      if (millisecondPrecision) {
        return DurationUnit.MILLISECONDS
      }
      val microsecondPrecision = nanoseconds >= 1_000 && nanoseconds % 1_000 == 0
      if (microsecondPrecision) {
        return DurationUnit.MICROSECONDS
      }
      val nanoSecondPrecision = nanoseconds != 0 && nanoseconds % 1000 != 0
      if (nanoSecondPrecision) {
        return DurationUnit.NANOSECONDS
      }

      if (seconds != 0) {
        return DurationUnit.SECONDS
      }

      if (minutes != 0) {
        return DurationUnit.MINUTES
      }

      if (hours != 0) {
        return DurationUnit.HOURS
      }

      // Return lowest precision unit.
      return DurationUnit.DAYS
    }
  }
}
