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
	// Register types with IDs matching C++ benchmark
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
	if err := f.RegisterStruct(StructList{}, 8); err != nil {
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
	return f
}

// ============================================================================
// NumericStruct Benchmarks
// ============================================================================

func BenchmarkFory_Struct_Serialize(b *testing.B) {
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

func BenchmarkProtobuf_Struct_Serialize(b *testing.B) {
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

func BenchmarkMsgpack_Struct_Serialize(b *testing.B) {
	obj := CreateNumericStruct()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, err := msgpack.Marshal(obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkFory_Struct_Deserialize(b *testing.B) {
	f := newFory()
	obj := CreateNumericStruct()
	data, err := f.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result NumericStruct
		err := f.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_Struct_Deserialize(b *testing.B) {
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
		var pbResult pb.Struct
		err := proto.Unmarshal(data, &pbResult)
		if err != nil {
			b.Fatal(err)
		}
		_ = FromPbStruct(&pbResult)
	}
}

func BenchmarkMsgpack_Struct_Deserialize(b *testing.B) {
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
// StructList Benchmarks
// ============================================================================

func BenchmarkFory_StructList_Serialize(b *testing.B) {
	f := newFory()
	obj := CreateStructList()
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

func BenchmarkProtobuf_StructList_Serialize(b *testing.B) {
	obj := CreateStructList()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		pbObj := ToPbStructList(obj)
		_, err := proto.Marshal(pbObj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkMsgpack_StructList_Serialize(b *testing.B) {
	obj := CreateStructList()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, err := msgpack.Marshal(obj)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkFory_StructList_Deserialize(b *testing.B) {
	f := newFory()
	obj := CreateStructList()
	data, err := f.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result StructList
		err := f.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_StructList_Deserialize(b *testing.B) {
	obj := CreateStructList()
	pbObj := ToPbStructList(obj)
	data, err := proto.Marshal(pbObj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var pbResult pb.StructList
		err := proto.Unmarshal(data, &pbResult)
		if err != nil {
			b.Fatal(err)
		}
		_ = FromPbStructList(&pbResult)
	}
}

func BenchmarkMsgpack_StructList_Deserialize(b *testing.B) {
	obj := CreateStructList()
	data, err := msgpack.Marshal(obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result StructList
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
	f := newFory()
	obj := CreateSample()
	data, err := f.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result Sample
		err := f.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_Sample_Deserialize(b *testing.B) {
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
	f := newFory()
	obj := CreateSampleList()
	data, err := f.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result SampleList
		err := f.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_SampleList_Deserialize(b *testing.B) {
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
	f := newFory()
	obj := CreateMediaContent()
	data, err := f.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result MediaContent
		err := f.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_MediaContent_Deserialize(b *testing.B) {
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
	f := newFory()
	obj := CreateMediaContentList()
	data, err := f.Serialize(&obj)
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var result MediaContentList
		err := f.Deserialize(data, &result)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkProtobuf_MediaContentList_Deserialize(b *testing.B) {
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

	// StructList sizes
	structList := CreateStructList()
	foryStructListData, _ := f.Serialize(&structList)
	pbStructListData, _ := proto.Marshal(ToPbStructList(structList))
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
	fmt.Printf("StructList:\n")
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
