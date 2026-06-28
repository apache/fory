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

use std::env;
use std::io::{Error as IoError, ErrorKind};
use std::path::PathBuf;
use std::pin::Pin;

use grpc_tests_generated::grpc_fbs::{GrpcFbsRequest, GrpcFbsResponse, GrpcFbsUnion};
use grpc_tests_generated::grpc_fbs_service::FbsGrpcService;
use grpc_tests_generated::grpc_fbs_service_grpc::fbs_grpc_service_client::FbsGrpcServiceClient;
use grpc_tests_generated::grpc_fbs_service_grpc::fbs_grpc_service_server::FbsGrpcServiceServer;
use grpc_tests_generated::grpc_fdl::{GrpcFdlRequest, GrpcFdlResponse, GrpcFdlUnion};
use grpc_tests_generated::grpc_fdl_service::FdlGrpcService;
use grpc_tests_generated::grpc_fdl_service_grpc::fdl_grpc_service_client::FdlGrpcServiceClient;
use grpc_tests_generated::grpc_fdl_service_grpc::fdl_grpc_service_server::FdlGrpcServiceServer;
use grpc_tests_generated::grpc_pb::grpc_pb_request;
use grpc_tests_generated::grpc_pb::grpc_pb_response;
use grpc_tests_generated::grpc_pb::{GrpcPbRequest, GrpcPbResponse};
use grpc_tests_generated::grpc_pb_service::PbGrpcService;
use grpc_tests_generated::grpc_pb_service_grpc::pb_grpc_service_client::PbGrpcServiceClient;
use grpc_tests_generated::grpc_pb_service_grpc::pb_grpc_service_server::PbGrpcServiceServer;
use tokio_stream::wrappers::{ReceiverStream, TcpListenerStream};
use tokio_stream::Stream;
use tonic::transport::{Channel, Server};
use tonic::{Request, Response, Status};

type AnyError = Box<dyn std::error::Error + Send + Sync>;
type AnyResult<T> = std::result::Result<T, AnyError>;
type StatusResult<T> = std::result::Result<T, Status>;
type GrpcStream<T> = Pin<Box<dyn Stream<Item = StatusResult<T>> + Send + 'static>>;

/// Runs a gRPC client or server according to the CLI arguments.
#[tokio::main]
async fn main() -> AnyResult<()> {
    let args = env::args().skip(1).collect::<Vec<_>>();
    match args.first().map(String::as_str) {
        Some("client") => run_client(required_arg(&args[1..], "--target")?).await,
        Some("server") => run_server(PathBuf::from(required_arg(&args[1..], "--port-file")?)).await,
        _ => Err(invalid_args(
            "usage: grpc_tests_interop client --target HOST:PORT | server --port-file PATH",
        )),
    }
}

/// Returns the value following a required CLI flag.
fn required_arg(args: &[String], name: &str) -> AnyResult<String> {
    let Some(index) = args.iter().position(|arg| arg == name) else {
        return Err(invalid_args(format!("missing required argument {name}")));
    };
    args.get(index + 1)
        .cloned()
        .ok_or_else(|| invalid_args(format!("missing value for {name}")))
}

fn invalid_args(message: impl Into<String>) -> AnyError {
    Box::new(IoError::new(ErrorKind::InvalidInput, message.into()))
}

// Connects to a gRPC server and exercises every schema service as a client.
async fn run_client(target: String) -> AnyResult<()> {
    let target = if target.contains("://") {
        target
    } else {
        format!("http://{target}")
    };
    let channel = Channel::from_shared(target)?.connect().await?;

    exercise_fdl(channel.clone()).await?;
    exercise_fbs(channel.clone()).await?;
    exercise_pb(channel).await?;
    Ok(())
}

// Starts a gRPC server registers services.
async fn run_server(port_file: PathBuf) -> AnyResult<()> {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let port = listener.local_addr()?.port();
    tokio::fs::write(&port_file, port.to_string()).await?;
    Server::builder()
        .add_service(FdlGrpcServiceServer::new(FdlServiceImpl))
        .add_service(FbsGrpcServiceServer::new(FbsServiceImpl))
        .add_service(PbGrpcServiceServer::new(PbServiceImpl))
        .serve_with_incoming(TcpListenerStream::new(listener))
        .await?;
    Ok(())
}

