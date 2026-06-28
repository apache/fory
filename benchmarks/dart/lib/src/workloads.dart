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

import 'dart:convert';
import 'dart:typed_data';

import 'package:fory/fory.dart';
import 'package:protobuf/protobuf.dart' as protobuf;

import 'generated/bench.pb.dart' as pb;
import 'models.dart';
import 'protobuf_convert.dart';

Object? benchmarkSink;

final class BenchmarkDefinition<TModel,
    TProto extends protobuf.GeneratedMessage> {
  final String dataType;
  final TModel Function() createModel;
  final TProto Function(TModel value) toProto;
  final Object? Function(TModel value, TProto protobufMessage)
      serializeProtobuf;
  final Object? Function(TModel value) serializeJson;
  final Object? Function(Fory fory, Uint8List bytes) parseFory;
  final Object? Function(Fory fory, Uint8List bytes) parseForyMismatch;
  final void Function(Object? decoded, TModel expected) verifyForyMismatch;
  final Object? Function(Uint8List bytes) parseProtobuf;
  final Object? Function(String text) parseJson;

  const BenchmarkDefinition({
    required this.dataType,
    required this.createModel,
    required this.toProto,
    required this.serializeProtobuf,
    required this.serializeJson,
    required this.parseFory,
    required this.parseForyMismatch,
    required this.verifyForyMismatch,
    required this.parseProtobuf,
    required this.parseJson,
  });

  InstantiatedBenchmark instantiate({
    required Fory writerFory,
    required Fory readerFory,
    required bool schemaMismatch,
  }) {
    final model = createModel();
    final buffer = Buffer();
    final protobufMessage = schemaMismatch ? null : toProto(model);
    final protobufBytes = protobufMessage?.writeToBuffer();
    final jsonText = schemaMismatch ? null : serializeJson(model) as String;

    writerFory.serializeTo(model, buffer);
    final foryBytes = Uint8List.sublistView(buffer.toBytes());
    final forySize = buffer.readableBytes;
    final decoded = schemaMismatch
        ? parseForyMismatch(readerFory, foryBytes)
        : parseFory(readerFory, foryBytes);
    if (schemaMismatch) {
      verifyForyMismatch(decoded, model);
    }

    return InstantiatedBenchmark(
      dataType: dataType,
      forySize: forySize,
      protobufSize: protobufBytes?.length,
      jsonSize: jsonText == null ? null : utf8.encode(jsonText).length,
      forySerialize: () {
        buffer.clear();
        writerFory.serializeTo(model, buffer);
        benchmarkSink = buffer.toBytes();
      },
      protobufSerialize: () {
        if (protobufMessage == null) {
          throw StateError(
            'Schema-mismatch mode supports only Fory benchmarks.',
          );
        }
        benchmarkSink = serializeProtobuf(model, protobufMessage);
      },
      jsonSerialize: () {
        if (jsonText == null) {
          throw StateError(
            'Schema-mismatch mode supports only Fory benchmarks.',
          );
        }
        benchmarkSink = serializeJson(model);
      },
      foryDeserialize: () {
        benchmarkSink = schemaMismatch
            ? parseForyMismatch(readerFory, foryBytes)
            : parseFory(readerFory, foryBytes);
      },
      protobufDeserialize: () {
        if (protobufBytes == null) {
          throw StateError(
            'Schema-mismatch mode supports only Fory benchmarks.',
          );
        }
        benchmarkSink = parseProtobuf(protobufBytes);
      },
      jsonDeserialize: () {
        if (jsonText == null) {
          throw StateError(
            'Schema-mismatch mode supports only Fory benchmarks.',
          );
        }
        benchmarkSink = parseJson(jsonText);
      },
    );
  }
}

final class InstantiatedBenchmark {
  final String dataType;
  final int forySize;
  final int? protobufSize;
  final int? jsonSize;
  final void Function() forySerialize;
  final void Function() protobufSerialize;
  final void Function() jsonSerialize;
  final void Function() foryDeserialize;
  final void Function() protobufDeserialize;
  final void Function() jsonDeserialize;

  const InstantiatedBenchmark({
    required this.dataType,
    required this.forySize,
    required this.protobufSize,
    required this.jsonSize,
    required this.forySerialize,
    required this.protobufSerialize,
    required this.jsonSerialize,
    required this.foryDeserialize,
    required this.protobufDeserialize,
    required this.jsonDeserialize,
  });
}

