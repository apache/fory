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

type dateSerializer struct{}

func (s dateSerializer) TypeId() TypeId       { return LOCAL_DATE }
func (s dateSerializer) NeedToWriteRef() bool { return true }

func (s dateSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	date := value.Interface().(Date)
	diff := time.Date(date.Year, date.Month, date.Day, 0, 0, 0, 0, time.Local).Sub(
		time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local))
	ctx.buffer.WriteInt32(int32(diff.Hours() / 24))
	return nil
}

func (s dateSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	diff := time.Duration(ctx.buffer.ReadInt32()) * 24 * time.Hour
	date := time.Date(1970, 1, 1, 0, 0, 0, 0, time.Local).Add(diff)
	value.Set(reflect.ValueOf(Date{date.Year(), date.Month(), date.Day()}))
	return nil
}

type timeSerializer struct{}

func (s timeSerializer) TypeId() TypeId       { return TIMESTAMP }
func (s timeSerializer) NeedToWriteRef() bool { return true }

func (s timeSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	ctx.buffer.WriteInt64(GetUnixMicro(value.Interface().(time.Time)))
	return nil
}

func (s timeSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	value.Set(reflect.ValueOf(CreateTimeFromUnixMicro(ctx.buffer.ReadInt64())))
	return nil
}
