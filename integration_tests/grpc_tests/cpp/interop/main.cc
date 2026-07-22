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

#include "grpc_fbs_service_grpc.h"
#include "grpc_fdl_service_grpc.h"
#include "grpc_pb_service_grpc.h"

#include <grpcpp/create_channel.h>
#include <grpcpp/security/credentials.h>
#include <grpcpp/server.h>
#include <grpcpp/server_builder.h>

#include <chrono>
#include <cstdint>
#include <exception>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

void CheckStatus(const std::string &method, const ::grpc::Status &status) {
  if (!status.ok()) {
    throw std::runtime_error(method + ": " + status.error_message());
  }
}

template <typename T>
void CheckEqual(const std::string &method, const T &actual, const T &expected) {
  if (!(actual == expected)) {
    throw std::runtime_error(method + ": response mismatch");
  }
}

template <typename T>
void CheckEqual(const std::string &method, const std::vector<T> &actual,
                const std::vector<T> &expected) {
  if (actual != expected) {
    throw std::runtime_error(method + ": response stream mismatch");
  }
}

std::string Join(const std::vector<std::string> &values) {
  std::string joined;
  for (std::size_t index = 0; index < values.size(); ++index) {
    if (index != 0) {
      joined += "+";
    }
    joined += values[index];
  }
  return joined;
}

template <typename Request>
Request MakeStringRequest(std::string id, std::int32_t count,
                          std::string body) {
  Request request;
  request.set_id(std::move(id));
  request.set_count(count);
  request.set_payload(std::move(body));
  return request;
}

template <typename Response, typename Request>
Response MakeStringResponse(const Request &request, const std::string &tag,
                            std::int32_t offset) {
  Response response;
  response.set_id(tag + ":" + request.id());
  response.set_count(request.count() + offset);
  response.set_payload(tag + ":" + request.payload());
  return response;
}

template <typename Response, typename Request>
Response AggregateStringRequests(const std::vector<Request> &requests) {
  std::vector<std::string> ids;
  std::vector<std::string> bodies;
  std::int32_t count = 0;
  for (const auto &request : requests) {
    ids.push_back(request.id());
    bodies.push_back(request.payload());
    count += request.count();
  }
  Response response;
  response.set_id("client:" + Join(ids));
  response.set_count(count);
  response.set_payload("client:" + Join(bodies));
  return response;
}

grpc_pb::GrpcPbRequest MakePbRequest(std::string id, std::uint32_t count,
                                     grpc_pb::GrpcPbRequest::Payload body) {
  grpc_pb::GrpcPbRequest request;
  request.set_id(std::move(id));
  request.set_count(count);
  *request.mutable_payload() = std::move(body);
  return request;
}

grpc_pb::GrpcPbResponse MakePbResponse(const grpc_pb::GrpcPbRequest &request,
                                       const std::string &tag,
                                       std::uint32_t offset) {
  grpc_pb::GrpcPbResponse response;
  response.set_id(tag + ":" + request.id());
  response.set_count(request.count() + offset);
  if (request.has_payload()) {
    if (request.payload().is_text()) {
      *response.mutable_payload() = grpc_pb::GrpcPbResponse::Payload::text(
          tag + ":" + request.payload().text());
    } else {
      *response.mutable_payload() = grpc_pb::GrpcPbResponse::Payload::number(
          request.payload().number() + offset);
    }
  }
  return response;
}

grpc_pb::GrpcPbResponse
AggregatePbRequests(const std::vector<grpc_pb::GrpcPbRequest> &requests) {
  std::vector<std::string> ids;
  std::uint32_t count = 0;
  for (const auto &request : requests) {
    ids.push_back(request.id());
    count += request.count();
  }
  grpc_pb::GrpcPbResponse response;
  response.set_id("client:" + Join(ids));
  response.set_count(count);
  *response.mutable_payload() =
      grpc_pb::GrpcPbResponse::Payload::text("client:" + Join(ids));
  return response;
}

template <typename Stub, typename Request, typename Response,
          typename ResponseFactory, typename AggregateFactory>
