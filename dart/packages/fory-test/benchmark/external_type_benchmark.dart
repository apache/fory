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

import 'dart:async';
import 'dart:convert';
import 'dart:developer';
import 'dart:io';
import 'dart:typed_data';

import 'package:external_type_test_models/models.dart' as third_party;
import 'package:fory/fory.dart';
import 'package:fory_test/model/external_serializers.dart';
import 'package:vm_service/vm_service.dart';
import 'package:vm_service/vm_service_io.dart';

Object? _sink;

final class _BenchmarkPair {
  _BenchmarkPair({
    required this.name,
    required this.ordinaryFory,
    required this.targetFory,
    required this.ordinaryValue,
    required this.targetValue,
  }) : ordinaryBytes = ordinaryFory.serialize(ordinaryValue),
       targetBytes = targetFory.serialize(targetValue) {
    if (!_sameBytes(ordinaryBytes, targetBytes)) {
      throw StateError('$name ordinary and target bytes differ.');
    }
  }

  final String name;
  final Fory ordinaryFory;
  final Fory targetFory;
  final Object ordinaryValue;
  final Object targetValue;
  final Uint8List ordinaryBytes;
  final Uint8List targetBytes;
}

void _registerPair(
  Fory ordinaryFory,
  Fory targetFory, {
  Type? ordinaryHolder,
  Type? targetHolder,
}) {
  ExternalSerializersForyModule.register(
    ordinaryFory,
    OrdinaryEquivalentUser,
    id: 101,
  );
  ExternalSerializersForyModule.register(
    targetFory,
    third_party.EquivalentUser,
    id: 101,
  );
  if (ordinaryHolder != null && targetHolder != null) {
    ExternalSerializersForyModule.register(
      ordinaryFory,
      ordinaryHolder,
      id: 102,
    );
    ExternalSerializersForyModule.register(targetFory, targetHolder, id: 102);
  }
}

_BenchmarkPair _pair({
  required String name,
  required Object ordinaryValue,
  required Object targetValue,
  Type? ordinaryHolder,
  Type? targetHolder,
}) {
  final ordinaryFory = Fory(compatible: false);
  final targetFory = Fory(compatible: false);
  _registerPair(
    ordinaryFory,
    targetFory,
    ordinaryHolder: ordinaryHolder,
    targetHolder: targetHolder,
  );
  return _BenchmarkPair(
    name: name,
    ordinaryFory: ordinaryFory,
    targetFory: targetFory,
    ordinaryValue: ordinaryValue,
    targetValue: targetValue,
  );
}

List<_BenchmarkPair> _createPairs() {
  const targetUser = third_party.EquivalentUser(name: 'Ada', age: 36);
  const ordinaryUser = OrdinaryEquivalentUser(name: 'Ada', age: 36);
  final targetUsers = List<third_party.EquivalentUser>.filled(
    32,
    targetUser,
    growable: false,
  );
  final ordinaryUsers = List<OrdinaryEquivalentUser>.filled(
    32,
    ordinaryUser,
    growable: false,
  );
  final targetMap = <String, third_party.EquivalentUser>{
    for (var index = 0; index < 32; index += 1) 'user$index': targetUser,
  };
  final ordinaryMap = <String, OrdinaryEquivalentUser>{
    for (var index = 0; index < 32; index += 1) 'user$index': ordinaryUser,
  };
  return <_BenchmarkPair>[
    _pair(name: 'root', ordinaryValue: ordinaryUser, targetValue: targetUser),
    _pair(
      name: 'direct_field',
      ordinaryValue: const OrdinaryDirectHolder(ordinaryUser),
      targetValue: const TargetDirectHolder(targetUser),
      ordinaryHolder: OrdinaryDirectHolder,
      targetHolder: TargetDirectHolder,
    ),
    _pair(
      name: 'list_field',
      ordinaryValue: OrdinaryListHolder(ordinaryUsers),
      targetValue: TargetListHolder(targetUsers),
      ordinaryHolder: OrdinaryListHolder,
      targetHolder: TargetListHolder,
    ),
    _pair(
      name: 'map_field',
      ordinaryValue: OrdinaryMapHolder(ordinaryMap),
      targetValue: TargetMapHolder(targetMap),
      ordinaryHolder: OrdinaryMapHolder,
      targetHolder: TargetMapHolder,
    ),
  ];
}

