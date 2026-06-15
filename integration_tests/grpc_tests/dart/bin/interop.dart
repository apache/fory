// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import 'dart:async';
import 'dart:io';

import 'package:fory/fory.dart';
import 'package:grpc/grpc.dart';

import 'package:fory_grpc_interop/generated/grpc_fdl/grpc_fdl.dart';
import 'package:fory_grpc_interop/generated/grpc_fdl/grpc_fdl_grpc.dart';

void _installFory() {
  GrpcFdlForyModule.install(Fory(compatible: true));
}

GrpcFdlRequest _request(String id, int count, String payload) {
  final r = GrpcFdlRequest();
  r.id = id;
  r.count = count;
  r.payload = payload;
  return r;
}

GrpcFdlResponse _response(GrpcFdlRequest request, String tag, int offset) {
  final r = GrpcFdlResponse();
  r.id = '$tag:${request.id}';
  r.count = request.count + offset;
  r.payload = '$tag:${request.payload}';
  return r;
}

GrpcFdlResponse _aggregate(List<GrpcFdlRequest> requests) {
  final r = GrpcFdlResponse();
  r.id = 'client:${requests.map((e) => e.id).join('+')}';
  r.count = requests.fold(0, (sum, e) => sum + e.count);
  r.payload = 'client:${requests.map((e) => e.payload).join('+')}';
  return r;
}

GrpcFdlUnion _unionRequest(GrpcFdlRequest request) =>
    GrpcFdlUnion.request(request);

GrpcFdlUnion _unionResponse(GrpcFdlRequest request, String tag, int offset) =>
    GrpcFdlUnion.response(_response(request, tag, offset));

GrpcFdlUnion _unionAggregate(List<GrpcFdlRequest> requests) =>
    GrpcFdlUnion.response(_aggregate(requests));

GrpcFdlRequest _requestFromUnion(GrpcFdlUnion union) => union.requestValue;

// ---- Assertion helpers: throw on mismatch so main exits non-zero ----

void _expect(Object? actual, Object? expected, String what) {
  if (actual != expected) {
    throw StateError(
      'mismatch [$what]\n  actual:   $actual\n  expected: $expected',
    );
  }
}

void _expectList(List<Object?> actual, List<Object?> expected, String what) {
  if (actual.length != expected.length) {
    throw StateError(
      'length mismatch [$what]: ${actual.length} != ${expected.length}',
    );
  }
  for (var i = 0; i < actual.length; i++) {
    _expect(actual[i], expected[i], '$what[$i]');
  }
}

class FdlService extends FdlGrpcServiceServiceBase {
  @override
  Future<GrpcFdlResponse> unaryMessage(
    ServiceCall call,
    GrpcFdlRequest request,
  ) async {
    return _response(request, 'unary', 10);
  }

  @override
  Stream<GrpcFdlResponse> serverStreamMessage(
    ServiceCall call,
    GrpcFdlRequest request,
  ) async* {
    for (var index = 0; index < 3; index++) {
      yield _response(request, 'server-$index', index);
    }
  }

  @override
  Future<GrpcFdlResponse> clientStreamMessage(
    ServiceCall call,
    Stream<GrpcFdlRequest> request,
  ) async {
    return _aggregate(await request.toList());
  }

  @override
  Stream<GrpcFdlResponse> bidiStreamMessage(
    ServiceCall call,
    Stream<GrpcFdlRequest> request,
  ) async* {
    var index = 0;
    await for (final value in request) {
      yield _response(value, 'bidi-$index', index);
      index++;
    }
  }

  @override
  Future<GrpcFdlUnion> unaryUnion(
    ServiceCall call,
    GrpcFdlUnion request,
  ) async {
    return _unionResponse(_requestFromUnion(request), 'unary', 10);
  }

  @override
  Stream<GrpcFdlUnion> serverStreamUnion(
    ServiceCall call,
    GrpcFdlUnion request,
  ) async* {
    final item = _requestFromUnion(request);
    for (var index = 0; index < 3; index++) {
      yield _unionResponse(item, 'server-$index', index);
    }
  }

