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
	require.Equal(t, "abcd_efg_hij", SnakeCase("ABCDEfgHij"))
	require.Equal(t, SnakeCase("ToSnake"), "to_snake")
	require.Equal(t, SnakeCase("toSnake"), "to_snake")
	require.Equal(t, SnakeCase("to_snake"), "to_snake")
	require.Equal(t, SnakeCase("AbcAbcAbc"), "abc_abc_abc")
	require.Equal(t, SnakeCase("ABC"), "abc")
	for input, expected := range map[string]string{
		"createdAt": "created_at", "CreatedAt": "created_at",
		"fooURL": "foo_url", "URLValue": "url_value",
		"HTTP2Server": "http2_server", "x2Y": "x2_y", "ABc": "a_bc",
		"fooÖl": "fooÖl", "ÖValue": "Övalue",
		"_URL_Value_": "_url_value_", "already__snake": "already__snake",
		"foo-Bar": "foo_bar", "": "",
	} {
		require.Equal(t, expected, SnakeCase(input), input)
		require.Equal(t, expected, SnakeCase(expected), expected)
	}
}

func TestTime(t *testing.T) {
	t1 := time.Now()
	seconds, nanos := GetUnixSecondsAndNanos(t1)
	t2 := CreateTimeFromUnixSecondsAndNanos(seconds, nanos)
	require.Equal(t, t1.Second(), t2.Second())
	require.Equal(t, t1.Nanosecond(), t2.Nanosecond())
	require.Equal(t, t1.Unix(), t2.Unix())
}
