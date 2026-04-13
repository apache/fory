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
  factory LocalDate.fromEpochDay(int epochDay) {
    final instant = DateTime.fromMillisecondsSinceEpoch(
      epochDay * Duration.millisecondsPerDay,
      isUtc: true,
    );
    return LocalDate(instant.year, instant.month, instant.day);
  }

  /// Converts this date to xlang epoch-day form.
  int toEpochDay() =>
      DateTime.utc(year, month, day).millisecondsSinceEpoch ~/
      Duration.millisecondsPerDay;

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