bool _sameBytes(Uint8List left, Uint8List right) {
  if (left.length != right.length) {
    return false;
  }
  for (var index = 0; index < left.length; index += 1) {
    if (left[index] != right[index]) {
      return false;
    }
  }
  return true;
}

double _measure(Object? Function() action, Duration duration) {
  final stopwatch = Stopwatch()..start();
  var operations = 0;
  do {
    _sink = action();
    operations += 1;
  } while (stopwatch.elapsed < duration);
  stopwatch.stop();
  return operations *
      Duration.microsecondsPerSecond /
      stopwatch.elapsedMicroseconds;
}

void _warmUp(
  Object? Function() ordinary,
  Object? Function() target,
  Duration duration,
) {
  final half = Duration(microseconds: duration.inMicroseconds ~/ 2);
  _measure(ordinary, half);
  _measure(target, half);
}

double _median(List<double> values) {
  values.sort();
  return values[values.length ~/ 2];
}

bool _runLane({
  required String name,
  required Object? Function() ordinary,
  required Object? Function() target,
  required int samples,
  required Duration duration,
  required Duration warmup,
  required double threshold,
}) {
  _warmUp(ordinary, target, warmup);
  final deltas = <double>[];
  for (var sample = 0; sample < samples; sample += 1) {
    late final double ordinaryOps;
    late final double targetOps;
    if (sample.isEven) {
      ordinaryOps = _measure(ordinary, duration);
      targetOps = _measure(target, duration);
    } else {
      targetOps = _measure(target, duration);
      ordinaryOps = _measure(ordinary, duration);
    }
    final delta = targetOps / ordinaryOps - 1;
    deltas.add(delta);
    stdout.writeln(
      '$name sample=${sample + 1} '
      'ordinary=${ordinaryOps.toStringAsFixed(0)} '
      'target=${targetOps.toStringAsFixed(0)} '
      'delta=${(delta * 100).toStringAsFixed(2)}%',
    );
  }
  final retained = _median(deltas);
  stdout.writeln(
    '$name retained_median_delta=${(retained * 100).toStringAsFixed(2)}%',
  );
  return retained >= -threshold;
}

int _readInt(List<String> arguments, String option, int fallback) {
  final index = arguments.indexOf(option);
  return index == -1 ? fallback : int.parse(arguments[index + 1]);
}

double _readDouble(List<String> arguments, String option, double fallback) {
  final index = arguments.indexOf(option);
  return index == -1 ? fallback : double.parse(arguments[index + 1]);
}

String? _readString(List<String> arguments, String option) {
  final index = arguments.indexOf(option);
  return index == -1 ? null : arguments[index + 1];
}

Object? Function() _operation(
  _BenchmarkPair pair, {
  required bool target,
  required bool deserialize,
}) {
  if (target) {
    return deserialize
        ? () => pair.targetFory.deserialize<Object?>(pair.targetBytes)
        : () => pair.targetFory.serialize(pair.targetValue);
  }
  return deserialize
      ? () => pair.ordinaryFory.deserialize<Object?>(pair.ordinaryBytes)
      : () => pair.ordinaryFory.serialize(pair.ordinaryValue);
}

Future<void> _allocationWorker(List<String> arguments) async {
  final pairName = arguments[1];
  final deserialize = arguments[2] == 'deserialize';
  final target = arguments[3] == 'target';
  final iterations = int.parse(arguments[4]);
  final pair = _createPairs().firstWhere((item) => item.name == pairName);
  final action = _operation(pair, target: target, deserialize: deserialize);
  for (var index = 0; index < 10000; index += 1) {
    _sink = action();
  }
  final serviceInfo = await Service.getInfo();
  final serverUri = serviceInfo.serverUri;
  if (serverUri == null) {
    throw StateError('Allocation worker requires the Dart VM service.');
  }
  stdout.writeln('SERVICE $serverUri');
  stdout.writeln('READY');
  await stdout.flush();
  debugger(message: 'allocation-ready');
  for (var index = 0; index < iterations; index += 1) {
    _sink = action();
  }
  debugger(message: 'allocation-done');
}

