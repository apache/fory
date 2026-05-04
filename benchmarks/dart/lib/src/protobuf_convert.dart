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

import 'dart:typed_data';

import 'package:fixnum/fixnum.dart' as fixnum;

import 'package:fory/fory.dart';

import 'generated/bench.pb.dart' as pb;
import 'generated/bench.pbenum.dart' as pbenum;
import 'models.dart';

pb.Struct toPbStruct(NumericStruct value) {
  return pb.Struct(
    f1: value.f1,
    f2: value.f2,
    f3: value.f3,
    f4: value.f4,
    f5: value.f5,
    f6: value.f6,
    f7: value.f7,
    f8: value.f8,
  );
}

NumericStruct fromPbStruct(pb.Struct value) {
  return NumericStruct(
    f1: value.f1,
    f2: value.f2,
    f3: value.f3,
    f4: value.f4,
    f5: value.f5,
    f6: value.f6,
    f7: value.f7,
    f8: value.f8,
  );
}

pb.StructList toPbStructList(StructList value) {
  return pb.StructList(
    structList: value.structList.map(toPbStruct),
  );
}

StructList fromPbStructList(pb.StructList value) {
  return StructList(
    structList: value.structList.map(fromPbStruct).toList(growable: false),
  );
}

pb.Sample toPbSample(Sample value) {
  return pb.Sample(
    intValue: value.intValue,
    longValue: fixnum.Int64(value.longValue),
    floatValue: value.floatValue.value,
    doubleValue: value.doubleValue,
    shortValue: value.shortValue,
    charValue: value.charValue,
    booleanValue: value.booleanValue,
    intValueBoxed: value.intValueBoxed,
    longValueBoxed: fixnum.Int64(value.longValueBoxed),
    floatValueBoxed: value.floatValueBoxed.value,
    doubleValueBoxed: value.doubleValueBoxed,
    shortValueBoxed: value.shortValueBoxed,
    charValueBoxed: value.charValueBoxed,
    booleanValueBoxed: value.booleanValueBoxed,
    intArray: value.intArray,
    longArray: value.longArray.map(fixnum.Int64.new),
    floatArray: value.floatArray,
    doubleArray: value.doubleArray,
    shortArray: value.shortArray,
    charArray: value.charArray,
    booleanArray: value.booleanArray.toList(growable: false),
    string: value.string,
  );
}

Sample fromPbSample(pb.Sample value) {
  return Sample(
    intValue: value.intValue,
    longValue: value.longValue.toInt(),
    floatValue: Float32(value.floatValue),
    doubleValue: value.doubleValue,
    shortValue: value.shortValue,
    charValue: value.charValue,
    booleanValue: value.booleanValue,
    intValueBoxed: value.intValueBoxed,
    longValueBoxed: value.longValueBoxed.toInt(),
    floatValueBoxed: Float32(value.floatValueBoxed),
    doubleValueBoxed: value.doubleValueBoxed,
    shortValueBoxed: value.shortValueBoxed,
    charValueBoxed: value.charValueBoxed,
    booleanValueBoxed: value.booleanValueBoxed,
    intArray: Int32List.fromList(value.intArray),
    longArray: Int64List.fromList(
      value.longArray.map((entry) => entry.toInt()).toList(growable: false),
    ),
    floatArray: Float32List.fromList(value.floatArray),
    doubleArray: Float64List.fromList(value.doubleArray),
    shortArray: Int32List.fromList(value.shortArray),
    charArray: Int32List.fromList(value.charArray),
    booleanArray: BoolList.fromList(value.booleanArray),
    string: value.string,
  );
}

pb.SampleList toPbSampleList(SampleList value) {
  return pb.SampleList(
    sampleList: value.sampleList.map(toPbSample),
  );
}

SampleList fromPbSampleList(pb.SampleList value) {
  return SampleList(
    sampleList: value.sampleList.map(fromPbSample).toList(growable: false),
  );
}

pb.Media toPbMedia(Media value) {
  final message = pb.Media(
    uri: value.uri,
    width: value.width,
    height: value.height,
    format: value.format,
    duration: fixnum.Int64(value.duration),
    size: fixnum.Int64(value.size),
    bitrate: value.bitrate,
    hasBitrate_9: value.hasBitrate,
    persons: value.persons,
    player: toPbPlayer(value.player),
    copyright: value.copyright,
  );
  if (value.title.isNotEmpty) {
    message.title = value.title;
  }
  return message;
}

Media fromPbMedia(pb.Media value) {
  return Media(
    uri: value.uri,
    title: value.hasTitle() ? value.title : '',
    width: value.width,
    height: value.height,
    format: value.format,
    duration: value.duration.toInt(),
    size: value.size.toInt(),
    bitrate: value.bitrate,
    hasBitrate: value.hasHasBitrate_9() ? value.hasBitrate_9 : false,
    persons: value.persons.toList(growable: false),
    player: fromPbPlayer(value.player),
    copyright: value.copyright,
  );
}

pb.Image toPbImage(Image value) {
  final message = pb.Image(
    uri: value.uri,
    width: value.width,
    height: value.height,
    size: toPbSize(value.size),
  );
  if (value.title.isNotEmpty) {
    message.title = value.title;
  }
  return message;
}

Image fromPbImage(pb.Image value) {
  return Image(
    uri: value.uri,
    title: value.hasTitle() ? value.title : '',
    width: value.width,
    height: value.height,
    size: fromPbSize(value.size),
  );
}

pb.MediaContent toPbMediaContent(MediaContent value) {
  return pb.MediaContent(
    media: toPbMedia(value.media),
    images: value.images.map(toPbImage),
  );
}

MediaContent fromPbMediaContent(pb.MediaContent value) {
  return MediaContent(
    media: fromPbMedia(value.media),
    images: value.images.map(fromPbImage).toList(growable: false),
  );
}

pb.MediaContentList toPbMediaContentList(MediaContentList value) {
  return pb.MediaContentList(
    mediaContentList: value.mediaContentList.map(toPbMediaContent),
  );
}

MediaContentList fromPbMediaContentList(pb.MediaContentList value) {
  return MediaContentList(
    mediaContentList:
        value.mediaContentList.map(fromPbMediaContent).toList(growable: false),
  );
}

pbenum.Player toPbPlayer(Player value) {
  switch (value) {
    case Player.java:
      return pbenum.Player.JAVA;
    case Player.flash:
      return pbenum.Player.FLASH;
  }
}

Player fromPbPlayer(pbenum.Player value) {
  switch (value) {
    case pbenum.Player.JAVA:
      return Player.java;
    case pbenum.Player.FLASH:
      return Player.flash;
    default:
      throw StateError('Unknown protobuf Player value: $value');
  }
}

pbenum.Size toPbSize(MediaSize value) {
  switch (value) {
    case MediaSize.small:
      return pbenum.Size.SMALL;
    case MediaSize.large:
      return pbenum.Size.LARGE;
  }
}

MediaSize fromPbSize(pbenum.Size value) {
  switch (value) {
    case pbenum.Size.SMALL:
      return MediaSize.small;
    case pbenum.Size.LARGE:
      return MediaSize.large;
    default:
      throw StateError('Unknown protobuf Size value: $value');
  }
}
