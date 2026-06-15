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
import 'package:json_annotation/json_annotation.dart';

part 'models.fory.dart';
part 'models.g.dart';

enum Player {
  java,
  flash,
}

enum MediaSize {
  small,
  large,
}

const int _kListSize = 5;

Float32 _float32FromJson(num value) => Float32(value);

double _float32ToJson(Float32 value) => value.value;

Int32List _int32ListFromJson(List<dynamic> values) =>
    Int32List.fromList(values.map((value) => (value as num).toInt()).toList());

List<int> _int32ListToJson(Int32List values) => values.toList(growable: false);

Int64List _int64ListFromJson(List<dynamic> values) =>
    Int64List.fromList(values.map((value) => (value as num).toInt()).toList());

List<int> _int64ListToJson(Int64List values) => values.toList(growable: false);

Float32List _float32ListFromJson(List<dynamic> values) => Float32List.fromList(
      values.map((value) => (value as num).toDouble()).toList(),
    );

List<double> _float32ListToJson(Float32List values) =>
    values.toList(growable: false);

Float64List _float64ListFromJson(List<dynamic> values) => Float64List.fromList(
      values.map((value) => (value as num).toDouble()).toList(),
    );

List<double> _float64ListToJson(Float64List values) =>
    values.toList(growable: false);

BoolList _boolListFromJson(List<dynamic> values) =>
    BoolList.fromList(values.map((value) => value as bool));

List<bool> _boolListToJson(BoolList values) => values.toList(growable: false);

@ForyStruct()
@JsonSerializable()
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
    required this.f9,
    required this.f10,
    required this.f11,
    required this.f12,
  });

  @ForyField(id: 1, type: Int32Type())
  final int f1;
  @ForyField(id: 2, type: Int32Type())
  final int f2;
  @ForyField(id: 3, type: Int32Type())
  final int f3;
  @ForyField(id: 4, type: Int32Type())
  final int f4;
  @ForyField(id: 5, type: Int32Type())
  final int f5;
  @ForyField(id: 6, type: Int32Type())
  final int f6;
  @ForyField(id: 7, type: Int32Type())
  final int f7;
  @ForyField(id: 8, type: Int32Type())
  final int f8;
  @ForyField(id: 9, type: Int32Type())
  final int f9;
  @ForyField(id: 10, type: Int32Type())
  final int f10;
  @ForyField(id: 11, type: Int32Type())
  final int f11;
  @ForyField(id: 12, type: Int32Type())
  final int f12;

  factory NumericStruct.fromJson(Map<String, dynamic> json) =>
      _$NumericStructFromJson(json);

  Map<String, dynamic> toJson() => _$NumericStructToJson(this);
}

@ForyStruct()
@JsonSerializable()
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

  @ForyField(id: 1, type: Int32Type())
  final int intValue;
  @ForyField(id: 2, type: Int64Type())
  final int longValue;
  @ForyField(id: 3)
  @JsonKey(fromJson: _float32FromJson, toJson: _float32ToJson)
  final Float32 floatValue;
  @ForyField(id: 4)
  final double doubleValue;
  @ForyField(id: 5, type: Int32Type())
  final int shortValue;
  @ForyField(id: 6, type: Int32Type())
  final int charValue;
  @ForyField(id: 7)
  final bool booleanValue;
  @ForyField(id: 8, type: Int32Type())
  final int intValueBoxed;
  @ForyField(id: 9, type: Int64Type())
  final int longValueBoxed;
  @ForyField(id: 10)
  @JsonKey(fromJson: _float32FromJson, toJson: _float32ToJson)
  final Float32 floatValueBoxed;
  @ForyField(id: 11)
  final double doubleValueBoxed;
  @ForyField(id: 12, type: Int32Type())
  final int shortValueBoxed;
  @ForyField(id: 13, type: Int32Type())
  final int charValueBoxed;
  @ForyField(id: 14)
  final bool booleanValueBoxed;
  @ForyField(id: 15)
  @JsonKey(fromJson: _int32ListFromJson, toJson: _int32ListToJson)
  final Int32List intArray;
  @ForyField(id: 16)
  @JsonKey(fromJson: _int64ListFromJson, toJson: _int64ListToJson)
  final Int64List longArray;
  @ForyField(id: 17)
  @JsonKey(fromJson: _float32ListFromJson, toJson: _float32ListToJson)
  final Float32List floatArray;
  @ForyField(id: 18)
  @JsonKey(fromJson: _float64ListFromJson, toJson: _float64ListToJson)
  final Float64List doubleArray;
  @ForyField(id: 19)
  @JsonKey(fromJson: _int32ListFromJson, toJson: _int32ListToJson)
  final Int32List shortArray;
  @ForyField(id: 20)
  @JsonKey(fromJson: _int32ListFromJson, toJson: _int32ListToJson)
  final Int32List charArray;
  @ArrayField(id: 21, element: BoolType())
  @JsonKey(fromJson: _boolListFromJson, toJson: _boolListToJson)
  final BoolList booleanArray;
  @ForyField(id: 22)
  final String string;

  factory Sample.fromJson(Map<String, dynamic> json) => _$SampleFromJson(json);

  Map<String, dynamic> toJson() => _$SampleToJson(this);
}

