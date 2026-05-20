# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from __future__ import annotations

import argparse
from concurrent import futures
from pathlib import Path
from typing import Iterable, Optional, Sequence

import grpc
import grpc_fbs
import grpc_fbs_grpc
import grpc_fdl
import grpc_fdl_grpc
import grpc_pb
import grpc_pb_grpc


def _fdl_request(id_value: str, count: int, payload: str) -> grpc_fdl.GrpcFdlRequest:
    return grpc_fdl.GrpcFdlRequest(id=id_value, count=count, payload=payload)


def _fdl_response(
    request: grpc_fdl.GrpcFdlRequest, tag: str, offset: int
) -> grpc_fdl.GrpcFdlResponse:
    return grpc_fdl.GrpcFdlResponse(
        id=f"{tag}:{request.id}",
        count=request.count + offset,
        payload=f"{tag}:{request.payload}",
    )


def _fdl_aggregate(
    requests: Sequence[grpc_fdl.GrpcFdlRequest],
) -> grpc_fdl.GrpcFdlResponse:
    return grpc_fdl.GrpcFdlResponse(
        id="client:" + "+".join(request.id for request in requests),
        count=sum(request.count for request in requests),
        payload="client:" + "+".join(request.payload for request in requests),
    )


def _fdl_union_request(
    request: grpc_fdl.GrpcFdlRequest,
) -> grpc_fdl.GrpcFdlUnion:
    return grpc_fdl.GrpcFdlUnion.request(request)


def _fdl_union_response(
    request: grpc_fdl.GrpcFdlRequest, tag: str, offset: int
) -> grpc_fdl.GrpcFdlUnion:
    return grpc_fdl.GrpcFdlUnion.response(_fdl_response(request, tag, offset))


def _fdl_union_aggregate(
    requests: Sequence[grpc_fdl.GrpcFdlRequest],
) -> grpc_fdl.GrpcFdlUnion:
    return grpc_fdl.GrpcFdlUnion.response(_fdl_aggregate(requests))


def _fdl_request_from_union(
    union: grpc_fdl.GrpcFdlUnion,
) -> grpc_fdl.GrpcFdlRequest:
    assert union.is_request()
    return union.request_value()


def _fbs_request(id_value: str, count: int, payload: str) -> grpc_fbs.GrpcFbsRequest:
    return grpc_fbs.GrpcFbsRequest(id=id_value, count=count, payload=payload)


def _fbs_response(
    request: grpc_fbs.GrpcFbsRequest, tag: str, offset: int
) -> grpc_fbs.GrpcFbsResponse:
    return grpc_fbs.GrpcFbsResponse(
        id=f"{tag}:{request.id}",
        count=request.count + offset,
        payload=f"{tag}:{request.payload}",
    )


def _fbs_aggregate(
    requests: Sequence[grpc_fbs.GrpcFbsRequest],
) -> grpc_fbs.GrpcFbsResponse:
    return grpc_fbs.GrpcFbsResponse(
        id="client:" + "+".join(request.id for request in requests),
        count=sum(request.count for request in requests),
        payload="client:" + "+".join(request.payload for request in requests),
    )


def _fbs_union_request(
    request: grpc_fbs.GrpcFbsRequest,
) -> grpc_fbs.GrpcFbsUnion:
    return grpc_fbs.GrpcFbsUnion.grpc_fbs_request(request)


def _fbs_union_response(
    request: grpc_fbs.GrpcFbsRequest, tag: str, offset: int
) -> grpc_fbs.GrpcFbsUnion:
    return grpc_fbs.GrpcFbsUnion.grpc_fbs_response(_fbs_response(request, tag, offset))


def _fbs_union_aggregate(
    requests: Sequence[grpc_fbs.GrpcFbsRequest],
) -> grpc_fbs.GrpcFbsUnion:
    return grpc_fbs.GrpcFbsUnion.grpc_fbs_response(_fbs_aggregate(requests))


def _fbs_request_from_union(
    union: grpc_fbs.GrpcFbsUnion,
) -> grpc_fbs.GrpcFbsRequest:
    assert union.is_grpc_fbs_request()
    return union.grpc_fbs_request_value()


def _pb_payload_text(value: str) -> grpc_pb.GrpcPbRequest.Payload:
    return grpc_pb.GrpcPbRequest.Payload.text(value)


