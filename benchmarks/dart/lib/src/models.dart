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

library;

import 'dart:typed_data';

import 'package:fory/fory.dart';

part 'models.fory.dart';

enum Player {
  java,
  flash,
}

enum MediaSize {
  small,
  large,
}

const int _kListSize = 5;

@ForyStruct()
class NumericStruct {
  NumericStruct({
    required this.f1,
    required this.f2,
    required this.f3,
    required this.f4,
    required this.f5,
    required this.f6,
    required this.f7,
    required this.f8,
  });

  @ForyField(id: 1)
  @Int32Type()
  final int f1;
  @ForyField(id: 2)
  @Int32Type()
  final int f2;
  @ForyField(id: 3)
  @Int32Type()
  final int f3;
  @ForyField(id: 4)
  @Int32Type()
  final int f4;
  @ForyField(id: 5)
  @Int32Type()
  final int f5;
  @ForyField(id: 6)
  @Int32Type()
  final int f6;
  @ForyField(id: 7)
  @Int32Type()
  final int f7;
  @ForyField(id: 8)
  @Int32Type()
  final int f8;
}

@ForyStruct()
class Sample {
  Sample({
    required this.intValue,
    required this.longValue,
    required this.floatValue,
    required this.doubleValue,
    required this.shortValue,
    required this.charValue,
    required this.booleanValue,
    required this.intValueBoxed,
    required this.longValueBoxed,
    required this.floatValueBoxed,
    required this.doubleValueBoxed,
    required this.shortValueBoxed,
    required this.charValueBoxed,
    required this.booleanValueBoxed,
    required this.intArray,
    required this.longArray,
    required this.floatArray,
    required this.doubleArray,
    required this.shortArray,
    required this.charArray,
    required this.booleanArray,
    required this.string,
  });

  @ForyField(id: 1)
  @Int32Type()
  final int intValue;
  @ForyField(id: 2)
  @Int64Type()
  final int longValue;
  @ForyField(id: 3)
  final Float32 floatValue;
  @ForyField(id: 4)
  final double doubleValue;
  @ForyField(id: 5)
  @Int32Type()
  final int shortValue;
  @ForyField(id: 6)
  @Int32Type()
  final int charValue;
  @ForyField(id: 7)
  final bool booleanValue;
  @ForyField(id: 8)
  @Int32Type()
  final int intValueBoxed;
  @ForyField(id: 9)
  @Int64Type()
  final int longValueBoxed;
  @ForyField(id: 10)
  final Float32 floatValueBoxed;
  @ForyField(id: 11)
  final double doubleValueBoxed;
  @ForyField(id: 12)
  @Int32Type()
  final int shortValueBoxed;
  @ForyField(id: 13)
  @Int32Type()
  final int charValueBoxed;
  @ForyField(id: 14)
  final bool booleanValueBoxed;
  @ForyField(id: 15)
  final Int32List intArray;
  @ForyField(id: 16)
  final Int64List longArray;
  @ForyField(id: 17)
  final Float32List floatArray;
  @ForyField(id: 18)
  final Float64List doubleArray;
  @ForyField(id: 19)
  final Int32List shortArray;
  @ForyField(id: 20)
  final Int32List charArray;
  @ForyField(id: 21)
  final List<bool> booleanArray;
  @ForyField(id: 22)
  final String string;
}

@ForyStruct()
class Media {
  Media({
    required this.uri,
    required this.title,
    required this.width,
    required this.height,
    required this.format,
    required this.duration,
    required this.size,
    required this.bitrate,
    required this.hasBitrate,
    required this.persons,
    required this.player,
    required this.copyright,
  });

  @ForyField(id: 1)
  final String uri;
  @ForyField(id: 2)
  final String title;
  @ForyField(id: 3)
  @Int32Type()
  final int width;
  @ForyField(id: 4)
  @Int32Type()
  final int height;
  @ForyField(id: 5)
  final String format;
  @ForyField(id: 6)
  @Int64Type()
  final int duration;
  @ForyField(id: 7)
  @Int64Type()
  final int size;
  @ForyField(id: 8)
  @Int32Type()
  final int bitrate;
  @ForyField(id: 9)
  final bool hasBitrate;
  @ForyField(id: 10)
  final List<String> persons;
  @ForyField(id: 11)
  final Player player;
  @ForyField(id: 12)
  final String copyright;
}

@ForyStruct()
class Image {
  Image({
    required this.uri,
    required this.title,
    required this.width,
    required this.height,
    required this.size,
  });

  @ForyField(id: 1)
  final String uri;
  @ForyField(id: 2)
  final String title;
  @ForyField(id: 3)
  @Int32Type()
  final int width;
  @ForyField(id: 4)
  @Int32Type()
  final int height;
  @ForyField(id: 5)
  final MediaSize size;
}

@ForyStruct()
class MediaContent {
  MediaContent({
    required this.media,
    required this.images,
  });

  @ForyField(id: 1)
  final Media media;
  @ForyField(id: 2)
  final List<Image> images;
}