Future<String> _nextWorkerLine(StreamIterator<String> lines) async {
  while (await lines.moveNext()) {
    final line = lines.current;
    if (line == 'READY' || line.startsWith('SERVICE ')) {
      return line;
    }
  }
  throw StateError('Allocation worker exited before completing its command.');
}

Map<String, (int, int)> _allocationSnapshot(AllocationProfile profile) {
  return <String, (int, int)>{
    for (final member in profile.members ?? const <ClassHeapStats>[])
      if (member.classRef?.name != null)
        member.classRef!.name!: (
          member.instancesAccumulated ?? 0,
          member.accumulatedSize ?? 0,
        ),
  };
}

String _normalizedAllocationClass(String name) {
  return switch (name) {
    'OrdinaryEquivalentUser' => 'EquivalentUser',
    'OrdinaryDirectHolder' => 'TargetDirectHolder',
    'OrdinaryListHolder' => 'TargetListHolder',
    'OrdinaryMapHolder' => 'TargetMapHolder',
    _ => name,
  };
}

Map<String, (int, int)> _allocationDelta(
  Map<String, (int, int)> profile,
  Map<String, (int, int)> baseline,
) {
  final result = <String, (int, int)>{};
  for (final entry in profile.entries) {
    final before = baseline[entry.key] ?? (0, 0);
    final instances = entry.value.$1 - before.$1;
    final bytes = entry.value.$2 - before.$2;
    if (instances <= 0 && bytes <= 0) {
      continue;
    }
    final name = _normalizedAllocationClass(entry.key);
    final current = result[name] ?? (0, 0);
    result[name] = (
      current.$1 + (instances < 0 ? 0 : instances),
      current.$2 + (bytes < 0 ? 0 : bytes),
    );
  }
  return result;
}

bool _sameAllocationCounts(
  Map<String, (int, int)> ordinary,
  Map<String, (int, int)> target,
) {
  final names = <String>{...ordinary.keys, ...target.keys};
  var same = true;
  for (final name in names) {
    final ordinaryValue = ordinary[name] ?? (0, 0);
    final targetValue = target[name] ?? (0, 0);
    if (ordinaryValue.$1 != targetValue.$1) {
      same = false;
      stderr.writeln(
        'allocation-count mismatch $name '
        'ordinary=${ordinaryValue.$1}/${ordinaryValue.$2} '
        'target=${targetValue.$1}/${targetValue.$2}',
      );
    }
  }
  return same;
}

Future<Map<String, (int, int)>> _allocationProfile(
  String pairName,
  String operation,
  int iterations, {
  required bool target,
}) async {
  final process = await Process.start(Platform.resolvedExecutable, <String>[
    '--deterministic',
    '--no-background-compilation',
    '--no-inline-alloc',
    '--optimization-counter-threshold=10',
    '--enable-vm-service=0',
    '--disable-service-auth-codes',
    Platform.script.toFilePath(),
    '--allocation-worker',
    pairName,
    operation,
    target ? 'target' : 'ordinary',
    '$iterations',
  ]);
  final lineController = StreamController<String>();
  var openStreams = 2;
  void closeStream() {
    openStreams -= 1;
    if (openStreams == 0) {
      lineController.close();
    }
  }

  process.stdout
      .transform(utf8.decoder)
      .transform(const LineSplitter())
      .listen(lineController.add, onDone: closeStream);
  process.stderr
      .transform(utf8.decoder)
      .transform(const LineSplitter())
      .listen(lineController.add, onDone: closeStream);
  final lines = StreamIterator<String>(lineController.stream);
  Uri? serviceUri;
  while (serviceUri == null) {
    final line = await _nextWorkerLine(lines);
    if (line.startsWith('SERVICE ')) {
      serviceUri = Uri.parse(line.substring('SERVICE '.length));
    }
  }
  while (await _nextWorkerLine(lines) != 'READY') {}
  final websocketUri = serviceUri.replace(
    scheme: 'ws',
    path: '${serviceUri.path}ws',
  );
  final service = await vmServiceConnectUri(websocketUri.toString());
  try {
    final vm = await service.getVM();
    final isolate = vm.isolates!.firstWhere((item) => item.name == 'main');
    final isolateId = isolate.id!;
    await service.streamListen(EventStreams.kDebug);
    final initialState = await service.getIsolate(isolateId);
    if (initialState.pauseEvent?.kind != EventKind.kPauseBreakpoint) {
      await service.onDebugEvent.firstWhere(
        (event) =>
            event.isolate?.id == isolateId &&
            event.kind == EventKind.kPauseBreakpoint,
      );
    }
    await service.getAllocationProfile(isolateId, reset: true, gc: true);
    final completed = service.onDebugEvent.firstWhere(
      (event) =>
          event.isolate?.id == isolateId &&
          event.kind == EventKind.kPauseBreakpoint,
    );
    await service.resume(isolateId);
    await completed;
    final profile = await service.getAllocationProfile(isolateId, gc: true);
    await service.resume(isolateId);
    return _allocationSnapshot(profile);
  } finally {
    await service.dispose();
    await process.exitCode;
    await lines.cancel();
  }
}

