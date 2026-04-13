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
import 'dart:io';

import 'package:args/args.dart';

import 'package:fory_dart_benchmark/src/workloads.dart';

const int _batchSize = 64;

final class BenchmarkRecord {
  final String serializer;
  final String dataType;
  final String operation;
  final List<double> samples;
  final double medianOpsPerSec;

  const BenchmarkRecord({
    required this.serializer,
    required this.dataType,
    required this.operation,
    required this.samples,
    required this.medianOpsPerSec,
  });

  Map<String, Object?> toJson() {
    return <String, Object?>{
      'serializer': serializer,
      'data_type': dataType,
      'operation': operation,
      'samples_ops_per_sec': samples,
      'median_ops_per_sec': medianOpsPerSec,
    };
  }
}

void main(List<String> arguments) {
  final parser = ArgParser()
    ..addOption('data', help: 'Comma-separated data types or all.')
    ..addOption('serializer', help: 'Comma-separated serializers or all.')
    ..addOption('operation', help: 'Comma-separated operations or all.')
    ..addOption(
      'samples',
      help: 'Number of measured samples.',
      defaultsTo: '5',
    )
    ..addOption(
      'duration',
      help: 'Per-sample measurement duration in seconds.',
      defaultsTo: '1.5',
    )
    ..addOption(
      'warmup',
      help: 'Warmup duration in seconds before each case.',
      defaultsTo: '1.0',
    )
    ..addOption('json-output', help: 'Write machine-readable results here.')
    ..addOption('size-output', help: 'Write serialized size table here.')
    ..addFlag('help', abbr: 'h', negatable: false);

  final args = parser.parse(arguments);
  if (args['help'] as bool) {
    stdout.writeln(parser.usage);
    return;
  }

  final selectedData = _parseFilter(args['data'] as String?);
  final selectedSerializers = _parseFilter(args['serializer'] as String?);
  final selectedOperations = _parseFilter(args['operation'] as String?);
  final samples = int.parse(args['samples'] as String);
  final duration = Duration(
    microseconds: (double.parse(args['duration'] as String) * 1000000).round(),
  );
  final warmup = Duration(
    microseconds: (double.parse(args['warmup'] as String) * 1000000).round(),
  );

  final definitions = buildBenchmarkDefinitions();
  final records = <BenchmarkRecord>[];
  final sizes = <String, Map<String, int>>{};

  for (final definition in definitions) {
    if (!_matches(selectedData, definition.dataType)) {
      continue;
    }
    final benchmark = definition.instantiate(newBenchmarkFory());
    sizes[benchmark.dataType] = <String, int>{
      'fory': benchmark.forySize,
      'protobuf': benchmark.protobufSize,
    };

    if (_matches(selectedSerializers, 'fory') &&
        _matches(selectedOperations, 'serialize')) {
      records.add(
        _runCase(
          serializer: 'fory',
          dataType: benchmark.dataType,
          operation: 'serialize',
          action: benchmark.forySerialize,
          samples: samples,
          duration: duration,
          warmup: warmup,
        ),
      );
    }
    if (_matches(selectedSerializers, 'fory') &&
        _matches(selectedOperations, 'deserialize')) {
      records.add(
        _runCase(
          serializer: 'fory',
          dataType: benchmark.dataType,
          operation: 'deserialize',
          action: benchmark.foryDeserialize,
          samples: samples,
          duration: duration,
          warmup: warmup,
        ),
      );
    }
    if (_matches(selectedSerializers, 'protobuf') &&
        _matches(selectedOperations, 'serialize')) {
      records.add(
        _runCase(
          serializer: 'protobuf',
          dataType: benchmark.dataType,
          operation: 'serialize',
          action: benchmark.protobufSerialize,
          samples: samples,
          duration: duration,
          warmup: warmup,
        ),
      );
    }
    if (_matches(selectedSerializers, 'protobuf') &&
        _matches(selectedOperations, 'deserialize')) {
      records.add(
        _runCase(
          serializer: 'protobuf',
          dataType: benchmark.dataType,
          operation: 'deserialize',
          action: benchmark.protobufDeserialize,
          samples: samples,
          duration: duration,
          warmup: warmup,
        ),
      );
    }
  }

  _printRecords(records);
  _printSizes(sizes);

  final jsonOutput = args['json-output'] as String?;
  if (jsonOutput != null && jsonOutput.isNotEmpty) {
    File(jsonOutput).writeAsStringSync(
      const JsonEncoder.withIndent('  ').convert(
        <String, Object?>{
          'metadata': <String, Object?>{
            'generated_at': DateTime.now().toUtc().toIso8601String(),
            'samples': samples,
            'duration_seconds': duration.inMicroseconds / 1000000,
            'warmup_seconds': warmup.inMicroseconds / 1000000,
            'batch_size': _batchSize,
            'dart_version': Platform.version,
            'os': Platform.operatingSystem,
            'os_version': Platform.operatingSystemVersion,
            'cpus': Platform.numberOfProcessors,
          },
          'results': records.map((record) => record.toJson()).toList(),
          'sizes': sizes,
        },
      ),
    );
  }

  final sizeOutput = args['size-output'] as String?;
  if (sizeOutput != null && sizeOutput.isNotEmpty) {
    File(sizeOutput).writeAsStringSync(_sizeTableText(sizes));
  }
}

