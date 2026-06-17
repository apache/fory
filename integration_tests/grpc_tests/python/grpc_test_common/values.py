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

from typing import List, Optional, Sequence

import grpc_fbs
import grpc_fdl
import grpc_pb


def fdl_request(id_value: str, count: int, body: str) -> grpc_fdl.GrpcFdlRequest:
    return grpc_fdl.GrpcFdlRequest(id=id_value, count=count, payload=body)


def fdl_response(
    request: grpc_fdl.GrpcFdlRequest, tag: str, offset: int
) -> grpc_fdl.GrpcFdlResponse:
    return grpc_fdl.GrpcFdlResponse(
        id=f"{tag}:{request.id}",
        count=request.count + offset,
        payload=f"{tag}:{request.payload}",
    )


def fdl_aggregate(
    requests: Sequence[grpc_fdl.GrpcFdlRequest],
) -> grpc_fdl.GrpcFdlResponse:
    return grpc_fdl.GrpcFdlResponse(
        id="client:" + "+".join(request.id for request in requests),
        count=sum(request.count for request in requests),
        payload="client:" + "+".join(request.payload for request in requests),
    )


def fdl_union_request(request: grpc_fdl.GrpcFdlRequest) -> grpc_fdl.GrpcFdlUnion:
    return grpc_fdl.GrpcFdlUnion.request(request)


def fdl_union_response(
    request: grpc_fdl.GrpcFdlRequest, tag: str, offset: int
) -> grpc_fdl.GrpcFdlUnion:
    return grpc_fdl.GrpcFdlUnion.response(fdl_response(request, tag, offset))


def fdl_union_aggregate(
    requests: Sequence[grpc_fdl.GrpcFdlRequest],
) -> grpc_fdl.GrpcFdlUnion:
    return grpc_fdl.GrpcFdlUnion.response(fdl_aggregate(requests))


def fdl_request_from_union(union: grpc_fdl.GrpcFdlUnion) -> grpc_fdl.GrpcFdlRequest:
    assert union.is_request()
    return union.request_value()


def fdl_message_requests() -> List[grpc_fdl.GrpcFdlRequest]:
    return [
        fdl_request("fdl-a", 1, "alpha"),
        fdl_request("fdl-b", 2, "beta"),
    ]


def fdl_union_requests() -> List[grpc_fdl.GrpcFdlRequest]:
    return [
        fdl_request("fdl-u-a", 3, "union-alpha"),
        fdl_request("fdl-u-b", 4, "union-beta"),
    ]


def fbs_request(id_value: str, count: int, body: str) -> grpc_fbs.GrpcFbsRequest:
    return grpc_fbs.GrpcFbsRequest(id=id_value, count=count, payload=body)


def fbs_response(
    request: grpc_fbs.GrpcFbsRequest, tag: str, offset: int
) -> grpc_fbs.GrpcFbsResponse:
    return grpc_fbs.GrpcFbsResponse(
        id=f"{tag}:{request.id}",
        count=request.count + offset,
        payload=f"{tag}:{request.payload}",
    )


def fbs_aggregate(
    requests: Sequence[grpc_fbs.GrpcFbsRequest],
) -> grpc_fbs.GrpcFbsResponse:
    return grpc_fbs.GrpcFbsResponse(
        id="client:" + "+".join(request.id for request in requests),
        count=sum(request.count for request in requests),
        payload="client:" + "+".join(request.payload for request in requests),
    )


def fbs_union_request(request: grpc_fbs.GrpcFbsRequest) -> grpc_fbs.GrpcFbsUnion:
    return grpc_fbs.GrpcFbsUnion.grpc_fbs_request(request)


def fbs_union_response(
    request: grpc_fbs.GrpcFbsRequest, tag: str, offset: int
) -> grpc_fbs.GrpcFbsUnion:
    return grpc_fbs.GrpcFbsUnion.grpc_fbs_response(fbs_response(request, tag, offset))


def fbs_union_aggregate(
    requests: Sequence[grpc_fbs.GrpcFbsRequest],
) -> grpc_fbs.GrpcFbsUnion:
    return grpc_fbs.GrpcFbsUnion.grpc_fbs_response(fbs_aggregate(requests))


def fbs_request_from_union(union: grpc_fbs.GrpcFbsUnion) -> grpc_fbs.GrpcFbsRequest:
    assert union.is_grpc_fbs_request()
    return union.grpc_fbs_request_value()


def fbs_message_requests() -> List[grpc_fbs.GrpcFbsRequest]:
    return [
        fbs_request("fbs-a", 5, "alpha"),
        fbs_request("fbs-b", 6, "beta"),
    ]


def fbs_union_requests() -> List[grpc_fbs.GrpcFbsRequest]:
    return [
        fbs_request("fbs-u-a", 7, "union-alpha"),
        fbs_request("fbs-u-b", 8, "union-beta"),
    ]


def pb_payload_text(value: str) -> grpc_pb.GrpcPbRequest.Payload:
    return grpc_pb.GrpcPbRequest.Payload.text(value)


def pb_response_payload(
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


def pb_request(
    id_value: str, count: int, payload: grpc_pb.GrpcPbRequest.Payload
) -> grpc_pb.GrpcPbRequest:
    return grpc_pb.GrpcPbRequest(id=id_value, count=count, payload=payload)


def pb_response(
    request: grpc_pb.GrpcPbRequest, tag: str, offset: int
) -> grpc_pb.GrpcPbResponse:
    return grpc_pb.GrpcPbResponse(
        id=f"{tag}:{request.id}",
        count=request.count + offset,
        payload=pb_response_payload(request.payload, tag, offset),
    )


def pb_aggregate(
    requests: Sequence[grpc_pb.GrpcPbRequest],
) -> grpc_pb.GrpcPbResponse:
    return grpc_pb.GrpcPbResponse(
        id="client:" + "+".join(request.id for request in requests),
        count=sum(request.count for request in requests),
        payload=grpc_pb.GrpcPbResponse.Payload.text(
            "client:" + "+".join(request.id for request in requests)
        ),
    )


def pb_requests() -> List[grpc_pb.GrpcPbRequest]:
    return [
        pb_request("pb-a", 9, pb_payload_text("alpha")),
        pb_request("pb-b", 10, grpc_pb.GrpcPbRequest.Payload.number(42)),
    ]
