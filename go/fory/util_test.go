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
	"github.com/stretchr/testify/require"
	"testing"
	"time"
)

func TestSnake(t *testing.T) {
	require.Equal(t, "a_bcd_efg_hij", SnakeCase("aBcdEfgHij"))
	require.Equal(t, "a_bcd_efg_hij", SnakeCase("ABcdEfgHij"))
	require.Equal(t, "a_b_c_d_efg_hij", SnakeCase("ABCDEfgHij"))
	require.Equal(t, SnakeCase("ToSnake"), "to_snake")
	require.Equal(t, SnakeCase("toSnake"), "to_snake")
	require.Equal(t, SnakeCase("to_snake"), "to_snake")
	require.Equal(t, SnakeCase("AbcAbcAbc"), "abc_abc_abc")
	require.Equal(t, SnakeCase("ABC"), "a_b_c")
}

func TestTime(t *testing.T) {
	t1 := time.Now()
	seconds, nanos := GetUnixSecondsAndNanos(t1)
	t2 := CreateTimeFromUnixSecondsAndNanos(seconds, nanos)
	require.Equal(t, t1.Second(), t2.Second())
	require.Equal(t, t1.Nanosecond(), t2.Nanosecond())
	require.Equal(t, t1.Unix(), t2.Unix())
}
