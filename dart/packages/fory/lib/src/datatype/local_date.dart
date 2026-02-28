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

/// A date without time and timezone information.
///
/// `LocalDate` represents a date without time components, similar to Java's LocalDate.
/// It's immutable and timezone-independent, storing only year, month, and day values.
class LocalDate implements Comparable<LocalDate> {
  /// The year component of this date.
  final int year;

  /// The month component of this date (1-12).
  final int month;

  /// The day component of this date (1-31).
  final int day;

  /// The Unix epoch date (1970-01-01)
  static final LocalDate epoch = LocalDate(1970, 1, 1);

  /// Creates a LocalDate instance from year, month, and day values.
  ///
  /// Month must be between 1-12 and day must be valid for the given month.
  LocalDate(this.year, this.month, this.day) {
    _validate();
  }

  /// Creates a LocalDate from the current date in the local timezone.
  factory LocalDate.now() {
    final now = DateTime.now();
    return LocalDate(now.year, now.month, now.day);
  }

  /// Creates a LocalDate from the current date in UTC.
  factory LocalDate.nowUtc() {
    final now = DateTime.now().toUtc();
    return LocalDate(now.year, now.month, now.day);
  }

  /// Creates a LocalDate from epoch days (days since 1970-01-01).
  ///
  /// [days] is the number of days from the epoch.
  /// If [utc] is true, the calculation uses UTC, otherwise local timezone.
  factory LocalDate.fromEpochDay(int days, {bool utc = false}) {
    final epochMillis = days * 86400000; // Convert days to milliseconds
    final dateTime = utc
        ? DateTime.fromMillisecondsSinceEpoch(epochMillis, isUtc: true)
        : DateTime.fromMillisecondsSinceEpoch(epochMillis);
    return LocalDate(dateTime.year, dateTime.month, dateTime.day);
  }

  /// Creates a LocalDate from epoch milliseconds (milliseconds since 1970-01-01T00:00:00Z).
  ///
  /// [milliseconds] is the number of milliseconds from the epoch.
  /// If [utc] is true, the calculation uses UTC, otherwise local timezone.
  factory LocalDate.fromEpochMillis(int milliseconds, {bool utc = false}) {
    final dateTime = utc
        ? DateTime.fromMillisecondsSinceEpoch(milliseconds, isUtc: true)
        : DateTime.fromMillisecondsSinceEpoch(milliseconds);
    return LocalDate(dateTime.year, dateTime.month, dateTime.day);
  }

  /// Creates a LocalDate from epoch seconds (seconds since 1970-01-01T00:00:00Z).
  ///
  /// [seconds] is the number of seconds from the epoch.
  /// If [utc] is true, the calculation uses UTC, otherwise local timezone.
  factory LocalDate.fromEpochSeconds(int seconds, {bool utc = false}) {
    return LocalDate.fromEpochMillis(seconds * 1000, utc: utc);
  }

  /// Parses a LocalDate from an ISO-8601 date string (yyyy-MM-dd).
  factory LocalDate.parse(String dateString) {
    final parts = dateString.split('-');
    if (parts.length != 3 || parts[1].length != 2 || parts[2].length != 2) {
      throw FormatException(
          'Invalid date format. Expected yyyy-MM-dd', dateString);
    }

    final year = int.parse(parts[0]);
    final month = int.parse(parts[1]);
    final day = int.parse(parts[2]);

    return LocalDate(year, month, day);
  }

  /// Creates a LocalDate from a DateTime instance.
  factory LocalDate.fromDateTime(DateTime dateTime) {
    return LocalDate(dateTime.year, dateTime.month, dateTime.day);
  }

  /// Returns the number of days since the Unix epoch (1970-01-01).
  ///
  /// If [utc] is true, the calculation uses UTC, otherwise local timezone.
  int toEpochDay({bool utc = false}) {
    final DateTime dateTime =
        utc ? DateTime.utc(year, month, day) : DateTime(year, month, day);

    // Calculate days since epoch
    final epochMillis = dateTime.millisecondsSinceEpoch;
    return (epochMillis / 86400000).floor(); // Convert milliseconds to days
  }

  /// Returns the number of milliseconds since the Unix epoch (1970-01-01T00:00:00Z).
  ///
  /// If [utc] is true, the calculation uses UTC, otherwise local timezone.
  int toEpochMillis({bool utc = false}) {
    final dateTime =
        utc ? DateTime.utc(year, month, day) : DateTime(year, month, day);
    return dateTime.millisecondsSinceEpoch;
  }

  /// Returns the number of seconds since the Unix epoch (1970-01-01T00:00:00Z).
  ///
  /// If [utc] is true, the calculation uses UTC, otherwise local timezone.
  int toEpochSeconds({bool utc = false}) {
    return (toEpochMillis(utc: utc) / 1000).floor();
  }

