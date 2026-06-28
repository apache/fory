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

import 'package:grpc/grpc.dart';

import 'package:fory_grpc_interop/generated/grpc_fdl/grpc_fdl.dart';
import 'package:fory_grpc_interop/generated/grpc_fdl/grpc_fdl_grpc.dart';
import 'package:fory_grpc_interop/generated/grpc_fbs/grpc_fbs.dart';
import 'package:fory_grpc_interop/generated/grpc_fbs/grpc_fbs_grpc.dart';
import 'package:fory_grpc_interop/generated/grpc_pb/grpc_pb.dart';
import 'package:fory_grpc_interop/generated/grpc_pb/grpc_pb_grpc.dart';

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

GrpcFdlRequest _fdlRequest(String id, int count, String payload) {
  return GrpcFdlRequest()
    ..id = id
    ..count = count
    ..payload = payload;
}

GrpcFdlResponse _fdlResponse(GrpcFdlRequest request, String tag, int offset) {
  return GrpcFdlResponse()
    ..id = '$tag:${request.id}'
    ..count = request.count + offset
    ..payload = '$tag:${request.payload}';
}

GrpcFdlResponse _fdlAggregate(List<GrpcFdlRequest> requests) {
  return GrpcFdlResponse()
    ..id = 'client:${requests.map((e) => e.id).join('+')}'
    ..count = requests.fold(0, (sum, e) => sum + e.count)
    ..payload = 'client:${requests.map((e) => e.payload).join('+')}';
}

GrpcFdlUnion _fdlUnionRequest(GrpcFdlRequest request) =>
    GrpcFdlUnion.request(request);

GrpcFdlUnion _fdlUnionResponse(
  GrpcFdlRequest request,
  String tag,
  int offset,
) => GrpcFdlUnion.response(_fdlResponse(request, tag, offset));

GrpcFdlUnion _fdlUnionAggregate(List<GrpcFdlRequest> requests) =>
    GrpcFdlUnion.response(_fdlAggregate(requests));

GrpcFdlRequest _fdlRequestFromUnion(GrpcFdlUnion union) => union.requestValue;

GrpcFbsRequest _fbsRequest(String id, int count, String payload) {
  return GrpcFbsRequest()
    ..id = id
    ..count = count
    ..payload = payload;
}

GrpcFbsResponse _fbsResponse(GrpcFbsRequest request, String tag, int offset) {
  return GrpcFbsResponse()
    ..id = '$tag:${request.id}'
    ..count = request.count + offset
    ..payload = '$tag:${request.payload}';
}

GrpcFbsResponse _fbsAggregate(List<GrpcFbsRequest> requests) {
  return GrpcFbsResponse()
    ..id = 'client:${requests.map((e) => e.id).join('+')}'
    ..count = requests.fold(0, (sum, e) => sum + e.count)
    ..payload = 'client:${requests.map((e) => e.payload).join('+')}';
}

GrpcFbsUnion _fbsUnionRequest(GrpcFbsRequest request) =>
    GrpcFbsUnion.grpcFbsRequest(request);

GrpcFbsUnion _fbsUnionResponse(
  GrpcFbsRequest request,
  String tag,
  int offset,
) => GrpcFbsUnion.grpcFbsResponse(_fbsResponse(request, tag, offset));

GrpcFbsUnion _fbsUnionAggregate(List<GrpcFbsRequest> requests) =>
    GrpcFbsUnion.grpcFbsResponse(_fbsAggregate(requests));

GrpcFbsRequest _fbsRequestFromUnion(GrpcFbsUnion union) =>
    union.grpcFbsRequestValue;

GrpcPbRequest _pbRequest(String id, int count, GrpcPbRequest_Payload payload) {
  return GrpcPbRequest()
    ..id = id
    ..count = count
    ..payload = payload;
}

GrpcPbResponse_Payload? _pbResponsePayload(
  GrpcPbRequest_Payload? payload,
  String tag,
  int offset,
) {
  if (payload == null) return null;
  if (payload.isText) {
    return GrpcPbResponse_Payload.text('$tag:${payload.textValue}');
  }
  return GrpcPbResponse_Payload.number(payload.numberValue + offset);
}

