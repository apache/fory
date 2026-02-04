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

package benchmark

// NumericStruct is a simple struct with 8 int32 fields
// Matches the C++ NumericStruct and protobuf Struct message
type NumericStruct struct {
	F1 int32 `msgpack:"f1" fory:"id=1"`
	F2 int32 `msgpack:"f2" fory:"id=2"`
	F3 int32 `msgpack:"f3" fory:"id=3"`
	F4 int32 `msgpack:"f4" fory:"id=4"`
	F5 int32 `msgpack:"f5" fory:"id=5"`
	F6 int32 `msgpack:"f6" fory:"id=6"`
	F7 int32 `msgpack:"f7" fory:"id=7"`
	F8 int32 `msgpack:"f8" fory:"id=8"`
}

// Sample is a complex struct with various types and arrays
// Matches the C++ Sample and protobuf Sample message
type Sample struct {
	IntValue          int32     `msgpack:"int_value" fory:"id=1"`
	LongValue         int64     `msgpack:"long_value" fory:"id=2"`
	FloatValue        float32   `msgpack:"float_value" fory:"id=3"`
	DoubleValue       float64   `msgpack:"double_value" fory:"id=4"`
	ShortValue        int32     `msgpack:"short_value" fory:"id=5"`
	CharValue         int32     `msgpack:"char_value" fory:"id=6"`
	BooleanValue      bool      `msgpack:"boolean_value" fory:"id=7"`
	IntValueBoxed     int32     `msgpack:"int_value_boxed" fory:"id=8"`
	LongValueBoxed    int64     `msgpack:"long_value_boxed" fory:"id=9"`
	FloatValueBoxed   float32   `msgpack:"float_value_boxed" fory:"id=10"`
	DoubleValueBoxed  float64   `msgpack:"double_value_boxed" fory:"id=11"`
	ShortValueBoxed   int32     `msgpack:"short_value_boxed" fory:"id=12"`
	CharValueBoxed    int32     `msgpack:"char_value_boxed" fory:"id=13"`
	BooleanValueBoxed bool      `msgpack:"boolean_value_boxed" fory:"id=14"`
	IntArray          []int32   `msgpack:"int_array" fory:"id=15"`
	LongArray         []int64   `msgpack:"long_array" fory:"id=16"`
	FloatArray        []float32 `msgpack:"float_array" fory:"id=17"`
	DoubleArray       []float64 `msgpack:"double_array" fory:"id=18"`
	ShortArray        []int32   `msgpack:"short_array" fory:"id=19"`
	CharArray         []int32   `msgpack:"char_array" fory:"id=20"`
	BooleanArray      []bool    `msgpack:"boolean_array" fory:"id=21"`
	String            string    `msgpack:"string" fory:"id=22"`
}

// Player enum type
type Player int32

const (
	PlayerJava  Player = 0
	PlayerFlash Player = 1
)

// Size enum type
type Size int32

const (
	SizeSmall Size = 0
	SizeLarge Size = 1
)

// Media represents media metadata
type Media struct {
	URI        string   `msgpack:"uri" fory:"id=1"`
	Title      string   `msgpack:"title" fory:"id=2"`
	Width      int32    `msgpack:"width" fory:"id=3"`
	Height     int32    `msgpack:"height" fory:"id=4"`
	Format     string   `msgpack:"format" fory:"id=5"`
	Duration   int64    `msgpack:"duration" fory:"id=6"`
	Size       int64    `msgpack:"size" fory:"id=7"`
	Bitrate    int32    `msgpack:"bitrate" fory:"id=8"`
	HasBitrate bool     `msgpack:"has_bitrate" fory:"id=9"`
	Persons    []string `msgpack:"persons" fory:"id=10"`
	Player     Player   `msgpack:"player" fory:"id=11"`
	Copyright  string   `msgpack:"copyright" fory:"id=12"`
}

// Image represents image metadata
type Image struct {
	URI    string `msgpack:"uri" fory:"id=1"`
	Title  string `msgpack:"title" fory:"id=2"`
	Width  int32  `msgpack:"width" fory:"id=3"`
	Height int32  `msgpack:"height" fory:"id=4"`
	Size   Size   `msgpack:"size" fory:"id=5"`
}

// MediaContent contains media and images
type MediaContent struct {
	Media  Media   `msgpack:"media" fory:"id=1"`
	Images []Image `msgpack:"images" fory:"id=2"`
}

// CreateNumericStruct creates test data matching C++ benchmark
func CreateNumericStruct() NumericStruct {
	return NumericStruct{
		F1: -12345,
		F2: 987654321,
		F3: -31415,
		F4: 27182818,
		F5: -32000,
		F6: 1000000,
		F7: -999999999,
		F8: 42,
	}
}

// CreateSample creates test data matching C++ benchmark
func CreateSample() Sample {
	return Sample{
		IntValue:          123,
		LongValue:         1230000,
		FloatValue:        12.345,
		DoubleValue:       1.234567,
		ShortValue:        12345,
		CharValue:         '!', // 33
		BooleanValue:      true,
		IntValueBoxed:     321,
		LongValueBoxed:    3210000,
		FloatValueBoxed:   54.321,
		DoubleValueBoxed:  7.654321,
		ShortValueBoxed:   32100,
		CharValueBoxed:    '$', // 36
		BooleanValueBoxed: false,
		IntArray:          []int32{-1234, -123, -12, -1, 0, 1, 12, 123, 1234},
		LongArray:         []int64{-123400, -12300, -1200, -100, 0, 100, 1200, 12300, 123400},
		FloatArray:        []float32{-12.34, -12.3, -12.0, -1.0, 0.0, 1.0, 12.0, 12.3, 12.34},
		DoubleArray:       []float64{-1.234, -1.23, -12.0, -1.0, 0.0, 1.0, 12.0, 1.23, 1.234},
		ShortArray:        []int32{-1234, -123, -12, -1, 0, 1, 12, 123, 1234},
		CharArray:         []int32{'a', 's', 'd', 'f', 'A', 'S', 'D', 'F'},
		BooleanArray:      []bool{true, false, false, true},
		String:            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
	}
}

// CreateMediaContent creates test data matching C++ benchmark
func CreateMediaContent() MediaContent {
	return MediaContent{
		Media: Media{
			URI:        "http://javaone.com/keynote.ogg",
			Title:      "",
			Width:      641,
			Height:     481,
			Format:     "video/theora\u1234",
			Duration:   18000001,
			Size:       58982401,
			Bitrate:    0,
			HasBitrate: false,
			Persons:    []string{"Bill Gates, Jr.", "Steven Jobs"},
			Player:     PlayerFlash,
			Copyright:  "Copyright (c) 2009, Scooby Dooby Doo",
		},
		Images: []Image{
			{
				URI:    "http://javaone.com/keynote_huge.jpg",
				Title:  "Javaone Keynote\u1234",
				Width:  32000,
				Height: 24000,
				Size:   SizeLarge,
			},
			{
				URI:    "http://javaone.com/keynote_large.jpg",
				Title:  "",
				Width:  1024,
				Height: 768,
				Size:   SizeLarge,
			},
			{
				URI:    "http://javaone.com/keynote_small.jpg",
				Title:  "",
				Width:  320,
				Height: 240,
				Size:   SizeSmall,
			},
		},
	}
}
