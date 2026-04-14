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

import 'package:fory/fory.dart';
import 'package:protobuf/protobuf.dart' as protobuf;

import 'generated/bench.pb.dart' as pb;
import 'models.dart';
import 'protobuf_convert.dart';

Object? benchmarkSink;

final class BenchmarkDefinition<TModel, TProto extends protobuf.GeneratedMessage> {
  final String dataType;
  final TModel Function() createModel;
  final TProto Function(TModel value) toProto;
  final Object? Function(TModel value, TProto protobufMessage)
      serializeProtobuf;
  final TModel Function(Fory fory, Uint8List bytes) parseFory;
  final Object? Function(Uint8List bytes) parseProtobuf;

  const BenchmarkDefinition({
    required this.dataType,
    required this.createModel,
    required this.toProto,
    required this.serializeProtobuf,
    required this.parseFory,
    required this.parseProtobuf,
  });

  InstantiatedBenchmark instantiate(Fory fory) {
    final model = createModel();
    final buffer = Buffer();
    final protobufMessage = toProto(model);
    final protobufBytes = protobufMessage.writeToBuffer();

    fory.serializeTo(model, buffer);
    final foryBytes = Uint8List.sublistView(buffer.toBytes());
    final forySize = buffer.readableBytes;

    return InstantiatedBenchmark(
      dataType: dataType,
      forySize: forySize,
      protobufSize: protobufBytes.length,
      forySerialize: () {
        buffer.clear();
        fory.serializeTo(model, buffer);
        benchmarkSink = buffer.toBytes();
      },
      protobufSerialize: () {
        benchmarkSink = serializeProtobuf(model, protobufMessage);
      },
      foryDeserialize: () {
        benchmarkSink = parseFory(fory, foryBytes);
      },
      protobufDeserialize: () {
        benchmarkSink = parseProtobuf(protobufBytes);
      },
    );
  }
}

final class InstantiatedBenchmark {
  final String dataType;
  final int forySize;
  final int protobufSize;
  final void Function() forySerialize;
  final void Function() protobufSerialize;
  final void Function() foryDeserialize;
  final void Function() protobufDeserialize;

  const InstantiatedBenchmark({
    required this.dataType,
    required this.forySize,
    required this.protobufSize,
    required this.forySerialize,
    required this.protobufSerialize,
    required this.foryDeserialize,
    required this.protobufDeserialize,
  });
}

List<BenchmarkDefinition<Object, protobuf.GeneratedMessage>>
buildBenchmarkDefinitions() {
  return <BenchmarkDefinition<Object, protobuf.GeneratedMessage>>[
    BenchmarkDefinition<NumericStruct, pb.Struct>(
      dataType: 'struct',
      createModel: createNumericStruct,
      toProto: toPbStruct,
      serializeProtobuf: (model, _) => toPbStruct(model).writeToBuffer(),
      parseFory: (fory, bytes) => fory.deserialize<NumericStruct>(bytes),
      parseProtobuf: (bytes) => fromPbStruct(pb.Struct.fromBuffer(bytes)),
    ),
    BenchmarkDefinition<Sample, pb.Sample>(
      dataType: 'sample',
      createModel: createSample,
      toProto: toPbSample,
      serializeProtobuf: (_, protobufMessage) => protobufMessage.writeToBuffer(),
      parseFory: (fory, bytes) => fory.deserialize<Sample>(bytes),
      parseProtobuf: pb.Sample.fromBuffer,
    ),
    BenchmarkDefinition<MediaContent, pb.MediaContent>(
      dataType: 'mediacontent',
      createModel: createMediaContent,
      toProto: toPbMediaContent,
      serializeProtobuf: (model, _) => toPbMediaContent(model).writeToBuffer(),
      parseFory: (fory, bytes) => fory.deserialize<MediaContent>(bytes),
      parseProtobuf: (bytes) =>
          fromPbMediaContent(pb.MediaContent.fromBuffer(bytes)),
    ),
    BenchmarkDefinition<StructList, pb.StructList>(
      dataType: 'structlist',
      createModel: createStructList,
      toProto: toPbStructList,
      serializeProtobuf: (model, _) => toPbStructList(model).writeToBuffer(),
      parseFory: (fory, bytes) => fory.deserialize<StructList>(bytes),
      parseProtobuf: (bytes) =>
          fromPbStructList(pb.StructList.fromBuffer(bytes)),
    ),
    BenchmarkDefinition<SampleList, pb.SampleList>(
      dataType: 'samplelist',
      createModel: createSampleList,
      toProto: toPbSampleList,
      serializeProtobuf: (model, _) => toPbSampleList(model).writeToBuffer(),
      parseFory: (fory, bytes) => fory.deserialize<SampleList>(bytes),
      parseProtobuf: (bytes) =>
          fromPbSampleList(pb.SampleList.fromBuffer(bytes)),
    ),
    BenchmarkDefinition<MediaContentList, pb.MediaContentList>(
      dataType: 'mediacontentlist',
      createModel: createMediaContentList,
      toProto: toPbMediaContentList,
      serializeProtobuf: (model, _) =>
          toPbMediaContentList(model).writeToBuffer(),
      parseFory: (fory, bytes) => fory.deserialize<MediaContentList>(bytes),
      parseProtobuf: (bytes) =>
          fromPbMediaContentList(pb.MediaContentList.fromBuffer(bytes)),
    ),
  ].cast<BenchmarkDefinition<Object, protobuf.GeneratedMessage>>();
}

Fory newBenchmarkFory() {
  final fory = Fory(compatible: true);
  registerBenchmarkTypes(fory);
  return fory;
}