@ForyStruct()
@JsonSerializable()
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
  @ForyField(id: 3, type: Int32Type())
  final int width;
  @ForyField(id: 4, type: Int32Type())
  final int height;
  @ForyField(id: 5)
  final String format;
  @ForyField(id: 6, type: Int64Type())
  final int duration;
  @ForyField(id: 7, type: Int64Type())
  final int size;
  @ForyField(id: 8, type: Int32Type())
  final int bitrate;
  @ForyField(id: 9)
  final bool hasBitrate;
  @ForyField(id: 10)
  final List<String> persons;
  @ForyField(id: 11)
  final Player player;
  @ForyField(id: 12)
  final String copyright;

  factory Media.fromJson(Map<String, dynamic> json) => _$MediaFromJson(json);

  Map<String, dynamic> toJson() => _$MediaToJson(this);
}

@ForyStruct()
@JsonSerializable()
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
  @ForyField(id: 3, type: Int32Type())
  final int width;
  @ForyField(id: 4, type: Int32Type())
  final int height;
  @ForyField(id: 5)
  final MediaSize size;

  factory Image.fromJson(Map<String, dynamic> json) => _$ImageFromJson(json);

  Map<String, dynamic> toJson() => _$ImageToJson(this);
}

@ForyStruct()
@JsonSerializable()
class MediaContent {
  MediaContent({
    required this.media,
    required this.images,
  });

  @ForyField(id: 1)
  final Media media;
  @ForyField(id: 2)
  final List<Image> images;

  factory MediaContent.fromJson(Map<String, dynamic> json) =>
      _$MediaContentFromJson(json);

  Map<String, dynamic> toJson() => _$MediaContentToJson(this);
}

@ForyStruct()
@JsonSerializable()
class NumericStructList {
  NumericStructList({
    required this.structList,
  });

  @ForyField(id: 1)
  final List<NumericStruct> structList;

  factory NumericStructList.fromJson(Map<String, dynamic> json) =>
      _$NumericStructListFromJson(json);

  Map<String, dynamic> toJson() => _$NumericStructListToJson(this);
}

@ForyStruct()
@JsonSerializable()
class SampleList {
  SampleList({
    required this.sampleList,
  });

  @ForyField(id: 1)
  final List<Sample> sampleList;

  factory SampleList.fromJson(Map<String, dynamic> json) =>
      _$SampleListFromJson(json);

  Map<String, dynamic> toJson() => _$SampleListToJson(this);
}

@ForyStruct()
@JsonSerializable()
class MediaContentList {
  MediaContentList({
    required this.mediaContentList,
  });

  @ForyField(id: 1)
  final List<MediaContent> mediaContentList;

  factory MediaContentList.fromJson(Map<String, dynamic> json) =>
      _$MediaContentListFromJson(json);

  Map<String, dynamic> toJson() => _$MediaContentListToJson(this);
}

@ForyStruct()
class NumericStructV2 {
  NumericStructV2({
    required this.f1,
    required this.f2,
    required this.f3,
    required this.f4,
    required this.f5,
    required this.f6,
    required this.f7,
    required this.f8,
    required this.f9,
    required this.f10,
    required this.f11,
    required this.f12,
  });

  @ForyField(id: 1, type: Int64Type())
  final int f1;
  @ForyField(id: 2, type: Int32Type())
  final int f2;
  @ForyField(id: 3, type: Int32Type())
  final int f3;
  @ForyField(id: 4, type: Int32Type())
  final int f4;
  @ForyField(id: 5, type: Int32Type())
  final int f5;
  @ForyField(id: 6, type: Int32Type())
  final int f6;
  @ForyField(id: 7, type: Int32Type())
  final int f7;
  @ForyField(id: 8, type: Int32Type())
  final int f8;
  @ForyField(id: 9, type: Int32Type())
  final int f9;
  @ForyField(id: 10, type: Int32Type())
  final int f10;
  @ForyField(id: 11, type: Int32Type())
  final int f11;
  @ForyField(id: 12, type: Int32Type())
  final int f12;
}

