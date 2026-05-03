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
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestDateUsesVarint64InXlangAndInt32InNative(t *testing.T) {
	date := Date{Year: 1969, Month: time.December, Day: 31}
	expectedDays, err := DateToEpochDay(date)
	require.NoError(t, err)

	for _, tc := range []struct {
		name  string
		fory  *Fory
		check func(*testing.T, *ByteBuffer)
	}{
		{
			name: "xlang",
			fory: NewFory(WithTrackRef(false), WithXlang(true)),
			check: func(t *testing.T, buf *ByteBuffer) {
				var err Error
				require.Equal(t, byte(XLangFlag), buf.ReadByte(&err))
				require.False(t, err.HasError(), err.Error())
				require.Equal(t, int8(NotNullValueFlag), buf.ReadInt8(&err))
				require.False(t, err.HasError(), err.Error())
				require.Equal(t, uint8(DATE), buf.ReadUint8(&err))
				require.False(t, err.HasError(), err.Error())
				require.Equal(t, expectedDays, buf.ReadVarint64(&err))
				require.False(t, err.HasError(), err.Error())
			},
		},
		{
			name: "native",
			fory: NewFory(WithTrackRef(false), WithXlang(false)),
			check: func(t *testing.T, buf *ByteBuffer) {
				var err Error
				require.Equal(t, byte(0), buf.ReadByte(&err))
				require.False(t, err.HasError(), err.Error())
				require.Equal(t, int8(NotNullValueFlag), buf.ReadInt8(&err))
				require.False(t, err.HasError(), err.Error())
				require.Equal(t, uint8(DATE), buf.ReadUint8(&err))
				require.False(t, err.HasError(), err.Error())
				require.Equal(t, int32(expectedDays), buf.ReadInt32(&err))
				require.False(t, err.HasError(), err.Error())
			},
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			data, err := tc.fory.Serialize(date)
			require.NoError(t, err)

			buf := NewByteBuffer(data)
			tc.check(t, buf)
			require.Equal(t, len(data), buf.ReaderIndex())
		})
	}
}

func TestXlangDateSupportsWideRange(t *testing.T) {
	fory := NewFory(WithTrackRef(false), WithXlang(true))
	date := Date{Year: 200000, Month: time.January, Day: 1}

	expectedDays, err := DateToEpochDay(date)
	require.NoError(t, err)

	data, err := Serialize(fory, &date)
	require.NoError(t, err)

	buf := NewByteBuffer(data)
	var bufErr Error
	require.Equal(t, byte(XLangFlag), buf.ReadByte(&bufErr))
	require.False(t, bufErr.HasError(), bufErr.Error())
	require.Equal(t, int8(NotNullValueFlag), buf.ReadInt8(&bufErr))
	require.False(t, bufErr.HasError(), bufErr.Error())
	require.Equal(t, uint8(DATE), buf.ReadUint8(&bufErr))
	require.False(t, bufErr.HasError(), bufErr.Error())
	require.Equal(t, expectedDays, buf.ReadVarint64(&bufErr))
	require.False(t, bufErr.HasError(), bufErr.Error())
	require.Equal(t, len(data), buf.ReaderIndex())

	var decoded Date
	err = Deserialize(fory, data, &decoded)
	require.NoError(t, err)
	require.Equal(t, date, decoded)
}

func TestDurationFromWireBounds(t *testing.T) {
	duration, err := durationFromWire(42, 7)
	require.NoError(t, err)
	require.Equal(t, 42*time.Second+7*time.Nanosecond, duration)

	duration, err = durationFromWire(minDurationNanos/nanosPerSecond, 0)
	require.NoError(t, err)
	require.Equal(t, time.Duration((minDurationNanos/nanosPerSecond)*nanosPerSecond), duration)

	_, err = durationFromWire(maxDurationNanos/nanosPerSecond, int32(maxDurationNanos%nanosPerSecond)+1)
	require.Error(t, err)

	_, err = durationFromWire(minDurationNanos/nanosPerSecond-1, 0)
	require.Error(t, err)

	_, err = durationFromWire(0, int32(nanosPerSecond))
	require.Error(t, err)
}
