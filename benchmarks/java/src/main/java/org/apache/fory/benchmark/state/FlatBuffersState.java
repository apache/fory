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
import com.google.flatbuffers.FlatBufferBuilder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.fory.benchmark.data.Image;
import org.apache.fory.benchmark.data.Media;
import org.apache.fory.benchmark.data.MediaContent;
import org.apache.fory.benchmark.data.Sample;
import org.apache.fory.benchmark.state.Example.Bar;
import org.apache.fory.benchmark.state.Example.Foo;
import org.apache.fory.benchmark.state.generated.FBSBar;
import org.apache.fory.benchmark.state.generated.FBSFoo;
import org.apache.fory.benchmark.state.generated.FBSImage;
import org.apache.fory.benchmark.state.generated.FBSMedia;
import org.apache.fory.benchmark.state.generated.FBSMediaContent;
import org.apache.fory.benchmark.state.generated.FBSSample;
import org.apache.fory.memory.ByteBufferUtil;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

//  flatc  -o src/main/java -j  src/main/java/org/apache/fory/integration_tests/state/bench.fbs
public class FlatBuffersState {

  public static byte[] serializeBar(Bar bar) {
    return buildBar(bar).sizedByteArray();
  }

  public static FlatBufferBuilder buildBar(Bar bar) {
    FlatBufferBuilder builder = new FlatBufferBuilder();
    int f2_offset = builder.createString(bar.f2);
    int[] f3_offsets = new int[bar.f3.size()];
    for (int i = 0; i < bar.f3.size(); i++) {
      f3_offsets[i] = buildFoo(builder, bar.f3.get(i));
    }
    int f3_offset = FBSBar.createF3Vector(builder, f3_offsets);
    int f4_key_offset;
    int f4_value_offset;
    {
      int[] keys = new int[bar.f4.size()];
      int[] valueOffsets = new int[bar.f4.size()];
      int i = 0;
      for (Map.Entry<Integer, Foo> entry : bar.f4.entrySet()) {
        keys[i] = entry.getKey();
        valueOffsets[i] = buildFoo(builder, entry.getValue());
        i++;
      }
      f4_key_offset = FBSBar.createF4KeyVector(builder, keys);
      f4_value_offset = FBSBar.createF4ValueVector(builder, valueOffsets);
    }
    int f9_offset = FBSBar.createF9Vector(builder, bar.f9);
    int f10_offset = FBSBar.createF10Vector(builder, bar.f10.stream().mapToLong(x -> x).toArray());
    FBSBar.startFBSBar(builder);
    FBSBar.addF1(builder, buildFoo(builder, bar.f1));
    FBSBar.addF2(builder, f2_offset);
    FBSBar.addF3(builder, f3_offset);
    FBSBar.addF4Key(builder, f4_key_offset);
    FBSBar.addF4Value(builder, f4_value_offset);
    FBSBar.addF5(builder, bar.f5);
    FBSBar.addF6(builder, bar.f6);
    FBSBar.addF7(builder, bar.f7);
    FBSBar.addF8(builder, bar.f8);
    FBSBar.addF9(builder, f9_offset);
    FBSBar.addF10(builder, f10_offset);
    builder.finish(FBSBar.endFBSBar(builder));
    return builder;
  }

  public static int buildFoo(FlatBufferBuilder builder, Foo foo) {
    int stringOffset = builder.createString(foo.f1);
    int[] keyOffsets = new int[foo.f2.size()];
    int[] values = new int[foo.f2.size()];
    int i = 0;
    for (Map.Entry<String, Integer> entry : foo.f2.entrySet()) {
      keyOffsets[i] = builder.createString(entry.getKey());
      values[i] = entry.getValue();
      i++;
    }
    int keyOffset = FBSFoo.createF2KeyVector(builder, keyOffsets);
    int f2ValueOffset = FBSFoo.createF2ValueVector(builder, values);
    return FBSFoo.createFBSFoo(builder, stringOffset, keyOffset, f2ValueOffset);
  }

