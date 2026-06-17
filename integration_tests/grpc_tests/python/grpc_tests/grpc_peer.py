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
import asyncio
from pathlib import Path
from typing import AsyncIterator, List, Sequence, TypeVar

import grpc.aio
import grpc_fbs_grpc
import grpc_fdl_grpc
import grpc_pb_grpc
from grpc_test_common import values

T = TypeVar("T")


async def _async_iter(items: Sequence[T]) -> AsyncIterator[T]:
    for item in items:
        yield item


async def _collect(call) -> List[object]:
    result = []
    async for item in call:
        result.append(item)
    return result


class FdlService(grpc_fdl_grpc.FdlGrpcServiceServicer):
    async def unary_message(self, request, context):
        return values.fdl_response(request, "unary", 10)

    async def server_stream_message(self, request, context):
        for index in range(3):
            yield values.fdl_response(request, f"server-{index}", index)

    async def client_stream_message(self, request_iterator, context):
        requests = []
        async for request in request_iterator:
            requests.append(request)
        return values.fdl_aggregate(requests)

    async def bidi_stream_message(self, request_iterator, context):
        index = 0
        async for request in request_iterator:
            yield values.fdl_response(request, f"bidi-{index}", index)
            index += 1

    async def unary_union(self, request, context):
        return values.fdl_union_response(
            values.fdl_request_from_union(request), "unary", 10
        )

    async def server_stream_union(self, request, context):
        item = values.fdl_request_from_union(request)
        for index in range(3):
            yield values.fdl_union_response(item, f"server-{index}", index)

    async def client_stream_union(self, request_iterator, context):
        requests = []
        async for item in request_iterator:
            requests.append(values.fdl_request_from_union(item))
        return values.fdl_union_aggregate(requests)

    async def bidi_stream_union(self, request_iterator, context):
        index = 0
        async for item in request_iterator:
            yield values.fdl_union_response(
                values.fdl_request_from_union(item), f"bidi-{index}", index
            )
            index += 1


class FbsService(grpc_fbs_grpc.FbsGrpcServiceServicer):
    async def unary_message(self, request, context):
        return values.fbs_response(request, "unary", 10)

    async def server_stream_message(self, request, context):
        for index in range(3):
            yield values.fbs_response(request, f"server-{index}", index)

    async def client_stream_message(self, request_iterator, context):
        requests = []
        async for request in request_iterator:
            requests.append(request)
        return values.fbs_aggregate(requests)

    async def bidi_stream_message(self, request_iterator, context):
        index = 0
        async for request in request_iterator:
            yield values.fbs_response(request, f"bidi-{index}", index)
            index += 1

    async def unary_union(self, request, context):
        return values.fbs_union_response(
            values.fbs_request_from_union(request), "unary", 10
        )

    async def server_stream_union(self, request, context):
        item = values.fbs_request_from_union(request)
        for index in range(3):
            yield values.fbs_union_response(item, f"server-{index}", index)

    async def client_stream_union(self, request_iterator, context):
        requests = []
        async for item in request_iterator:
            requests.append(values.fbs_request_from_union(item))
        return values.fbs_union_aggregate(requests)

    async def bidi_stream_union(self, request_iterator, context):
        index = 0
        async for item in request_iterator:
            yield values.fbs_union_response(
                values.fbs_request_from_union(item), f"bidi-{index}", index
            )
            index += 1


class PbService(grpc_pb_grpc.PbGrpcServiceServicer):
    async def unary_message(self, request, context):
        return values.pb_response(request, "unary", 10)

    async def server_stream_message(self, request, context):
        for index in range(3):
            yield values.pb_response(request, f"server-{index}", index)

    async def client_stream_message(self, request_iterator, context):
        requests = []
        async for request in request_iterator:
            requests.append(request)
        return values.pb_aggregate(requests)

    async def bidi_stream_message(self, request_iterator, context):
        index = 0
        async for request in request_iterator:
            yield values.pb_response(request, f"bidi-{index}", index)
            index += 1


async def _exercise_message_stub(
    stub,
    requests: Sequence[object],
    response_fn,
    aggregate_fn,
) -> None:
    first = requests[0]
    assert await stub.unary_message(first) == response_fn(first, "unary", 10)
    assert await _collect(stub.server_stream_message(first)) == [
        response_fn(first, f"server-{index}", index) for index in range(3)
    ]
    assert await stub.client_stream_message(_async_iter(requests)) == aggregate_fn(
        requests
    )
    assert await _collect(stub.bidi_stream_message(_async_iter(requests))) == [
        response_fn(request, f"bidi-{index}", index)
        for index, request in enumerate(requests)
    ]


async def _exercise_union_stub(
    stub,
    requests: Sequence[object],
    union_request_fn,
    union_response_fn,
    union_aggregate_fn,
) -> None:
    union_requests = [union_request_fn(request) for request in requests]
    first = union_requests[0]
    first_request = requests[0]
    assert await stub.unary_union(first) == union_response_fn(
        first_request, "unary", 10
    )
    assert await _collect(stub.server_stream_union(first)) == [
        union_response_fn(first_request, f"server-{index}", index) for index in range(3)
    ]
    assert await stub.client_stream_union(
        _async_iter(union_requests)
    ) == union_aggregate_fn(requests)
    assert await _collect(stub.bidi_stream_union(_async_iter(union_requests))) == [
        union_response_fn(request, f"bidi-{index}", index)
        for index, request in enumerate(requests)
    ]


async def run_client(target: str) -> None:
    async with grpc.aio.insecure_channel(target) as channel:
        await _exercise_message_stub(
            grpc_fdl_grpc.FdlGrpcServiceStub(channel),
            values.fdl_message_requests(),
            values.fdl_response,
            values.fdl_aggregate,
        )
        await _exercise_union_stub(
            grpc_fdl_grpc.FdlGrpcServiceStub(channel),
            values.fdl_union_requests(),
            values.fdl_union_request,
            values.fdl_union_response,
            values.fdl_union_aggregate,
        )

        await _exercise_message_stub(
            grpc_fbs_grpc.FbsGrpcServiceStub(channel),
            values.fbs_message_requests(),
            values.fbs_response,
            values.fbs_aggregate,
        )
        await _exercise_union_stub(
            grpc_fbs_grpc.FbsGrpcServiceStub(channel),
            values.fbs_union_requests(),
            values.fbs_union_request,
            values.fbs_union_response,
            values.fbs_union_aggregate,
        )

        await _exercise_message_stub(
            grpc_pb_grpc.PbGrpcServiceStub(channel),
            values.pb_requests(),
            values.pb_response,
            values.pb_aggregate,
        )


async def run_server(port_file: Path) -> None:
    server = grpc.aio.server()
    grpc_fdl_grpc.add_servicer(FdlService(), server)
    grpc_fbs_grpc.add_servicer(FbsService(), server)
    grpc_pb_grpc.add_servicer(PbService(), server)
    port = server.add_insecure_port("127.0.0.1:0")
    await server.start()
    port_file.write_text(str(port))
    await server.wait_for_termination()


def main() -> int:
    parser = argparse.ArgumentParser(description="Java/Python Fory gRPC async peer")
    subparsers = parser.add_subparsers(dest="command", required=True)
    client_parser = subparsers.add_parser("client")
    client_parser.add_argument("--target", required=True)
    server_parser = subparsers.add_parser("server")
    server_parser.add_argument("--port-file", type=Path, required=True)
    args = parser.parse_args()

    if args.command == "client":
        asyncio.run(run_client(args.target))
    else:
        asyncio.run(run_server(args.port_file))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
