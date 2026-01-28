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

type dateSerializer struct{}

func (s dateSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	if refMode != RefModeNone {
		ctx.buffer.WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.buffer.WriteVaruint32Small7(uint32(DATE))
	}
	s.WriteData(ctx, value)
}

func (s dateSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	date := value.Interface().(Date)
	diff := time.Date(date.Year, date.Month, date.Day, 0, 0, 0, 0, time.Local).Sub(
		time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local))
	ctx.buffer.WriteInt32(int32(diff.Hours() / 24))
}

func (s dateSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	err := ctx.Err()
	if refMode != RefModeNone {
		if ctx.buffer.ReadInt8(err) == NullFlag {
			return
		}
	}
	if readType {
		_ = ctx.buffer.ReadVaruint32Small7(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s dateSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	err := ctx.Err()
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
		ctx.buffer.WriteVaruint32Small7(uint32(TIMESTAMP))
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
		_ = ctx.buffer.ReadVaruint32Small7(err)
	}
	if ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s timeSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}