def _pb_response_payload(
    payload: Optional[grpc_pb.GrpcPbRequest.Payload],
    tag: str,
    offset: int,
) -> Optional[grpc_pb.GrpcPbResponse.Payload]:
    if payload is None:
        return None
    if payload.is_text():
        return grpc_pb.GrpcPbResponse.Payload.text(f"{tag}:{payload.text_value()}")
    assert payload.is_number()
    return grpc_pb.GrpcPbResponse.Payload.number(payload.number_value() + offset)


def _pb_request(
    id_value: str, count: int, payload: grpc_pb.GrpcPbRequest.Payload
) -> grpc_pb.GrpcPbRequest:
    return grpc_pb.GrpcPbRequest(id=id_value, count=count, payload=payload)


def _pb_response(
    request: grpc_pb.GrpcPbRequest, tag: str, offset: int
) -> grpc_pb.GrpcPbResponse:
    return grpc_pb.GrpcPbResponse(
        id=f"{tag}:{request.id}",
        count=request.count + offset,
        payload=_pb_response_payload(request.payload, tag, offset),
    )


def _pb_aggregate(
    requests: Sequence[grpc_pb.GrpcPbRequest],
) -> grpc_pb.GrpcPbResponse:
    return grpc_pb.GrpcPbResponse(
        id="client:" + "+".join(request.id for request in requests),
        count=sum(request.count for request in requests),
        payload=grpc_pb.GrpcPbResponse.Payload.text(
            "client:" + "+".join(request.id for request in requests)
        ),
    )


class FdlService(grpc_fdl_grpc.FdlGrpcServiceServicer):
    def unary_message(self, request, context):
        return _fdl_response(request, "unary", 10)

    def server_stream_message(self, request, context):
        for index in range(3):
            yield _fdl_response(request, f"server-{index}", index)

    def client_stream_message(self, request_iterator, context):
        return _fdl_aggregate(list(request_iterator))

    def bidi_stream_message(self, request_iterator, context):
        for index, request in enumerate(request_iterator):
            yield _fdl_response(request, f"bidi-{index}", index)

    def unary_union(self, request, context):
        return _fdl_union_response(_fdl_request_from_union(request), "unary", 10)

    def server_stream_union(self, request, context):
        item = _fdl_request_from_union(request)
        for index in range(3):
            yield _fdl_union_response(item, f"server-{index}", index)

    def client_stream_union(self, request_iterator, context):
        requests = [_fdl_request_from_union(item) for item in request_iterator]
        return _fdl_union_aggregate(requests)

    def bidi_stream_union(self, request_iterator, context):
        for index, item in enumerate(request_iterator):
            yield _fdl_union_response(
                _fdl_request_from_union(item), f"bidi-{index}", index
            )


class FbsService(grpc_fbs_grpc.FbsGrpcServiceServicer):
    def unary_message(self, request, context):
        return _fbs_response(request, "unary", 10)

    def server_stream_message(self, request, context):
        for index in range(3):
            yield _fbs_response(request, f"server-{index}", index)

    def client_stream_message(self, request_iterator, context):
        return _fbs_aggregate(list(request_iterator))

    def bidi_stream_message(self, request_iterator, context):
        for index, request in enumerate(request_iterator):
            yield _fbs_response(request, f"bidi-{index}", index)

    def unary_union(self, request, context):
        return _fbs_union_response(_fbs_request_from_union(request), "unary", 10)

    def server_stream_union(self, request, context):
        item = _fbs_request_from_union(request)
        for index in range(3):
            yield _fbs_union_response(item, f"server-{index}", index)

    def client_stream_union(self, request_iterator, context):
        requests = [_fbs_request_from_union(item) for item in request_iterator]
        return _fbs_union_aggregate(requests)

    def bidi_stream_union(self, request_iterator, context):
        for index, item in enumerate(request_iterator):
            yield _fbs_union_response(
                _fbs_request_from_union(item), f"bidi-{index}", index
            )


class PbService(grpc_pb_grpc.PbGrpcServiceServicer):
    def unary_message(self, request, context):
        return _pb_response(request, "unary", 10)

    def server_stream_message(self, request, context):
        for index in range(3):
            yield _pb_response(request, f"server-{index}", index)

    def client_stream_message(self, request_iterator, context):
        return _pb_aggregate(list(request_iterator))

    def bidi_stream_message(self, request_iterator, context):
        for index, request in enumerate(request_iterator):
            yield _pb_response(request, f"bidi-{index}", index)