  public static Bar deserializeBar(ByteBuffer buffer) {
    Bar bar = new Bar();
    FBSBar fbsBar = FBSBar.getRootAsFBSBar(buffer);
    bar.f1 = deserializeFoo(fbsBar.f1());
    bar.f2 = fbsBar.f2();
    {
      ArrayList<Foo> f3List = new ArrayList<>();
      for (int i = 0; i < fbsBar.f3Length(); i++) {
        f3List.add(deserializeFoo(fbsBar.f3(i)));
      }
      bar.f3 = f3List;
    }
    {
      Map<Integer, Foo> f4 = new HashMap<>();
      for (int i = 0; i < fbsBar.f4KeyLength(); i++) {
        f4.put(fbsBar.f4Key(i), deserializeFoo(fbsBar.f4Value(i)));
      }
      bar.f4 = f4;
    }
    bar.f5 = fbsBar.f5();
    bar.f6 = fbsBar.f6();
    bar.f7 = fbsBar.f7();
    bar.f8 = fbsBar.f8();
    {
      short[] f9 = new short[fbsBar.f9Length()];
      for (int i = 0; i < fbsBar.f9Length(); i++) {
        f9[i] = fbsBar.f9(i);
      }
      bar.f9 = f9;
    }
    {
      List<Long> f10 = new ArrayList<>();
      for (int i = 0; i < fbsBar.f10Length(); i++) {
        f10.add(fbsBar.f10(i));
      }
      bar.f10 = f10;
    }
    return bar;
  }

  public static Foo deserializeFoo(FBSFoo fbsFoo) {
    Foo foo = new Foo();
    foo.f1 = fbsFoo.string();
    HashMap<String, Integer> map = new HashMap<>();
    foo.f2 = map;
    for (int i = 0; i < fbsFoo.f2KeyLength(); i++) {
      map.put(fbsFoo.f2Key(i), fbsFoo.f2Value(i));
    }
    return foo;
  }

  public static byte[] serializeSample(Sample sample) {
    FlatBufferBuilder builder = new FlatBufferBuilder();
    return serializeSample(sample, builder).sizedByteArray();
  }

  public static ByteBuffer serializeSample(Sample sample, ByteBuffer buffer) {
    FlatBufferBuilder builder = new FlatBufferBuilder(buffer);
    return serializeSample(sample, builder).dataBuffer();
  }

  public static FlatBufferBuilder serializeSample(Sample sample, FlatBufferBuilder builder) {
    int stringOffset = builder.createString(sample.string);
    int intArrayOffset = FBSSample.createIntArrayVector(builder, sample.intArray);
    int longArrayOffset = FBSSample.createLongArrayVector(builder, sample.longArray);
    int floatArrayOffset = FBSSample.createFloatArrayVector(builder, sample.floatArray);
    int doubleArrayOffset = FBSSample.createDoubleArrayVector(builder, sample.doubleArray);
    int shortArrayOffset = FBSSample.createShortArrayVector(builder, sample.shortArray);
    short[] charArray = new short[sample.charArray.length];
    for (int i = 0; i < sample.charArray.length; i++) {
      charArray[i] = (short) sample.charArray[i];
    }
    int charArrayOffset = FBSSample.createCharArrayVector(builder, charArray);
    int booleanArrayOffset = FBSSample.createBooleanArrayVector(builder, sample.booleanArray);
    FBSSample.startFBSSample(builder);
    FBSSample.addIntValue(builder, sample.intValue);
    FBSSample.addLongValue(builder, sample.longValue);
    FBSSample.addFloatValue(builder, sample.floatValue);
    FBSSample.addDoubleValue(builder, sample.doubleValue);
    FBSSample.addShortValue(builder, sample.shortValue);
    FBSSample.addCharValue(builder, (short) sample.charValue);
    FBSSample.addBooleanValue(builder, sample.booleanValue);
    FBSSample.addIntValueBoxed(builder, sample.intValueBoxed);
    FBSSample.addLongValueBoxed(builder, sample.longValueBoxed);
    FBSSample.addFloatValueBoxed(builder, sample.floatValueBoxed);
    FBSSample.addDoubleValueBoxed(builder, sample.doubleValueBoxed);
    FBSSample.addShortValueBoxed(builder, sample.shortValueBoxed);
    FBSSample.addCharValueBoxed(builder, (short) sample.charValueBoxed.charValue());
    FBSSample.addBooleanValueBoxed(builder, sample.booleanValueBoxed);
    FBSSample.addString(builder, stringOffset);
    FBSSample.addIntArray(builder, intArrayOffset);
    FBSSample.addLongArray(builder, longArrayOffset);
    FBSSample.addFloatArray(builder, floatArrayOffset);
    FBSSample.addDoubleArray(builder, doubleArrayOffset);
    FBSSample.addShortArray(builder, shortArrayOffset);
    FBSSample.addCharArray(builder, charArrayOffset);
    FBSSample.addBooleanArray(builder, booleanArrayOffset);
    builder.finish(FBSSample.endFBSSample(builder));
    return builder;
  }

