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

import (
	"fmt"
	"os"
	"testing"

	pb "github.com/apache/fory/benchmarks/go/proto"
	"github.com/apache/fory/go/fory"
	"github.com/vmihailenco/msgpack/v5"
	"google.golang.org/protobuf/proto"
)

// ============================================================================
// Fory Setup
// ============================================================================

func newFory() *fory.Fory {
	f := fory.New(
		fory.WithXlang(true),
		fory.WithTrackRef(false),
		fory.WithCompatible(true),
	)
	registerForyTypes(f)
	return f
}

func newForyReader() *fory.Fory {
	f := fory.New(
		fory.WithXlang(true),
		fory.WithTrackRef(false),
		fory.WithCompatible(true),
	)
	if schemaMismatchEnabled() {
		registerForyTypesV2(f)
	} else {
		registerForyTypes(f)
	}
	return f
}

func registerForyTypes(f *fory.Fory) {
	// Register types with IDs matching the existing Go benchmark schema.
	if err := f.RegisterStruct(NumericStruct{}, 1); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(Sample{}, 2); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(Media{}, 3); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(Image{}, 4); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(MediaContent{}, 5); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(NumericStructList{}, 8); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(SampleList{}, 9); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(MediaContentList{}, 10); err != nil {
		panic(err)
	}
	if err := f.RegisterEnum(Player(0), 6); err != nil {
		panic(err)
	}
	if err := f.RegisterEnum(Size(0), 7); err != nil {
		panic(err)
	}
}

func registerForyTypesV2(f *fory.Fory) {
	if err := f.RegisterStruct(NumericStructV2{}, 1); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(SampleV2{}, 2); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(MediaV2{}, 3); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(ImageV2{}, 4); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(MediaContentV2{}, 5); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(NumericStructListV2{}, 8); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(SampleListV2{}, 9); err != nil {
		panic(err)
	}
	if err := f.RegisterStruct(MediaContentListV2{}, 10); err != nil {
		panic(err)
	}
	if err := f.RegisterEnum(Player(0), 6); err != nil {
		panic(err)
	}
	if err := f.RegisterEnum(Size(0), 7); err != nil {
		panic(err)
	}
}

func schemaMismatchEnabled() bool {
	return os.Getenv("FORY_BENCH_SCHEMA_MISMATCH") == "1"
}

func rejectNonForySchemaMismatch(b testing.TB) {
	if schemaMismatchEnabled() {
		b.Fatalf("FORY_BENCH_SCHEMA_MISMATCH=1 supports only Fory benchmarks; rerun with --serializer fory")
	}
}

// ============================================================================
// NumericStruct Benchmarks
// ============================================================================

