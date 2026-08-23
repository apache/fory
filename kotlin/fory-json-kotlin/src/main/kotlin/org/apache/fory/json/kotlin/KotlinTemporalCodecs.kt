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

import java.lang.reflect.Method
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimedValue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.DirectUnboxedValueCodec
import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.reader.Latin1JsonReader
import org.apache.fory.json.reader.Utf16JsonReader
import org.apache.fory.json.reader.Utf8JsonReader
import org.apache.fory.json.writer.JsonWriter
import org.apache.fory.json.writer.StringJsonWriter
import org.apache.fory.json.writer.Utf8JsonWriter
import org.apache.fory.reflect.TypeRef

/** Allocation-free token codecs for Kotlin's standard temporal and UUID values. */
@OptIn(ExperimentalUuidApi::class)
internal object KotlinTemporalCodecs {
  private const val SECONDS_PER_DAY = 86_400L
  private const val DAYS_0000_TO_1970 = 719_528L
  private const val MIN_INSTANT_SECOND = -31_557_014_167_219_200L
  private const val MAX_INSTANT_SECOND = 31_556_889_864_403_199L
  // Kotlin Duration 2.3 uses this millisecond boundary as both saturating limit and infinity.
  private const val MAX_DURATION_MILLIS = Long.MAX_VALUE / 2
  /** Returns whether this family owns the exact Kotlin class instead of metadata fallback. */
  fun supports(rawType: Class<*>): Boolean =
    rawType == Duration::class.java || rawType == Instant::class.java || rawType == Uuid::class.java

  fun create(type: TypeRef<*>): JsonValueCodec<*>? =
    when (type.rawType) {
      Duration::class.java -> DurationCodec
      Instant::class.java -> InstantCodec
      Uuid::class.java -> UuidCodec
      else -> null
    }

  @JvmStatic
  @JvmName("writeDurationRaw")
  fun writeDurationRaw(writer: JsonWriter, value: Duration) = writeDurationValue(writer, value)

  @JvmStatic
  @JvmName("readDurationRaw")
  fun readDurationRaw(reader: JsonReader): Duration {
    val text = reader.readQuotedText() ?: nullDuration()
    return parseDuration(text)
  }

  @JvmName("timedDurationRaw") fun timedDurationRaw(value: TimedValue<*>): Duration = value.duration

  @JvmName("newTimedValue")
  fun newTimedValue(value: Any?, duration: Duration): TimedValue<Any?> = TimedValue(value, duration)

  private object DurationCodec : JsonValueCodec<Duration>, DirectUnboxedValueCodec {
    private object Methods {
      val read: Method =
        KotlinTemporalCodecs::class.java.getMethod("readDurationRaw", JsonReader::class.java)
      val write: Method =
        KotlinTemporalCodecs::class
          .java
          .getMethod("writeDurationRaw", JsonWriter::class.java, java.lang.Long.TYPE)
    }

