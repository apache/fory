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

/// Timestamp with second and nanosecond precision in UTC.
final class Timestamp implements Comparable<Timestamp> {
  /// Whole seconds since the Unix epoch.
  final int seconds;

  /// Nanoseconds within the second.
  final int nanoseconds;

  /// Creates a timestamp from epoch seconds and nanoseconds.
  const Timestamp(this.seconds, this.nanoseconds);

  /// Converts a [DateTime] to UTC timestamp components.
  factory Timestamp.fromDateTime(DateTime value) {
    final utcValue = value.toUtc();
    final microseconds = utcValue.microsecondsSinceEpoch;
    final seconds = microseconds ~/ Duration.microsecondsPerSecond;
    final micros = microseconds % Duration.microsecondsPerSecond;
    return Timestamp(seconds, micros * 1000);
  }

  /// Converts this timestamp to a UTC [DateTime].
  DateTime toDateTime() => DateTime.fromMicrosecondsSinceEpoch(
        seconds * Duration.microsecondsPerSecond + nanoseconds ~/ 1000,
        isUtc: true,
      );

  @override
  int compareTo(Timestamp other) {
    final secondsCompare = seconds.compareTo(other.seconds);
    if (secondsCompare != 0) {
      return secondsCompare;
    }
    return nanoseconds.compareTo(other.nanoseconds);
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is Timestamp &&
          other.seconds == seconds &&
          other.nanoseconds == nanoseconds;

  @override
  int get hashCode => Object.hash(seconds, nanoseconds);

  @override
  String toString() => toDateTime().toIso8601String();
}
