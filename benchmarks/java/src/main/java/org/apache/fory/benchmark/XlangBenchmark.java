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

package org.apache.fory.benchmark;

import com.google.flatbuffers.FlatBufferBuilder;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.annotation.Int32Type;
import org.apache.fory.benchmark.xlang.generated.FBSImage;
import org.apache.fory.benchmark.xlang.generated.FBSMedia;
import org.apache.fory.benchmark.xlang.generated.FBSMediaContent;
import org.apache.fory.benchmark.xlang.generated.FBSMediaContentList;
import org.apache.fory.benchmark.xlang.generated.FBSNumericStruct;
import org.apache.fory.benchmark.xlang.generated.FBSNumericStructList;
import org.apache.fory.benchmark.xlang.generated.FBSSample;
import org.apache.fory.benchmark.xlang.generated.FBSSampleList;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.integration_tests.state.generated.ProtoMessage;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.StaticGeneratedStructSerializer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@Fork(value = 1)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class XlangBenchmark {
  private static final int LIST_SIZE = 5;
  private static final String SCHEMA_MISMATCH_ENV = "FORY_BENCH_SCHEMA_MISMATCH";

  @State(Scope.Thread)
  public static class XlangState {
    @Param({"true", "false"})
    public boolean codegen;

    public Fory foryWriter;
    public Fory foryReader;
    public boolean schemaMismatch;

    public NumericStruct numericStruct;
    public Sample sample;
    public MediaContent mediaContent;
    public NumericStructList numericStructList;
    public SampleList sampleList;
    public MediaContentList mediaContentList;

    public byte[] foryNumericStructBytes;
    public byte[] forySampleBytes;
    public byte[] foryMediaContentBytes;
    public byte[] foryNumericStructListBytes;
    public byte[] forySampleListBytes;
    public byte[] foryMediaContentListBytes;

    public byte[] protobufNumericStructBytes;
    public byte[] protobufSampleBytes;
    public byte[] protobufMediaContentBytes;
    public byte[] protobufNumericStructListBytes;
    public byte[] protobufSampleListBytes;
    public byte[] protobufMediaContentListBytes;

    public byte[] flatbufferNumericStructBytes;
    public byte[] flatbufferSampleBytes;
    public byte[] flatbufferMediaContentBytes;
    public byte[] flatbufferNumericStructListBytes;
    public byte[] flatbufferSampleListBytes;
    public byte[] flatbufferMediaContentListBytes;

    public ByteBuffer flatbufferNumericStructBuffer;
    public ByteBuffer flatbufferSampleBuffer;
    public ByteBuffer flatbufferMediaContentBuffer;
    public ByteBuffer flatbufferNumericStructListBuffer;
    public ByteBuffer flatbufferSampleListBuffer;
    public ByteBuffer flatbufferMediaContentListBuffer;

    @Setup(Level.Trial)
    public void setup() {
      schemaMismatch = schemaMismatchEnabled();
      foryWriter = newBenchmarkFory(codegen);
      registerForyTypes(foryWriter);
      if (schemaMismatch) {
        foryReader = newBenchmarkFory(codegen);
        registerForyTypesV2(foryReader);
      } else {
        foryReader = foryWriter;
      }

      numericStruct = createNumericStruct();
      sample = createSample();
      mediaContent = createMediaContent();
      numericStructList = createNumericStructList();
      sampleList = createSampleList();
      mediaContentList = createMediaContentList();

      foryNumericStructBytes = foryWriter.serialize(numericStruct);
      forySampleBytes = foryWriter.serialize(sample);
      foryMediaContentBytes = foryWriter.serialize(mediaContent);
      foryNumericStructListBytes = foryWriter.serialize(numericStructList);
      forySampleListBytes = foryWriter.serialize(sampleList);
      foryMediaContentListBytes = foryWriter.serialize(mediaContentList);

      if (!schemaMismatch) {
        protobufNumericStructBytes = toFixedProto(numericStruct).toByteArray();
        protobufSampleBytes = toProto(sample).toByteArray();
        protobufMediaContentBytes = toProto(mediaContent).toByteArray();
        protobufNumericStructListBytes = toProto(numericStructList).toByteArray();
        protobufSampleListBytes = toProto(sampleList).toByteArray();
        protobufMediaContentListBytes = toProto(mediaContentList).toByteArray();

        flatbufferNumericStructBytes = toFlatBuffer(numericStruct);
        flatbufferSampleBytes = toFlatBuffer(sample);
        flatbufferMediaContentBytes = toFlatBuffer(mediaContent);
        flatbufferNumericStructListBytes = toFlatBuffer(numericStructList);
        flatbufferSampleListBytes = toFlatBuffer(sampleList);
        flatbufferMediaContentListBytes = toFlatBuffer(mediaContentList);

        flatbufferNumericStructBuffer = ByteBuffer.wrap(flatbufferNumericStructBytes);
        flatbufferSampleBuffer = ByteBuffer.wrap(flatbufferSampleBytes);
        flatbufferMediaContentBuffer = ByteBuffer.wrap(flatbufferMediaContentBytes);
        flatbufferNumericStructListBuffer = ByteBuffer.wrap(flatbufferNumericStructListBytes);
        flatbufferSampleListBuffer = ByteBuffer.wrap(flatbufferSampleListBytes);
        flatbufferMediaContentListBuffer = ByteBuffer.wrap(flatbufferMediaContentListBytes);
      }

      verifySetup();
    }

    private void verifySetup() {
      verifyForySerializerMode(foryWriter, NumericStruct.class);
      verifyForySerializerMode(foryWriter, Sample.class);
      verifyForySerializerMode(foryWriter, MediaContent.class);
      if (schemaMismatch) {
        verifyForySerializerMode(foryReader, NumericStructV2.class);
        verifyForySerializerMode(foryReader, SampleV2.class);
        verifyForySerializerMode(foryReader, MediaContentV2.class);
        verifySchemaMismatch();
      } else {
        foryReader.deserialize(foryNumericStructBytes);
        fromProtoStruct(protobufNumericStructBytes);
        fromFlatBufferNumericStruct(flatbufferNumericStructBuffer);
      }
    }

    private void verifyForySerializerMode(Fory fory, Class<?> type) {
      Serializer<?> serializer = fory.getTypeResolver().getSerializer(type);
      boolean staticSerializer = serializer instanceof StaticGeneratedStructSerializer;
      if (staticSerializer == codegen) {
        throw new IllegalStateException(
            "Unexpected serializer for "
                + type.getName()
                + " with codegen="
                + codegen
                + ": "
                + serializer.getClass().getName());
      }
    }

    private void verifySchemaMismatch() {
      NumericStructV2 numeric = (NumericStructV2) foryReader.deserialize(foryNumericStructBytes);
      if (numeric.f1 != numericStruct.f1) {
        throw new IllegalStateException("NumericStructV2 schema mismatch read failed");
      }
      SampleV2 sampleV2 = (SampleV2) foryReader.deserialize(forySampleBytes);
      if (sampleV2.int_value != sample.int_value) {
        throw new IllegalStateException("SampleV2 schema mismatch read failed");
      }
      MediaContentV2 mediaContentV2 =
          (MediaContentV2) foryReader.deserialize(foryMediaContentBytes);
      if (mediaContentV2.media.width != mediaContent.media.width
          || mediaContentV2.images.isEmpty()
          || mediaContentV2.images.get(0).width != mediaContent.images.get(0).width) {
        throw new IllegalStateException("MediaContentV2 schema mismatch read failed");
      }
      NumericStructListV2 structList =
          (NumericStructListV2) foryReader.deserialize(foryNumericStructListBytes);
      if (structList.struct_list.isEmpty()
          || structList.struct_list.get(0).f1 != numericStructList.struct_list.get(0).f1) {
        throw new IllegalStateException("NumericStructListV2 schema mismatch read failed");
      }
      SampleListV2 sampleListV2 = (SampleListV2) foryReader.deserialize(forySampleListBytes);
      if (sampleListV2.sample_list.isEmpty()
          || sampleListV2.sample_list.get(0).int_value != sampleList.sample_list.get(0).int_value) {
        throw new IllegalStateException("SampleListV2 schema mismatch read failed");
      }
      MediaContentListV2 mediaList =
          (MediaContentListV2) foryReader.deserialize(foryMediaContentListBytes);
      if (mediaList.media_content_list.isEmpty()
          || mediaList.media_content_list.get(0).media.width
              != mediaContentList.media_content_list.get(0).media.width
          || mediaList.media_content_list.get(0).images.isEmpty()
          || mediaList.media_content_list.get(0).images.get(0).width
              != mediaContentList.media_content_list.get(0).images.get(0).width) {
        throw new IllegalStateException("MediaContentListV2 schema mismatch read failed");
      }
    }
  }

  private static Fory newBenchmarkFory(boolean codegen) {
    return Fory.builder()
        .withXlang(true)
        .withCompatible(true)
        .withCodegen(codegen)
        .withRefTracking(false)
        .withClassVersionCheck(false)
        .requireClassRegistration(true)
        .build();
  }

  private static boolean schemaMismatchEnabled() {
    return "1".equals(System.getenv(SCHEMA_MISMATCH_ENV));
  }

  private static void rejectNonForySchemaMismatch() {
    if (schemaMismatchEnabled()) {
      throw new IllegalStateException(
          SCHEMA_MISMATCH_ENV + "=1 supports only Fory benchmarks; rerun with --serializer fory");
    }
  }

  private static void registerForyTypes(Fory fory) {
    fory.register(NumericStruct.class, 1);
    fory.register(Sample.class, 2);
    fory.register(Media.class, 3);
    fory.register(Image.class, 4);
    fory.register(MediaContent.class, 5);
    fory.register(NumericStructList.class, 6);
    fory.register(SampleList.class, 7);
    fory.register(MediaContentList.class, 8);
    fory.register(Player.class, 101);
    fory.register(Size.class, 102);
  }

  private static void registerForyTypesV2(Fory fory) {
    fory.register(NumericStructV2.class, 1);
    fory.register(SampleV2.class, 2);
    fory.register(MediaV2.class, 3);
    fory.register(ImageV2.class, 4);
    fory.register(MediaContentV2.class, 5);
    fory.register(NumericStructListV2.class, 6);
    fory.register(SampleListV2.class, 7);
    fory.register(MediaContentListV2.class, 8);
    fory.register(Player.class, 101);
    fory.register(Size.class, 102);
  }

  @Benchmark
  public Object BM_Fory_NumericStruct_Serialize(XlangState state) {
    return state.foryWriter.serialize(state.numericStruct);
  }

  @Benchmark
  public Object BM_Fory_NumericStruct_Deserialize(XlangState state) {
    return state.foryReader.deserialize(state.foryNumericStructBytes);
  }

  @Benchmark
  public Object BM_Protobuf_NumericStruct_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toFixedProto(state.numericStruct).toByteArray();
  }

  @Benchmark
  public Object BM_Protobuf_NumericStruct_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromProtoStruct(state.protobufNumericStructBytes);
  }

  @Benchmark
  public Object BM_Flatbuffer_NumericStruct_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toFlatBuffer(state.numericStruct);
  }

  @Benchmark
  public Object BM_Flatbuffer_NumericStruct_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromFlatBufferNumericStruct(state.flatbufferNumericStructBuffer);
  }

  @Benchmark
  public Object BM_Fory_Sample_Serialize(XlangState state) {
    return state.foryWriter.serialize(state.sample);
  }

  @Benchmark
  public Object BM_Fory_Sample_Deserialize(XlangState state) {
    return state.foryReader.deserialize(state.forySampleBytes);
  }

  @Benchmark
  public Object BM_Protobuf_Sample_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toProto(state.sample).toByteArray();
  }

  @Benchmark
  public Object BM_Protobuf_Sample_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromProtoSample(state.protobufSampleBytes);
  }

  @Benchmark
  public Object BM_Flatbuffer_Sample_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toFlatBuffer(state.sample);
  }

  @Benchmark
  public Object BM_Flatbuffer_Sample_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromFlatBufferSample(state.flatbufferSampleBuffer);
  }

  @Benchmark
  public Object BM_Fory_MediaContent_Serialize(XlangState state) {
    return state.foryWriter.serialize(state.mediaContent);
  }

  @Benchmark
  public Object BM_Fory_MediaContent_Deserialize(XlangState state) {
    return state.foryReader.deserialize(state.foryMediaContentBytes);
  }

  @Benchmark
  public Object BM_Protobuf_MediaContent_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toProto(state.mediaContent).toByteArray();
  }

  @Benchmark
  public Object BM_Protobuf_MediaContent_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromProtoMediaContent(state.protobufMediaContentBytes);
  }

  @Benchmark
  public Object BM_Flatbuffer_MediaContent_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toFlatBuffer(state.mediaContent);
  }

  @Benchmark
  public Object BM_Flatbuffer_MediaContent_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromFlatBufferMediaContent(state.flatbufferMediaContentBuffer);
  }

  @Benchmark
  public Object BM_Fory_NumericStructList_Serialize(XlangState state) {
    return state.foryWriter.serialize(state.numericStructList);
  }

  @Benchmark
  public Object BM_Fory_NumericStructList_Deserialize(XlangState state) {
    return state.foryReader.deserialize(state.foryNumericStructListBytes);
  }

  @Benchmark
  public Object BM_Protobuf_NumericStructList_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toProto(state.numericStructList).toByteArray();
  }

  @Benchmark
  public Object BM_Protobuf_NumericStructList_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromProtoNumericStructList(state.protobufNumericStructListBytes);
  }

  @Benchmark
  public Object BM_Flatbuffer_NumericStructList_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toFlatBuffer(state.numericStructList);
  }

  @Benchmark
  public Object BM_Flatbuffer_NumericStructList_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromFlatBufferNumericStructList(state.flatbufferNumericStructListBuffer);
  }

  @Benchmark
  public Object BM_Fory_SampleList_Serialize(XlangState state) {
    return state.foryWriter.serialize(state.sampleList);
  }

  @Benchmark
  public Object BM_Fory_SampleList_Deserialize(XlangState state) {
    return state.foryReader.deserialize(state.forySampleListBytes);
  }

  @Benchmark
  public Object BM_Protobuf_SampleList_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toProto(state.sampleList).toByteArray();
  }

  @Benchmark
  public Object BM_Protobuf_SampleList_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromProtoSampleList(state.protobufSampleListBytes);
  }

  @Benchmark
  public Object BM_Flatbuffer_SampleList_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toFlatBuffer(state.sampleList);
  }

  @Benchmark
  public Object BM_Flatbuffer_SampleList_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromFlatBufferSampleList(state.flatbufferSampleListBuffer);
  }

  @Benchmark
  public Object BM_Fory_MediaContentList_Serialize(XlangState state) {
    return state.foryWriter.serialize(state.mediaContentList);
  }

  @Benchmark
  public Object BM_Fory_MediaContentList_Deserialize(XlangState state) {
    return state.foryReader.deserialize(state.foryMediaContentListBytes);
  }

  @Benchmark
  public Object BM_Protobuf_MediaContentList_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toProto(state.mediaContentList).toByteArray();
  }

  @Benchmark
  public Object BM_Protobuf_MediaContentList_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromProtoMediaContentList(state.protobufMediaContentListBytes);
  }

  @Benchmark
  public Object BM_Flatbuffer_MediaContentList_Serialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return toFlatBuffer(state.mediaContentList);
  }

  @Benchmark
  public Object BM_Flatbuffer_MediaContentList_Deserialize(XlangState state) {
    rejectNonForySchemaMismatch();
    return fromFlatBufferMediaContentList(state.flatbufferMediaContentListBuffer);
  }

  private static NumericStruct createNumericStruct() {
    NumericStruct struct = new NumericStruct();
    struct.f1 = -12345;
    struct.f2 = 987654321;
    struct.f3 = -31415;
    struct.f4 = 27182818;
    struct.f5 = -32000;
    struct.f6 = 1000000;
    struct.f7 = -999999999;
    struct.f8 = 42;
    struct.f9 = 123456789;
    struct.f10 = -42;
    struct.f11 = 31415926;
    struct.f12 = -27182818;
    return struct;
  }

  private static Sample createSample() {
    Sample sample = new Sample();
    sample.int_value = 123;
    sample.long_value = 1230000L;
    sample.float_value = 12.345f;
    sample.double_value = 1.234567;
    sample.short_value = 12345;
    sample.char_value = '!';
    sample.boolean_value = true;
    sample.int_value_boxed = 321;
    sample.long_value_boxed = 3210000L;
    sample.float_value_boxed = 54.321f;
    sample.double_value_boxed = 7.654321;
    sample.short_value_boxed = 32100;
    sample.char_value_boxed = '$';
    sample.boolean_value_boxed = false;
    sample.int_array = new int[] {-1234, -123, -12, -1, 0, 1, 12, 123, 1234};
    sample.long_array = new long[] {-123400, -12300, -1200, -100, 0, 100, 1200, 12300, 123400};
    sample.float_array =
        new float[] {-12.34f, -12.3f, -12.0f, -1.0f, 0.0f, 1.0f, 12.0f, 12.3f, 12.34f};
    sample.double_array = new double[] {-1.234, -1.23, -12.0, -1.0, 0.0, 1.0, 12.0, 1.23, 1.234};
    sample.short_array = new int[] {-1234, -123, -12, -1, 0, 1, 12, 123, 1234};
    sample.char_array = new int[] {'a', 's', 'd', 'f', 'A', 'S', 'D', 'F'};
    sample.boolean_array = new boolean[] {true, false, false, true};
    sample.string = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    return sample;
  }

  private static MediaContent createMediaContent() {
    MediaContent mediaContent = new MediaContent();
    Media media = new Media();
    media.uri = "http://javaone.com/keynote.ogg";
    media.title = "";
    media.width = 641;
    media.height = 481;
    media.format = "video/theora\u1234";
    media.duration = 18000001L;
    media.size = 58982401L;
    media.bitrate = 0;
    media.has_bitrate = false;
    media.persons = Arrays.asList("Bill Gates, Jr.", "Steven Jobs");
    media.player = Player.FLASH;
    media.copyright = "Copyright (c) 2009, Scooby Dooby Doo";
    mediaContent.media = media;
    mediaContent.images = new ArrayList<>(3);
    mediaContent.images.add(
        createImage(
            "http://javaone.com/keynote_huge.jpg",
            "Javaone Keynote\u1234",
            32000,
            24000,
            Size.LARGE));
    mediaContent.images.add(
        createImage("http://javaone.com/keynote_large.jpg", "", 1024, 768, Size.LARGE));
    mediaContent.images.add(
        createImage("http://javaone.com/keynote_small.jpg", "", 320, 240, Size.SMALL));
    return mediaContent;
  }

  private static Image createImage(String uri, String title, int width, int height, Size size) {
    Image image = new Image();
    image.uri = uri;
    image.title = title;
    image.width = width;
    image.height = height;
    image.size = size;
    return image;
  }

  private static NumericStructList createNumericStructList() {
    NumericStructList list = new NumericStructList();
    list.struct_list = new ArrayList<>(LIST_SIZE);
    for (int i = 0; i < LIST_SIZE; i++) {
      list.struct_list.add(createNumericStruct());
    }
    return list;
  }

  private static SampleList createSampleList() {
    SampleList list = new SampleList();
    list.sample_list = new ArrayList<>(LIST_SIZE);
    for (int i = 0; i < LIST_SIZE; i++) {
      list.sample_list.add(createSample());
    }
    return list;
  }

  private static MediaContentList createMediaContentList() {
    MediaContentList list = new MediaContentList();
    list.media_content_list = new ArrayList<>(LIST_SIZE);
    for (int i = 0; i < LIST_SIZE; i++) {
      list.media_content_list.add(createMediaContent());
    }
    return list;
  }

  private static ProtoMessage.NumericStruct toProto(NumericStruct struct) {
    return ProtoMessage.NumericStruct.newBuilder()
        .setF1(struct.f1)
        .setF2(struct.f2)
        .setF3(struct.f3)
        .setF4(struct.f4)
        .setF5(struct.f5)
        .setF6(struct.f6)
        .setF7(struct.f7)
        .setF8(struct.f8)
        .setF9(struct.f9)
        .setF10(struct.f10)
        .setF11(struct.f11)
        .setF12(struct.f12)
        .build();
  }

  private static ProtoMessage.FixedNumericStruct toFixedProto(NumericStruct struct) {
    return ProtoMessage.FixedNumericStruct.newBuilder()
        .setF1(struct.f1)
        .setF2(struct.f2)
        .setF3(struct.f3)
        .setF4(struct.f4)
        .setF5(struct.f5)
        .setF6(struct.f6)
        .setF7(struct.f7)
        .setF8(struct.f8)
        .setF9(struct.f9)
        .setF10(struct.f10)
        .setF11(struct.f11)
        .setF12(struct.f12)
        .build();
  }

  private static ProtoMessage.Sample toProto(Sample sample) {
    ProtoMessage.Sample.Builder builder = ProtoMessage.Sample.newBuilder();
    builder.setIntValue(sample.int_value);
    builder.setLongValue(sample.long_value);
    builder.setFloatValue(sample.float_value);
    builder.setDoubleValue(sample.double_value);
    builder.setShortValue(sample.short_value);
    builder.setCharValue(sample.char_value);
    builder.setBooleanValue(sample.boolean_value);
    builder.setIntValueBoxed(sample.int_value_boxed);
    builder.setLongValueBoxed(sample.long_value_boxed);
    builder.setFloatValueBoxed(sample.float_value_boxed);
    builder.setDoubleValueBoxed(sample.double_value_boxed);
    builder.setShortValueBoxed(sample.short_value_boxed);
    builder.setCharValueBoxed(sample.char_value_boxed);
    builder.setBooleanValueBoxed(sample.boolean_value_boxed);
    for (int value : sample.int_array) {
      builder.addIntArray(value);
    }
    for (long value : sample.long_array) {
      builder.addLongArray(value);
    }
    for (float value : sample.float_array) {
      builder.addFloatArray(value);
    }
    for (double value : sample.double_array) {
      builder.addDoubleArray(value);
    }
    for (int value : sample.short_array) {
      builder.addShortArray(value);
    }
    for (int value : sample.char_array) {
      builder.addCharArray(value);
    }
    for (boolean value : sample.boolean_array) {
      builder.addBooleanArray(value);
    }
    builder.setString(sample.string);
    return builder.build();
  }

  private static ProtoMessage.MediaContent toProto(MediaContent mediaContent) {
    ProtoMessage.MediaContent.Builder builder = ProtoMessage.MediaContent.newBuilder();
    builder.setMedia(toProto(mediaContent.media));
    for (Image image : mediaContent.images) {
      builder.addImages(toProto(image));
    }
    return builder.build();
  }

  private static ProtoMessage.Media toProto(Media media) {
    ProtoMessage.Media.Builder builder = ProtoMessage.Media.newBuilder();
    builder.setUri(media.uri);
    builder.setTitle(media.title);
    builder.setWidth(media.width);
    builder.setHeight(media.height);
    builder.setFormat(media.format);
    builder.setDuration(media.duration);
    builder.setSize(media.size);
    builder.setBitrate(media.bitrate);
    builder.setHasBitrate(media.has_bitrate);
    builder.addAllPersons(media.persons);
    builder.setPlayerValue(media.player.ordinal());
    builder.setCopyright(media.copyright);
    return builder.build();
  }

  private static ProtoMessage.Image toProto(Image image) {
    ProtoMessage.Image.Builder builder = ProtoMessage.Image.newBuilder();
    builder.setUri(image.uri);
    builder.setTitle(image.title);
    builder.setWidth(image.width);
    builder.setHeight(image.height);
    builder.setSizeValue(image.size.ordinal());
    return builder.build();
  }

  private static ProtoMessage.NumericStructList toProto(NumericStructList numericStructList) {
    ProtoMessage.NumericStructList.Builder builder = ProtoMessage.NumericStructList.newBuilder();
    for (NumericStruct struct : numericStructList.struct_list) {
      builder.addStructList(toProto(struct));
    }
    return builder.build();
  }

  private static ProtoMessage.SampleList toProto(SampleList sampleList) {
    ProtoMessage.SampleList.Builder builder = ProtoMessage.SampleList.newBuilder();
    for (Sample sample : sampleList.sample_list) {
      builder.addSampleList(toProto(sample));
    }
    return builder.build();
  }

  private static ProtoMessage.MediaContentList toProto(MediaContentList mediaContentList) {
    ProtoMessage.MediaContentList.Builder builder = ProtoMessage.MediaContentList.newBuilder();
    for (MediaContent mediaContent : mediaContentList.media_content_list) {
      builder.addMediaContentList(toProto(mediaContent));
    }
    return builder.build();
  }

  private static NumericStruct fromProtoStruct(byte[] bytes) {
    try {
      return fromProto(ProtoMessage.FixedNumericStruct.parseFrom(bytes));
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Sample fromProtoSample(byte[] bytes) {
    try {
      return fromProto(ProtoMessage.Sample.parseFrom(bytes));
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  private static MediaContent fromProtoMediaContent(byte[] bytes) {
    try {
      return fromProto(ProtoMessage.MediaContent.parseFrom(bytes));
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  private static NumericStructList fromProtoNumericStructList(byte[] bytes) {
    try {
      return fromProto(ProtoMessage.NumericStructList.parseFrom(bytes));
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SampleList fromProtoSampleList(byte[] bytes) {
    try {
      return fromProto(ProtoMessage.SampleList.parseFrom(bytes));
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  private static MediaContentList fromProtoMediaContentList(byte[] bytes) {
    try {
      return fromProto(ProtoMessage.MediaContentList.parseFrom(bytes));
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  private static NumericStruct fromProto(ProtoMessage.NumericStruct proto) {
    NumericStruct struct = new NumericStruct();
    struct.f1 = proto.getF1();
    struct.f2 = proto.getF2();
    struct.f3 = proto.getF3();
    struct.f4 = proto.getF4();
    struct.f5 = proto.getF5();
    struct.f6 = proto.getF6();
    struct.f7 = proto.getF7();
    struct.f8 = proto.getF8();
    struct.f9 = proto.getF9();
    struct.f10 = proto.getF10();
    struct.f11 = proto.getF11();
    struct.f12 = proto.getF12();
    return struct;
  }

  private static NumericStruct fromProto(ProtoMessage.FixedNumericStruct proto) {
    NumericStruct struct = new NumericStruct();
    struct.f1 = proto.getF1();
    struct.f2 = proto.getF2();
    struct.f3 = proto.getF3();
    struct.f4 = proto.getF4();
    struct.f5 = proto.getF5();
    struct.f6 = proto.getF6();
    struct.f7 = proto.getF7();
    struct.f8 = proto.getF8();
    struct.f9 = proto.getF9();
    struct.f10 = proto.getF10();
    struct.f11 = proto.getF11();
    struct.f12 = proto.getF12();
    return struct;
  }

  private static Sample fromProto(ProtoMessage.Sample proto) {
    Sample sample = new Sample();
    sample.int_value = proto.getIntValue();
    sample.long_value = proto.getLongValue();
    sample.float_value = proto.getFloatValue();
    sample.double_value = proto.getDoubleValue();
    sample.short_value = proto.getShortValue();
    sample.char_value = proto.getCharValue();
    sample.boolean_value = proto.getBooleanValue();
    sample.int_value_boxed = proto.getIntValueBoxed();
    sample.long_value_boxed = proto.getLongValueBoxed();
    sample.float_value_boxed = proto.getFloatValueBoxed();
    sample.double_value_boxed = proto.getDoubleValueBoxed();
    sample.short_value_boxed = proto.getShortValueBoxed();
    sample.char_value_boxed = proto.getCharValueBoxed();
    sample.boolean_value_boxed = proto.getBooleanValueBoxed();
    sample.int_array = new int[proto.getIntArrayCount()];
    for (int i = 0; i < sample.int_array.length; i++) {
      sample.int_array[i] = proto.getIntArray(i);
    }
    sample.long_array = new long[proto.getLongArrayCount()];
    for (int i = 0; i < sample.long_array.length; i++) {
      sample.long_array[i] = proto.getLongArray(i);
    }
    sample.float_array = new float[proto.getFloatArrayCount()];
    for (int i = 0; i < sample.float_array.length; i++) {
      sample.float_array[i] = proto.getFloatArray(i);
    }
    sample.double_array = new double[proto.getDoubleArrayCount()];
    for (int i = 0; i < sample.double_array.length; i++) {
      sample.double_array[i] = proto.getDoubleArray(i);
    }
    sample.short_array = new int[proto.getShortArrayCount()];
    for (int i = 0; i < sample.short_array.length; i++) {
      sample.short_array[i] = proto.getShortArray(i);
    }
    sample.char_array = new int[proto.getCharArrayCount()];
    for (int i = 0; i < sample.char_array.length; i++) {
      sample.char_array[i] = proto.getCharArray(i);
    }
    sample.boolean_array = new boolean[proto.getBooleanArrayCount()];
    for (int i = 0; i < sample.boolean_array.length; i++) {
      sample.boolean_array[i] = proto.getBooleanArray(i);
    }
    sample.string = proto.getString();
    return sample;
  }

  private static MediaContent fromProto(ProtoMessage.MediaContent proto) {
    MediaContent mediaContent = new MediaContent();
    mediaContent.media = fromProto(proto.getMedia());
    mediaContent.images = new ArrayList<>(proto.getImagesCount());
    for (ProtoMessage.Image image : proto.getImagesList()) {
      mediaContent.images.add(fromProto(image));
    }
    return mediaContent;
  }

  private static Media fromProto(ProtoMessage.Media proto) {
    Media media = new Media();
    media.uri = proto.getUri();
    media.title = proto.getTitle();
    media.width = proto.getWidth();
    media.height = proto.getHeight();
    media.format = proto.getFormat();
    media.duration = proto.getDuration();
    media.size = proto.getSize();
    media.bitrate = proto.getBitrate();
    media.has_bitrate = proto.getHasBitrate();
    media.persons = new ArrayList<>(proto.getPersonsList());
    media.player = Player.values()[proto.getPlayerValue()];
    media.copyright = proto.getCopyright();
    return media;
  }

  private static Image fromProto(ProtoMessage.Image proto) {
    Image image = new Image();
    image.uri = proto.getUri();
    image.title = proto.getTitle();
    image.width = proto.getWidth();
    image.height = proto.getHeight();
    image.size = Size.values()[proto.getSizeValue()];
    return image;
  }

  private static NumericStructList fromProto(ProtoMessage.NumericStructList proto) {
    NumericStructList list = new NumericStructList();
    list.struct_list = new ArrayList<>(proto.getStructListCount());
    for (ProtoMessage.NumericStruct struct : proto.getStructListList()) {
      list.struct_list.add(fromProto(struct));
    }
    return list;
  }

  private static SampleList fromProto(ProtoMessage.SampleList proto) {
    SampleList list = new SampleList();
    list.sample_list = new ArrayList<>(proto.getSampleListCount());
    for (ProtoMessage.Sample sample : proto.getSampleListList()) {
      list.sample_list.add(fromProto(sample));
    }
    return list;
  }

  private static MediaContentList fromProto(ProtoMessage.MediaContentList proto) {
    MediaContentList list = new MediaContentList();
    list.media_content_list = new ArrayList<>(proto.getMediaContentListCount());
    for (ProtoMessage.MediaContent mediaContent : proto.getMediaContentListList()) {
      list.media_content_list.add(fromProto(mediaContent));
    }
    return list;
  }

  private static byte[] toFlatBuffer(NumericStruct struct) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int root = buildFlatBuffer(builder, struct);
    builder.finish(root);
    return builder.sizedByteArray();
  }

  private static byte[] toFlatBuffer(Sample sample) {
    FlatBufferBuilder builder = new FlatBufferBuilder(512);
    int root = buildFlatBuffer(builder, sample);
    builder.finish(root);
    return builder.sizedByteArray();
  }

  private static byte[] toFlatBuffer(MediaContent mediaContent) {
    FlatBufferBuilder builder = new FlatBufferBuilder(512);
    int root = buildFlatBuffer(builder, mediaContent);
    builder.finish(root);
    return builder.sizedByteArray();
  }

  private static byte[] toFlatBuffer(NumericStructList numericStructList) {
    FlatBufferBuilder builder = new FlatBufferBuilder(512);
    int[] offsets = new int[numericStructList.struct_list.size()];
    for (int i = 0; i < offsets.length; i++) {
      offsets[i] = buildFlatBuffer(builder, numericStructList.struct_list.get(i));
    }
    int vector = FBSNumericStructList.createStructListVector(builder, offsets);
    int root = FBSNumericStructList.createFBSNumericStructList(builder, vector);
    builder.finish(root);
    return builder.sizedByteArray();
  }

  private static byte[] toFlatBuffer(SampleList sampleList) {
    FlatBufferBuilder builder = new FlatBufferBuilder(2048);
    int[] offsets = new int[sampleList.sample_list.size()];
    for (int i = 0; i < offsets.length; i++) {
      offsets[i] = buildFlatBuffer(builder, sampleList.sample_list.get(i));
    }
    int vector = FBSSampleList.createSampleListVector(builder, offsets);
    int root = FBSSampleList.createFBSSampleList(builder, vector);
    builder.finish(root);
    return builder.sizedByteArray();
  }

  private static byte[] toFlatBuffer(MediaContentList mediaContentList) {
    FlatBufferBuilder builder = new FlatBufferBuilder(4096);
    int[] offsets = new int[mediaContentList.media_content_list.size()];
    for (int i = 0; i < offsets.length; i++) {
      offsets[i] = buildFlatBuffer(builder, mediaContentList.media_content_list.get(i));
    }
    int vector = FBSMediaContentList.createMediaContentListVector(builder, offsets);
    int root = FBSMediaContentList.createFBSMediaContentList(builder, vector);
    builder.finish(root);
    return builder.sizedByteArray();
  }

  private static int buildFlatBuffer(FlatBufferBuilder builder, NumericStruct struct) {
    return FBSNumericStruct.createFBSNumericStruct(
        builder,
        struct.f1,
        struct.f2,
        struct.f3,
        struct.f4,
        struct.f5,
        struct.f6,
        struct.f7,
        struct.f8,
        struct.f9,
        struct.f10,
        struct.f11,
        struct.f12);
  }

  private static int buildFlatBuffer(FlatBufferBuilder builder, Sample sample) {
    int stringOffset = builder.createString(sample.string);
    int intArrayOffset = FBSSample.createIntArrayVector(builder, sample.int_array);
    int longArrayOffset = FBSSample.createLongArrayVector(builder, sample.long_array);
    int floatArrayOffset = FBSSample.createFloatArrayVector(builder, sample.float_array);
    int doubleArrayOffset = FBSSample.createDoubleArrayVector(builder, sample.double_array);
    int shortArrayOffset = FBSSample.createShortArrayVector(builder, sample.short_array);
    int charArrayOffset = FBSSample.createCharArrayVector(builder, sample.char_array);
    int booleanArrayOffset = FBSSample.createBooleanArrayVector(builder, sample.boolean_array);
    return FBSSample.createFBSSample(
        builder,
        sample.int_value,
        sample.long_value,
        sample.float_value,
        sample.double_value,
        sample.short_value,
        sample.char_value,
        sample.boolean_value,
        sample.int_value_boxed,
        sample.long_value_boxed,
        sample.float_value_boxed,
        sample.double_value_boxed,
        sample.short_value_boxed,
        sample.char_value_boxed,
        sample.boolean_value_boxed,
        intArrayOffset,
        longArrayOffset,
        floatArrayOffset,
        doubleArrayOffset,
        shortArrayOffset,
        charArrayOffset,
        booleanArrayOffset,
        stringOffset);
  }

  private static int buildFlatBuffer(FlatBufferBuilder builder, MediaContent mediaContent) {
    int mediaOffset = buildFlatBuffer(builder, mediaContent.media);
    int[] imageOffsets = new int[mediaContent.images.size()];
    for (int i = 0; i < imageOffsets.length; i++) {
      imageOffsets[i] = buildFlatBuffer(builder, mediaContent.images.get(i));
    }
    int imagesOffset = FBSMediaContent.createImagesVector(builder, imageOffsets);
    return FBSMediaContent.createFBSMediaContent(builder, mediaOffset, imagesOffset);
  }

  private static int buildFlatBuffer(FlatBufferBuilder builder, Media media) {
    int uriOffset = builder.createString(media.uri);
    int titleOffset = builder.createString(media.title);
    int formatOffset = builder.createString(media.format);
    int[] personsOffsets = new int[media.persons.size()];
    for (int i = 0; i < personsOffsets.length; i++) {
      personsOffsets[i] = builder.createString(media.persons.get(i));
    }
    int personsOffset = FBSMedia.createPersonsVector(builder, personsOffsets);
    int copyrightOffset = builder.createString(media.copyright);
    return FBSMedia.createFBSMedia(
        builder,
        uriOffset,
        titleOffset,
        media.width,
        media.height,
        formatOffset,
        media.duration,
        media.size,
        media.bitrate,
        media.has_bitrate,
        personsOffset,
        (byte) media.player.ordinal(),
        copyrightOffset);
  }

  private static int buildFlatBuffer(FlatBufferBuilder builder, Image image) {
    int uriOffset = builder.createString(image.uri);
    int titleOffset = builder.createString(image.title);
    return FBSImage.createFBSImage(
        builder, uriOffset, titleOffset, image.width, image.height, (byte) image.size.ordinal());
  }

  private static NumericStruct fromFlatBufferNumericStruct(ByteBuffer buffer) {
    return fromFlatBuffer(FBSNumericStruct.getRootAsFBSNumericStruct(buffer));
  }

  private static Sample fromFlatBufferSample(ByteBuffer buffer) {
    return fromFlatBuffer(FBSSample.getRootAsFBSSample(buffer));
  }

  private static MediaContent fromFlatBufferMediaContent(ByteBuffer buffer) {
    return fromFlatBuffer(FBSMediaContent.getRootAsFBSMediaContent(buffer));
  }

  private static NumericStructList fromFlatBufferNumericStructList(ByteBuffer buffer) {
    FBSNumericStructList fbsList = FBSNumericStructList.getRootAsFBSNumericStructList(buffer);
    NumericStructList list = new NumericStructList();
    list.struct_list = new ArrayList<>(fbsList.structListLength());
    for (int i = 0; i < fbsList.structListLength(); i++) {
      list.struct_list.add(fromFlatBuffer(fbsList.structList(i)));
    }
    return list;
  }

  private static SampleList fromFlatBufferSampleList(ByteBuffer buffer) {
    FBSSampleList fbsList = FBSSampleList.getRootAsFBSSampleList(buffer);
    SampleList list = new SampleList();
    list.sample_list = new ArrayList<>(fbsList.sampleListLength());
    for (int i = 0; i < fbsList.sampleListLength(); i++) {
      list.sample_list.add(fromFlatBuffer(fbsList.sampleList(i)));
    }
    return list;
  }

  private static MediaContentList fromFlatBufferMediaContentList(ByteBuffer buffer) {
    FBSMediaContentList fbsList = FBSMediaContentList.getRootAsFBSMediaContentList(buffer);
    MediaContentList list = new MediaContentList();
    list.media_content_list = new ArrayList<>(fbsList.mediaContentListLength());
    for (int i = 0; i < fbsList.mediaContentListLength(); i++) {
      list.media_content_list.add(fromFlatBuffer(fbsList.mediaContentList(i)));
    }
    return list;
  }

  private static NumericStruct fromFlatBuffer(FBSNumericStruct fbsStruct) {
    NumericStruct struct = new NumericStruct();
    struct.f1 = fbsStruct.f1();
    struct.f2 = fbsStruct.f2();
    struct.f3 = fbsStruct.f3();
    struct.f4 = fbsStruct.f4();
    struct.f5 = fbsStruct.f5();
    struct.f6 = fbsStruct.f6();
    struct.f7 = fbsStruct.f7();
    struct.f8 = fbsStruct.f8();
    struct.f9 = fbsStruct.f9();
    struct.f10 = fbsStruct.f10();
    struct.f11 = fbsStruct.f11();
    struct.f12 = fbsStruct.f12();
    return struct;
  }

  private static Sample fromFlatBuffer(FBSSample fbsSample) {
    Sample sample = new Sample();
    sample.int_value = fbsSample.intValue();
    sample.long_value = fbsSample.longValue();
    sample.float_value = fbsSample.floatValue();
    sample.double_value = fbsSample.doubleValue();
    sample.short_value = fbsSample.shortValue();
    sample.char_value = fbsSample.charValue();
    sample.boolean_value = fbsSample.booleanValue();
    sample.int_value_boxed = fbsSample.intValueBoxed();
    sample.long_value_boxed = fbsSample.longValueBoxed();
    sample.float_value_boxed = fbsSample.floatValueBoxed();
    sample.double_value_boxed = fbsSample.doubleValueBoxed();
    sample.short_value_boxed = fbsSample.shortValueBoxed();
    sample.char_value_boxed = fbsSample.charValueBoxed();
    sample.boolean_value_boxed = fbsSample.booleanValueBoxed();
    sample.int_array = new int[fbsSample.intArrayLength()];
    for (int i = 0; i < sample.int_array.length; i++) {
      sample.int_array[i] = fbsSample.intArray(i);
    }
    sample.long_array = new long[fbsSample.longArrayLength()];
    for (int i = 0; i < sample.long_array.length; i++) {
      sample.long_array[i] = fbsSample.longArray(i);
    }
    sample.float_array = new float[fbsSample.floatArrayLength()];
    for (int i = 0; i < sample.float_array.length; i++) {
      sample.float_array[i] = fbsSample.floatArray(i);
    }
    sample.double_array = new double[fbsSample.doubleArrayLength()];
    for (int i = 0; i < sample.double_array.length; i++) {
      sample.double_array[i] = fbsSample.doubleArray(i);
    }
    sample.short_array = new int[fbsSample.shortArrayLength()];
    for (int i = 0; i < sample.short_array.length; i++) {
      sample.short_array[i] = fbsSample.shortArray(i);
    }
    sample.char_array = new int[fbsSample.charArrayLength()];
    for (int i = 0; i < sample.char_array.length; i++) {
      sample.char_array[i] = fbsSample.charArray(i);
    }
    sample.boolean_array = new boolean[fbsSample.booleanArrayLength()];
    for (int i = 0; i < sample.boolean_array.length; i++) {
      sample.boolean_array[i] = fbsSample.booleanArray(i);
    }
    sample.string = fbsSample.string();
    return sample;
  }

  private static MediaContent fromFlatBuffer(FBSMediaContent fbsMediaContent) {
    MediaContent mediaContent = new MediaContent();
    mediaContent.media = fromFlatBuffer(fbsMediaContent.media());
    mediaContent.images = new ArrayList<>(fbsMediaContent.imagesLength());
    for (int i = 0; i < fbsMediaContent.imagesLength(); i++) {
      mediaContent.images.add(fromFlatBuffer(fbsMediaContent.images(i)));
    }
    return mediaContent;
  }

  private static Media fromFlatBuffer(FBSMedia fbsMedia) {
    Media media = new Media();
    media.uri = fbsMedia.uri();
    media.title = fbsMedia.title();
    media.width = fbsMedia.width();
    media.height = fbsMedia.height();
    media.format = fbsMedia.format();
    media.duration = fbsMedia.duration();
    media.size = fbsMedia.size();
    media.bitrate = fbsMedia.bitrate();
    media.has_bitrate = fbsMedia.hasBitrate();
    media.persons = new ArrayList<>(fbsMedia.personsLength());
    for (int i = 0; i < fbsMedia.personsLength(); i++) {
      media.persons.add(fbsMedia.persons(i));
    }
    media.player = Player.values()[fbsMedia.player()];
    media.copyright = fbsMedia.copyright();
    return media;
  }

  private static Image fromFlatBuffer(FBSImage fbsImage) {
    Image image = new Image();
    image.uri = fbsImage.uri();
    image.title = fbsImage.title();
    image.width = fbsImage.width();
    image.height = fbsImage.height();
    image.size = Size.values()[fbsImage.size()];
    return image;
  }

  public enum Player {
    JAVA,
    FLASH
  }

  public enum Size {
    SMALL,
    LARGE
  }

  @ForyStruct
  public static class NumericStruct {
    @ForyField(id = 1)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f1;

    @ForyField(id = 2)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f2;

    @ForyField(id = 3)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f3;

    @ForyField(id = 4)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f4;

    @ForyField(id = 5)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f5;

    @ForyField(id = 6)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f6;

    @ForyField(id = 7)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f7;

    @ForyField(id = 8)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f8;

    @ForyField(id = 9)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f9;

    @ForyField(id = 10)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f10;

    @ForyField(id = 11)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f11;

    @ForyField(id = 12)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f12;
  }

  @ForyStruct
  public static class Sample {
    @ForyField(id = 1)
    public int int_value;

    @ForyField(id = 2)
    public long long_value;

    @ForyField(id = 3)
    public float float_value;

    @ForyField(id = 4)
    public double double_value;

    @ForyField(id = 5)
    public int short_value;

    @ForyField(id = 6)
    public int char_value;

    @ForyField(id = 7)
    public boolean boolean_value;

    @ForyField(id = 8)
    public int int_value_boxed;

    @ForyField(id = 9)
    public long long_value_boxed;

    @ForyField(id = 10)
    public float float_value_boxed;

    @ForyField(id = 11)
    public double double_value_boxed;

    @ForyField(id = 12)
    public int short_value_boxed;

    @ForyField(id = 13)
    public int char_value_boxed;

    @ForyField(id = 14)
    public boolean boolean_value_boxed;

    @ForyField(id = 15)
    public int[] int_array;

    @ForyField(id = 16)
    public long[] long_array;

    @ForyField(id = 17)
    public float[] float_array;

    @ForyField(id = 18)
    public double[] double_array;

    @ForyField(id = 19)
    public int[] short_array;

    @ForyField(id = 20)
    public int[] char_array;

    @ForyField(id = 21)
    public boolean[] boolean_array;

    @ForyField(id = 22)
    public String string;
  }

  @ForyStruct
  public static class Media {
    @ForyField(id = 1)
    public String uri;

    @ForyField(id = 2)
    public String title;

    @ForyField(id = 3)
    public int width;

    @ForyField(id = 4)
    public int height;

    @ForyField(id = 5)
    public String format;

    @ForyField(id = 6)
    public long duration;

    @ForyField(id = 7)
    public long size;

    @ForyField(id = 8)
    public int bitrate;

    @ForyField(id = 9)
    public boolean has_bitrate;

    @ForyField(id = 10)
    public List<String> persons;

    @ForyField(id = 11)
    public Player player;

    @ForyField(id = 12)
    public String copyright;
  }

  @ForyStruct
  public static class Image {
    @ForyField(id = 1)
    public String uri;

    @ForyField(id = 2)
    public String title;

    @ForyField(id = 3)
    public int width;

    @ForyField(id = 4)
    public int height;

    @ForyField(id = 5)
    public Size size;
  }

  @ForyStruct
  public static class MediaContent {
    @ForyField(id = 1)
    public Media media;

    @ForyField(id = 2)
    public List<Image> images;
  }

  @ForyStruct
  public static class NumericStructList {
    @ForyField(id = 1)
    public List<NumericStruct> struct_list;
  }

  @ForyStruct
  public static class SampleList {
    @ForyField(id = 1)
    public List<Sample> sample_list;
  }

  @ForyStruct
  public static class MediaContentList {
    @ForyField(id = 1)
    public List<MediaContent> media_content_list;
  }

  @ForyStruct
  public static class NumericStructV2 {
    @ForyField(id = 1)
    public long f1;

    @ForyField(id = 2)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f2;

    @ForyField(id = 3)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f3;

    @ForyField(id = 4)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f4;

    @ForyField(id = 5)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f5;

    @ForyField(id = 6)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f6;

    @ForyField(id = 7)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f7;

    @ForyField(id = 8)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f8;

    @ForyField(id = 9)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f9;

    @ForyField(id = 10)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f10;

    @ForyField(id = 11)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f11;

    @ForyField(id = 12)
    public @Int32Type(encoding = Int32Encoding.FIXED) int f12;
  }

  @ForyStruct
  public static class SampleV2 {
    @ForyField(id = 1)
    public long int_value;

    @ForyField(id = 2)
    public long long_value;

    @ForyField(id = 3)
    public float float_value;

    @ForyField(id = 4)
    public double double_value;

    @ForyField(id = 5)
    public int short_value;

    @ForyField(id = 6)
    public int char_value;

    @ForyField(id = 7)
    public boolean boolean_value;

    @ForyField(id = 8)
    public int int_value_boxed;

    @ForyField(id = 9)
    public long long_value_boxed;

    @ForyField(id = 10)
    public float float_value_boxed;

    @ForyField(id = 11)
    public double double_value_boxed;

    @ForyField(id = 12)
    public int short_value_boxed;

    @ForyField(id = 13)
    public int char_value_boxed;

    @ForyField(id = 14)
    public boolean boolean_value_boxed;

    @ForyField(id = 15)
    public int[] int_array;

    @ForyField(id = 16)
    public long[] long_array;

    @ForyField(id = 17)
    public float[] float_array;

    @ForyField(id = 18)
    public double[] double_array;

    @ForyField(id = 19)
    public int[] short_array;

    @ForyField(id = 20)
    public int[] char_array;

    @ForyField(id = 21)
    public boolean[] boolean_array;

    @ForyField(id = 22)
    public String string;
  }

  @ForyStruct
  public static class MediaV2 {
    @ForyField(id = 1)
    public String uri;

    @ForyField(id = 2)
    public String title;

    @ForyField(id = 3)
    public long width;

    @ForyField(id = 4)
    public int height;

    @ForyField(id = 5)
    public String format;

    @ForyField(id = 6)
    public long duration;

    @ForyField(id = 7)
    public long size;

    @ForyField(id = 8)
    public int bitrate;

    @ForyField(id = 9)
    public boolean has_bitrate;

    @ForyField(id = 10)
    public List<String> persons;

    @ForyField(id = 11)
    public Player player;

    @ForyField(id = 12)
    public String copyright;
  }

  @ForyStruct
  public static class ImageV2 {
    @ForyField(id = 1)
    public String uri;

    @ForyField(id = 2)
    public String title;

    @ForyField(id = 3)
    public long width;

    @ForyField(id = 4)
    public int height;

    @ForyField(id = 5)
    public Size size;
  }

  @ForyStruct
  public static class MediaContentV2 {
    @ForyField(id = 1)
    public MediaV2 media;

    @ForyField(id = 2)
    public List<ImageV2> images;
  }

  @ForyStruct
  public static class NumericStructListV2 {
    @ForyField(id = 1)
    public List<NumericStructV2> struct_list;
  }

  @ForyStruct
  public static class SampleListV2 {
    @ForyField(id = 1)
    public List<SampleV2> sample_list;
  }

  @ForyStruct
  public static class MediaContentListV2 {
    @ForyField(id = 1)
    public List<MediaContentV2> media_content_list;
  }
}