GrpcPbResponse _pbResponse(GrpcPbRequest request, String tag, int offset) {
  return GrpcPbResponse()
    ..id = '$tag:${request.id}'
    ..count = request.count + offset
    ..payload = _pbResponsePayload(request.payload, tag, offset);
}

GrpcPbResponse _pbAggregate(List<GrpcPbRequest> requests) {
  final ids = requests.map((e) => e.id).join('+');
  return GrpcPbResponse()
    ..id = 'client:$ids'
    ..count = requests.fold(0, (sum, e) => sum + e.count)
    ..payload = GrpcPbResponse_Payload.text('client:$ids');
}

class FdlService extends FdlGrpcServiceServiceBase {
  @override
  Future<GrpcFdlResponse> unaryMessage(ServiceCall c, GrpcFdlRequest r) async =>
      _fdlResponse(r, 'unary', 10);

  @override
  Stream<GrpcFdlResponse> serverStreamMessage(
    ServiceCall c,
    GrpcFdlRequest r,
  ) async* {
    for (var i = 0; i < 3; i++) {
      yield _fdlResponse(r, 'server-$i', i);
    }
  }

  @override
  Future<GrpcFdlResponse> clientStreamMessage(
    ServiceCall c,
    Stream<GrpcFdlRequest> r,
  ) async => _fdlAggregate(await r.toList());

  @override
  Stream<GrpcFdlResponse> bidiStreamMessage(
    ServiceCall c,
    Stream<GrpcFdlRequest> r,
  ) async* {
    var i = 0;
    await for (final v in r) {
      yield _fdlResponse(v, 'bidi-$i', i);
      i++;
    }
  }

  @override
  Future<GrpcFdlUnion> unaryUnion(ServiceCall c, GrpcFdlUnion r) async =>
      _fdlUnionResponse(_fdlRequestFromUnion(r), 'unary', 10);

  @override
  Stream<GrpcFdlUnion> serverStreamUnion(ServiceCall c, GrpcFdlUnion r) async* {
    final item = _fdlRequestFromUnion(r);
    for (var i = 0; i < 3; i++) {
      yield _fdlUnionResponse(item, 'server-$i', i);
    }
  }

  @override
  Future<GrpcFdlUnion> clientStreamUnion(
    ServiceCall c,
    Stream<GrpcFdlUnion> r,
  ) async {
    final requests = <GrpcFdlRequest>[];
    await for (final item in r) {
      requests.add(_fdlRequestFromUnion(item));
    }
    return _fdlUnionAggregate(requests);
  }

  @override
  Stream<GrpcFdlUnion> bidiStreamUnion(
    ServiceCall c,
    Stream<GrpcFdlUnion> r,
  ) async* {
    var i = 0;
    await for (final item in r) {
      yield _fdlUnionResponse(_fdlRequestFromUnion(item), 'bidi-$i', i);
      i++;
    }
  }
}

class FbsService extends FbsGrpcServiceServiceBase {
  @override
  Future<GrpcFbsResponse> unaryMessage(ServiceCall c, GrpcFbsRequest r) async =>
      _fbsResponse(r, 'unary', 10);

  @override
  Stream<GrpcFbsResponse> serverStreamMessage(
    ServiceCall c,
    GrpcFbsRequest r,
  ) async* {
    for (var i = 0; i < 3; i++) {
      yield _fbsResponse(r, 'server-$i', i);
    }
  }

  @override
  Future<GrpcFbsResponse> clientStreamMessage(
    ServiceCall c,
    Stream<GrpcFbsRequest> r,
  ) async => _fbsAggregate(await r.toList());

  @override
  Stream<GrpcFbsResponse> bidiStreamMessage(
    ServiceCall c,
    Stream<GrpcFbsRequest> r,
  ) async* {
    var i = 0;
    await for (final v in r) {
      yield _fbsResponse(v, 'bidi-$i', i);
      i++;
    }
  }

  @override
  Future<GrpcFbsUnion> unaryUnion(ServiceCall c, GrpcFbsUnion r) async =>
      _fbsUnionResponse(_fbsRequestFromUnion(r), 'unary', 10);