  /// Validates that the date is valid.
  void _validate() {
    if (month < 1 || month > 12) {
      throw ArgumentError('Month must be between 1 and 12');
    }

    final daysInMonth = getDaysInMonth(year, month);
    if (day < 1 || day > daysInMonth) {
      throw ArgumentError(
          'Day must be between 1 and $daysInMonth for month $month');
    }
  }

  /// Returns the number of days in the specified month of the year.
  static int getDaysInMonth(int year, int month) {
    if (month == 2) {
      return isLeapYear(year) ? 29 : 28;
    }
    return const [0, 31, 0, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month];
  }

  /// Checks if the specified year is a leap year.
  static bool isLeapYear(int year) {
    return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
  }

  /// Returns a DateTime at midnight (00:00:00) on this date in the local timezone.
  DateTime toDateTime() {
    return DateTime(year, month, day);
  }

  /// Returns a DateTime at midnight (00:00:00) on this date in UTC.
  DateTime toDateTimeUtc() {
    return DateTime.utc(year, month, day);
  }

  /// Returns a new LocalDate with the specified number of days added.
  LocalDate plusDays(int days) {
    final dateTime = toDateTime().add(Duration(days: days));
    return LocalDate(dateTime.year, dateTime.month, dateTime.day);
  }

  /// Returns a new LocalDate with the specified number of months added.
  LocalDate plusMonths(int months) {
    // Calculate the total number of months
    int totalMonths = year * 12 + (month - 1) + months;

    // Calculate the new year and month from the total number of months
    int newYear = totalMonths ~/ 12;
    int newMonth = (totalMonths % 12) + 1;

    // Ensure the day is valid for the new month
    final daysInMonth = getDaysInMonth(newYear, newMonth);
    int newDay = day <= daysInMonth ? day : daysInMonth;

    return LocalDate(newYear, newMonth, newDay);
  }

  /// Returns a new LocalDate with the specified number of years added.
  LocalDate plusYears(int years) {
    // Handle Feb 29 edge case
    if (month == 2 && day == 29 && !isLeapYear(year + years)) {
      return LocalDate(year + years, 2, 28);
    }
    return LocalDate(year + years, month, day);
  }

  /// Returns a new LocalDate with the specified number of days subtracted.
  LocalDate minusDays(int days) {
    return plusDays(-days);
  }

  /// Returns a new LocalDate with the specified number of months subtracted.
  LocalDate minusMonths(int months) {
    return plusMonths(-months);
  }

  /// Returns a new LocalDate with the specified number of years subtracted.
  LocalDate minusYears(int years) {
    return plusYears(-years);
  }

  /// Returns the number of days between this date and another date.
  int daysBetween(LocalDate other) {
    return (toDateTime().difference(other.toDateTime()).inDays).abs();
  }

  /// Returns true if this date is after the specified date.
  bool isAfter(LocalDate other) {
    return compareTo(other) > 0;
  }

  /// Returns true if this date is before the specified date.
  bool isBefore(LocalDate other) {
    return compareTo(other) < 0;
  }

  /// Returns the day of week (1 = Monday, 7 = Sunday).
  int get dayOfWeek {
    final dateTime = toDateTime();
    int dow = dateTime.weekday;
    return dow;
  }

  /// Returns the day of year (1-366).
  int get dayOfYear {
    final firstDayOfYear = LocalDate(year, 1, 1);
    return daysBetween(firstDayOfYear) + 1;
  }

  /// Returns a formatted string representation of this date.
  ///
  /// The default format is ISO-8601 (yyyy-MM-dd).
  /// For more complex formatting, consider using the intl package.
  String format([String pattern = 'yyyy-MM-dd']) {
    if (pattern == 'yyyy-MM-dd') {
      return '$year-${month.toString().padLeft(2, '0')}-${day.toString().padLeft(2, '0')}';
    }

    // Basic implementation for common patterns
    return pattern
        .replaceAll('yyyy', year.toString())
        .replaceAll('MM', month.toString().padLeft(2, '0'))
        .replaceAll('dd', day.toString().padLeft(2, '0'))
        .replaceAll('M', month.toString())
        .replaceAll('d', day.toString());

    // For more complex formatting, consider using the intl package
  }

  /// Returns the ISO-8601 string representation of this date (yyyy-MM-dd).
  @override
  String toString() {
    return format();
  }

  /// Compares this LocalDate with another.
  @override
  int compareTo(LocalDate other) {
    int result = year - other.year;
    if (result != 0) return result;

    result = month - other.month;
    if (result != 0) return result;

    return day - other.day;
  }

  /// Returns true if this date is equal to the specified object.
  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is LocalDate &&
        other.year == year &&
        other.month == month &&
        other.day == day;
  }

  /// Returns a hash code value for this date.
  @override
  int get hashCode => Object.hash(year, month, day);
}
