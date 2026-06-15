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

package org.apache.fory.grpc_tests

import grpc_fbs.FbsGrpcServiceGrpcKt
import grpc_fbs.GrpcFbsRequest
import grpc_fbs.GrpcFbsResponse
import grpc_fbs.GrpcFbsUnion
import grpc_fdl.FdlGrpcServiceGrpcKt
import grpc_fdl.GrpcFdlRequest
import grpc_fdl.GrpcFdlResponse
import grpc_fdl.GrpcFdlUnion
import grpc_pb.GrpcPbRequest
import grpc_pb.GrpcPbRequestPayload
import grpc_pb.GrpcPbResponse
import grpc_pb.GrpcPbResponsePayload
import grpc_pb.PbGrpcServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>): Unit = runBlocking {
  when (val command = args.firstOrNull()) {
    "client" -> runClient(requireOption(args, "--target"))
    "server" -> runServer(requireOption(args, "--port-file"))
    else -> error("Expected command 'client' or 'server', got '$command'")
  }
}

private suspend fun runClient(target: String) {
  val channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build()
  try {
    exerciseFdl(channel)
    exerciseFbs(channel)
    exercisePb(channel)
  } finally {
    channel.shutdownNow()
    channel.awaitTermination(10, TimeUnit.SECONDS)
  }
}

private fun runServer(portFile: String) {
  val server =
    ServerBuilder.forPort(0)
      .addService(FdlService())
      .addService(FbsService())
      .addService(PbService())
      .build()
      .start()
  Files.write(Paths.get(portFile), server.port.toString().toByteArray(StandardCharsets.UTF_8))
  server.awaitTermination()
}

private suspend fun exerciseFdl(channel: ManagedChannel) {
  val stub = FdlGrpcServiceGrpcKt.FdlGrpcServiceCoroutineStub(channel)
  val requests = listOf(fdlRequest("fdl-a", 1, "alpha"), fdlRequest("fdl-b", 2, "beta"))
  exerciseMessages(
    requests,
    stub::unaryMessage,
    stub::serverStreamMessage,
    stub::clientStreamMessage,
    stub::bidiStreamMessage,
    ::fdlResponse,
    ::fdlAggregate,
  )

  val unionRequests =
    listOf(
      fdlUnionRequest(fdlRequest("fdl-u-a", 3, "union-alpha")),
      fdlUnionRequest(fdlRequest("fdl-u-b", 4, "union-beta")),
    )
  exerciseUnions(
    unionRequests,
    stub::unaryUnion,
    stub::serverStreamUnion,
    stub::clientStreamUnion,
    stub::bidiStreamUnion,
    ::fdlRequestFromUnion,
    ::fdlUnionResponse,
    ::fdlUnionAggregate,
  )
}

private suspend fun exerciseFbs(channel: ManagedChannel) {
  val stub = FbsGrpcServiceGrpcKt.FbsGrpcServiceCoroutineStub(channel)
  val requests = listOf(fbsRequest("fbs-a", 5, "alpha"), fbsRequest("fbs-b", 6, "beta"))
  exerciseMessages(
    requests,
    stub::unaryMessage,
    stub::serverStreamMessage,
    stub::clientStreamMessage,
    stub::bidiStreamMessage,
    ::fbsResponse,
    ::fbsAggregate,
  )

  val unionRequests =
    listOf(
      fbsUnionRequest(fbsRequest("fbs-u-a", 7, "union-alpha")),
      fbsUnionRequest(fbsRequest("fbs-u-b", 8, "union-beta")),
    )
  exerciseUnions(
    unionRequests,
    stub::unaryUnion,
    stub::serverStreamUnion,
    stub::clientStreamUnion,
    stub::bidiStreamUnion,
    ::fbsRequestFromUnion,
    ::fbsUnionResponse,
    ::fbsUnionAggregate,
  )
}