@ForyStruct()
class StructList {
  StructList({
    required this.structList,
  });

  @ForyField(id: 1)
  final List<NumericStruct> structList;
}

@ForyStruct()
class SampleList {
  SampleList({
    required this.sampleList,
  });

  @ForyField(id: 1)
  final List<Sample> sampleList;
}

@ForyStruct()
class MediaContentList {
  MediaContentList({
    required this.mediaContentList,
  });

  @ForyField(id: 1)
  final List<MediaContent> mediaContentList;
}

void registerBenchmarkTypes(Fory fory) {
  ModelsFory.register(fory, NumericStruct, id: 1);
  ModelsFory.register(fory, Sample, id: 2);
  ModelsFory.register(fory, Media, id: 3);
  ModelsFory.register(fory, Image, id: 4);
  ModelsFory.register(fory, MediaContent, id: 5);
  ModelsFory.register(fory, StructList, id: 6);
  ModelsFory.register(fory, SampleList, id: 7);
  ModelsFory.register(fory, MediaContentList, id: 8);
  ModelsFory.register(fory, Player, id: 9);
  ModelsFory.register(fory, MediaSize, id: 10);
}

NumericStruct createNumericStruct() {
  return NumericStruct(
    f1: -12345,
    f2: 987654321,
    f3: -31415,
    f4: 27182818,
    f5: -32000,
    f6: 1000000,
    f7: -999999999,
    f8: 42,
  );
}

Sample createSample() {
  return Sample(
    intValue: 123,
    longValue: 1230000,
    floatValue: Float32(12.345),
    doubleValue: 1.234567,
    shortValue: 12345,
    charValue: '!'.codeUnitAt(0),
    booleanValue: true,
    intValueBoxed: 321,
    longValueBoxed: 3210000,
    floatValueBoxed: Float32(54.321),
    doubleValueBoxed: 7.654321,
    shortValueBoxed: 32100,
    charValueBoxed: r'$'.codeUnitAt(0),
    booleanValueBoxed: false,
    intArray: Int32List.fromList(<int>[
      -1234,
      -123,
      -12,
      -1,
      0,
      1,
      12,
      123,
      1234,
    ]),
    longArray: Int64List.fromList(<int>[
      -123400,
      -12300,
      -1200,
      -100,
      0,
      100,
      1200,
      12300,
      123400,
    ]),
    floatArray: Float32List.fromList(<double>[
      -12.34,
      -12.3,
      -12.0,
      -1.0,
      0.0,
      1.0,
      12.0,
      12.3,
      12.34,
    ]),
    doubleArray: Float64List.fromList(<double>[
      -1.234,
      -1.23,
      -12.0,
      -1.0,
      0.0,
      1.0,
      12.0,
      1.23,
      1.234,
    ]),
    shortArray: Int32List.fromList(<int>[
      -1234,
      -123,
      -12,
      -1,
      0,
      1,
      12,
      123,
      1234,
    ]),
    charArray: Int32List.fromList(<int>[
      'a'.codeUnitAt(0),
      's'.codeUnitAt(0),
      'd'.codeUnitAt(0),
      'f'.codeUnitAt(0),
      'A'.codeUnitAt(0),
      'S'.codeUnitAt(0),
      'D'.codeUnitAt(0),
      'F'.codeUnitAt(0),
    ]),
    booleanArray: <bool>[true, false, false, true],
    string: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789',
  );
}

MediaContent createMediaContent() {
  return MediaContent(
    media: Media(
      uri: 'http://javaone.com/keynote.ogg',
      title: '',
      width: 641,
      height: 481,
      format: 'video/theora\u1234',
      duration: 18000001,
      size: 58982401,
      bitrate: 0,
      hasBitrate: false,
      persons: <String>['Bill Gates, Jr.', 'Steven Jobs'],
      player: Player.flash,
      copyright: 'Copyright (c) 2009, Scooby Dooby Doo',
    ),
    images: <Image>[
      Image(
        uri: 'http://javaone.com/keynote_huge.jpg',
        title: 'Javaone Keynote\u1234',
        width: 32000,
        height: 24000,
        size: MediaSize.large,
      ),
      Image(
        uri: 'http://javaone.com/keynote_large.jpg',
        title: '',
        width: 1024,
        height: 768,
        size: MediaSize.large,
      ),
      Image(
        uri: 'http://javaone.com/keynote_small.jpg',
        title: '',
        width: 320,
        height: 240,
        size: MediaSize.small,
      ),
    ],
  );
}

StructList createStructList() {
  return StructList(
    structList: List<NumericStruct>.generate(
      _kListSize,
      (_) => createNumericStruct(),
      growable: false,
    ),
  );
}

SampleList createSampleList() {
  return SampleList(
    sampleList: List<Sample>.generate(
      _kListSize,
      (_) => createSample(),
      growable: false,
    ),
  );
}

MediaContentList createMediaContentList() {
  return MediaContentList(
    mediaContentList: List<MediaContent>.generate(
      _kListSize,
      (_) => createMediaContent(),
      growable: false,
    ),
  );
}