// Drains a streaming RPC response into a vector for equality assertions.
async fn collect_stream<T>(mut stream: tonic::codec::Streaming<T>) -> StatusResult<Vec<T>> {
    let mut values = Vec::new();
    while let Some(value) = stream.message().await? {
        values.push(value);
    }
    Ok(values)
}

// Builds a FDL request.
fn fdl_request(id: &str, count: i32, payload: &str) -> GrpcFdlRequest {
    GrpcFdlRequest {
        id: id.to_string(),
        count,
        payload: payload.to_string(),
    }
}

// Builds the expected FDL response for a FDL request.
fn fdl_response(request: &GrpcFdlRequest, tag: &str, offset: i32) -> GrpcFdlResponse {
    GrpcFdlResponse {
        id: format!("{tag}:{}", request.id),
        count: request.count + offset,
        payload: format!("{tag}:{}", request.payload),
    }
}

// Builds the aggregate FDL response used by client-streaming RPCs.
fn fdl_aggregate(requests: &[GrpcFdlRequest]) -> GrpcFdlResponse {
    GrpcFdlResponse {
        id: format!(
            "client:{}",
            requests
                .iter()
                .map(|request| request.id.as_str())
                .collect::<Vec<_>>()
                .join("+")
        ),
        count: requests.iter().map(|request| request.count).sum(),
        payload: format!(
            "client:{}",
            requests
                .iter()
                .map(|request| request.payload.as_str())
                .collect::<Vec<_>>()
                .join("+")
        ),
    }
}

// Extracts the request arm from a FDL union.
fn fdl_request_from_union(union: GrpcFdlUnion) -> StatusResult<GrpcFdlRequest> {
    match union {
        GrpcFdlUnion::Request(request) => Ok(request),
        _ => Err(Status::invalid_argument("expected GrpcFdlUnion::Request")),
    }
}

async fn exercise_fdl(channel: Channel) -> AnyResult<()> {
    let mut client = FdlGrpcServiceClient::new(channel);
    let requests = vec![
        fdl_request("fdl-a", 1, "alpha"),
        fdl_request("fdl-b", 2, "beta"),
    ];
    let first = requests[0].clone();
    assert_eq!(
        client.unary_message(first.clone()).await?.into_inner(),
        fdl_response(&first, "unary", 10)
    );
    assert_eq!(
        collect_stream(
            client
                .server_stream_message(first.clone())
                .await?
                .into_inner()
        )
        .await?,
        (0..3)
            .map(|index| fdl_response(&first, &format!("server-{index}"), index))
            .collect::<Vec<_>>()
    );
    assert_eq!(
        client
            .client_stream_message(tokio_stream::iter(requests.clone()))
            .await?
            .into_inner(),
        fdl_aggregate(&requests)
    );
    assert_eq!(
        collect_stream(
            client
                .bidi_stream_message(tokio_stream::iter(requests.clone()))
                .await?
                .into_inner()
        )
        .await?,
        requests
            .iter()
            .enumerate()
            .map(|(index, request)| fdl_response(request, &format!("bidi-{index}"), index as i32))
            .collect::<Vec<_>>()
    );

    let union_requests = vec![
        fdl_request("fdl-u-a", 3, "union-alpha"),
        fdl_request("fdl-u-b", 4, "union-beta"),
    ];
    let union_payloads = union_requests
        .iter()
        .cloned()
        .map(GrpcFdlUnion::Request)
        .collect::<Vec<_>>();
    let first_union = union_payloads[0].clone();
    assert_eq!(
        client.unary_union(first_union.clone()).await?.into_inner(),
        GrpcFdlUnion::Response(fdl_response(&union_requests[0], "unary", 10))
    );
    assert_eq!(
        collect_stream(client.server_stream_union(first_union).await?.into_inner()).await?,
        (0..3)
            .map(|index| {
                GrpcFdlUnion::Response(fdl_response(
                    &union_requests[0],
                    &format!("server-{index}"),
                    index,
                ))
            })
            .collect::<Vec<_>>()
    );
    assert_eq!(
        client
            .client_stream_union(tokio_stream::iter(union_payloads.clone()))
            .await?
            .into_inner(),
        GrpcFdlUnion::Response(fdl_aggregate(&union_requests))
    );
    assert_eq!(
        collect_stream(
            client
                .bidi_stream_union(tokio_stream::iter(union_payloads))
                .await?
                .into_inner()
        )
        .await?,
        union_requests
            .iter()
            .enumerate()
            .map(|(index, request)| {
                GrpcFdlUnion::Response(fdl_response(
                    request,
                    &format!("bidi-{index}"),
                    index as i32,
                ))
            })
            .collect::<Vec<_>>()
    );
    Ok(())
}