private suspend fun exercisePb(channel: ManagedChannel) {
  val stub = PbGrpcServiceGrpcKt.PbGrpcServiceCoroutineStub(channel)
  val requests =
    listOf(
      pbRequest("pb-a", 9U, GrpcPbRequestPayload.Text("alpha")),
      pbRequest("pb-b", 10U, GrpcPbRequestPayload.Number(42U)),
    )
  exerciseMessages(
    requests,
    stub::unaryMessage,
    stub::serverStreamMessage,
    stub::clientStreamMessage,
    stub::bidiStreamMessage,
    ::pbResponse,
    ::pbAggregate,
  )
}

private suspend fun <Request : Any, Response : Any> exerciseMessages(
  requests: List<Request>,
  unary: suspend (Request) -> Response,
  serverStream: (Request) -> Flow<Response>,
  clientStream: suspend (Flow<Request>) -> Response,
  bidiStream: (Flow<Request>) -> Flow<Response>,
  response: (Request, String, Int) -> Response,
  aggregate: (List<Request>) -> Response,
) {
  val first = requests.first()
  assertEquals("unary", unary(first), response(first, "unary", 10))
  assertEquals(
    "serverStream",
    serverStream(first).toList(),
    listOf(
      response(first, "server-0", 0),
      response(first, "server-1", 1),
      response(first, "server-2", 2),
    ),
  )
  assertEquals("clientStream", clientStream(requests.asFlow()), aggregate(requests))
  assertEquals(
    "bidiStream",
    bidiStream(requests.asFlow()).toList(),
    requests.mapIndexed { index, request -> response(request, "bidi-$index", index) },
  )
}

private suspend fun <Request : Any, Union : Any> exerciseUnions(
  requests: List<Union>,
  unary: suspend (Union) -> Union,
  serverStream: (Union) -> Flow<Union>,
  clientStream: suspend (Flow<Union>) -> Union,
  bidiStream: (Flow<Union>) -> Flow<Union>,
  requestFromUnion: (Union) -> Request,
  unionResponse: (Request, String, Int) -> Union,
  unionAggregate: (List<Union>) -> Union,
) {
  val first = requests.first()
  val firstRequest = requestFromUnion(first)
  assertEquals("unaryUnion", unary(first), unionResponse(firstRequest, "unary", 10))
  assertEquals(
    "serverStreamUnion",
    serverStream(first).toList(),
    listOf(
      unionResponse(firstRequest, "server-0", 0),
      unionResponse(firstRequest, "server-1", 1),
      unionResponse(firstRequest, "server-2", 2),
    ),
  )
  assertEquals("clientStreamUnion", clientStream(requests.asFlow()), unionAggregate(requests))
  assertEquals(
    "bidiStreamUnion",
    bidiStream(requests.asFlow()).toList(),
    requests.mapIndexed { index, request ->
      unionResponse(requestFromUnion(request), "bidi-$index", index)
    },
  )
}

private class FdlService : FdlGrpcServiceGrpcKt.FdlGrpcServiceCoroutineImplBase() {
  override suspend fun unaryMessage(request: GrpcFdlRequest): GrpcFdlResponse =
    fdlResponse(request, "unary", 10)

  override fun serverStreamMessage(request: GrpcFdlRequest): Flow<GrpcFdlResponse> =
    flow {
      repeat(3) { index -> emit(fdlResponse(request, "server-$index", index)) }
    }

  override suspend fun clientStreamMessage(requests: Flow<GrpcFdlRequest>): GrpcFdlResponse =
    fdlAggregate(requests.toList())

  override fun bidiStreamMessage(requests: Flow<GrpcFdlRequest>): Flow<GrpcFdlResponse> =
    flow {
      var index = 0
      requests.collect { request ->
        emit(fdlResponse(request, "bidi-$index", index))
        index++
      }
    }

  override suspend fun unaryUnion(request: GrpcFdlUnion): GrpcFdlUnion =
    fdlUnionResponse(fdlRequestFromUnion(request), "unary", 10)

  override fun serverStreamUnion(request: GrpcFdlUnion): Flow<GrpcFdlUnion> =
    flow {
      val value = fdlRequestFromUnion(request)
      repeat(3) { index -> emit(fdlUnionResponse(value, "server-$index", index)) }
    }

