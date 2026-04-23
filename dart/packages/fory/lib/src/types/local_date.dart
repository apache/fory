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

import 'package:fory/src/types/int64.dart';

/// Calendar date without time-of-day or time-zone information.
final class LocalDate implements Comparable<LocalDate> {
  /// Year component.
  final int year;

  /// Month component in the range `1..12`.
  final int month;

  /// Day-of-month component.
  final int day;

  /// Creates a date from explicit calendar components.
  const LocalDate(this.year, this.month, this.day);

  /// Creates a date from the xlang epoch-day representation.
  factory LocalDate.fromEpochDay(Int64 epochDay) {
    final instant = DateTime.fromMillisecondsSinceEpoch(
      epochDay.toInt() * Duration.millisecondsPerDay,
      isUtc: true,
    );
    return LocalDate(instant.year, instant.month, instant.day);
  }

  /// Creates a date from a [DateTime] by taking its UTC calendar date.
  factory LocalDate.fromDateTime(DateTime value) {
    final utcValue = value.toUtc();
    return LocalDate(utcValue.year, utcValue.month, utcValue.day);
  }

  /// Converts this date to xlang epoch-day form.
  Int64 toEpochDay() => Int64(
        DateTime.utc(year, month, day).millisecondsSinceEpoch ~/
            Duration.millisecondsPerDay,
      );

  /// Converts this date to a UTC [DateTime] at midnight.
  DateTime toDateTime() => DateTime.utc(year, month, day);

  @override
  int compareTo(LocalDate other) {
    final yearCompare = year.compareTo(other.year);
    if (yearCompare != 0) {
      return yearCompare;
    }
    final monthCompare = month.compareTo(other.month);
    if (monthCompare != 0) {
      return monthCompare;
    }
    return day.compareTo(other.day);
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is LocalDate &&
          other.year == year &&
          other.month == month &&
          other.day == day;

  @override
  int get hashCode => Object.hash(year, month, day);

  @override
  String toString() =>
      '$year-${month.toString().padLeft(2, '0')}-${day.toString().padLeft(2, '0')}';
}