struct FdlServiceImpl;

#[tonic::async_trait]
impl FdlGrpcService for FdlServiceImpl {
    type ServerStreamMessageStream = GrpcStream<GrpcFdlResponse>;
    type BidiStreamMessageStream = GrpcStream<GrpcFdlResponse>;
    type ServerStreamUnionStream = GrpcStream<GrpcFdlUnion>;
    type BidiStreamUnionStream = GrpcStream<GrpcFdlUnion>;

    async fn unary_message(
        &self,
        request: Request<GrpcFdlRequest>,
    ) -> StatusResult<Response<GrpcFdlResponse>> {
        Ok(Response::new(fdl_response(
            &request.into_inner(),
            "unary",
            10,
        )))
    }

    async fn server_stream_message(
        &self,
        request: Request<GrpcFdlRequest>,
    ) -> StatusResult<Response<Self::ServerStreamMessageStream>> {
        let request = request.into_inner();
        Ok(Response::new(Box::pin(tokio_stream::iter(
            (0..3)
                .map(|index| Ok(fdl_response(&request, &format!("server-{index}"), index)))
                .collect::<Vec<_>>(),
        ))))
    }

    async fn client_stream_message(
        &self,
        request: Request<tonic::Streaming<GrpcFdlRequest>>,
    ) -> StatusResult<Response<GrpcFdlResponse>> {
        let mut stream = request.into_inner();
        let mut requests = Vec::new();
        while let Some(request) = stream.message().await? {
            requests.push(request);
        }
        Ok(Response::new(fdl_aggregate(&requests)))
    }

    async fn bidi_stream_message(
        &self,
        request: Request<tonic::Streaming<GrpcFdlRequest>>,
    ) -> StatusResult<Response<Self::BidiStreamMessageStream>> {
        let mut stream = request.into_inner();
        let (tx, rx) = tokio::sync::mpsc::channel(4);
        tokio::spawn(async move {
            let mut index = 0;
            loop {
                match stream.message().await {
                    Ok(Some(request)) => {
                        let response = fdl_response(&request, &format!("bidi-{index}"), index);
                        if tx.send(Ok(response)).await.is_err() {
                            break;
                        }
                        index += 1;
                    }
                    Ok(None) => break,
                    Err(error) => {
                        let _ = tx.send(Err(error)).await;
                        break;
                    }
                }
            }
        });
        Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
    }

    async fn unary_union(
        &self,
        request: Request<GrpcFdlUnion>,
    ) -> StatusResult<Response<GrpcFdlUnion>> {
        let request = fdl_request_from_union(request.into_inner())?;
        Ok(Response::new(GrpcFdlUnion::Response(fdl_response(
            &request, "unary", 10,
        ))))
    }

    async fn server_stream_union(
        &self,
        request: Request<GrpcFdlUnion>,
    ) -> StatusResult<Response<Self::ServerStreamUnionStream>> {
        let request = fdl_request_from_union(request.into_inner())?;
        Ok(Response::new(Box::pin(tokio_stream::iter(
            (0..3)
                .map(|index| {
                    Ok(GrpcFdlUnion::Response(fdl_response(
                        &request,
                        &format!("server-{index}"),
                        index,
                    )))
                })
                .collect::<Vec<_>>(),
        ))))
    }

