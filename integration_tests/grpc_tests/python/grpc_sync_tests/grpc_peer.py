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
from typing import Iterable, Sequence

import grpc
import grpc_fbs_grpc
import grpc_fdl_grpc
import grpc_pb_grpc
from grpc_test_common import values


class FdlService(grpc_fdl_grpc.FdlGrpcServiceServicer):
    def unary_message(self, request, context):
        return values.fdl_response(request, "unary", 10)

    def server_stream_message(self, request, context):
        for index in range(3):
            yield values.fdl_response(request, f"server-{index}", index)

    def client_stream_message(self, request_iterator, context):
        return values.fdl_aggregate(list(request_iterator))

    def bidi_stream_message(self, request_iterator, context):
        for index, request in enumerate(request_iterator):
            yield values.fdl_response(request, f"bidi-{index}", index)

    def unary_union(self, request, context):
        return values.fdl_union_response(
            values.fdl_request_from_union(request), "unary", 10
        )

    def server_stream_union(self, request, context):
        item = values.fdl_request_from_union(request)
        for index in range(3):
            yield values.fdl_union_response(item, f"server-{index}", index)

    def client_stream_union(self, request_iterator, context):
        requests = [values.fdl_request_from_union(item) for item in request_iterator]
        return values.fdl_union_aggregate(requests)

    def bidi_stream_union(self, request_iterator, context):
        for index, item in enumerate(request_iterator):
            yield values.fdl_union_response(
                values.fdl_request_from_union(item), f"bidi-{index}", index
            )


class FbsService(grpc_fbs_grpc.FbsGrpcServiceServicer):
    def unary_message(self, request, context):
        return values.fbs_response(request, "unary", 10)

    def server_stream_message(self, request, context):
        for index in range(3):
            yield values.fbs_response(request, f"server-{index}", index)

    def client_stream_message(self, request_iterator, context):
        return values.fbs_aggregate(list(request_iterator))

    def bidi_stream_message(self, request_iterator, context):
        for index, request in enumerate(request_iterator):
            yield values.fbs_response(request, f"bidi-{index}", index)

    def unary_union(self, request, context):
        return values.fbs_union_response(
            values.fbs_request_from_union(request), "unary", 10
        )

    def server_stream_union(self, request, context):
        item = values.fbs_request_from_union(request)
        for index in range(3):
            yield values.fbs_union_response(item, f"server-{index}", index)

    def client_stream_union(self, request_iterator, context):
        requests = [values.fbs_request_from_union(item) for item in request_iterator]
        return values.fbs_union_aggregate(requests)

    def bidi_stream_union(self, request_iterator, context):
        for index, item in enumerate(request_iterator):
            yield values.fbs_union_response(
                values.fbs_request_from_union(item), f"bidi-{index}", index
            )


class PbService(grpc_pb_grpc.PbGrpcServiceServicer):
    def unary_message(self, request, context):
        return values.pb_response(request, "unary", 10)

    def server_stream_message(self, request, context):
        for index in range(3):
            yield values.pb_response(request, f"server-{index}", index)

    def client_stream_message(self, request_iterator, context):
        return values.pb_aggregate(list(request_iterator))

    def bidi_stream_message(self, request_iterator, context):
        for index, request in enumerate(request_iterator):
            yield values.pb_response(request, f"bidi-{index}", index)


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
            values.fdl_message_requests(),
            values.fdl_response,
            values.fdl_aggregate,
        )
        _exercise_union_stub(
            grpc_fdl_grpc.FdlGrpcServiceStub(channel),
            values.fdl_union_requests(),
            values.fdl_union_request,
            values.fdl_union_response,
            values.fdl_union_aggregate,
        )

        _exercise_message_stub(
            grpc_fbs_grpc.FbsGrpcServiceStub(channel),
            values.fbs_message_requests(),
            values.fbs_response,
            values.fbs_aggregate,
        )
        _exercise_union_stub(
            grpc_fbs_grpc.FbsGrpcServiceStub(channel),
            values.fbs_union_requests(),
            values.fbs_union_request,
            values.fbs_union_response,
            values.fbs_union_aggregate,
        )

        _exercise_message_stub(
            grpc_pb_grpc.PbGrpcServiceStub(channel),
            values.pb_requests(),
            values.pb_response,
            values.pb_aggregate,
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
    parser = argparse.ArgumentParser(description="Java/Python Fory gRPC sync peer")
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
