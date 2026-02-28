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

/// A class representing a point in time as microseconds since Unix epoch.
/// Provides utilities for timestamp manipulation, comparison, and formatting.
class TimeStamp {
  /// The number of microseconds since the "Unix epoch" 1970-01-01T00:00:00Z (UTC).
  final int microsecondsSinceEpoch;

  /// Creates a [TimeStamp] from microseconds since epoch.
  const TimeStamp(this.microsecondsSinceEpoch);

  /// Creates a [TimeStamp] from the current time.
  factory TimeStamp.now() {
    return TimeStamp(DateTime.now().microsecondsSinceEpoch);
  }

  /// Creates a [TimeStamp] from milliseconds since epoch.
  factory TimeStamp.fromMillisecondsSinceEpoch(int milliseconds) {
    return TimeStamp(milliseconds * 1000);
  }

  factory TimeStamp.fromSecondsSinceEpoch(int seconds) {
    return TimeStamp(seconds * 1000000);
  }

  /// Creates a [TimeStamp] from a [DateTime] object.
  factory TimeStamp.fromDateTime(DateTime dateTime) {
    return TimeStamp(dateTime.microsecondsSinceEpoch);
  }

  /// Creates a [TimeStamp] from date components.
  /// All parameters are in local time.
  factory TimeStamp.fromDateComponents({
    required int year,
    required int month,
    required int day,
    int hour = 0,
    int minute = 0,
    int second = 0,
    int millisecond = 0,
    int microsecond = 0,
  }) {
    final dateTime = DateTime(
        year, month, day, hour, minute, second, millisecond, microsecond);
    return TimeStamp.fromDateTime(dateTime);
  }

  /// Creates a [TimeStamp] from date components in UTC.
  factory TimeStamp.fromUtcDateComponents({
    required int year,
    required int month,
    required int day,
    int hour = 0,
    int minute = 0,
    int second = 0,
    int millisecond = 0,
    int microsecond = 0,
  }) {
    final dateTime = DateTime.utc(
        year, month, day, hour, minute, second, millisecond, microsecond);
    return TimeStamp.fromDateTime(dateTime);
  }

  /// Returns this timestamp as milliseconds since epoch.
  int toMillisecondsSinceEpoch() => microsecondsSinceEpoch ~/ 1000;

  /// Returns this timestamp as seconds since epoch.
  int toSecondsSinceEpoch() => microsecondsSinceEpoch ~/ 1000000;

  /// Converts this timestamp to a [DateTime] object.
  DateTime toDateTime() =>
      DateTime.fromMicrosecondsSinceEpoch(microsecondsSinceEpoch);

  /// Converts this timestamp to a UTC [DateTime] object.
  DateTime toUtcDateTime() =>
      DateTime.fromMicrosecondsSinceEpoch(microsecondsSinceEpoch, isUtc: true);

  /// Returns a new [TimeStamp] with the specified duration added.
  TimeStamp add(Duration duration) {
    return TimeStamp(microsecondsSinceEpoch + duration.inMicroseconds);
  }

  /// Returns a new [TimeStamp] with the specified duration subtracted.
  TimeStamp subtract(Duration duration) {
    return TimeStamp(microsecondsSinceEpoch - duration.inMicroseconds);
  }

  /// Returns the difference between this timestamp and [other] as a [Duration].
  Duration difference(TimeStamp other) {
    return Duration(
        microseconds: microsecondsSinceEpoch - other.microsecondsSinceEpoch);
  }

  /// Formats this timestamp as a string using the specified [pattern].
  /// If no pattern is provided, uses ISO8601 format.
  String format({String? pattern}) {
    if (pattern == null) {
      return toUtcDateTime().toIso8601String();
    }

    // Basic implementation - in a real app, you might use a formatting library
    final dt = toUtcDateTime();
    return pattern
        .replaceAll('yyyy', dt.year.toString().padLeft(4, '0'))
        .replaceAll('MM', dt.month.toString().padLeft(2, '0'))
        .replaceAll('dd', dt.day.toString().padLeft(2, '0'))
        .replaceAll('HH', dt.hour.toString().padLeft(2, '0'))
        .replaceAll('mm', dt.minute.toString().padLeft(2, '0'))
        .replaceAll('ss', dt.second.toString().padLeft(2, '0'))
        .replaceAll('SSS', dt.millisecond.toString().padLeft(3, '0'));
  }

  /// Returns true if this timestamp is before [other].
  bool isBefore(TimeStamp other) =>
      microsecondsSinceEpoch < other.microsecondsSinceEpoch;

  /// Returns true if this timestamp is after [other].
  bool isAfter(TimeStamp other) =>
      microsecondsSinceEpoch > other.microsecondsSinceEpoch;

  /// Returns true if this timestamp is at the same time as [other].
  bool isAtSameMomentAs(TimeStamp other) =>
      microsecondsSinceEpoch == other.microsecondsSinceEpoch;

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is TimeStamp &&
        other.microsecondsSinceEpoch == microsecondsSinceEpoch;
  }

  @override
  int get hashCode => microsecondsSinceEpoch.hashCode;

  @override
  String toString() => format();
}