    async fn client_stream_union(
        &self,
        request: Request<tonic::Streaming<GrpcFdlUnion>>,
    ) -> StatusResult<Response<GrpcFdlUnion>> {
        let mut stream = request.into_inner();
        let mut requests = Vec::new();
        while let Some(union) = stream.message().await? {
            requests.push(fdl_request_from_union(union)?);
        }
        Ok(Response::new(GrpcFdlUnion::Response(fdl_aggregate(
            &requests,
        ))))
    }

    async fn bidi_stream_union(
        &self,
        request: Request<tonic::Streaming<GrpcFdlUnion>>,
    ) -> StatusResult<Response<Self::BidiStreamUnionStream>> {
        let mut stream = request.into_inner();
        let (tx, rx) = tokio::sync::mpsc::channel(4);
        tokio::spawn(async move {
            let mut index = 0;
            loop {
                match stream.message().await {
                    Ok(Some(union)) => match fdl_request_from_union(union) {
                        Ok(request) => {
                            let response = GrpcFdlUnion::Response(fdl_response(
                                &request,
                                &format!("bidi-{index}"),
                                index,
                            ));
                            if tx.send(Ok(response)).await.is_err() {
                                break;
                            }
                            index += 1;
                        }
                        Err(error) => {
                            let _ = tx.send(Err(error)).await;
                            break;
                        }
                    },
                    Ok(None) => break,
                    Err(error) => {
                        let _ = tx.send(Err(error)).await;
                        break;
                    }
                }
            }
        });
        Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
    }
}

// Builds a FBS request.
fn fbs_request(id: &str, count: i32, payload: &str) -> GrpcFbsRequest {
    GrpcFbsRequest {
        id: id.to_string(),
        count,
        payload: payload.to_string(),
    }
}

// Builds the expected FBS response for a request.
fn fbs_response(request: &GrpcFbsRequest, tag: &str, offset: i32) -> GrpcFbsResponse {
    GrpcFbsResponse {
        id: format!("{tag}:{}", request.id),
        count: request.count + offset,
        payload: format!("{tag}:{}", request.payload),
    }
}

// Builds the aggregate FBS response used by client-streaming RPCs.
fn fbs_aggregate(requests: &[GrpcFbsRequest]) -> GrpcFbsResponse {
    GrpcFbsResponse {
        id: format!(
            "client:{}",
            requests
                .iter()
                .map(|request| request.id.as_str())
                .collect::<Vec<_>>()
                .join("+")
        ),
        count: requests.iter().map(|request| request.count).sum(),
        payload: format!(
            "client:{}",
            requests
                .iter()
                .map(|request| request.payload.as_str())
                .collect::<Vec<_>>()
                .join("+")
        ),
    }
}

// Extracts the request arm from a FBS union.
fn fbs_request_from_union(union: GrpcFbsUnion) -> StatusResult<GrpcFbsRequest> {
    match union {
        GrpcFbsUnion::GrpcFbsRequest(request) => Ok(request),
        _ => Err(Status::invalid_argument(
            "expected GrpcFbsUnion::GrpcFbsRequest",
        )),
    }
}