    override fun writeString(writer: StringJsonWriter, value: Duration?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: Duration?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): Duration? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): Duration? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): Duration? = read(reader)

    override fun carrierType(): Class<*> = java.lang.Long.TYPE

    override fun readLatin1Carrier(reader: Latin1JsonReader): Any =
      KotlinTemporalAccess.readDurationCarrier(reader)

    override fun readUtf16Carrier(reader: Utf16JsonReader): Any =
      KotlinTemporalAccess.readDurationCarrier(reader)

    override fun readUtf8Carrier(reader: Utf8JsonReader): Any =
      KotlinTemporalAccess.readDurationCarrier(reader)

    override fun writeStringCarrier(writer: StringJsonWriter, carrier: Any) =
      KotlinTemporalAccess.writeDurationCarrier(writer, carrier)

    override fun writeUtf8Carrier(writer: Utf8JsonWriter, carrier: Any) =
      KotlinTemporalAccess.writeDurationCarrier(writer, carrier)

    override fun readCarrierMethod(): Method = Methods.read

    override fun writeCarrierMethod(): Method = Methods.write

    private fun write(writer: JsonWriter, value: Duration?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writeDurationValue(writer, value)
    }

    private fun read(reader: JsonReader): Duration? {
      val text = reader.readQuotedText() ?: return null
      return parseDuration(text)
    }
  }

  private fun writeDurationValue(writer: JsonWriter, value: Duration) {
    val negative = value.isNegative()
    val magnitude = value.absoluteValue
    if (magnitude.isInfinite()) {
      writer.writeIsoDuration(true, negative, 0, 0, 0, 0)
      return
    }
    writer.writeIsoDuration(
      false,
      negative,
      magnitude.inWholeHours,
      (magnitude.inWholeMinutes % 60).toInt(),
      (magnitude.inWholeSeconds % 60).toInt(),
      (magnitude - magnitude.inWholeSeconds.seconds).inWholeNanoseconds.toInt(),
    )
  }

  private object InstantCodec : JsonValueCodec<Instant> {
    override fun writeString(writer: StringJsonWriter, value: Instant?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: Instant?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): Instant? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): Instant? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): Instant? = read(reader)

    private fun write(writer: JsonWriter, value: Instant?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writer.writeIsoInstant(value.epochSeconds, value.nanosecondsOfSecond)
    }

    private fun read(reader: JsonReader): Instant? {
      val text = reader.readQuotedText() ?: return null
      return parseInstant(text)
    }
  }

  private object UuidCodec : JsonValueCodec<Uuid> {
    override fun writeString(writer: StringJsonWriter, value: Uuid?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: Uuid?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): Uuid? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): Uuid? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): Uuid? = read(reader)

    private fun write(writer: JsonWriter, value: Uuid?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writer.writeUuid(KotlinTemporalAccess.uuidHigh(value), KotlinTemporalAccess.uuidLow(value))
    }

    private fun read(reader: JsonReader): Uuid? {
      val text = reader.readQuotedText() ?: return null
      if (
        text.length != 36 || text[8] != '-' || text[13] != '-' || text[18] != '-' || text[23] != '-'
      ) {
        invalidUuid()
      }
      var high = 0L
      var low = 0L
      var digits = 0
      for (index in 0 until 36) {
        if (index == 8 || index == 13 || index == 18 || index == 23) continue
        val digit = hex(text[index])
        if (digits < 16) high = (high shl 4) or digit.toLong()
        else low = (low shl 4) or digit.toLong()
        digits++
      }
      return Uuid.fromLongs(high, low)
    }
  }

  private fun parseDuration(text: CharSequence): Duration {
    val length = text.length
    if (length < 3) invalidDuration()
    var index = 0
    val negative = text[index] == '-'
    if (negative || text[index] == '+') index++
    if (index >= length || text[index++] != 'P') invalidDuration()
    var totalMillis = 0L
    var totalNanos = 0L
    var sawComponent = false
    var sawTimeComponent = false
    var inTime = false
    var seen = 0
    var lastTimeOrder = 0
    while (index < length) {
      if (text[index] == 'T') {
        if (inTime) invalidDuration()
        inTime = true
        index++
        if (index == length) invalidDuration()
        continue
      }
      var componentSign = 1
      if (text[index] == '-' || text[index] == '+') {
        if (text[index] == '-') componentSign = -1
        index++
      }
      val numberStart = index
      var number = 0L
      var overflow = false
      while (index < length) {
        val ch = text[index]
        if (ch !in '0'..'9') break
        val digit = ch.code - '0'.code
        if (!overflow) {
          if (number > (MAX_DURATION_MILLIS - digit) / 10) {
            number = MAX_DURATION_MILLIS
            overflow = true
          } else {
            number = number * 10 + digit
          }
        }
        index++
      }
      if (index == numberStart || index >= length) invalidDuration()
      var fractionNanos = 0L
      if (text[index] == '.') {
        if (!inTime) invalidDuration()
        index++
        var fractionDigits = 0
        var fraction = 0L
        while (index < length && text[index] in '0'..'9') {
          if (fractionDigits < 15) {
            fraction = fraction * 10 + text[index].code - '0'.code
          }
          fractionDigits++
          index++
        }
        if (fractionDigits == 0 || index >= length || text[index] != 'S') invalidDuration()
        // parseIsoString consumes 15 fraction digits, ignores the rest, then rounds to nanos.
        repeat(15 - minOf(fractionDigits, 15)) { fraction *= 10 }
        fractionNanos = componentSign * (fraction * 0.000001).roundToLong()
      }
      when (text[index++]) {
        'D' -> {
          if (inTime || seen and 1 != 0) invalidDuration()
          seen = seen or 1
          totalMillis = signedMillis(number, componentSign, 86_400_000L)
        }
        'H' -> {
          if (!inTime || seen and 2 != 0 || lastTimeOrder >= 1) invalidDuration()
          seen = seen or 2
          lastTimeOrder = 1
          sawTimeComponent = true
          totalMillis =
            addDurationMillis(totalMillis, signedMillis(number, componentSign, 3_600_000L))
        }
        'M' -> {
          if (!inTime || seen and 4 != 0 || lastTimeOrder >= 2) invalidDuration()
          seen = seen or 4
          lastTimeOrder = 2
          sawTimeComponent = true
          totalMillis = addDurationMillis(totalMillis, signedMillis(number, componentSign, 60_000L))
        }
        'S' -> {
          if (!inTime || seen and 8 != 0 || lastTimeOrder >= 3) invalidDuration()
          seen = seen or 8
          lastTimeOrder = 3
          sawTimeComponent = true
          totalMillis = addDurationMillis(totalMillis, signedMillis(number, componentSign, 1_000L))
          totalNanos = fractionNanos
          if (index != length) invalidDuration()
        }
        else -> invalidDuration()
      }
      sawComponent = true
    }
    if (!sawComponent || inTime && !sawTimeComponent) invalidDuration()
    var value = totalMillis.milliseconds + totalNanos.nanoseconds
    if (negative) value = -value
    return value
  }

  private fun signedMillis(value: Long, sign: Int, multiplier: Long): Long {
    val magnitude =
      if (value > MAX_DURATION_MILLIS / multiplier) MAX_DURATION_MILLIS else value * multiplier
    return if (sign < 0) -magnitude else magnitude
  }

  private fun addDurationMillis(total: Long, component: Long): Long {
    if (total == MAX_DURATION_MILLIS || total == -MAX_DURATION_MILLIS) {
      if (
        (component == MAX_DURATION_MILLIS || component == -MAX_DURATION_MILLIS) &&
          total xor component < 0
      ) {
        invalidDuration()
      }
      return total
    }
    if (component == MAX_DURATION_MILLIS || component == -MAX_DURATION_MILLIS) return component
    return (total + component).coerceIn(-MAX_DURATION_MILLIS, MAX_DURATION_MILLIS)
  }

  private fun parseInstant(text: CharSequence): Instant {
    val length = text.length
    if (length < 20) invalidInstant()
    var index = 0
    var negativeYear = false
    var explicitPositive = false
    when (text[index]) {
      '-' -> {
        negativeYear = true
        index++
      }
      '+' -> {
        explicitPositive = true
        index++
      }
    }
    val yearStart = index
    var year = 0L
    while (index < length && text[index] in '0'..'9') {
      if (year > 1_000_000_000L) invalidInstant()
      year = year * 10 + text[index].code - '0'.code
      index++
    }
    val yearDigits = index - yearStart
    if (yearDigits < 4 || index >= length || text[index++] != '-') invalidInstant()
    if (!negativeYear && !explicitPositive && yearDigits != 4) invalidInstant()
    if (explicitPositive && yearDigits <= 4) invalidInstant()
    if (negativeYear) year = -year
    if (year !in -1_000_000_000L..1_000_000_000L) invalidInstant()
    val month = twoDigits(text, index)
    index += 2
    if (index >= length || text[index++] != '-') invalidInstant()
    val day = twoDigits(text, index)
    index += 2
    if (index >= length || text[index] != 'T' && text[index] != 't') invalidInstant()
    index++
    val hour = twoDigits(text, index)
    index += 2
    if (index >= length || text[index++] != ':') invalidInstant()
    val minute = twoDigits(text, index)
    index += 2
    if (index >= length || text[index++] != ':') invalidInstant()
    val second = twoDigits(text, index)
    index += 2
    var nano = 0
    if (index < length && text[index] == '.') {
      index++
      val fractionStart = index
      while (index < length && text[index] in '0'..'9') {
        if (index - fractionStart >= 9) invalidInstant()
        nano = nano * 10 + text[index].code - '0'.code
        index++
      }
      val digits = index - fractionStart
      if (digits == 0) invalidInstant()
      repeat(9 - digits) { nano *= 10 }
    }
    var offsetSeconds = 0
    if (index < length && (text[index] == 'Z' || text[index] == 'z')) {
      index++
    } else {
      if (index >= length || text[index] != '+' && text[index] != '-') invalidInstant()
      val offsetNegative = text[index++] == '-'
      val offsetHour = twoDigits(text, index)
      index += 2
      var offsetMinute = 0
      var offsetSecond = 0
      if (index < length && text[index] == ':') {
        offsetMinute = twoDigits(text, ++index)
        index += 2
        if (index < length && text[index] == ':') {
          offsetSecond = twoDigits(text, ++index)
          index += 2
        }
      }
      if (
        offsetHour > 18 ||
          offsetMinute > 59 ||
          offsetSecond > 59 ||
          offsetHour == 18 && (offsetMinute != 0 || offsetSecond != 0)
      ) {
        invalidInstant()
      }
      offsetSeconds = offsetHour * 3_600 + offsetMinute * 60 + offsetSecond
      if (offsetNegative) offsetSeconds = -offsetSeconds
    }
    if (index != length) invalidInstant()
    validateDateTime(year, month, day, hour, minute, second)
    val epochDay = epochDay(year, month, day)
    var epochSecond =
      Math.addExact(
        Math.multiplyExact(epochDay, SECONDS_PER_DAY),
        hour * 3_600L + minute * 60L + second,
      )
    epochSecond = Math.subtractExact(epochSecond, offsetSeconds.toLong())
    // fromEpochSeconds saturates, while Instant.parse rejects values outside this exact range.
    if (epochSecond !in MIN_INSTANT_SECOND..MAX_INSTANT_SECOND) invalidInstant()
    return Instant.fromEpochSeconds(epochSecond, nano)
  }

  private fun epochDay(year: Long, month: Int, day: Int): Long {
    var total = 365L * year
    total +=
      if (year >= 0) (year + 3) / 4 - (year + 99) / 100 + (year + 399) / 400
      else -(year / -4 - year / -100 + year / -400)
    total += (367 * month - 362) / 12
    total += day - 1
    if (month > 2) total -= if (leapYear(year)) 1 else 2
    return total - DAYS_0000_TO_1970
  }

  private fun validateDateTime(
    year: Long,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
  ) {
    if (month !in 1..12 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
      invalidInstant()
    }
    val maxDay =
      when (month) {
        2 -> if (leapYear(year)) 29 else 28
        4,
        6,
        9,
        11 -> 30
        else -> 31
      }
    if (day !in 1..maxDay) invalidInstant()
  }

  private fun leapYear(year: Long): Boolean =
    year % 4L == 0L && (year % 100L != 0L || year % 400L == 0L)

  private fun twoDigits(text: CharSequence, index: Int): Int {
    if (index + 1 >= text.length) invalidInstant()
    val high = text[index]
    val low = text[index + 1]
    if (high !in '0'..'9' || low !in '0'..'9') invalidInstant()
    return (high.code - '0'.code) * 10 + low.code - '0'.code
  }

  private fun hex(value: Char): Int =
    when (value) {
      in '0'..'9' -> value.code - '0'.code
      in 'a'..'f' -> value.code - 'a'.code + 10
      in 'A'..'F' -> value.code - 'A'.code + 10
      else -> invalidUuid()
    }

  private fun invalidDuration(): Nothing =
    throw ForyJsonException("Invalid Kotlin Duration ISO JSON value")

  private fun nullDuration(): Nothing = throw ForyJsonException("Kotlin Duration cannot be null")

  private fun invalidInstant(): Nothing =
    throw ForyJsonException("Invalid Kotlin Instant ISO JSON value")

  private fun invalidUuid(): Nothing = throw ForyJsonException("Invalid Kotlin Uuid JSON value")
}