void ExerciseMessages(Stub *stub, const std::vector<Request> &requests,
                      ResponseFactory response_factory,
                      AggregateFactory aggregate_factory) {
  const Request &first = requests.front();

  Response unary_response;
  ::grpc::ClientContext unary_context;
  CheckStatus("UnaryMessage",
              stub->UnaryMessage(&unary_context, first, &unary_response));
  CheckEqual("UnaryMessage", unary_response,
             response_factory(first, "unary", 10));

  std::vector<Response> server_responses;
  ::grpc::ClientContext server_context;
  auto reader = stub->ServerStreamMessage(&server_context, first);
  Response server_response;
  while (reader->Read(&server_response)) {
    server_responses.push_back(server_response);
  }
  CheckStatus("ServerStreamMessage", reader->Finish());
  CheckEqual("ServerStreamMessage", server_responses,
             std::vector<Response>{response_factory(first, "server-0", 0),
                                   response_factory(first, "server-1", 1),
                                   response_factory(first, "server-2", 2)});

  Response client_response;
  ::grpc::ClientContext client_context;
  auto writer = stub->ClientStreamMessage(&client_context, &client_response);
  for (const auto &request : requests) {
    if (!writer->Write(request)) {
      throw std::runtime_error("ClientStreamMessage: write failed");
    }
  }
  writer->WritesDone();
  CheckStatus("ClientStreamMessage", writer->Finish());
  CheckEqual("ClientStreamMessage", client_response,
             aggregate_factory(requests));

  std::vector<Response> bidi_responses;
  ::grpc::ClientContext bidi_context;
  auto stream = stub->BidiStreamMessage(&bidi_context);
  // Read each echo before sending the next request so synchronous flow control
  // cannot block both peers.
  for (const auto &request : requests) {
    if (!stream->Write(request)) {
      throw std::runtime_error("BidiStreamMessage: write failed");
    }
    Response bidi_response;
    if (!stream->Read(&bidi_response)) {
      throw std::runtime_error("BidiStreamMessage: response read failed");
    }
    bidi_responses.push_back(bidi_response);
  }
  stream->WritesDone();
  Response extra_bidi_response;
  if (stream->Read(&extra_bidi_response)) {
    throw std::runtime_error("BidiStreamMessage: unexpected response");
  }
  CheckStatus("BidiStreamMessage", stream->Finish());
  std::vector<Response> expected_bidi;
  for (std::size_t index = 0; index < requests.size(); ++index) {
    expected_bidi.push_back(response_factory(
        requests[index], "bidi-" + std::to_string(index), index));
  }
  CheckEqual("BidiStreamMessage", bidi_responses, expected_bidi);
}

template <typename Stub, typename Union, typename Request,
          typename RequestUnionFactory, typename ResponseUnionFactory,
          typename AggregateFactory>
void ExerciseUnions(Stub *stub, const std::vector<Request> &requests,
                    RequestUnionFactory request_union_factory,
                    ResponseUnionFactory response_union_factory,
                    AggregateFactory aggregate_factory) {
  std::vector<Union> unions;
  for (const auto &request : requests) {
    unions.push_back(request_union_factory(request));
  }

  Union unary_response;
  ::grpc::ClientContext unary_context;
  CheckStatus("UnaryUnion", stub->UnaryUnion(&unary_context, unions.front(),
                                             &unary_response));
  CheckEqual("UnaryUnion", unary_response,
             response_union_factory(requests.front(), "unary", 10));

  std::vector<Union> server_responses;
  ::grpc::ClientContext server_context;
  auto reader = stub->ServerStreamUnion(&server_context, unions.front());
  Union server_response;
  while (reader->Read(&server_response)) {
    server_responses.push_back(server_response);
  }
  CheckStatus("ServerStreamUnion", reader->Finish());
  CheckEqual("ServerStreamUnion", server_responses,
             std::vector<Union>{
                 response_union_factory(requests.front(), "server-0", 0),
                 response_union_factory(requests.front(), "server-1", 1),
                 response_union_factory(requests.front(), "server-2", 2)});

  Union client_response;
  ::grpc::ClientContext client_context;
  auto writer = stub->ClientStreamUnion(&client_context, &client_response);
  for (const auto &value : unions) {
    if (!writer->Write(value)) {
      throw std::runtime_error("ClientStreamUnion: write failed");
    }
  }
  writer->WritesDone();
  CheckStatus("ClientStreamUnion", writer->Finish());
  CheckEqual("ClientStreamUnion", client_response, aggregate_factory(requests));

  std::vector<Union> bidi_responses;
  ::grpc::ClientContext bidi_context;
  auto stream = stub->BidiStreamUnion(&bidi_context);
  // Read each echo before sending the next request so synchronous flow control
  // cannot block both peers.
  for (const auto &value : unions) {
    if (!stream->Write(value)) {
      throw std::runtime_error("BidiStreamUnion: write failed");
    }
    Union bidi_response;
    if (!stream->Read(&bidi_response)) {
      throw std::runtime_error("BidiStreamUnion: response read failed");
    }
    bidi_responses.push_back(bidi_response);
  }
  stream->WritesDone();
  Union extra_bidi_response;
  if (stream->Read(&extra_bidi_response)) {
    throw std::runtime_error("BidiStreamUnion: unexpected response");
  }
  CheckStatus("BidiStreamUnion", stream->Finish());
  std::vector<Union> expected_bidi;
  for (std::size_t index = 0; index < requests.size(); ++index) {
    expected_bidi.push_back(response_union_factory(
        requests[index], "bidi-" + std::to_string(index), index));
  }
  CheckEqual("BidiStreamUnion", bidi_responses, expected_bidi);
}