async fn exercise_fbs(channel: Channel) -> AnyResult<()> {
    let mut client = FbsGrpcServiceClient::new(channel);
    let requests = vec![
        fbs_request("fbs-a", 5, "alpha"),
        fbs_request("fbs-b", 6, "beta"),
    ];
    let first = requests[0].clone();
    assert_eq!(
        client.unary_message(first.clone()).await?.into_inner(),
        fbs_response(&first, "unary", 10)
    );
    assert_eq!(
        collect_stream(
            client
                .server_stream_message(first.clone())
                .await?
                .into_inner()
        )
        .await?,
        (0..3)
            .map(|index| fbs_response(&first, &format!("server-{index}"), index))
            .collect::<Vec<_>>()
    );
    assert_eq!(
        client
            .client_stream_message(tokio_stream::iter(requests.clone()))
            .await?
            .into_inner(),
        fbs_aggregate(&requests)
    );
    assert_eq!(
        collect_stream(
            client
                .bidi_stream_message(tokio_stream::iter(requests.clone()))
                .await?
                .into_inner()
        )
        .await?,
        requests
            .iter()
            .enumerate()
            .map(|(index, request)| fbs_response(request, &format!("bidi-{index}"), index as i32))
            .collect::<Vec<_>>()
    );

    let union_requests = vec![
        fbs_request("fbs-u-a", 7, "union-alpha"),
        fbs_request("fbs-u-b", 8, "union-beta"),
    ];
    let union_payloads = union_requests
        .iter()
        .cloned()
        .map(GrpcFbsUnion::GrpcFbsRequest)
        .collect::<Vec<_>>();
    let first_union = union_payloads[0].clone();
    assert_eq!(
        client.unary_union(first_union.clone()).await?.into_inner(),
        GrpcFbsUnion::GrpcFbsResponse(fbs_response(&union_requests[0], "unary", 10))
    );
    assert_eq!(
        collect_stream(client.server_stream_union(first_union).await?.into_inner()).await?,
        (0..3)
            .map(|index| {
                GrpcFbsUnion::GrpcFbsResponse(fbs_response(
                    &union_requests[0],
                    &format!("server-{index}"),
                    index,
                ))
            })
            .collect::<Vec<_>>()
    );
    assert_eq!(
        client
            .client_stream_union(tokio_stream::iter(union_payloads.clone()))
            .await?
            .into_inner(),
        GrpcFbsUnion::GrpcFbsResponse(fbs_aggregate(&union_requests))
    );
    assert_eq!(
        collect_stream(
            client
                .bidi_stream_union(tokio_stream::iter(union_payloads))
                .await?
                .into_inner()
        )
        .await?,
        union_requests
            .iter()
            .enumerate()
            .map(|(index, request)| {
                GrpcFbsUnion::GrpcFbsResponse(fbs_response(
                    request,
                    &format!("bidi-{index}"),
                    index as i32,
                ))
            })
            .collect::<Vec<_>>()
    );
    Ok(())
}

struct FbsServiceImpl;

#[tonic::async_trait]
impl FbsGrpcService for FbsServiceImpl {
    type ServerStreamMessageStream = GrpcStream<GrpcFbsResponse>;
    type BidiStreamMessageStream = GrpcStream<GrpcFbsResponse>;
    type ServerStreamUnionStream = GrpcStream<GrpcFbsUnion>;
    type BidiStreamUnionStream = GrpcStream<GrpcFbsUnion>;

    async fn unary_message(
        &self,
        request: Request<GrpcFbsRequest>,
    ) -> StatusResult<Response<GrpcFbsResponse>> {
        Ok(Response::new(fbs_response(
            &request.into_inner(),
            "unary",
            10,
        )))
    }

    async fn server_stream_message(
        &self,
        request: Request<GrpcFbsRequest>,
    ) -> StatusResult<Response<Self::ServerStreamMessageStream>> {
        let request = request.into_inner();
        Ok(Response::new(Box::pin(tokio_stream::iter(
            (0..3)
                .map(|index| Ok(fbs_response(&request, &format!("server-{index}"), index)))
                .collect::<Vec<_>>(),
        ))))
    }

    async fn client_stream_message(
        &self,
        request: Request<tonic::Streaming<GrpcFbsRequest>>,
    ) -> StatusResult<Response<GrpcFbsResponse>> {
        let mut stream = request.into_inner();
        let mut requests = Vec::new();
        while let Some(request) = stream.message().await? {
            requests.push(request);
        }
        Ok(Response::new(fbs_aggregate(&requests)))
    }

    async fn bidi_stream_message(
        &self,
        request: Request<tonic::Streaming<GrpcFbsRequest>>,
    ) -> StatusResult<Response<Self::BidiStreamMessageStream>> {
        let mut stream = request.into_inner();
        let (tx, rx) = tokio::sync::mpsc::channel(4);
        tokio::spawn(async move {
            let mut index = 0;
            loop {
                match stream.message().await {
                    Ok(Some(request)) => {
                        let response = fbs_response(&request, &format!("bidi-{index}"), index);
                        if tx.send(Ok(response)).await.is_err() {
                            break;
                        }
                        index += 1;
                    }
                    Ok(None) => break,
                    Err(error) => {
                        let _ = tx.send(Err(error)).await;
                        break;
                    }
                }
            }
        });
        Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
    }