def _assert_iterable_equal(
    actual: Iterable[object], expected: Sequence[object]
) -> None:
    assert list(actual) == list(expected)


def _exercise_message_stub(
    stub,
    requests: Sequence[object],
    response_fn,
    aggregate_fn,
) -> None:
    first = requests[0]
    assert stub.unary_message(first) == response_fn(first, "unary", 10)
    _assert_iterable_equal(
        stub.server_stream_message(first),
        [response_fn(first, f"server-{index}", index) for index in range(3)],
    )
    assert stub.client_stream_message(iter(requests)) == aggregate_fn(requests)
    _assert_iterable_equal(
        stub.bidi_stream_message(iter(requests)),
        [
            response_fn(request, f"bidi-{index}", index)
            for index, request in enumerate(requests)
        ],
    )


def _exercise_union_stub(
    stub,
    requests: Sequence[object],
    union_request_fn,
    union_response_fn,
    union_aggregate_fn,
) -> None:
    union_requests = [union_request_fn(request) for request in requests]
    first = union_requests[0]
    first_request = requests[0]
    assert stub.unary_union(first) == union_response_fn(first_request, "unary", 10)
    _assert_iterable_equal(
        stub.server_stream_union(first),
        [
            union_response_fn(first_request, f"server-{index}", index)
            for index in range(3)
        ],
    )
    assert stub.client_stream_union(iter(union_requests)) == union_aggregate_fn(
        requests
    )
    _assert_iterable_equal(
        stub.bidi_stream_union(iter(union_requests)),
        [
            union_response_fn(request, f"bidi-{index}", index)
            for index, request in enumerate(requests)
        ],
    )


def run_client(target: str) -> None:
    with grpc.insecure_channel(target) as channel:
        _exercise_message_stub(
            grpc_fdl_grpc.FdlGrpcServiceStub(channel),
            [
                _fdl_request("fdl-a", 1, "alpha"),
                _fdl_request("fdl-b", 2, "beta"),
            ],
            _fdl_response,
            _fdl_aggregate,
        )
        _exercise_union_stub(
            grpc_fdl_grpc.FdlGrpcServiceStub(channel),
            [
                _fdl_request("fdl-u-a", 3, "union-alpha"),
                _fdl_request("fdl-u-b", 4, "union-beta"),
            ],
            _fdl_union_request,
            _fdl_union_response,
            _fdl_union_aggregate,
        )

        _exercise_message_stub(
            grpc_fbs_grpc.FbsGrpcServiceStub(channel),
            [
                _fbs_request("fbs-a", 5, "alpha"),
                _fbs_request("fbs-b", 6, "beta"),
            ],
            _fbs_response,
            _fbs_aggregate,
        )
        _exercise_union_stub(
            grpc_fbs_grpc.FbsGrpcServiceStub(channel),
            [
                _fbs_request("fbs-u-a", 7, "union-alpha"),
                _fbs_request("fbs-u-b", 8, "union-beta"),
            ],
            _fbs_union_request,
            _fbs_union_response,
            _fbs_union_aggregate,
        )

        _exercise_message_stub(
            grpc_pb_grpc.PbGrpcServiceStub(channel),
            [
                _pb_request("pb-a", 9, _pb_payload_text("alpha")),
                _pb_request("pb-b", 10, grpc_pb.GrpcPbRequest.Payload.number(42)),
            ],
            _pb_response,
            _pb_aggregate,
        )


def run_server(port_file: Path) -> None:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
    grpc_fdl_grpc.add_servicer(FdlService(), server)
    grpc_fbs_grpc.add_servicer(FbsService(), server)
    grpc_pb_grpc.add_servicer(PbService(), server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    port_file.write_text(str(port))
    server.wait_for_termination()


def main() -> int:
    parser = argparse.ArgumentParser(description="Java/Python Fory gRPC interop peer")
    subparsers = parser.add_subparsers(dest="command", required=True)
    client_parser = subparsers.add_parser("client")
    client_parser.add_argument("--target", required=True)
    server_parser = subparsers.add_parser("server")
    server_parser.add_argument("--port-file", type=Path, required=True)
    args = parser.parse_args()

    if args.command == "client":
        run_client(args.target)
    else:
        run_server(args.port_file)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
