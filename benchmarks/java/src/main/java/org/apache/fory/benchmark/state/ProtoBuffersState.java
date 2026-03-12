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

package org.apache.fory.benchmark.state;

import com.google.common.base.Preconditions;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.HashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.fory.benchmark.data.Image;
import org.apache.fory.benchmark.data.Media;
import org.apache.fory.benchmark.data.MediaContent;
import org.apache.fory.benchmark.data.Sample;
import org.apache.fory.benchmark.state.Example.Bar;
import org.apache.fory.benchmark.state.Example.Foo;
import org.apache.fory.integration_tests.state.generated.ProtoMessage;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@Warmup(iterations = 3)
@Measurement(iterations = 3)
@Fork(value = 0)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class ProtoBuffersState {
  // protoc --experimental_allow_proto3_optional
  // -I=src/main/java/io/ray/fory/integration_tests/state --java_out=src/main/java/ bench.proto

  public static byte[] serializeBar(Bar bar) {
    return build(bar).build().toByteArray();
  }

  public static ProtoMessage.Bar.Builder build(Bar bar) {
    ProtoMessage.Bar.Builder barBuilder = ProtoMessage.Bar.newBuilder();
    if (bar.f1 == null) {
      barBuilder.clearF1();
    } else {
      barBuilder.setF1(buildFoo(bar.f1));
    }
    if (bar.f2 == null) {
      barBuilder.clearF2();
    } else {
      barBuilder.setF2(bar.f2);
    }
    if (bar.f3 == null) {
      barBuilder.clearF3();
    } else {
      for (Foo foo : bar.f3) {
        barBuilder.addF3(buildFoo(foo));
      }
    }
    if (bar.f4 == null) {
      barBuilder.clearF4();
    } else {
      bar.f4.forEach(
          (k, v) -> {
            ProtoMessage.Foo.Builder fooBuilder1 = ProtoMessage.Foo.newBuilder();
            fooBuilder1.setF1(v.f1);
            v.f2.forEach(fooBuilder1::putF2);
            barBuilder.putF4(k, fooBuilder1.build());
          });
    }
    if (bar.f5 == null) {
      barBuilder.clearF5();
    } else {
      barBuilder.setF5(bar.f5);
    }
    if (bar.f6 == null) {
      barBuilder.clearF6();
    } else {
      barBuilder.setF6(bar.f6);
    }
    if (bar.f7 == null) {
      barBuilder.clearF7();
    } else {
      barBuilder.setF7(bar.f7);
    }
    if (bar.f8 == null) {
      barBuilder.clearF8();
    } else {
      barBuilder.setF8(bar.f8);
    }
    if (bar.f9 == null) {
      barBuilder.clearF9();
    } else {
      for (short i : bar.f9) {
        barBuilder.addF9(i);
      }
    }
    if (bar.f10 == null) {
      barBuilder.clearF10();
    } else {
      barBuilder.addAllF10(bar.f10);
    }
    return barBuilder;
  }

  public static ProtoMessage.Foo.Builder buildFoo(Foo foo) {
    ProtoMessage.Foo.Builder builder = ProtoMessage.Foo.newBuilder();
    if (foo.f1 == null) {
      builder.clearF1();
    } else {
      builder.setF1(foo.f1);
    }
    if (foo.f2 == null) {
      builder.clearF2();
    } else {
      foo.f2.forEach(builder::putF2);
    }
    return builder;
  }

  public static Foo fromFooBuilder(ProtoMessage.Foo.Builder builder) {
    Foo foo = new Foo();
    if (builder.hasF1()) {
      foo.f1 = builder.getF1();
    }
    foo.f2 = builder.getF2Map();
    return foo;
  }

  public static Bar deserializeBar(byte[] bytes) throws InvalidProtocolBufferException {
    Bar bar = new Bar();
    ProtoMessage.Bar.Builder barBuilder = ProtoMessage.Bar.newBuilder();
    barBuilder.mergeFrom(bytes);
    if (barBuilder.hasF1()) {
      bar.f1 = fromFooBuilder(barBuilder.getF1Builder());
    }
    if (barBuilder.hasF2()) {
      bar.f2 = barBuilder.getF2();
    }
    bar.f3 =
        barBuilder.getF3BuilderList().stream()
            .map(ProtoBuffersState::fromFooBuilder)
            .collect(Collectors.toList());
    bar.f4 = new HashMap<>();
    barBuilder.getF4Map().forEach((k, v) -> bar.f4.put(k, fromFooBuilder(v.toBuilder())));
    if (barBuilder.hasF5()) {
      bar.f5 = barBuilder.getF5();
    }
    if (barBuilder.hasF6()) {
      bar.f6 = barBuilder.getF6();
    }
    if (barBuilder.hasF7()) {
      bar.f7 = barBuilder.getF7();
    }
    if (barBuilder.hasF8()) {
      bar.f8 = barBuilder.getF8();
    }
    bar.f9 = new short[barBuilder.getF9Count()];
    for (int i = 0; i < barBuilder.getF9Count(); i++) {
      bar.f9[i] = (short) barBuilder.getF9(i);
    }
    bar.f10 = barBuilder.getF10List();
    return bar;
  }

  public static byte[] serializeSample(Sample sample) {
    return buildSample(sample).toByteArray();
  }

  public static ProtoMessage.Sample buildSample(Sample sample) {
    ProtoMessage.Sample.Builder builder = ProtoMessage.Sample.newBuilder();
    builder.setIntValue(sample.intValue);
    builder.setLongValue(sample.longValue);
    builder.setFloatValue(sample.floatValue);
    builder.setDoubleValue(sample.doubleValue);
    builder.setShortValue(sample.shortValue);
    builder.setCharValue(sample.charValue);
    builder.setBooleanValue(sample.booleanValue);

    builder.setIntValueBoxed(sample.intValueBoxed);
    builder.setLongValueBoxed(sample.longValueBoxed);
    builder.setFloatValueBoxed(sample.floatValueBoxed);
    builder.setDoubleValueBoxed(sample.doubleValueBoxed);
    builder.setShortValueBoxed(sample.shortValueBoxed);
    builder.setCharValueBoxed(sample.charValueBoxed);
    builder.setBooleanValueBoxed(sample.booleanValueBoxed);

    for (int i = 0; i < sample.intArray.length; i++) {
      builder.addIntArray(sample.intArray[i]);
    }
    for (int i = 0; i < sample.longArray.length; i++) {
      builder.addLongArray(sample.longArray[i]);
    }
    for (int i = 0; i < sample.floatArray.length; i++) {
      builder.addFloatArray(sample.floatArray[i]);
    }
    for (int i = 0; i < sample.doubleArray.length; i++) {
      builder.addDoubleArray(sample.doubleArray[i]);
    }
    for (int i = 0; i < sample.shortArray.length; i++) {
      builder.addShortArray(sample.shortArray[i]);
    }
    for (int i = 0; i < sample.charArray.length; i++) {
      builder.addCharArray(sample.charArray[i]);
    }
    for (int i = 0; i < sample.booleanArray.length; i++) {
      builder.addBooleanArray(sample.booleanArray[i]);
    }
    builder.setString(sample.string);
    return builder.build();
  }

  public static Sample deserializeSample(byte[] bytes) {
    Sample sample = new Sample();
    ProtoMessage.Sample.Builder sampleBuilder = ProtoMessage.Sample.newBuilder();
    try {
      sampleBuilder.mergeFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
    sample.intValue = sampleBuilder.getIntValue();
    sample.longValue = sampleBuilder.getLongValue();
    sample.floatValue = sampleBuilder.getFloatValue();
    sample.doubleValue = sampleBuilder.getDoubleValue();
    sample.shortValue = (short) sampleBuilder.getShortValue();
    sample.charValue = (char) sampleBuilder.getCharValue();
    sample.booleanValue = sampleBuilder.getBooleanValue();

    sample.intValueBoxed = sampleBuilder.getIntValueBoxed();
    sample.longValueBoxed = sampleBuilder.getLongValueBoxed();
    sample.floatValueBoxed = sampleBuilder.getFloatValueBoxed();
    sample.doubleValueBoxed = sampleBuilder.getDoubleValueBoxed();
    sample.shortValueBoxed = (short) (sampleBuilder.getShortValueBoxed());
    sample.charValueBoxed = (char) (sampleBuilder.getCharValueBoxed());
    sample.booleanValueBoxed = sampleBuilder.getBooleanValueBoxed();

    sample.intArray = sampleBuilder.getIntArrayList().stream().mapToInt(i -> i).toArray();
    sample.longArray = sampleBuilder.getLongArrayList().stream().mapToLong(i -> i).toArray();
    {
      float[] floatArray = new float[sampleBuilder.getFloatArrayCount()];
      for (int i = 0; i < floatArray.length; i++) {
        floatArray[i] = sampleBuilder.getFloatArray(i);
      }
      sample.floatArray = floatArray;
    }
    sample.doubleArray = sampleBuilder.getDoubleArrayList().stream().mapToDouble(i -> i).toArray();
    {
      short[] shortArray = new short[sampleBuilder.getShortArrayCount()];
      for (int i = 0; i < shortArray.length; i++) {
        shortArray[i] = (short) sampleBuilder.getShortArray(i);
      }
      sample.shortArray = shortArray;
    }
    {
      char[] charArray = new char[sampleBuilder.getCharArrayCount()];
      for (int i = 0; i < charArray.length; i++) {
        charArray[i] = (char) sampleBuilder.getCharArray(i);
      }
      sample.charArray = charArray;
    }
    {
      boolean[] booleanArray = new boolean[sampleBuilder.getBooleanArrayCount()];
      for (int i = 0; i < booleanArray.length; i++) {
        booleanArray[i] = sampleBuilder.getBooleanArray(i);
      }
      sample.booleanArray = booleanArray;
    }
    sample.string = sampleBuilder.getString();
    return sample;
  }

  public static byte[] serializeMediaContent(MediaContent mediaContent) {
    ProtoMessage.MediaContent.Builder builder = ProtoMessage.MediaContent.newBuilder();
    builder.setMedia(serializeMedia(mediaContent.media));
    mediaContent.images.forEach(image -> builder.addImages(serializeImage(image)));
    return builder.build().toByteArray();
  }

  private static ProtoMessage.Image serializeImage(Image image) {
    ProtoMessage.Image.Builder builder = ProtoMessage.Image.newBuilder();
    builder.setUri(image.uri);
    if (image.title != null) {
      builder.setTitle(image.title);
    } else {
      builder.clearTitle();
    }
    builder.setWidth(image.width);
    builder.setHeight(image.height);
    builder.setSize(ProtoMessage.Size.forNumber(image.size.ordinal()));
    Preconditions.checkArgument(image.media == null);
    return builder.build();
  }

  private static ProtoMessage.Media serializeMedia(Media media) {
    ProtoMessage.Media.Builder builder = ProtoMessage.Media.newBuilder();
    builder.setUri(media.uri);
    if (media.title != null) {
      builder.setTitle(media.title);
    } else {
      builder.clearTitle();
    }
    builder.setWidth(media.width);
    builder.setHeight(media.height);
    builder.setFormat(media.format);
    builder.setDuration(media.duration);
    builder.setSize(media.size);
    builder.setBitrate(media.bitrate);
    builder.setHasBitrate(media.hasBitrate);
    builder.addAllPersons(media.persons);
    builder.setPlayerValue(media.player.ordinal());
    builder.setCopyright(media.copyright);
    return builder.build();
  }

  public static MediaContent deserializeMediaContent(byte[] bytes) {
    ProtoMessage.MediaContent.Builder builder = ProtoMessage.MediaContent.newBuilder();
    MediaContent mediaContent = new MediaContent();
    try {
      builder.mergeFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
    mediaContent.media = deserializeMedia(builder.getMedia());
    mediaContent.images =
        builder.getImagesList().stream()
            .map(ProtoBuffersState::deserializeImage)
            .collect(Collectors.toList());
    return mediaContent;
  }

  private static Media deserializeMedia(ProtoMessage.Media mediaProto) {
    Media media = new Media();
    media.uri = mediaProto.getUri();
    if (mediaProto.hasTitle()) {
      media.title = mediaProto.getTitle();
    }
    media.width = mediaProto.getWidth();
    media.height = mediaProto.getHeight();
    media.format = mediaProto.getFormat();
    media.duration = mediaProto.getDuration();
    media.size = mediaProto.getSize();
    media.bitrate = mediaProto.getBitrate();
    media.hasBitrate = mediaProto.getHasBitrate();
    media.persons = mediaProto.getPersonsList();
    media.player = Media.Player.values()[mediaProto.getPlayerValue()];
    media.copyright = mediaProto.getCopyright();
    return media;
  }

  private static Image deserializeImage(ProtoMessage.Image imageProto) {
    Image image = new Image();
    image.uri = imageProto.getUri();
    if (imageProto.hasTitle()) {
      image.title = imageProto.getTitle();
    }
    image.width = imageProto.getWidth();
    image.height = imageProto.getHeight();
    image.size = Image.Size.values()[imageProto.getSizeValue()];
    return image;
  }

  @State(Scope.Thread)
  public abstract static class ProtoBuffersBenchmarkState extends BenchmarkState {
    @Param({"array"})
    public BufferType bufferType;

    @Param({"false"})
    public boolean references;

    public byte[] data;

    @Setup(Level.Trial)
    public void setup() {}
  }

  public static class ProtoBuffersUserTypeState extends ProtoBuffersBenchmarkState {
    @Param({"SAMPLE", "MEDIA_CONTENT"})
    public ObjectType objectType;

    public Object object;

    @Override
    public void setup() {
      super.setup();
      Function<byte[], @Nullable Object> deserializeFunc;
      switch (objectType) {
        case SAMPLE:
          object = new Sample().populate(references);
          data = serializeSample((Sample) object);
          deserializeFunc = ProtoBuffersState::deserializeSample;
          break;
        case MEDIA_CONTENT:
          object = new MediaContent().populate(references);
          data = serializeMediaContent((MediaContent) object);
          deserializeFunc = ProtoBuffersState::deserializeMediaContent;
          break;
        default:
          throw new UnsupportedOperationException(
              String.format("Unsupported object type %s", objectType));
      }
      Object newObj = deserializeFunc.apply(data);
      Preconditions.checkArgument(object.equals(newObj));
    }
  }

  public static void main(String[] args) {
    for (ObjectType objectType : new ObjectType[] {ObjectType.SAMPLE, ObjectType.MEDIA_CONTENT}) {
      ProtoBuffersUserTypeState state = new ProtoBuffersUserTypeState();
      state.objectType = objectType;
      state.setup();
    }
  }
}