func BenchmarkFory_NumericStruct_Serialize(b *testing.B) {
	f := newFory()
	obj := CreateNumericStruct()
	buf := fory.NewByteBuffer(make([]byte, 0, 128))

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.Reset()
		err := f.SerializeTo(buf, &obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_NumericStruct_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateNumericStruct()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		// Fair comparison: convert struct to protobuf, then serialize
		pbObj := ToPbStruct(obj)
		_, err := proto.Marshal(pbObj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkMsgpack_NumericStruct_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateNumericStruct()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, err := msgpack.Marshal(obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkFory_NumericStruct_Deserialize(b *testing.B) {
	writer := newFory()
	reader := newForyReader()
	obj := CreateNumericStruct()
	data, err := writer.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	if schemaMismatchEnabled() {
		var result NumericStructV2
		if err := reader.Deserialize(data, &result); err != nil {
			b.Fatal(err)
		}
		if result.F1 != int64(obj.F1) {
			b.Fatal("NumericStructV2 schema mismatch read failed")
		}
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			var result NumericStructV2
			if err := reader.Deserialize(data, &result); err != nil {
				b.Fatal(err)
			}
		}
		return
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result NumericStruct
		err := reader.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_NumericStruct_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateNumericStruct()
	pbObj := ToPbStruct(obj)
	data, err := proto.Marshal(pbObj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		// Fair comparison: deserialize and convert protobuf to plain struct
		// (Same pattern as C++ benchmark's ParseFromString -> FromPbStruct)
		var pbResult pb.NumericStruct
		err := proto.Unmarshal(data, &pbResult)
		if err != nil {
			b.Fatal(err)
		}
		_ = FromPbStruct(&pbResult)
	}
}

func BenchmarkMsgpack_NumericStruct_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateNumericStruct()
	data, err := msgpack.Marshal(obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result NumericStruct
		err := msgpack.Unmarshal(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

// ============================================================================
// NumericStructList Benchmarks
// ============================================================================

func BenchmarkFory_NumericStructList_Serialize(b *testing.B) {
	f := newFory()
	obj := CreateNumericStructList()
	buf := fory.NewByteBuffer(make([]byte, 0, 65536))

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.Reset()
		err := f.SerializeTo(buf, &obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_NumericStructList_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateNumericStructList()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		pbObj := ToPbNumericStructList(obj)
		_, err := proto.Marshal(pbObj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkMsgpack_NumericStructList_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateNumericStructList()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, err := msgpack.Marshal(obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkFory_NumericStructList_Deserialize(b *testing.B) {
	writer := newFory()
	reader := newForyReader()
	obj := CreateNumericStructList()
	data, err := writer.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	if schemaMismatchEnabled() {
		var result NumericStructListV2
		if err := reader.Deserialize(data, &result); err != nil {
			b.Fatal(err)
		}
		if len(result.NumericStructs) == 0 || result.NumericStructs[0].F1 != int64(obj.NumericStructs[0].F1) {
			b.Fatal("NumericStructListV2 schema mismatch read failed")
		}
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			var result NumericStructListV2
			if err := reader.Deserialize(data, &result); err != nil {
				b.Fatal(err)
			}
		}
		return
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result NumericStructList
		err := reader.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_NumericStructList_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateNumericStructList()
	pbObj := ToPbNumericStructList(obj)
	data, err := proto.Marshal(pbObj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var pbResult pb.NumericStructList
		err := proto.Unmarshal(data, &pbResult)
		if err != nil {
			b.Fatal(err)
		}
		_ = FromPbNumericStructList(&pbResult)
	}
}

func BenchmarkMsgpack_NumericStructList_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateNumericStructList()
	data, err := msgpack.Marshal(obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result NumericStructList
		err := msgpack.Unmarshal(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

// ============================================================================
// Sample Benchmarks
// ============================================================================

func BenchmarkFory_Sample_Serialize(b *testing.B) {
	f := newFory()
	obj := CreateSample()
	buf := fory.NewByteBuffer(make([]byte, 0, 512))

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.Reset()
		err := f.SerializeTo(buf, &obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_Sample_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateSample()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		pbObj := ToPbSample(obj)
		_, err := proto.Marshal(pbObj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkMsgpack_Sample_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateSample()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, err := msgpack.Marshal(obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkFory_Sample_Deserialize(b *testing.B) {
	writer := newFory()
	reader := newForyReader()
	obj := CreateSample()
	data, err := writer.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	if schemaMismatchEnabled() {
		var result SampleV2
		if err := reader.Deserialize(data, &result); err != nil {
			b.Fatal(err)
		}
		if result.IntValue != int64(obj.IntValue) {
			b.Fatal("SampleV2 schema mismatch read failed")
		}
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			var result SampleV2
			if err := reader.Deserialize(data, &result); err != nil {
				b.Fatal(err)
			}
		}
		return
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result Sample
		err := reader.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_Sample_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateSample()
	pbObj := ToPbSample(obj)
	data, err := proto.Marshal(pbObj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var pbResult pb.Sample
		err := proto.Unmarshal(data, &pbResult)
		if err != nil {
			b.Fatal(err)
		}
		_ = FromPbSample(&pbResult)
	}
}

func BenchmarkMsgpack_Sample_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateSample()
	data, err := msgpack.Marshal(obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result Sample
		err := msgpack.Unmarshal(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

// ============================================================================
// SampleList Benchmarks
// ============================================================================

func BenchmarkFory_SampleList_Serialize(b *testing.B) {
	f := newFory()
	obj := CreateSampleList()
	buf := fory.NewByteBuffer(make([]byte, 0, 131072))

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.Reset()
		err := f.SerializeTo(buf, &obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_SampleList_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateSampleList()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		pbObj := ToPbSampleList(obj)
		_, err := proto.Marshal(pbObj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkMsgpack_SampleList_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateSampleList()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, err := msgpack.Marshal(obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkFory_SampleList_Deserialize(b *testing.B) {
	writer := newFory()
	reader := newForyReader()
	obj := CreateSampleList()
	data, err := writer.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	if schemaMismatchEnabled() {
		var result SampleListV2
		if err := reader.Deserialize(data, &result); err != nil {
			b.Fatal(err)
		}
		if len(result.SampleList) == 0 || result.SampleList[0].IntValue != int64(obj.SampleList[0].IntValue) {
			b.Fatal("SampleListV2 schema mismatch read failed")
		}
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			var result SampleListV2
			if err := reader.Deserialize(data, &result); err != nil {
				b.Fatal(err)
			}
		}
		return
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result SampleList
		err := reader.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_SampleList_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateSampleList()
	pbObj := ToPbSampleList(obj)
	data, err := proto.Marshal(pbObj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var pbResult pb.SampleList
		err := proto.Unmarshal(data, &pbResult)
		if err != nil {
			b.Fatal(err)
		}
		_ = FromPbSampleList(&pbResult)
	}
}

func BenchmarkMsgpack_SampleList_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateSampleList()
	data, err := msgpack.Marshal(obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result SampleList
		err := msgpack.Unmarshal(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

// ============================================================================
// MediaContent Benchmarks
// ============================================================================

func BenchmarkFory_MediaContent_Serialize(b *testing.B) {
	f := newFory()
	obj := CreateMediaContent()
	buf := fory.NewByteBuffer(make([]byte, 0, 512))

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.Reset()
		err := f.SerializeTo(buf, &obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_MediaContent_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateMediaContent()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		pbObj := ToPbMediaContent(obj)
		_, err := proto.Marshal(pbObj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkMsgpack_MediaContent_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateMediaContent()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, err := msgpack.Marshal(obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkFory_MediaContent_Deserialize(b *testing.B) {
	writer := newFory()
	reader := newForyReader()
	obj := CreateMediaContent()
	data, err := writer.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	if schemaMismatchEnabled() {
		var result MediaContentV2
		if err := reader.Deserialize(data, &result); err != nil {
			b.Fatal(err)
		}
		if result.Media.Width != int64(obj.Media.Width) ||
			len(result.Images) == 0 || result.Images[0].Width != int64(obj.Images[0].Width) {
			b.Fatal("MediaContentV2 schema mismatch read failed")
		}
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			var result MediaContentV2
			if err := reader.Deserialize(data, &result); err != nil {
				b.Fatal(err)
			}
		}
		return
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result MediaContent
		err := reader.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_MediaContent_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateMediaContent()
	pbObj := ToPbMediaContent(obj)
	data, err := proto.Marshal(pbObj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var pbResult pb.MediaContent
		err := proto.Unmarshal(data, &pbResult)
		if err != nil {
			b.Fatal(err)
		}
		_ = FromPbMediaContent(&pbResult)
	}
}

func BenchmarkMsgpack_MediaContent_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateMediaContent()
	data, err := msgpack.Marshal(obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result MediaContent
		err := msgpack.Unmarshal(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

// ============================================================================
// MediaContentList Benchmarks
// ============================================================================

func BenchmarkFory_MediaContentList_Serialize(b *testing.B) {
	f := newFory()
	obj := CreateMediaContentList()
	buf := fory.NewByteBuffer(make([]byte, 0, 131072))

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		buf.Reset()
		err := f.SerializeTo(buf, &obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_MediaContentList_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateMediaContentList()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		pbObj := ToPbMediaContentList(obj)
		_, err := proto.Marshal(pbObj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkMsgpack_MediaContentList_Serialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateMediaContentList()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, err := msgpack.Marshal(obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkFory_MediaContentList_Deserialize(b *testing.B) {
	writer := newFory()
	reader := newForyReader()
	obj := CreateMediaContentList()
	data, err := writer.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	if schemaMismatchEnabled() {
		var result MediaContentListV2
		if err := reader.Deserialize(data, &result); err != nil {
			b.Fatal(err)
		}
		if len(result.MediaContentList) == 0 ||
			result.MediaContentList[0].Media.Width != int64(obj.MediaContentList[0].Media.Width) ||
			len(result.MediaContentList[0].Images) == 0 ||
			result.MediaContentList[0].Images[0].Width != int64(obj.MediaContentList[0].Images[0].Width) {
			b.Fatal("MediaContentListV2 schema mismatch read failed")
		}
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			var result MediaContentListV2
			if err := reader.Deserialize(data, &result); err != nil {
				b.Fatal(err)
			}
		}
		return
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result MediaContentList
		err := reader.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_MediaContentList_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateMediaContentList()
	pbObj := ToPbMediaContentList(obj)
	data, err := proto.Marshal(pbObj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var pbResult pb.MediaContentList
		err := proto.Unmarshal(data, &pbResult)
		if err != nil {
			b.Fatal(err)
		}
		_ = FromPbMediaContentList(&pbResult)
	}
}

func BenchmarkMsgpack_MediaContentList_Deserialize(b *testing.B) {
	rejectNonForySchemaMismatch(b)
	obj := CreateMediaContentList()
	data, err := msgpack.Marshal(obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result MediaContentList
		err := msgpack.Unmarshal(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

// ============================================================================
// Size Comparison (run once to print sizes)
// ============================================================================

func TestPrintSerializedSizes(t *testing.T) {
	f := newFory()
	if schemaMismatchEnabled() {
		numericStruct := CreateNumericStruct()
		foryStructData, _ := f.Serialize(&numericStruct)
		sample := CreateSample()
		forySampleData, _ := f.Serialize(&sample)
		mediaContent := CreateMediaContent()
		foryMediaData, _ := f.Serialize(&mediaContent)
		structList := CreateNumericStructList()
		foryStructListData, _ := f.Serialize(&structList)
		sampleList := CreateSampleList()
		forySampleListData, _ := f.Serialize(&sampleList)
		mediaContentList := CreateMediaContentList()
		foryMediaContentListData, _ := f.Serialize(&mediaContentList)

		fmt.Println("============================================")
		fmt.Println("Serialized Sizes (bytes):")
		fmt.Println("============================================")
		fmt.Printf("NumericStruct:\n")
		fmt.Printf("  Fory:     %d bytes\n", len(foryStructData))
		fmt.Printf("Sample:\n")
		fmt.Printf("  Fory:     %d bytes\n", len(forySampleData))
		fmt.Printf("MediaContent:\n")
		fmt.Printf("  Fory:     %d bytes\n", len(foryMediaData))
		fmt.Printf("NumericStructList:\n")
		fmt.Printf("  Fory:     %d bytes\n", len(foryStructListData))
		fmt.Printf("SampleList:\n")
		fmt.Printf("  Fory:     %d bytes\n", len(forySampleListData))
		fmt.Printf("MediaContentList:\n")
		fmt.Printf("  Fory:     %d bytes\n", len(foryMediaContentListData))
		fmt.Println("============================================")
		return
	}

	// NumericStruct sizes
	numericStruct := CreateNumericStruct()
	foryStructData, _ := f.Serialize(&numericStruct)
	pbStructData, _ := proto.Marshal(ToPbStruct(numericStruct))
	msgpackStructData, _ := msgpack.Marshal(numericStruct)

	// Sample sizes
	sample := CreateSample()
	forySampleData, _ := f.Serialize(&sample)
	pbSampleData, _ := proto.Marshal(ToPbSample(sample))
	msgpackSampleData, _ := msgpack.Marshal(sample)

	// MediaContent sizes
	mediaContent := CreateMediaContent()
	foryMediaData, _ := f.Serialize(&mediaContent)
	pbMediaData, _ := proto.Marshal(ToPbMediaContent(mediaContent))
	msgpackMediaData, _ := msgpack.Marshal(mediaContent)

	// NumericStructList sizes
	structList := CreateNumericStructList()
	foryStructListData, _ := f.Serialize(&structList)
	pbStructListData, _ := proto.Marshal(ToPbNumericStructList(structList))
	msgpackStructListData, _ := msgpack.Marshal(structList)

	// SampleList sizes
	sampleList := CreateSampleList()
	forySampleListData, _ := f.Serialize(&sampleList)
	pbSampleListData, _ := proto.Marshal(ToPbSampleList(sampleList))
	msgpackSampleListData, _ := msgpack.Marshal(sampleList)

	// MediaContentList sizes
	mediaContentList := CreateMediaContentList()
	foryMediaContentListData, _ := f.Serialize(&mediaContentList)
	pbMediaContentListData, _ := proto.Marshal(ToPbMediaContentList(mediaContentList))
	msgpackMediaContentListData, _ := msgpack.Marshal(mediaContentList)

	fmt.Println("============================================")
	fmt.Println("Serialized Sizes (bytes):")
	fmt.Println("============================================")
	fmt.Printf("NumericStruct:\n")
	fmt.Printf("  Fory:     %d bytes\n", len(foryStructData))
	fmt.Printf("  Protobuf: %d bytes\n", len(pbStructData))
	fmt.Printf("  Msgpack:  %d bytes\n", len(msgpackStructData))
	fmt.Printf("Sample:\n")
	fmt.Printf("  Fory:     %d bytes\n", len(forySampleData))
	fmt.Printf("  Protobuf: %d bytes\n", len(pbSampleData))
	fmt.Printf("  Msgpack:  %d bytes\n", len(msgpackSampleData))
	fmt.Printf("MediaContent:\n")
	fmt.Printf("  Fory:     %d bytes\n", len(foryMediaData))
	fmt.Printf("  Protobuf: %d bytes\n", len(pbMediaData))
	fmt.Printf("  Msgpack:  %d bytes\n", len(msgpackMediaData))
	fmt.Printf("NumericStructList:\n")
	fmt.Printf("  Fory:     %d bytes\n", len(foryStructListData))
	fmt.Printf("  Protobuf: %d bytes\n", len(pbStructListData))
	fmt.Printf("  Msgpack:  %d bytes\n", len(msgpackStructListData))
	fmt.Printf("SampleList:\n")
	fmt.Printf("  Fory:     %d bytes\n", len(forySampleListData))
	fmt.Printf("  Protobuf: %d bytes\n", len(pbSampleListData))
	fmt.Printf("  Msgpack:  %d bytes\n", len(msgpackSampleListData))
	fmt.Printf("MediaContentList:\n")
	fmt.Printf("  Fory:     %d bytes\n", len(foryMediaContentListData))
	fmt.Printf("  Protobuf: %d bytes\n", len(pbMediaContentListData))
	fmt.Printf("  Msgpack:  %d bytes\n", len(msgpackMediaContentListData))
	fmt.Println("============================================")
}