  @override
  Future<GrpcFdlUnion> clientStreamUnion(
    ServiceCall call,
    Stream<GrpcFdlUnion> request,
  ) async {
    final requests = <GrpcFdlRequest>[];
    await for (final item in request) {
      requests.add(_requestFromUnion(item));
    }
    return _unionAggregate(requests);
  }

  @override
  Stream<GrpcFdlUnion> bidiStreamUnion(
    ServiceCall call,
    Stream<GrpcFdlUnion> request,
  ) async* {
    var index = 0;
    await for (final item in request) {
      yield _unionResponse(_requestFromUnion(item), 'bidi-$index', index);
      index++;
    }
  }
}

Future<void> _exerciseMessages(FdlGrpcServiceClient stub) async {
  final requests = [
    _request('fdl-a', 1, 'alpha'),
    _request('fdl-b', 2, 'beta'),
  ];
  final first = requests[0];

  _expect(
    await stub.unaryMessage(first),
    _response(first, 'unary', 10),
    'unaryMessage',
  );

  _expectList(await stub.serverStreamMessage(first).toList(), [
    for (var i = 0; i < 3; i++) _response(first, 'server-$i', i),
  ], 'serverStreamMessage');

  _expect(
    await stub.clientStreamMessage(Stream.fromIterable(requests)),
    _aggregate(requests),
    'clientStreamMessage',
  );

  _expectList(
    await stub.bidiStreamMessage(Stream.fromIterable(requests)).toList(),
    [
      for (var i = 0; i < requests.length; i++)
        _response(requests[i], 'bidi-$i', i),
    ],
    'bidiStreamMessage',
  );
}

Future<void> _exerciseUnions(FdlGrpcServiceClient stub) async {
  final requests = [
    _request('fdl-u-a', 3, 'union-alpha'),
    _request('fdl-u-b', 4, 'union-beta'),
  ];
  final unions = [for (final r in requests) _unionRequest(r)];
  final first = requests[0];

  _expect(
    await stub.unaryUnion(unions[0]),
    _unionResponse(first, 'unary', 10),
    'unaryUnion',
  );

  _expectList(await stub.serverStreamUnion(unions[0]).toList(), [
    for (var i = 0; i < 3; i++) _unionResponse(first, 'server-$i', i),
  ], 'serverStreamUnion');

  _expect(
    await stub.clientStreamUnion(Stream.fromIterable(unions)),
    _unionAggregate(requests),
    'clientStreamUnion',
  );

  _expectList(
    await stub.bidiStreamUnion(Stream.fromIterable(unions)).toList(),
    [
      for (var i = 0; i < requests.length; i++)
        _unionResponse(requests[i], 'bidi-$i', i),
    ],
    'bidiStreamUnion',
  );
}

Future<void> _runClient(String target) async {
  final parts = target.split(':');
  final host = parts[0];
  final port = int.parse(parts[1]);
  final channel = ClientChannel(
    host,
    port: port,
    options: const ChannelOptions(credentials: ChannelCredentials.insecure()),
  );
  try {
    final stub = FdlGrpcServiceClient(channel);
    await _exerciseMessages(stub);
    await _exerciseUnions(stub);
  } finally {
    await channel.shutdown();
  }
}

Future<void> _runServer(String portFilePath) async {
  final server = Server.create(services: [FdlService()]);
  await server.serve(address: InternetAddress.loopbackIPv4, port: 0);
  final port = server.port!;
  await File(portFilePath).writeAsString('$port', flush: true);
  // Block forever; the Java harness terminates this process.
  await Completer<void>().future;
}

String _flag(List<String> args, String name) {
  final i = args.indexOf(name);
  if (i < 0 || i + 1 >= args.length) {
    throw ArgumentError('missing $name');
  }
  return args[i + 1];
}

Future<void> main(List<String> args) async {
  _installFory();
  try {
    if (args.isNotEmpty && args[0] == 'client') {
      await _runClient(_flag(args, '--target'));
    } else if (args.isNotEmpty && args[0] == 'server') {
      await _runServer(_flag(args, '--port-file'));
    } else {
      stderr.writeln(
        'usage: interop.dart <client --target H:P | server --port-file PATH>',
      );
      exit(2);
    }
  } catch (e, st) {
    stderr.writeln('interop peer failed: $e\n$st');
    exit(1);
  }
}
