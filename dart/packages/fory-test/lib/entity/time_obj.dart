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

import 'package:fory/fory.dart';

part '../generated/time_obj.g.dart';

@ForyClass(promiseAcyclic: true)
class TimeObj {
  final LocalDate date1;
  final LocalDate date2;
  final LocalDate date3;
  final LocalDate date4;
  final TimeStamp dateTime1;
  final TimeStamp dateTime2;
  final TimeStamp dateTime3;
  final TimeStamp dateTime4;

  const TimeObj(
    this.date1,
    this.date2,
    this.date3,
    this.date4,
    this.dateTime1,
    this.dateTime2,
    this.dateTime3,
    this.dateTime4,
  );

  @override
  int get hashCode => Object.hash(
      date1, date2, date3, date4, dateTime1, dateTime2, dateTime3, dateTime4);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other is TimeObj &&
            runtimeType == other.runtimeType &&
            date1 == other.date1 &&
            date2 == other.date2 &&
            date3 == other.date3 &&
            date4 == other.date4 &&
            dateTime1 == other.dateTime1 &&
            dateTime2 == other.dateTime2 &&
            dateTime3 == other.dateTime3 &&
            dateTime4 == other.dateTime4);
  }
}
