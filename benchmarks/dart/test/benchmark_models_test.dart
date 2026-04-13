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

import 'package:protobuf/protobuf.dart' as protobuf;
import 'package:test/test.dart';

import 'package:fory_dart_benchmark/src/models.dart';
import 'package:fory_dart_benchmark/src/protobuf_convert.dart';
import 'package:fory_dart_benchmark/src/workloads.dart';

void main() {
  test('compatible benchmark models round trip through generated serializers', () {
    expectRoundTrip<NumericStruct, protobuf.GeneratedMessage>(
      createNumericStruct(),
      toPbStruct,
    );
    expectRoundTrip<Sample, protobuf.GeneratedMessage>(
      createSample(),
      toPbSample,
    );
    expectRoundTrip<MediaContent, protobuf.GeneratedMessage>(
      createMediaContent(),
      toPbMediaContent,
    );
    expectRoundTrip<StructList, protobuf.GeneratedMessage>(
      createStructList(),
      toPbStructList,
    );
    expectRoundTrip<SampleList, protobuf.GeneratedMessage>(
      createSampleList(),
      toPbSampleList,
    );
    expectRoundTrip<MediaContentList, protobuf.GeneratedMessage>(
      createMediaContentList(),
      toPbMediaContentList,
    );
  });

  test('benchmark fory sizes stay aligned with the C++ reference payloads', () {
    final fory = newBenchmarkFory();
    final definitions = buildBenchmarkDefinitions();
    final expectedSizes = <String, int>{
      'struct': 58,
      'sample': 446,
      'mediacontent': 365,
      'structlist': 184,
      'samplelist': 1980,
      'mediacontentlist': 1535,
    };

    for (final definition in definitions) {
      final benchmark = definition.instantiate(fory);
      expect(
        benchmark.forySize,
        expectedSizes[benchmark.dataType],
        reason: 'Unexpected Fory size for ${benchmark.dataType}.',
      );
    }
  });
}

void expectRoundTrip<T, P extends protobuf.GeneratedMessage>(
  T value,
  P Function(T value) toProto,
) {
  final fory = newBenchmarkFory();
  final bytes = fory.serialize(value);
  final decoded = fory.deserialize<T>(bytes);
  expect(
    toProto(decoded).writeToBuffer(),
    orderedEquals(toProto(value).writeToBuffer()),
  );
}