    async fn unary_union(
        &self,
        request: Request<GrpcFbsUnion>,
    ) -> StatusResult<Response<GrpcFbsUnion>> {
        let request = fbs_request_from_union(request.into_inner())?;
        Ok(Response::new(GrpcFbsUnion::GrpcFbsResponse(fbs_response(
            &request, "unary", 10,
        ))))
    }

    async fn server_stream_union(
        &self,
        request: Request<GrpcFbsUnion>,
    ) -> StatusResult<Response<Self::ServerStreamUnionStream>> {
        let request = fbs_request_from_union(request.into_inner())?;
        Ok(Response::new(Box::pin(tokio_stream::iter(
            (0..3)
                .map(|index| {
                    Ok(GrpcFbsUnion::GrpcFbsResponse(fbs_response(
                        &request,
                        &format!("server-{index}"),
                        index,
                    )))
                })
                .collect::<Vec<_>>(),
        ))))
    }

    async fn client_stream_union(
        &self,
        request: Request<tonic::Streaming<GrpcFbsUnion>>,
    ) -> StatusResult<Response<GrpcFbsUnion>> {
        let mut stream = request.into_inner();
        let mut requests = Vec::new();
        while let Some(union) = stream.message().await? {
            requests.push(fbs_request_from_union(union)?);
        }
        Ok(Response::new(GrpcFbsUnion::GrpcFbsResponse(fbs_aggregate(
            &requests,
        ))))
    }

    async fn bidi_stream_union(
        &self,
        request: Request<tonic::Streaming<GrpcFbsUnion>>,
    ) -> StatusResult<Response<Self::BidiStreamUnionStream>> {
        let mut stream = request.into_inner();
        let (tx, rx) = tokio::sync::mpsc::channel(4);
        tokio::spawn(async move {
            let mut index = 0;
            loop {
                match stream.message().await {
                    Ok(Some(union)) => match fbs_request_from_union(union) {
                        Ok(request) => {
                            let response = GrpcFbsUnion::GrpcFbsResponse(fbs_response(
                                &request,
                                &format!("bidi-{index}"),
                                index,
                            ));
                            if tx.send(Ok(response)).await.is_err() {
                                break;
                            }
                            index += 1;
                        }
                        Err(error) => {
                            let _ = tx.send(Err(error)).await;
                            break;
                        }
                    },
                    Ok(None) => break,
                    Err(error) => {
                        let _ = tx.send(Err(error)).await;
                        break;
                    }
                }
            }
        });
        Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
    }
}

// Builds a protobuf request.
fn pb_request(id: &str, count: u32, payload: grpc_pb_request::Payload) -> GrpcPbRequest {
    GrpcPbRequest {
        id: id.to_string(),
        count,
        payload: Some(payload),
    }
}

// Maps a protobuf request oneof payload to the expected response payload.
fn pb_response_payload(
    payload: &Option<grpc_pb_request::Payload>,
    tag: &str,
    offset: u32,
) -> StatusResult<Option<grpc_pb_response::Payload>> {
    match payload {
        None => Ok(None),
        Some(grpc_pb_request::Payload::Text(value)) => Ok(Some(grpc_pb_response::Payload::Text(
            format!("{tag}:{value}"),
        ))),
        Some(grpc_pb_request::Payload::Number(value)) => {
            Ok(Some(grpc_pb_response::Payload::Number(value + offset)))
        }
        Some(grpc_pb_request::Payload::Unknown(_)) => Err(Status::invalid_argument(
            "expected grpc_pb_request::Payload text or number",
        )),
    }
}

// Builds the expected protobuf response for a request.
fn pb_response(request: &GrpcPbRequest, tag: &str, offset: u32) -> StatusResult<GrpcPbResponse> {
    Ok(GrpcPbResponse {
        id: format!("{tag}:{}", request.id),
        count: request.count + offset,
        payload: pb_response_payload(&request.payload, tag, offset)?,
    })
}