Future<bool> _checkAllocationLane(
  String pairName,
  String operation,
  int iterations,
) async {
  final ordinaryBaseline = await _allocationProfile(
    pairName,
    operation,
    0,
    target: false,
  );
  final ordinary = await _allocationProfile(
    pairName,
    operation,
    iterations,
    target: false,
  );
  final targetBaseline = await _allocationProfile(
    pairName,
    operation,
    0,
    target: true,
  );
  final target = await _allocationProfile(
    pairName,
    operation,
    iterations,
    target: true,
  );
  final same = _sameAllocationCounts(
    _allocationDelta(ordinary, ordinaryBaseline),
    _allocationDelta(target, targetBaseline),
  );
  stdout.writeln(
    '$pairName/$operation allocation_counts='
    '${same ? 'identical' : 'different'} '
    'iterations=$iterations',
  );
  return same;
}

Future<bool> _checkAllocations(int iterations) async {
  var passed = true;
  for (final pairName in <String>[
    'root',
    'direct_field',
    'list_field',
    'map_field',
  ]) {
    for (final operation in <String>['serialize', 'deserialize']) {
      passed =
          await _checkAllocationLane(pairName, operation, iterations) && passed;
    }
  }
  return passed;
}

Future<void> main(List<String> arguments) async {
  if (arguments.firstOrNull == '--allocation-worker') {
    await _allocationWorker(arguments);
    return;
  }
  if (arguments.contains('--check-allocations')) {
    final iterations = _readInt(arguments, '--allocation-iterations', 1000);
    if (!await _checkAllocations(iterations)) {
      exitCode = 1;
    }
    return;
  }
  final samples = _readInt(arguments, '--samples', 7);
  final duration = Duration(
    milliseconds: _readInt(arguments, '--duration-ms', 1000),
  );
  final warmup = Duration(
    milliseconds: _readInt(arguments, '--warmup-ms', 1000),
  );
  final threshold = _readDouble(arguments, '--threshold', 0.01);
  final selectedData = _readString(arguments, '--data');
  final selectedOperation = _readString(arguments, '--operation');
  var passed = true;
  for (final pair in _createPairs().where(
    (pair) => selectedData == null || pair.name == selectedData,
  )) {
    if (selectedOperation == null || selectedOperation == 'serialize') {
      passed =
          _runLane(
            name: '${pair.name}/serialize',
            ordinary: () => pair.ordinaryFory.serialize(pair.ordinaryValue),
            target: () => pair.targetFory.serialize(pair.targetValue),
            samples: samples,
            duration: duration,
            warmup: warmup,
            threshold: threshold,
          ) &&
          passed;
    }
    if (selectedOperation == null || selectedOperation == 'deserialize') {
      passed =
          _runLane(
            name: '${pair.name}/deserialize',
            ordinary:
                () =>
                    pair.ordinaryFory.deserialize<Object?>(pair.ordinaryBytes),
            target:
                () => pair.targetFory.deserialize<Object?>(pair.targetBytes),
            samples: samples,
            duration: duration,
            warmup: warmup,
            threshold: threshold,
          ) &&
          passed;
    }
  }
  if (!passed) {
    exitCode = 1;
  }
  if (_sink == null) {
    throw StateError('Benchmark produced no value.');
  }
}