  @override
  Stream<GrpcFbsUnion> serverStreamUnion(ServiceCall c, GrpcFbsUnion r) async* {
    final item = _fbsRequestFromUnion(r);
    for (var i = 0; i < 3; i++) {
      yield _fbsUnionResponse(item, 'server-$i', i);
    }
  }

  @override
  Future<GrpcFbsUnion> clientStreamUnion(
    ServiceCall c,
    Stream<GrpcFbsUnion> r,
  ) async {
    final requests = <GrpcFbsRequest>[];
    await for (final item in r) {
      requests.add(_fbsRequestFromUnion(item));
    }
    return _fbsUnionAggregate(requests);
  }

  @override
  Stream<GrpcFbsUnion> bidiStreamUnion(
    ServiceCall c,
    Stream<GrpcFbsUnion> r,
  ) async* {
    var i = 0;
    await for (final item in r) {
      yield _fbsUnionResponse(_fbsRequestFromUnion(item), 'bidi-$i', i);
      i++;
    }
  }
}

class PbService extends PbGrpcServiceServiceBase {
  @override
  Future<GrpcPbResponse> unaryMessage(ServiceCall c, GrpcPbRequest r) async =>
      _pbResponse(r, 'unary', 10);

  @override
  Stream<GrpcPbResponse> serverStreamMessage(
    ServiceCall c,
    GrpcPbRequest r,
  ) async* {
    for (var i = 0; i < 3; i++) {
      yield _pbResponse(r, 'server-$i', i);
    }
  }

  @override
  Future<GrpcPbResponse> clientStreamMessage(
    ServiceCall c,
    Stream<GrpcPbRequest> r,
  ) async => _pbAggregate(await r.toList());

  @override
  Stream<GrpcPbResponse> bidiStreamMessage(
    ServiceCall c,
    Stream<GrpcPbRequest> r,
  ) async* {
    var i = 0;
    await for (final v in r) {
      yield _pbResponse(v, 'bidi-$i', i);
      i++;
    }
  }
}

Future<void> _exerciseFdl(FdlGrpcServiceClient stub) async {
  final messages = [
    _fdlRequest('fdl-a', 1, 'alpha'),
    _fdlRequest('fdl-b', 2, 'beta'),
  ];
  final first = messages[0];
  _expect(
    await stub.unaryMessage(first),
    _fdlResponse(first, 'unary', 10),
    'fdl.unaryMessage',
  );
  _expectList(await stub.serverStreamMessage(first).toList(), [
    for (var i = 0; i < 3; i++) _fdlResponse(first, 'server-$i', i),
  ], 'fdl.serverStreamMessage');
  _expect(
    await stub.clientStreamMessage(Stream.fromIterable(messages)),
    _fdlAggregate(messages),
    'fdl.clientStreamMessage',
  );
  _expectList(
    await stub.bidiStreamMessage(Stream.fromIterable(messages)).toList(),
    [
      for (var i = 0; i < messages.length; i++)
        _fdlResponse(messages[i], 'bidi-$i', i),
    ],
    'fdl.bidiStreamMessage',
  );

  final unionReqs = [
    _fdlRequest('fdl-u-a', 3, 'union-alpha'),
    _fdlRequest('fdl-u-b', 4, 'union-beta'),
  ];
  final unions = [for (final r in unionReqs) _fdlUnionRequest(r)];
  final unionFirst = unionReqs[0];
  _expect(
    await stub.unaryUnion(unions[0]),
    _fdlUnionResponse(unionFirst, 'unary', 10),
    'fdl.unaryUnion',
  );
  _expectList(await stub.serverStreamUnion(unions[0]).toList(), [
    for (var i = 0; i < 3; i++) _fdlUnionResponse(unionFirst, 'server-$i', i),
  ], 'fdl.serverStreamUnion');
  _expect(
    await stub.clientStreamUnion(Stream.fromIterable(unions)),
    _fdlUnionAggregate(unionReqs),
    'fdl.clientStreamUnion',
  );
  _expectList(
    await stub.bidiStreamUnion(Stream.fromIterable(unions)).toList(),
    [
      for (var i = 0; i < unionReqs.length; i++)
        _fdlUnionResponse(unionReqs[i], 'bidi-$i', i),
    ],
    'fdl.bidiStreamUnion',
  );
}

