// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package fory

import (
	"reflect"
	"time"
)

// Date represents an imprecise date
type Date struct {
	Year  int
	Month time.Month
	Day   int
}

var dateReflectType = reflect.TypeFor[Date]()

func isLeapYear(year int64) bool {
	return year%4 == 0 && (year%100 != 0 || year%400 == 0)
}

func daysInMonth(year int64, month time.Month) int {
	switch month {
	case time.January, time.March, time.May, time.July, time.August, time.October, time.December:
		return 31
	case time.April, time.June, time.September, time.November:
		return 30
	case time.February:
		if isLeapYear(year) {
			return 29
		}
		return 28
	default:
		return 0
	}
}

func floorDiv(value, divisor int64) int64 {
	quotient := value / divisor
	remainder := value % divisor
	if remainder != 0 && ((remainder > 0) != (divisor > 0)) {
		quotient--
	}
	return quotient
}

func daysFromCivil(year int64, month time.Month, day int64) int64 {
	y := year
	m := int64(month)
	if m <= 2 {
		y--
	}
	era := floorDiv(y, 400)
	yoe := y - era*400
	monthPrime := m - 3
	if m <= 2 {
		monthPrime = m + 9
	}
	doy := (153*monthPrime+2)/5 + day - 1
	doe := yoe*365 + yoe/4 - yoe/100 + doy
	return era*146097 + doe - 719468
}

func civilFromDays(days int64) (int64, time.Month, int) {
	z := days + 719468
	era := floorDiv(z, 146097)
	doe := z - era*146097
	yoe := (doe - doe/1460 + doe/36524 - doe/146096) / 365
	year := yoe + era*400
	doy := doe - (365*yoe + yoe/4 - yoe/100)
	monthPrime := (5*doy + 2) / 153
	day := int(doy - (153*monthPrime+2)/5 + 1)
	month := monthPrime + 3
	if month > 12 {
		month -= 12
	}
	if month <= 2 {
		year++
	}
	return year, time.Month(month), day
}

// DateToEpochDay converts a Date to its day offset from the Unix epoch.
func DateToEpochDay(date Date) (int64, error) {
	year := int64(date.Year)
	if date.Month < time.January || date.Month > time.December {
		return 0, SerializationErrorf("invalid date month %d", date.Month)
	}
	maxDay := daysInMonth(year, date.Month)
	if date.Day < 1 || date.Day > maxDay {
		return 0, SerializationErrorf(
			"invalid date day %d for %d-%02d",
			date.Day,
			date.Year,
			int(date.Month),
		)
	}
	return daysFromCivil(year, date.Month, int64(date.Day)), nil
}

// DateFromEpochDay converts a Unix-epoch day offset to a Date.
func DateFromEpochDay(days int64) (Date, error) {
	year, month, day := civilFromDays(days)
	if year < int64(MinInt) || year > int64(MaxInt) {
		return Date{}, DeserializationErrorf("date year %d out of int range", year)
	}
	return Date{Year: int(year), Month: month, Day: day}, nil
}

type dateSerializer struct{}

func (s dateSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(DATE))
	}
	s.WriteData(ctx, value)
}

func (s dateSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	date := value.Interface().(Date)
	if ctx.TypeResolver().IsXlang() {
		days, err := DateToEpochDay(date)
		if err != nil {
			ctx.SetError(FromError(err))
			return
		}
		ctx.buffer.WriteVarint64(days)
		return
	}
	diff := time.Date(date.Year, date.Month, date.Day, 0, 0, 0, 0, time.Local).Sub(
		time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local))
	days := int64(diff.Hours() / 24)
	ctx.buffer.WriteInt32(int32(days))
}

func (s dateSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s dateSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	if ctx.TypeResolver().IsXlang() {
		days := ctx.buffer.ReadVarint64(err)
		if ctx.HasError() {
			return
		}
		date, convErr := DateFromEpochDay(days)
		if convErr != nil {
			ctx.SetError(FromError(convErr))
			return
		}
		value.Set(reflect.ValueOf(date))
		return
	}
	diff := time.Duration(ctx.buffer.ReadInt32(err)) * 24 * time.Hour
	date := time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local).Add(diff)
	value.Set(reflect.ValueOf(Date{date.Year(), date.Month(), date.Day()}))
}

func (s dateSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type timeSerializer struct{}

var timeReflectType = reflect.TypeFor[time.Time]()

func (s timeSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	seconds, nanos := GetUnixSecondsAndNanos(value.Interface().(time.Time))
	ctx.buffer.WriteInt64(seconds)
	ctx.buffer.WriteUint32(nanos)
}

func (s timeSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteUint8(uint8(TIMESTAMP))
	}
	s.WriteData(ctx, value)
}

func (s timeSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
	seconds := ctx.buffer.ReadInt64(err)
	nanos := ctx.buffer.ReadUint32(err)
	value.Set(reflect.ValueOf(CreateTimeFromUnixSecondsAndNanos(seconds, nanos)))
}

func (s timeSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadUint8(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s timeSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}
