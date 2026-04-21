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

import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/write_context.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/types/local_date.dart';
import 'package:fory/src/types/timestamp.dart';

const int _nanosecondsPerSecond = 1000000000;
final Expando<_ExactDurationWire> _exactDurationWire =
    Expando<_ExactDurationWire>('fory_exact_duration_wire');

final class _ExactDurationWire {
  final int seconds;
  final int nanoseconds;
  final int microseconds;

  const _ExactDurationWire(this.seconds, this.nanoseconds, this.microseconds);
}

void _validateDurationNanoseconds(int nanoseconds) {
  if (nanoseconds < -999999999 || nanoseconds > 999999999) {
    throw StateError(
      'Duration nanoseconds $nanoseconds are out of range [-999999999, 999999999].',
    );
  }
}

void _validateTimestampNanoseconds(int nanoseconds) {
  if (nanoseconds < 0 || nanoseconds >= _nanosecondsPerSecond) {
    throw StateError(
      'Timestamp nanoseconds $nanoseconds are out of range [0, 999999999].',
    );
  }
}

void _validateDateTimeNanoseconds(int nanoseconds) {
  _validateTimestampNanoseconds(nanoseconds);
  if (nanoseconds.remainder(1000) != 0) {
    throw StateError(
      'Timestamp nanoseconds $nanoseconds exceed Dart DateTime microsecond precision.',
    );
  }
}

int durationWireSeconds(Duration value) {
  final exact = _exactDurationWire[value];
  if (exact != null && exact.microseconds == value.inMicroseconds) {
    return exact.seconds;
  }
  return value.inMicroseconds ~/ Duration.microsecondsPerSecond;
}

int durationWireNanoseconds(Duration value) {
  final exact = _exactDurationWire[value];
  if (exact != null && exact.microseconds == value.inMicroseconds) {
    return exact.nanoseconds;
  }
  return value.inMicroseconds.remainder(Duration.microsecondsPerSecond) * 1000;
}

Duration durationFromWire(int seconds, int nanoseconds) {
  _validateDurationNanoseconds(nanoseconds);
  if ((seconds > 0 && nanoseconds < 0) || (seconds < 0 && nanoseconds > 0)) {
    throw StateError(
      'Duration wire value has inconsistent signs: seconds=$seconds, nanoseconds=$nanoseconds.',
    );
  }
  final totalNanoseconds = seconds * _nanosecondsPerSecond + nanoseconds;
  final microseconds = totalNanoseconds ~/ 1000;
  final value = Duration(microseconds: microseconds);
  if (nanoseconds.remainder(1000) != 0) {
    _exactDurationWire[value] = _ExactDurationWire(
      seconds,
      nanoseconds,
      microseconds,
    );
  }
  return value;
}

int timestampWireNanoseconds(Timestamp value) {
  _validateTimestampNanoseconds(value.nanoseconds);
  return value.nanoseconds;
}

int dateTimeWireSeconds(DateTime value) {
  final utcValue = value.toUtc();
  final microseconds = utcValue.microsecondsSinceEpoch;
  var seconds = microseconds ~/ Duration.microsecondsPerSecond;
  var micros = microseconds.remainder(Duration.microsecondsPerSecond);
  if (micros < 0) {
    micros += Duration.microsecondsPerSecond;
    seconds -= 1;
  }
  return seconds;
}

int dateTimeWireNanoseconds(DateTime value) {
  final utcValue = value.toUtc();
  var micros =
      utcValue.microsecondsSinceEpoch.remainder(Duration.microsecondsPerSecond);
  if (micros < 0) {
    micros += Duration.microsecondsPerSecond;
  }
  return micros * 1000;
}

Timestamp timestampFromWire(int seconds, int nanoseconds) {
  _validateTimestampNanoseconds(nanoseconds);
  return Timestamp(seconds, nanoseconds);
}

DateTime dateTimeFromWire(int seconds, int nanoseconds) {
  _validateDateTimeNanoseconds(nanoseconds);
  return DateTime.fromMicrosecondsSinceEpoch(
    seconds * Duration.microsecondsPerSecond + nanoseconds ~/ 1000,
    isUtc: true,
  );
}

final class LocalDateSerializer extends Serializer<LocalDate> {
  const LocalDateSerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, LocalDate value) {
    context.buffer.writeVarInt64(value.toEpochDay());
  }

  @override
  LocalDate read(ReadContext context) {
    return LocalDate.fromEpochDay(context.buffer.readVarInt64());
  }
}

final class DurationSerializer extends Serializer<Duration> {
  const DurationSerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, Duration value) {
    context.buffer.writeVarInt64(durationWireSeconds(value));
    context.buffer.writeInt32(durationWireNanoseconds(value));
  }

  @override
  Duration read(ReadContext context) {
    return durationFromWire(
      context.buffer.readVarInt64(),
      context.buffer.readInt32(),
    );
  }
}

final class TimestampSerializer extends Serializer<Timestamp> {
  const TimestampSerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, Timestamp value) {
    context.buffer.writeInt64(value.seconds);
    context.buffer.writeUint32(timestampWireNanoseconds(value));
  }

  @override
  Timestamp read(ReadContext context) {
    return timestampFromWire(
        context.buffer.readInt64(), context.buffer.readUint32());
  }
}

final class DateTimeSerializer extends Serializer<DateTime> {
  const DateTimeSerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, DateTime value) {
    context.buffer.writeInt64(dateTimeWireSeconds(value));
    context.buffer.writeUint32(dateTimeWireNanoseconds(value));
  }

  @override
  DateTime read(ReadContext context) {
    return dateTimeFromWire(
      context.buffer.readInt64(),
      context.buffer.readUint32(),
    );
  }
}

const LocalDateSerializer localDateSerializer = LocalDateSerializer();
const DurationSerializer durationSerializer = DurationSerializer();
const TimestampSerializer timestampSerializer = TimestampSerializer();
const DateTimeSerializer dateTimeSerializer = DateTimeSerializer();