  public static Sample deserializeSample(ByteBuffer data) {
    Sample sample = new Sample();
    FBSSample fbsSample = FBSSample.getRootAsFBSSample(data);
    sample.intValue = fbsSample.intValue();
    sample.longValue = fbsSample.longValue();
    sample.floatValue = fbsSample.floatValue();
    sample.doubleValue = fbsSample.doubleValue();
    sample.shortValue = fbsSample.shortValue();
    sample.charValue = (char) fbsSample.charValue();
    sample.booleanValue = fbsSample.booleanValue();

    sample.intValueBoxed = fbsSample.intValueBoxed();
    sample.longValueBoxed = fbsSample.longValueBoxed();
    sample.floatValueBoxed = fbsSample.floatValueBoxed();
    sample.doubleValueBoxed = fbsSample.doubleValueBoxed();
    sample.shortValueBoxed = fbsSample.shortValueBoxed();
    sample.charValueBoxed = (char) fbsSample.charValueBoxed();
    sample.booleanValueBoxed = fbsSample.booleanValueBoxed();

    {
      int[] intArray = new int[fbsSample.intArrayLength()];
      for (int i = 0; i < intArray.length; i++) {
        intArray[i] = fbsSample.intArray(i);
      }
      sample.intArray = intArray;
    }
    {
      long[] longArray = new long[fbsSample.longArrayLength()];
      for (int i = 0; i < longArray.length; i++) {
        longArray[i] = fbsSample.longArray(i);
      }
      sample.longArray = longArray;
    }
    {
      float[] floatArray = new float[fbsSample.floatArrayLength()];
      for (int i = 0; i < floatArray.length; i++) {
        floatArray[i] = fbsSample.floatArray(i);
      }
      sample.floatArray = floatArray;
    }
    {
      double[] doubleArray = new double[fbsSample.doubleArrayLength()];
      for (int i = 0; i < doubleArray.length; i++) {
        doubleArray[i] = fbsSample.doubleArray(i);
      }
      sample.doubleArray = doubleArray;
    }
    {
      short[] shortArray = new short[fbsSample.shortArrayLength()];
      for (int i = 0; i < shortArray.length; i++) {
        shortArray[i] = fbsSample.shortArray(i);
      }
      sample.shortArray = shortArray;
    }
    {
      char[] charArray = new char[fbsSample.charArrayLength()];
      for (int i = 0; i < charArray.length; i++) {
        charArray[i] = (char) fbsSample.charArray(i);
      }
      sample.charArray = charArray;
    }
    {
      boolean[] booleanArray = new boolean[fbsSample.booleanArrayLength()];
      for (int i = 0; i < booleanArray.length; i++) {
        booleanArray[i] = fbsSample.booleanArray(i);
      }
      sample.booleanArray = booleanArray;
    }
    sample.string = fbsSample.string();
    return sample;
  }

  public static FlatBufferBuilder serializeMediaContent(MediaContent mediaContent) {
    return serializeMediaContent(mediaContent, new FlatBufferBuilder());
  }

  public static FlatBufferBuilder serializeMediaContent(
      MediaContent mediaContent, FlatBufferBuilder builder) {
    int mediaOffset = serializeMediaContent(mediaContent.media, builder);
    int[] imageOffsets = new int[mediaContent.images.size()];
    for (int i = 0; i < imageOffsets.length; i++) {
      imageOffsets[i] = serializeImage(mediaContent.images.get(i), builder);
    }
    int imagesOffset = FBSMediaContent.createImagesVector(builder, imageOffsets);
    FBSMediaContent.startFBSMediaContent(builder);
    FBSMediaContent.addMedia(builder, mediaOffset);
    FBSMediaContent.addImages(builder, imagesOffset);
    builder.finish(FBSMediaContent.endFBSMediaContent(builder));
    return builder;
  }

  private static int serializeMediaContent(Media media, FlatBufferBuilder builder) {
    int uriOffset = builder.createString(media.uri);
    int titleOffset = 0;
    if (media.title != null) {
      titleOffset = builder.createString(media.title);
    }
    int formatOffset = builder.createString(media.format);
    int[] personsOffsets = new int[media.persons.size()];
    for (int i = 0; i < personsOffsets.length; i++) {
      personsOffsets[i] = builder.createString(media.persons.get(i));
    }
    int personsOffset = FBSMedia.createPersonsVector(builder, personsOffsets);
    int copyrightOffset = builder.createString(media.copyright);
    FBSMedia.startFBSMedia(builder);
    FBSMedia.addUri(builder, uriOffset);
    if (media.title != null) {
      FBSMedia.addTitle(builder, titleOffset);
    }
    FBSMedia.addFormat(builder, formatOffset);
    FBSMedia.addWidth(builder, media.width);
    FBSMedia.addHeight(builder, media.height);
    FBSMedia.addDuration(builder, media.duration);
    FBSMedia.addSize(builder, media.size);
    FBSMedia.addBitrate(builder, media.bitrate);
    FBSMedia.addHasBitrate(builder, media.hasBitrate);
    FBSMedia.addPlayer(builder, (byte) media.player.ordinal());
    FBSMedia.addCopyright(builder, copyrightOffset);
    FBSMedia.addPersons(builder, personsOffset);
    return FBSMedia.endFBSMedia(builder);
  }