template <typename Request, typename Response, typename Reader>
::grpc::Status ReadAndAggregate(Reader *reader, Response *response) {
  std::vector<Request> requests;
  Request request;
  while (reader->Read(&request)) {
    requests.push_back(request);
  }
  *response = AggregateStringRequests<Response>(requests);
  return ::grpc::Status::OK;
}

template <typename Request, typename Response, typename Stream>
::grpc::Status EchoStringStream(Stream *stream) {
  Request request;
  std::int32_t index = 0;
  while (stream->Read(&request)) {
    if (!stream->Write(MakeStringResponse<Response>(
            request, "bidi-" + std::to_string(index), index))) {
      return ::grpc::Status(::grpc::StatusCode::UNKNOWN,
                            "response write failed");
    }
    ++index;
  }
  return ::grpc::Status::OK;
}

class FdlService final : public grpc_fdl::service::FdlGrpcService {
public:
  ::grpc::Status UnaryMessage(::grpc::ServerContext *,
                              const grpc_fdl::GrpcFdlRequest *request,
                              grpc_fdl::GrpcFdlResponse *response) override {
    *response =
        MakeStringResponse<grpc_fdl::GrpcFdlResponse>(*request, "unary", 10);
    return ::grpc::Status::OK;
  }

  ::grpc::Status ServerStreamMessage(
      ::grpc::ServerContext *, const grpc_fdl::GrpcFdlRequest *request,
      ::grpc::ServerWriter<grpc_fdl::GrpcFdlResponse> *writer) override {
    for (std::int32_t index = 0; index < 3; ++index) {
      if (!writer->Write(MakeStringResponse<grpc_fdl::GrpcFdlResponse>(
              *request, "server-" + std::to_string(index), index))) {
        return ::grpc::Status(::grpc::StatusCode::UNKNOWN,
                              "response write failed");
      }
    }
    return ::grpc::Status::OK;
  }

  ::grpc::Status
  ClientStreamMessage(::grpc::ServerContext *,
                      ::grpc::ServerReader<grpc_fdl::GrpcFdlRequest> *reader,
                      grpc_fdl::GrpcFdlResponse *response) override {
    return ReadAndAggregate<grpc_fdl::GrpcFdlRequest>(reader, response);
  }

  ::grpc::Status BidiStreamMessage(
      ::grpc::ServerContext *,
      ::grpc::ServerReaderWriter<grpc_fdl::GrpcFdlResponse,
                                 grpc_fdl::GrpcFdlRequest> *stream) override {
    return EchoStringStream<grpc_fdl::GrpcFdlRequest,
                            grpc_fdl::GrpcFdlResponse>(stream);
  }

  ::grpc::Status UnaryUnion(::grpc::ServerContext *,
                            const grpc_fdl::GrpcFdlUnion *request,
                            grpc_fdl::GrpcFdlUnion *response) override {
    const auto *value = request->as_request();
    if (value == nullptr) {
      return InvalidUnion();
    }
    *response = grpc_fdl::GrpcFdlUnion::response(
        MakeStringResponse<grpc_fdl::GrpcFdlResponse>(*value, "unary", 10));
    return ::grpc::Status::OK;
  }