List<BenchmarkDefinition<Object, protobuf.GeneratedMessage>>
    buildBenchmarkDefinitions() {
  return <BenchmarkDefinition<Object, protobuf.GeneratedMessage>>[
    BenchmarkDefinition<NumericStruct, pb.NumericStruct>(
      dataType: 'struct',
      createModel: createNumericStruct,
      toProto: toPbStruct,
      serializeProtobuf: (model, _) => toPbStruct(model).writeToBuffer(),
      serializeJson: (model) => jsonEncode(model.toJson()),
      parseFory: (fory, bytes) => fory.deserialize<NumericStruct>(bytes),
      parseForyMismatch: (fory, bytes) =>
          fory.deserialize<NumericStructV2>(bytes),
      verifyForyMismatch: (decoded, expected) {
        if (decoded is! NumericStructV2 || decoded.f1 != expected.f1) {
          throw StateError('NumericStructV2 schema mismatch read failed.');
        }
      },
      parseProtobuf: (bytes) =>
          fromPbStruct(pb.NumericStruct.fromBuffer(bytes)),
      parseJson: (text) => NumericStruct.fromJson(jsonDecode(text)),
    ),
    BenchmarkDefinition<Sample, pb.Sample>(
      dataType: 'sample',
      createModel: createSample,
      toProto: toPbSample,
      serializeProtobuf: (_, protobufMessage) =>
          protobufMessage.writeToBuffer(),
      serializeJson: (model) => jsonEncode(model.toJson()),
      parseFory: (fory, bytes) => fory.deserialize<Sample>(bytes),
      parseForyMismatch: (fory, bytes) => fory.deserialize<SampleV2>(bytes),
      verifyForyMismatch: (decoded, expected) {
        if (decoded is! SampleV2 || decoded.intValue != expected.intValue) {
          throw StateError('SampleV2 schema mismatch read failed.');
        }
      },
      parseProtobuf: pb.Sample.fromBuffer,
      parseJson: (text) => Sample.fromJson(jsonDecode(text)),
    ),
    BenchmarkDefinition<MediaContent, pb.MediaContent>(
      dataType: 'mediacontent',
      createModel: createMediaContent,
      toProto: toPbMediaContent,
      serializeProtobuf: (model, _) => toPbMediaContent(model).writeToBuffer(),
      serializeJson: (model) => jsonEncode(model.toJson()),
      parseFory: (fory, bytes) => fory.deserialize<MediaContent>(bytes),
      parseForyMismatch: (fory, bytes) =>
          fory.deserialize<MediaContentV2>(bytes),
      verifyForyMismatch: (decoded, expected) {
        if (decoded is! MediaContentV2 ||
            decoded.media.width != expected.media.width ||
            decoded.images.isEmpty ||
            decoded.images.first.width != expected.images.first.width) {
          throw StateError('MediaContentV2 schema mismatch read failed.');
        }
      },
      parseProtobuf: (bytes) =>
          fromPbMediaContent(pb.MediaContent.fromBuffer(bytes)),
      parseJson: (text) => MediaContent.fromJson(jsonDecode(text)),
    ),
    BenchmarkDefinition<NumericStructList, pb.NumericStructList>(
      dataType: 'structlist',
      createModel: createNumericStructList,
      toProto: toPbNumericStructList,
      serializeProtobuf: (model, _) =>
          toPbNumericStructList(model).writeToBuffer(),
      serializeJson: (model) => jsonEncode(model.toJson()),
      parseFory: (fory, bytes) => fory.deserialize<NumericStructList>(bytes),
      parseForyMismatch: (fory, bytes) =>
          fory.deserialize<NumericStructListV2>(bytes),
      verifyForyMismatch: (decoded, expected) {
        if (decoded is! NumericStructListV2 ||
            decoded.structList.isEmpty ||
            decoded.structList.first.f1 != expected.structList.first.f1) {
          throw StateError('NumericStructListV2 schema mismatch read failed.');
        }
      },
      parseProtobuf: (bytes) =>
          fromPbNumericStructList(pb.NumericStructList.fromBuffer(bytes)),
      parseJson: (text) => NumericStructList.fromJson(jsonDecode(text)),
    ),
    BenchmarkDefinition<SampleList, pb.SampleList>(
      dataType: 'samplelist',
      createModel: createSampleList,
      toProto: toPbSampleList,
      serializeProtobuf: (model, _) => toPbSampleList(model).writeToBuffer(),
      serializeJson: (model) => jsonEncode(model.toJson()),
      parseFory: (fory, bytes) => fory.deserialize<SampleList>(bytes),
      parseForyMismatch: (fory, bytes) => fory.deserialize<SampleListV2>(bytes),
      verifyForyMismatch: (decoded, expected) {
        if (decoded is! SampleListV2 ||
            decoded.sampleList.isEmpty ||
            decoded.sampleList.first.intValue !=
                expected.sampleList.first.intValue) {
          throw StateError('SampleListV2 schema mismatch read failed.');
        }
      },
      parseProtobuf: (bytes) =>
          fromPbSampleList(pb.SampleList.fromBuffer(bytes)),
      parseJson: (text) => SampleList.fromJson(jsonDecode(text)),
    ),
    BenchmarkDefinition<MediaContentList, pb.MediaContentList>(
      dataType: 'mediacontentlist',
      createModel: createMediaContentList,
      toProto: toPbMediaContentList,
      serializeProtobuf: (model, _) =>
          toPbMediaContentList(model).writeToBuffer(),
      serializeJson: (model) => jsonEncode(model.toJson()),
      parseFory: (fory, bytes) => fory.deserialize<MediaContentList>(bytes),
      parseForyMismatch: (fory, bytes) =>
          fory.deserialize<MediaContentListV2>(bytes),
      verifyForyMismatch: (decoded, expected) {
        if (decoded is! MediaContentListV2 ||
            decoded.mediaContentList.isEmpty ||
            decoded.mediaContentList.first.media.width !=
                expected.mediaContentList.first.media.width ||
            decoded.mediaContentList.first.images.isEmpty ||
            decoded.mediaContentList.first.images.first.width !=
                expected.mediaContentList.first.images.first.width) {
          throw StateError('MediaContentListV2 schema mismatch read failed.');
        }
      },
      parseProtobuf: (bytes) =>
          fromPbMediaContentList(pb.MediaContentList.fromBuffer(bytes)),
      parseJson: (text) => MediaContentList.fromJson(jsonDecode(text)),
    ),
  ].cast<BenchmarkDefinition<Object, protobuf.GeneratedMessage>>();
}

Fory newBenchmarkFory() {
  return newBenchmarkWriterFory();
}

Fory newBenchmarkWriterFory() {
  final fory = Fory(compatible: true);
  registerBenchmarkTypes(fory);
  return fory;
}

Fory newBenchmarkReaderFory({required bool schemaMismatch}) {
  final fory = Fory(compatible: true);
  if (schemaMismatch) {
    registerBenchmarkTypesV2(fory);
  } else {
    registerBenchmarkTypes(fory);
  }
  return fory;
}