  override suspend fun clientStreamUnion(requests: Flow<GrpcFdlUnion>): GrpcFdlUnion =
    fdlUnionAggregate(requests.toList())

  override fun bidiStreamUnion(requests: Flow<GrpcFdlUnion>): Flow<GrpcFdlUnion> =
    flow {
      var index = 0
      requests.collect { request ->
        emit(fdlUnionResponse(fdlRequestFromUnion(request), "bidi-$index", index))
        index++
      }
    }
}

private class FbsService : FbsGrpcServiceGrpcKt.FbsGrpcServiceCoroutineImplBase() {
  override suspend fun unaryMessage(request: GrpcFbsRequest): GrpcFbsResponse =
    fbsResponse(request, "unary", 10)

  override fun serverStreamMessage(request: GrpcFbsRequest): Flow<GrpcFbsResponse> =
    flow {
      repeat(3) { index -> emit(fbsResponse(request, "server-$index", index)) }
    }

  override suspend fun clientStreamMessage(requests: Flow<GrpcFbsRequest>): GrpcFbsResponse =
    fbsAggregate(requests.toList())

  override fun bidiStreamMessage(requests: Flow<GrpcFbsRequest>): Flow<GrpcFbsResponse> =
    flow {
      var index = 0
      requests.collect { request ->
        emit(fbsResponse(request, "bidi-$index", index))
        index++
      }
    }

  override suspend fun unaryUnion(request: GrpcFbsUnion): GrpcFbsUnion =
    fbsUnionResponse(fbsRequestFromUnion(request), "unary", 10)

  override fun serverStreamUnion(request: GrpcFbsUnion): Flow<GrpcFbsUnion> =
    flow {
      val value = fbsRequestFromUnion(request)
      repeat(3) { index -> emit(fbsUnionResponse(value, "server-$index", index)) }
    }

  override suspend fun clientStreamUnion(requests: Flow<GrpcFbsUnion>): GrpcFbsUnion =
    fbsUnionAggregate(requests.toList())

  override fun bidiStreamUnion(requests: Flow<GrpcFbsUnion>): Flow<GrpcFbsUnion> =
    flow {
      var index = 0
      requests.collect { request ->
        emit(fbsUnionResponse(fbsRequestFromUnion(request), "bidi-$index", index))
        index++
      }
    }
}

private class PbService : PbGrpcServiceGrpcKt.PbGrpcServiceCoroutineImplBase() {
  override suspend fun unaryMessage(request: GrpcPbRequest): GrpcPbResponse =
    pbResponse(request, "unary", 10)

  override fun serverStreamMessage(request: GrpcPbRequest): Flow<GrpcPbResponse> =
    flow {
      repeat(3) { index -> emit(pbResponse(request, "server-$index", index)) }
    }

  override suspend fun clientStreamMessage(requests: Flow<GrpcPbRequest>): GrpcPbResponse =
    pbAggregate(requests.toList())

  override fun bidiStreamMessage(requests: Flow<GrpcPbRequest>): Flow<GrpcPbResponse> =
    flow {
      var index = 0
      requests.collect { request ->
        emit(pbResponse(request, "bidi-$index", index))
        index++
      }
    }
}

private fun fdlRequest(id: String, count: Int, payload: String): GrpcFdlRequest =
  GrpcFdlRequest(id, count, payload)

private fun fdlResponse(request: GrpcFdlRequest, tag: String, offset: Int): GrpcFdlResponse =
  GrpcFdlResponse("$tag:${request.id}", request.count + offset, "$tag:${request.payload}")

private fun fdlAggregate(requests: List<GrpcFdlRequest>): GrpcFdlResponse =
  GrpcFdlResponse(
    "client:" + requests.joinToString("+") { it.id },
    requests.sumOf { it.count },
    "client:" + requests.joinToString("+") { it.payload },
  )

private fun fdlUnionRequest(request: GrpcFdlRequest): GrpcFdlUnion = GrpcFdlUnion.Request(request)