  ::grpc::Status ServerStreamUnion(
      ::grpc::ServerContext *, const grpc_fdl::GrpcFdlUnion *request,
      ::grpc::ServerWriter<grpc_fdl::GrpcFdlUnion> *writer) override {
    const auto *value = request->as_request();
    if (value == nullptr) {
      return InvalidUnion();
    }
    for (std::int32_t index = 0; index < 3; ++index) {
      if (!writer->Write(grpc_fdl::GrpcFdlUnion::response(
              MakeStringResponse<grpc_fdl::GrpcFdlResponse>(
                  *value, "server-" + std::to_string(index), index)))) {
        return ::grpc::Status(::grpc::StatusCode::UNKNOWN,
                              "response write failed");
      }
    }
    return ::grpc::Status::OK;
  }

  ::grpc::Status
  ClientStreamUnion(::grpc::ServerContext *,
                    ::grpc::ServerReader<grpc_fdl::GrpcFdlUnion> *reader,
                    grpc_fdl::GrpcFdlUnion *response) override {
    std::vector<grpc_fdl::GrpcFdlRequest> requests;
    grpc_fdl::GrpcFdlUnion value;
    while (reader->Read(&value)) {
      const auto *request = value.as_request();
      if (request == nullptr) {
        return InvalidUnion();
      }
      requests.push_back(*request);
    }
    *response = grpc_fdl::GrpcFdlUnion::response(
        AggregateStringRequests<grpc_fdl::GrpcFdlResponse>(requests));
    return ::grpc::Status::OK;
  }

  ::grpc::Status BidiStreamUnion(
      ::grpc::ServerContext *,
      ::grpc::ServerReaderWriter<grpc_fdl::GrpcFdlUnion, grpc_fdl::GrpcFdlUnion>
          *stream) override {
    grpc_fdl::GrpcFdlUnion value;
    std::int32_t index = 0;
    while (stream->Read(&value)) {
      const auto *request = value.as_request();
      if (request == nullptr) {
        return InvalidUnion();
      }
      if (!stream->Write(grpc_fdl::GrpcFdlUnion::response(
              MakeStringResponse<grpc_fdl::GrpcFdlResponse>(
                  *request, "bidi-" + std::to_string(index), index)))) {
        return ::grpc::Status(::grpc::StatusCode::UNKNOWN,
                              "response write failed");
      }
      ++index;
    }
    return ::grpc::Status::OK;
  }

private:
  static ::grpc::Status InvalidUnion() {
    return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT,
                          "expected GrpcFdlUnion request");
  }
};

class FbsService final : public grpc_fbs::service::FbsGrpcService {
public:
  ::grpc::Status UnaryMessage(::grpc::ServerContext *,
                              const grpc_fbs::GrpcFbsRequest *request,
                              grpc_fbs::GrpcFbsResponse *response) override {
    *response =
        MakeStringResponse<grpc_fbs::GrpcFbsResponse>(*request, "unary", 10);
    return ::grpc::Status::OK;
  }

  ::grpc::Status ServerStreamMessage(
      ::grpc::ServerContext *, const grpc_fbs::GrpcFbsRequest *request,
      ::grpc::ServerWriter<grpc_fbs::GrpcFbsResponse> *writer) override {
    for (std::int32_t index = 0; index < 3; ++index) {
      if (!writer->Write(MakeStringResponse<grpc_fbs::GrpcFbsResponse>(
              *request, "server-" + std::to_string(index), index))) {
        return ::grpc::Status(::grpc::StatusCode::UNKNOWN,
                              "response write failed");
      }
    }
    return ::grpc::Status::OK;
  }

  ::grpc::Status
  ClientStreamMessage(::grpc::ServerContext *,
                      ::grpc::ServerReader<grpc_fbs::GrpcFbsRequest> *reader,
                      grpc_fbs::GrpcFbsResponse *response) override {
    return ReadAndAggregate<grpc_fbs::GrpcFbsRequest>(reader, response);
  }

  ::grpc::Status BidiStreamMessage(
      ::grpc::ServerContext *,
      ::grpc::ServerReaderWriter<grpc_fbs::GrpcFbsResponse,
                                 grpc_fbs::GrpcFbsRequest> *stream) override {
    return EchoStringStream<grpc_fbs::GrpcFbsRequest,
                            grpc_fbs::GrpcFbsResponse>(stream);
  }