  private static int serializeImage(Image image, FlatBufferBuilder builder) {
    int uriOffset = builder.createString(image.uri);
    int titleOffset = 0;
    if (image.title != null) {
      titleOffset = builder.createString(image.title);
    }
    Preconditions.checkArgument(image.media == null);
    FBSImage.startFBSImage(builder);
    FBSImage.addUri(builder, uriOffset);
    if (image.title != null) {
      FBSImage.addTitle(builder, titleOffset);
    }
    FBSImage.addWidth(builder, image.width);
    FBSImage.addHeight(builder, image.height);
    FBSImage.addSize(builder, (byte) image.size.ordinal());
    return FBSImage.endFBSImage(builder);
  }

  public static MediaContent deserializeMediaContent(ByteBuffer data) {
    MediaContent mediaContent = new MediaContent();
    FBSMediaContent fbsMediaContent = FBSMediaContent.getRootAsFBSMediaContent(data);
    mediaContent.media = deserializeMedia(fbsMediaContent.media());
    List<Image> images = new ArrayList<>();
    for (int i = 0; i < fbsMediaContent.imagesLength(); i++) {
      images.add(deserializeImage(fbsMediaContent.images(i)));
    }
    mediaContent.images = images;
    return mediaContent;
  }

  private static Image deserializeImage(FBSImage fbsImage) {
    Image image = new Image();
    image.uri = fbsImage.uri();
    image.title = fbsImage.title();
    image.width = fbsImage.width();
    image.height = fbsImage.height();
    image.size = Image.Size.values()[fbsImage.size()];
    return image;
  }

  private static Media deserializeMedia(FBSMedia fbsMedia) {
    Media media = new Media();
    media.uri = fbsMedia.uri();
    media.title = fbsMedia.title();
    media.width = fbsMedia.width();
    media.height = fbsMedia.height();
    media.format = fbsMedia.format();
    media.duration = fbsMedia.duration();
    media.size = fbsMedia.size();
    media.bitrate = fbsMedia.bitrate();
    media.hasBitrate = fbsMedia.hasBitrate();
    List<String> persons = new ArrayList<>();
    for (int i = 0; i < fbsMedia.personsLength(); i++) {
      persons.add(fbsMedia.persons(i));
    }
    media.persons = persons;
    media.player = Media.Player.values()[fbsMedia.player()];
    media.copyright = fbsMedia.copyright();
    return media;
  }

  @State(Scope.Thread)
  public abstract static class FlatBuffersBenchmarkState extends BenchmarkState {
    @Param({"false"})
    public boolean references;

    public byte[] data;
    public ByteBuffer deserializedData;
    public ByteBuffer directBuffer;

    @Setup(Level.Trial)
    public void setup() {
      directBuffer = ByteBuffer.allocateDirect(10 * 1024 * 1024);
    }
  }

  public static class FlatBuffersUserTypeState extends FlatBuffersBenchmarkState {
    @Param({"SAMPLE", "MEDIA_CONTENT"})
    public ObjectType objectType;

    public Object object;

    @Override
    public void setup() {
      super.setup();
      Function<ByteBuffer, @Nullable Object> deserializeFunc;
      switch (objectType) {
        case SAMPLE:
          object = new Sample().populate(references);
          data = serializeSample((Sample) object);
          deserializeFunc = FlatBuffersState::deserializeSample;
          break;
        case MEDIA_CONTENT:
          object = new MediaContent().populate(references);
          data = serializeMediaContent((MediaContent) object).sizedByteArray();
          deserializeFunc = FlatBuffersState::deserializeMediaContent;
          break;
        default:
          throw new UnsupportedOperationException(
              String.format("Unsupported object type %s", objectType));
      }
      if (bufferType == BufferType.directBuffer) {
        deserializedData = ByteBuffer.allocateDirect(data.length);
        deserializedData.put(data);
      } else {
        deserializedData = ByteBuffer.wrap(data);
      }
      ByteBufferUtil.clearBuffer(deserializedData);
      Object newObj = deserializeFunc.apply(deserializedData);
      Preconditions.checkArgument(object.equals(newObj));
    }
  }

  public static void main(String[] args) {
    for (BufferType bufferType : BufferType.values()) {
      for (ObjectType objectType : new ObjectType[] {ObjectType.SAMPLE, ObjectType.MEDIA_CONTENT}) {
        FlatBuffersUserTypeState state = new FlatBuffersUserTypeState();
        state.objectType = objectType;
        state.bufferType = bufferType;
        state.setup();
      }
    }
  }
}