// Builds the aggregate protobuf response used by client-streaming RPCs.
fn pb_aggregate(requests: &[GrpcPbRequest]) -> GrpcPbResponse {
    GrpcPbResponse {
        id: format!(
            "client:{}",
            requests
                .iter()
                .map(|request| request.id.as_str())
                .collect::<Vec<_>>()
                .join("+")
        ),
        count: requests.iter().map(|request| request.count).sum(),
        payload: Some(grpc_pb_response::Payload::Text(format!(
            "client:{}",
            requests
                .iter()
                .map(|request| request.id.as_str())
                .collect::<Vec<_>>()
                .join("+")
        ))),
    }
}

async fn exercise_pb(channel: Channel) -> AnyResult<()> {
    let mut client = PbGrpcServiceClient::new(channel);
    let requests = vec![
        pb_request(
            "pb-a",
            9,
            grpc_pb_request::Payload::Text("alpha".to_string()),
        ),
        pb_request("pb-b", 10, grpc_pb_request::Payload::Number(42)),
    ];
    let first = requests[0].clone();
    assert_eq!(
        client.unary_message(first.clone()).await?.into_inner(),
        pb_response(&first, "unary", 10)?
    );
    assert_eq!(
        collect_stream(
            client
                .server_stream_message(first.clone())
                .await?
                .into_inner()
        )
        .await?,
        (0..3)
            .map(|index| pb_response(&first, &format!("server-{index}"), index))
            .collect::<StatusResult<Vec<_>>>()?
    );
    assert_eq!(
        client
            .client_stream_message(tokio_stream::iter(requests.clone()))
            .await?
            .into_inner(),
        pb_aggregate(&requests)
    );
    assert_eq!(
        collect_stream(
            client
                .bidi_stream_message(tokio_stream::iter(requests.clone()))
                .await?
                .into_inner()
        )
        .await?,
        requests
            .iter()
            .enumerate()
            .map(|(index, request)| pb_response(request, &format!("bidi-{index}"), index as u32))
            .collect::<StatusResult<Vec<_>>>()?
    );
    Ok(())
}

struct PbServiceImpl;

#[tonic::async_trait]
impl PbGrpcService for PbServiceImpl {
    type ServerStreamMessageStream = GrpcStream<GrpcPbResponse>;
    type BidiStreamMessageStream = GrpcStream<GrpcPbResponse>;

    async fn unary_message(
        &self,
        request: Request<GrpcPbRequest>,
    ) -> StatusResult<Response<GrpcPbResponse>> {
        Ok(Response::new(pb_response(
            &request.into_inner(),
            "unary",
            10,
        )?))
    }

    async fn server_stream_message(
        &self,
        request: Request<GrpcPbRequest>,
    ) -> StatusResult<Response<Self::ServerStreamMessageStream>> {
        let request = request.into_inner();
        let responses = (0..3)
            .map(|index| pb_response(&request, &format!("server-{index}"), index))
            .collect::<StatusResult<Vec<_>>>()?;
        Ok(Response::new(Box::pin(tokio_stream::iter(
            responses.into_iter().map(Ok).collect::<Vec<_>>(),
        ))))
    }

    async fn client_stream_message(
        &self,
        request: Request<tonic::Streaming<GrpcPbRequest>>,
    ) -> StatusResult<Response<GrpcPbResponse>> {
        let mut stream = request.into_inner();
        let mut requests = Vec::new();
        while let Some(request) = stream.message().await? {
            requests.push(request);
        }
        Ok(Response::new(pb_aggregate(&requests)))
    }

    async fn bidi_stream_message(
        &self,
        request: Request<tonic::Streaming<GrpcPbRequest>>,
    ) -> StatusResult<Response<Self::BidiStreamMessageStream>> {
        let mut stream = request.into_inner();
        let (tx, rx) = tokio::sync::mpsc::channel(4);
        tokio::spawn(async move {
            let mut index = 0;
            loop {
                match stream.message().await {
                    Ok(Some(request)) => {
                        match pb_response(&request, &format!("bidi-{index}"), index) {
                            Ok(response) => {
                                if tx.send(Ok(response)).await.is_err() {
                                    break;
                                }
                                index += 1;
                            }
                            Err(error) => {
                                let _ = tx.send(Err(error)).await;
                                break;
                            }
                        }
                    }
                    Ok(None) => break,
                    Err(error) => {
                        let _ = tx.send(Err(error)).await;
                        break;
                    }
                }
            }
        });
        Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
    }
}