  ::grpc::Status UnaryUnion(::grpc::ServerContext *,
                            const grpc_fbs::GrpcFbsUnion *request,
                            grpc_fbs::GrpcFbsUnion *response) override {
    const auto *value = request->as_grpc_fbs_request();
    if (value == nullptr) {
      return InvalidUnion();
    }
    *response = grpc_fbs::GrpcFbsUnion::grpc_fbs_response(
        MakeStringResponse<grpc_fbs::GrpcFbsResponse>(*value, "unary", 10));
    return ::grpc::Status::OK;
  }

  ::grpc::Status ServerStreamUnion(
      ::grpc::ServerContext *, const grpc_fbs::GrpcFbsUnion *request,
      ::grpc::ServerWriter<grpc_fbs::GrpcFbsUnion> *writer) override {
    const auto *value = request->as_grpc_fbs_request();
    if (value == nullptr) {
      return InvalidUnion();
    }
    for (std::int32_t index = 0; index < 3; ++index) {
      if (!writer->Write(grpc_fbs::GrpcFbsUnion::grpc_fbs_response(
              MakeStringResponse<grpc_fbs::GrpcFbsResponse>(
                  *value, "server-" + std::to_string(index), index)))) {
        return ::grpc::Status(::grpc::StatusCode::UNKNOWN,
                              "response write failed");
      }
    }
    return ::grpc::Status::OK;
  }

  ::grpc::Status
  ClientStreamUnion(::grpc::ServerContext *,
                    ::grpc::ServerReader<grpc_fbs::GrpcFbsUnion> *reader,
                    grpc_fbs::GrpcFbsUnion *response) override {
    std::vector<grpc_fbs::GrpcFbsRequest> requests;
    grpc_fbs::GrpcFbsUnion value;
    while (reader->Read(&value)) {
      const auto *request = value.as_grpc_fbs_request();
      if (request == nullptr) {
        return InvalidUnion();
      }
      requests.push_back(*request);
    }
    *response = grpc_fbs::GrpcFbsUnion::grpc_fbs_response(
        AggregateStringRequests<grpc_fbs::GrpcFbsResponse>(requests));
    return ::grpc::Status::OK;
  }

  ::grpc::Status BidiStreamUnion(
      ::grpc::ServerContext *,
      ::grpc::ServerReaderWriter<grpc_fbs::GrpcFbsUnion, grpc_fbs::GrpcFbsUnion>
          *stream) override {
    grpc_fbs::GrpcFbsUnion value;
    std::int32_t index = 0;
    while (stream->Read(&value)) {
      const auto *request = value.as_grpc_fbs_request();
      if (request == nullptr) {
        return InvalidUnion();
      }
      if (!stream->Write(grpc_fbs::GrpcFbsUnion::grpc_fbs_response(
              MakeStringResponse<grpc_fbs::GrpcFbsResponse>(
                  *request, "bidi-" + std::to_string(index), index)))) {
        return ::grpc::Status(::grpc::StatusCode::UNKNOWN,
                              "response write failed");
      }
      ++index;
    }
    return ::grpc::Status::OK;
  }

private:
  static ::grpc::Status InvalidUnion() {
    return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT,
                          "expected GrpcFbsUnion request");
  }
};

class PbService final : public grpc_pb::service::PbGrpcService {
public:
  ::grpc::Status UnaryMessage(::grpc::ServerContext *,
                              const grpc_pb::GrpcPbRequest *request,
                              grpc_pb::GrpcPbResponse *response) override {
    *response = MakePbResponse(*request, "unary", 10);
    return ::grpc::Status::OK;
  }

  ::grpc::Status ServerStreamMessage(
      ::grpc::ServerContext *, const grpc_pb::GrpcPbRequest *request,
      ::grpc::ServerWriter<grpc_pb::GrpcPbResponse> *writer) override {
    for (std::uint32_t index = 0; index < 3; ++index) {
      if (!writer->Write(MakePbResponse(
              *request, "server-" + std::to_string(index), index))) {
        return ::grpc::Status(::grpc::StatusCode::UNKNOWN,
                              "response write failed");
      }
    }
    return ::grpc::Status::OK;
  }