private fun fdlUnionResponse(request: GrpcFdlRequest, tag: String, offset: Int): GrpcFdlUnion =
  GrpcFdlUnion.Response(fdlResponse(request, tag, offset))

private fun fdlUnionAggregate(requests: List<GrpcFdlUnion>): GrpcFdlUnion =
  GrpcFdlUnion.Response(fdlAggregate(requests.map(::fdlRequestFromUnion)))

private fun fdlRequestFromUnion(union: GrpcFdlUnion): GrpcFdlRequest =
  when (union) {
    is GrpcFdlUnion.Request -> union.value
    else -> error("Expected GrpcFdlUnion.Request, got ${union::class.simpleName}")
  }

private fun fbsRequest(id: String, count: Int, payload: String): GrpcFbsRequest =
  GrpcFbsRequest(id, count, payload)

private fun fbsResponse(request: GrpcFbsRequest, tag: String, offset: Int): GrpcFbsResponse =
  GrpcFbsResponse("$tag:${request.id}", request.count + offset, "$tag:${request.payload}")

private fun fbsAggregate(requests: List<GrpcFbsRequest>): GrpcFbsResponse =
  GrpcFbsResponse(
    "client:" + requests.joinToString("+") { it.id },
    requests.sumOf { it.count },
    "client:" + requests.joinToString("+") { it.payload },
  )

private fun fbsUnionRequest(request: GrpcFbsRequest): GrpcFbsUnion =
  GrpcFbsUnion.GrpcFbsRequest(request)

private fun fbsUnionResponse(request: GrpcFbsRequest, tag: String, offset: Int): GrpcFbsUnion =
  GrpcFbsUnion.GrpcFbsResponse(fbsResponse(request, tag, offset))

private fun fbsUnionAggregate(requests: List<GrpcFbsUnion>): GrpcFbsUnion =
  GrpcFbsUnion.GrpcFbsResponse(fbsAggregate(requests.map(::fbsRequestFromUnion)))

private fun fbsRequestFromUnion(union: GrpcFbsUnion): GrpcFbsRequest =
  when (union) {
    is GrpcFbsUnion.GrpcFbsRequest -> union.value
    else -> error("Expected GrpcFbsUnion.GrpcFbsRequest, got ${union::class.simpleName}")
  }

private fun pbRequest(id: String, count: UInt, payload: GrpcPbRequestPayload): GrpcPbRequest =
  GrpcPbRequest(id, count, payload)

private fun pbResponse(request: GrpcPbRequest, tag: String, offset: Int): GrpcPbResponse =
  GrpcPbResponse(
    "$tag:${request.id}",
    request.count + offset.toUInt(),
    pbResponsePayload(request.payload, tag, offset),
  )

private fun pbResponsePayload(
  payload: GrpcPbRequestPayload?,
  tag: String,
  offset: Int,
): GrpcPbResponsePayload? =
  when (payload) {
    null -> null
    is GrpcPbRequestPayload.Text -> GrpcPbResponsePayload.Text("$tag:${payload.value}")
    is GrpcPbRequestPayload.Number -> GrpcPbResponsePayload.Number(payload.value + offset.toUInt())
    is GrpcPbRequestPayload.Unknown ->
      error("Expected known GrpcPbRequestPayload, got ${payload::class.simpleName}")
  }

private fun pbAggregate(requests: List<GrpcPbRequest>): GrpcPbResponse =
  GrpcPbResponse(
    "client:" + requests.joinToString("+") { it.id },
    requests.fold(0U) { total, request -> total + request.count },
    GrpcPbResponsePayload.Text("client:" + requests.joinToString("+") { it.id }),
  )

private fun requireOption(args: Array<String>, name: String): String {
  val index = args.indexOf(name)
  require(index >= 0 && index + 1 < args.size) { "Missing required option $name" }
  return args[index + 1]
}

private fun <T> assertEquals(name: String, actual: T, expected: T) {
  check(actual == expected) { "$name: expected <$expected>, got <$actual>" }
}