@ForyStruct()
class SampleV2 {
  SampleV2({
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

  @ForyField(id: 1, type: Int64Type())
  final int intValue;
  @ForyField(id: 2, type: Int64Type())
  final int longValue;
  @ForyField(id: 3)
  final Float32 floatValue;
  @ForyField(id: 4)
  final double doubleValue;
  @ForyField(id: 5, type: Int32Type())
  final int shortValue;
  @ForyField(id: 6, type: Int32Type())
  final int charValue;
  @ForyField(id: 7)
  final bool booleanValue;
  @ForyField(id: 8, type: Int32Type())
  final int intValueBoxed;
  @ForyField(id: 9, type: Int64Type())
  final int longValueBoxed;
  @ForyField(id: 10)
  final Float32 floatValueBoxed;
  @ForyField(id: 11)
  final double doubleValueBoxed;
  @ForyField(id: 12, type: Int32Type())
  final int shortValueBoxed;
  @ForyField(id: 13, type: Int32Type())
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
  @ArrayField(id: 21, element: BoolType())
  final BoolList booleanArray;
  @ForyField(id: 22)
  final String string;
}

@ForyStruct()
class MediaV2 {
  MediaV2({
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
  @ForyField(id: 3, type: Int64Type())
  final int width;
  @ForyField(id: 4, type: Int32Type())
  final int height;
  @ForyField(id: 5)
  final String format;
  @ForyField(id: 6, type: Int64Type())
  final int duration;
  @ForyField(id: 7, type: Int64Type())
  final int size;
  @ForyField(id: 8, type: Int32Type())
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
class ImageV2 {
  ImageV2({
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
  @ForyField(id: 3, type: Int64Type())
  final int width;
  @ForyField(id: 4, type: Int32Type())
  final int height;
  @ForyField(id: 5)
  final MediaSize size;
}

@ForyStruct()
class MediaContentV2 {
  MediaContentV2({
    required this.media,
    required this.images,
  });

  @ForyField(id: 1)
  final MediaV2 media;
  @ForyField(id: 2)
  final List<ImageV2> images;
}

@ForyStruct()
class NumericStructListV2 {
  NumericStructListV2({
    required this.structList,
  });

  @ForyField(id: 1)
  final List<NumericStructV2> structList;
}

@ForyStruct()
class SampleListV2 {
  SampleListV2({
    required this.sampleList,
  });

  @ForyField(id: 1)
  final List<SampleV2> sampleList;
}

@ForyStruct()
class MediaContentListV2 {
  MediaContentListV2({
    required this.mediaContentList,
  });

  @ForyField(id: 1)
  final List<MediaContentV2> mediaContentList;
}

void registerBenchmarkTypes(Fory fory) {
  ModelsForyModule.register(fory, NumericStruct, id: 1);
  ModelsForyModule.register(fory, Sample, id: 2);
  ModelsForyModule.register(fory, Media, id: 3);
  ModelsForyModule.register(fory, Image, id: 4);
  ModelsForyModule.register(fory, MediaContent, id: 5);
  ModelsForyModule.register(fory, NumericStructList, id: 6);
  ModelsForyModule.register(fory, SampleList, id: 7);
  ModelsForyModule.register(fory, MediaContentList, id: 8);
  ModelsForyModule.register(fory, Player, id: 9);
  ModelsForyModule.register(fory, MediaSize, id: 10);
}

void registerBenchmarkTypesV2(Fory fory) {
  ModelsForyModule.register(fory, NumericStructV2, id: 1);
  ModelsForyModule.register(fory, SampleV2, id: 2);
  ModelsForyModule.register(fory, MediaV2, id: 3);
  ModelsForyModule.register(fory, ImageV2, id: 4);
  ModelsForyModule.register(fory, MediaContentV2, id: 5);
  ModelsForyModule.register(fory, NumericStructListV2, id: 6);
  ModelsForyModule.register(fory, SampleListV2, id: 7);
  ModelsForyModule.register(fory, MediaContentListV2, id: 8);
  ModelsForyModule.register(fory, Player, id: 9);
  ModelsForyModule.register(fory, MediaSize, id: 10);
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
    f9: 123456789,
    f10: -42,
    f11: 31415926,
    f12: -27182818,
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
    booleanArray: BoolList.fromList(<bool>[true, false, false, true]),
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

NumericStructList createNumericStructList() {
  return NumericStructList(
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