  ::grpc::Status
  ClientStreamMessage(::grpc::ServerContext *,
                      ::grpc::ServerReader<grpc_pb::GrpcPbRequest> *reader,
                      grpc_pb::GrpcPbResponse *response) override {
    std::vector<grpc_pb::GrpcPbRequest> requests;
    grpc_pb::GrpcPbRequest request;
    while (reader->Read(&request)) {
      requests.push_back(request);
    }
    *response = AggregatePbRequests(requests);
    return ::grpc::Status::OK;
  }

  ::grpc::Status BidiStreamMessage(
      ::grpc::ServerContext *,
      ::grpc::ServerReaderWriter<grpc_pb::GrpcPbResponse,
                                 grpc_pb::GrpcPbRequest> *stream) override {
    grpc_pb::GrpcPbRequest request;
    std::uint32_t index = 0;
    while (stream->Read(&request)) {
      if (!stream->Write(MakePbResponse(
              request, "bidi-" + std::to_string(index), index))) {
        return ::grpc::Status(::grpc::StatusCode::UNKNOWN,
                              "response write failed");
      }
      ++index;
    }
    return ::grpc::Status::OK;
  }
};

void ExerciseFdl(const std::shared_ptr<::grpc::Channel> &channel) {
  auto stub = grpc_fdl::service::grpc::FdlGrpcServiceStub::NewStub(channel);
  const std::vector<grpc_fdl::GrpcFdlRequest> requests{
      MakeStringRequest<grpc_fdl::GrpcFdlRequest>("fdl-a", 1, "alpha"),
      MakeStringRequest<grpc_fdl::GrpcFdlRequest>("fdl-b", 2, "beta")};
  ExerciseMessages<grpc_fdl::service::grpc::FdlGrpcServiceStub,
                   grpc_fdl::GrpcFdlRequest, grpc_fdl::GrpcFdlResponse>(
      stub.get(), requests,
      [](const auto &request, const auto &tag, auto offset) {
        return MakeStringResponse<grpc_fdl::GrpcFdlResponse>(request, tag,
                                                             offset);
      },
      [](const auto &values) {
        return AggregateStringRequests<grpc_fdl::GrpcFdlResponse>(values);
      });

  const std::vector<grpc_fdl::GrpcFdlRequest> union_requests{
      MakeStringRequest<grpc_fdl::GrpcFdlRequest>("fdl-u-a", 3, "union-alpha"),
      MakeStringRequest<grpc_fdl::GrpcFdlRequest>("fdl-u-b", 4, "union-beta")};
  ExerciseUnions<grpc_fdl::service::grpc::FdlGrpcServiceStub,
                 grpc_fdl::GrpcFdlUnion>(
      stub.get(), union_requests,
      [](const auto &request) {
        return grpc_fdl::GrpcFdlUnion::request(request);
      },
      [](const auto &request, const auto &tag, auto offset) {
        return grpc_fdl::GrpcFdlUnion::response(
            MakeStringResponse<grpc_fdl::GrpcFdlResponse>(request, tag,
                                                          offset));
      },
      [](const auto &values) {
        return grpc_fdl::GrpcFdlUnion::response(
            AggregateStringRequests<grpc_fdl::GrpcFdlResponse>(values));
      });
}

void ExerciseFbs(const std::shared_ptr<::grpc::Channel> &channel) {
  auto stub = grpc_fbs::service::grpc::FbsGrpcServiceStub::NewStub(channel);
  const std::vector<grpc_fbs::GrpcFbsRequest> requests{
      MakeStringRequest<grpc_fbs::GrpcFbsRequest>("fbs-a", 5, "alpha"),
      MakeStringRequest<grpc_fbs::GrpcFbsRequest>("fbs-b", 6, "beta")};
  ExerciseMessages<grpc_fbs::service::grpc::FbsGrpcServiceStub,
                   grpc_fbs::GrpcFbsRequest, grpc_fbs::GrpcFbsResponse>(
      stub.get(), requests,
      [](const auto &request, const auto &tag, auto offset) {
        return MakeStringResponse<grpc_fbs::GrpcFbsResponse>(request, tag,
                                                             offset);
      },
      [](const auto &values) {
        return AggregateStringRequests<grpc_fbs::GrpcFbsResponse>(values);
      });

  const std::vector<grpc_fbs::GrpcFbsRequest> union_requests{
      MakeStringRequest<grpc_fbs::GrpcFbsRequest>("fbs-u-a", 7, "union-alpha"),
      MakeStringRequest<grpc_fbs::GrpcFbsRequest>("fbs-u-b", 8, "union-beta")};
  ExerciseUnions<grpc_fbs::service::grpc::FbsGrpcServiceStub,
                 grpc_fbs::GrpcFbsUnion>(
      stub.get(), union_requests,
      [](const auto &request) {
        return grpc_fbs::GrpcFbsUnion::grpc_fbs_request(request);
      },
      [](const auto &request, const auto &tag, auto offset) {
        return grpc_fbs::GrpcFbsUnion::grpc_fbs_response(
            MakeStringResponse<grpc_fbs::GrpcFbsResponse>(request, tag,
                                                          offset));
      },
      [](const auto &values) {
        return grpc_fbs::GrpcFbsUnion::grpc_fbs_response(
            AggregateStringRequests<grpc_fbs::GrpcFbsResponse>(values));
      });
}