Future<void> _exerciseFbs(FbsGrpcServiceClient stub) async {
  final messages = [
    _fbsRequest('fbs-a', 5, 'alpha'),
    _fbsRequest('fbs-b', 6, 'beta'),
  ];
  final first = messages[0];
  _expect(
    await stub.unaryMessage(first),
    _fbsResponse(first, 'unary', 10),
    'fbs.unaryMessage',
  );
  _expectList(await stub.serverStreamMessage(first).toList(), [
    for (var i = 0; i < 3; i++) _fbsResponse(first, 'server-$i', i),
  ], 'fbs.serverStreamMessage');
  _expect(
    await stub.clientStreamMessage(Stream.fromIterable(messages)),
    _fbsAggregate(messages),
    'fbs.clientStreamMessage',
  );
  _expectList(
    await stub.bidiStreamMessage(Stream.fromIterable(messages)).toList(),
    [
      for (var i = 0; i < messages.length; i++)
        _fbsResponse(messages[i], 'bidi-$i', i),
    ],
    'fbs.bidiStreamMessage',
  );

  final unionReqs = [
    _fbsRequest('fbs-u-a', 7, 'union-alpha'),
    _fbsRequest('fbs-u-b', 8, 'union-beta'),
  ];
  final unions = [for (final r in unionReqs) _fbsUnionRequest(r)];
  final unionFirst = unionReqs[0];
  _expect(
    await stub.unaryUnion(unions[0]),
    _fbsUnionResponse(unionFirst, 'unary', 10),
    'fbs.unaryUnion',
  );
  _expectList(await stub.serverStreamUnion(unions[0]).toList(), [
    for (var i = 0; i < 3; i++) _fbsUnionResponse(unionFirst, 'server-$i', i),
  ], 'fbs.serverStreamUnion');
  _expect(
    await stub.clientStreamUnion(Stream.fromIterable(unions)),
    _fbsUnionAggregate(unionReqs),
    'fbs.clientStreamUnion',
  );
  _expectList(
    await stub.bidiStreamUnion(Stream.fromIterable(unions)).toList(),
    [
      for (var i = 0; i < unionReqs.length; i++)
        _fbsUnionResponse(unionReqs[i], 'bidi-$i', i),
    ],
    'fbs.bidiStreamUnion',
  );
}

Future<void> _exercisePb(PbGrpcServiceClient stub) async {
  final messages = [
    _pbRequest('pb-a', 9, GrpcPbRequest_Payload.text('alpha')),
    _pbRequest('pb-b', 10, GrpcPbRequest_Payload.number(42)),
  ];
  final first = messages[0];
  _expect(
    await stub.unaryMessage(first),
    _pbResponse(first, 'unary', 10),
    'pb.unaryMessage',
  );
  _expectList(await stub.serverStreamMessage(first).toList(), [
    for (var i = 0; i < 3; i++) _pbResponse(first, 'server-$i', i),
  ], 'pb.serverStreamMessage');
  _expect(
    await stub.clientStreamMessage(Stream.fromIterable(messages)),
    _pbAggregate(messages),
    'pb.clientStreamMessage',
  );
  _expectList(
    await stub.bidiStreamMessage(Stream.fromIterable(messages)).toList(),
    [
      for (var i = 0; i < messages.length; i++)
        _pbResponse(messages[i], 'bidi-$i', i),
    ],
    'pb.bidiStreamMessage',
  );
}

Future<void> _runClient(String target) async {
  final parts = target.split(':');
  final channel = ClientChannel(
    parts[0],
    port: int.parse(parts[1]),
    options: const ChannelOptions(credentials: ChannelCredentials.insecure()),
  );
  try {
    await _exerciseFdl(FdlGrpcServiceClient(channel));
    await _exerciseFbs(FbsGrpcServiceClient(channel));
    await _exercisePb(PbGrpcServiceClient(channel));
  } finally {
    await channel.shutdown();
  }
}

Future<void> _runServer(String portFilePath) async {
  final server = Server.create(
    services: [FdlService(), FbsService(), PbService()],
  );
  await server.serve(address: InternetAddress.loopbackIPv4, port: 0);
  await File(portFilePath).writeAsString('${server.port!}', flush: true);
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