BenchmarkRecord _runCase({
  required String serializer,
  required String dataType,
  required String operation,
  required void Function() action,
  required int samples,
  required Duration duration,
  required Duration warmup,
}) {
  _spin(action, warmup);
  final values = <double>[];
  for (var i = 0; i < samples; i++) {
    values.add(_measure(action, duration));
  }
  final median = _median(values);
  return BenchmarkRecord(
    serializer: serializer,
    dataType: dataType,
    operation: operation,
    samples: values,
    medianOpsPerSec: median,
  );
}

void _spin(void Function() action, Duration duration) {
  final stopwatch = Stopwatch()..start();
  while (stopwatch.elapsed < duration) {
    for (var i = 0; i < _batchSize; i++) {
      action();
    }
  }
}

double _measure(void Function() action, Duration duration) {
  var iterations = 0;
  final stopwatch = Stopwatch()..start();
  while (stopwatch.elapsed < duration) {
    for (var i = 0; i < _batchSize; i++) {
      action();
    }
    iterations += _batchSize;
  }
  stopwatch.stop();
  final elapsedSeconds = stopwatch.elapsedMicroseconds / 1000000;
  return iterations / elapsedSeconds;
}

double _median(List<double> values) {
  final sorted = values.toList()..sort();
  if (sorted.isEmpty) {
    return 0;
  }
  final middle = sorted.length ~/ 2;
  if (sorted.length.isOdd) {
    return sorted[middle];
  }
  return (sorted[middle - 1] + sorted[middle]) / 2;
}

Set<String>? _parseFilter(String? raw) {
  if (raw == null || raw.isEmpty || raw == 'all') {
    return null;
  }
  return raw
      .split(',')
      .map((entry) => entry.trim().toLowerCase())
      .where((entry) => entry.isNotEmpty)
      .toSet();
}

bool _matches(Set<String>? filter, String value) {
  return filter == null || filter.contains(value);
}

void _printRecords(List<BenchmarkRecord> records) {
  stdout.writeln('Serializer benchmark results (median ops/s)');
  stdout.writeln(
    '---------------------------------------------------------------',
  );
  for (final record in records) {
    stdout.writeln(
      '${record.serializer.padRight(10)} '
      '${record.dataType.padRight(16)} '
      '${record.operation.padRight(12)} '
      '${record.medianOpsPerSec.toStringAsFixed(2)}',
    );
  }
  stdout.writeln();
}

void _printSizes(Map<String, Map<String, int>> sizes) {
  stdout.writeln('Serialized sizes (bytes)');
  stdout.writeln('------------------------');
  for (final entry in sizes.entries) {
    stdout.writeln(
      '${entry.key.padRight(16)} '
      'fory=${entry.value['fory']} '
      'protobuf=${entry.value['protobuf']}',
    );
  }
}

String _sizeTableText(Map<String, Map<String, int>> sizes) {
  final buffer = StringBuffer()
    ..writeln('Serialized sizes (bytes)')
    ..writeln('========================');
  for (final entry in sizes.entries) {
    buffer.writeln(
      '${entry.key}: '
      'fory=${entry.value['fory']} '
      'protobuf=${entry.value['protobuf']}',
    );
  }
  return buffer.toString();
}