void ExercisePb(const std::shared_ptr<::grpc::Channel> &channel) {
  auto stub = grpc_pb::service::grpc::PbGrpcServiceStub::NewStub(channel);
  const std::vector<grpc_pb::GrpcPbRequest> requests{
      MakePbRequest("pb-a", 9, grpc_pb::GrpcPbRequest::Payload::text("alpha")),
      MakePbRequest("pb-b", 10, grpc_pb::GrpcPbRequest::Payload::number(42))};
  ExerciseMessages<grpc_pb::service::grpc::PbGrpcServiceStub,
                   grpc_pb::GrpcPbRequest, grpc_pb::GrpcPbResponse>(
      stub.get(), requests,
      [](const auto &request, const auto &tag, auto offset) {
        return MakePbResponse(request, tag, offset);
      },
      [](const auto &values) { return AggregatePbRequests(values); });
}

std::string RequiredArgument(const std::vector<std::string> &args,
                             const std::string &name) {
  for (std::size_t index = 0; index < args.size(); ++index) {
    if (args[index] == name) {
      if (index + 1 == args.size()) {
        throw std::invalid_argument("missing value for " + name);
      }
      return args[index + 1];
    }
  }
  throw std::invalid_argument("missing required argument " + name);
}

void RunClient(const std::string &target) {
  auto channel =
      ::grpc::CreateChannel(target, ::grpc::InsecureChannelCredentials());
  if (!channel->WaitForConnected(std::chrono::system_clock::now() +
                                 std::chrono::seconds(30))) {
    throw std::runtime_error("timed out connecting to " + target);
  }
  ExerciseFdl(channel);
  ExerciseFbs(channel);
  ExercisePb(channel);
}

void RunServer(const std::string &port_file) {
  FdlService fdl_impl;
  FbsService fbs_impl;
  PbService pb_impl;
  grpc_fdl::service::grpc::FdlGrpcServiceServiceGrpc fdl_service(&fdl_impl);
  grpc_fbs::service::grpc::FbsGrpcServiceServiceGrpc fbs_service(&fbs_impl);
  grpc_pb::service::grpc::PbGrpcServiceServiceGrpc pb_service(&pb_impl);

  ::grpc::ServerBuilder builder;
  int port = 0;
  builder.AddListeningPort("127.0.0.1:0", ::grpc::InsecureServerCredentials(),
                           &port);
  builder.RegisterService(&fdl_service);
  builder.RegisterService(&fbs_service);
  builder.RegisterService(&pb_service);
  std::unique_ptr<::grpc::Server> server = builder.BuildAndStart();
  if (server == nullptr || port == 0) {
    throw std::runtime_error("failed to start gRPC server");
  }

  std::ofstream output(port_file);
  output << port;
  output.close();
  if (!output) {
    throw std::runtime_error("failed to write port file " + port_file);
  }
  server->Wait();
}

} // namespace

int main(int argc, char **argv) {
  try {
    const std::vector<std::string> args(argv + 1, argv + argc);
    if (args.empty()) {
      throw std::invalid_argument(
          "usage: grpc_interop client --target HOST:PORT | server "
          "--port-file PATH");
    }
    if (args.front() == "client") {
      RunClient(RequiredArgument(args, "--target"));
      return 0;
    }
    if (args.front() == "server") {
      RunServer(RequiredArgument(args, "--port-file"));
      return 0;
    }
    throw std::invalid_argument("unknown mode " + args.front());
  } catch (const std::exception &error) {
    std::cerr << error.what() << std::endl;
    return 1;
  }
}
