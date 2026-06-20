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

"""Swift gRPC service companion generator (grpc-swift v1)."""

from typing import Dict, List, Set

from fory_compiler.generators.base import GeneratedFile
from fory_compiler.generators.services.base import StreamingMode, streaming_mode
from fory_compiler.ir.ast import RpcMethod, Service

# Availability gate matching grpc-swift's async/await APIs.
_ASYNC_AVAILABLE = "@available(macOS 10.15, iOS 13, tvOS 13, watchOS 6, *)"


class SwiftServiceMixin:
    """Generates Swift gRPC service companions backed by Fory serialization."""

    def generate_services(self) -> List[GeneratedFile]:
        services = [s for s in self.schema.services if not self.is_imported_type(s)]
        if not services:
            return []
        self._check_swift_grpc_method_collisions(services)
        return [self._generate_swift_service(service) for service in services]

    def _grpc_prefix(self) -> str:
        return "_".join(self._package_components_for_schema(self.schema))

    def _service_symbol(self, service: Service) -> str:
        name = self.to_pascal_case(service.name)
        prefix = self._grpc_prefix()
        return f"{prefix}_{name}" if prefix else name

    def swift_grpc_output_path(self, service: Service) -> str:
        package = self.schema.package
        package_path = package.replace(".", "/") if package else ""
        file_name = f"{self.to_pascal_case(service.name)}Grpc.swift"
        return f"{package_path}/{file_name}" if package_path else file_name

    def swift_grpc_service_symbols(self, service: Service) -> List[str]:
        base = self._service_symbol(service)
        modes = {streaming_mode(m) for m in service.methods}
        symbols = [
            f"{base}Metadata",
            f"{base}Provider",
            f"{base}AsyncProvider",
            f"{base}AsyncClient",
        ]
        if modes & {StreamingMode.SERVER_STREAMING, StreamingMode.BIDIRECTIONAL}:
            symbols += [
                f"{base}StreamingResponseContext",
                f"{base}AsyncResponseStream",
                f"{base}ResponseStream",
            ]
        if StreamingMode.CLIENT_STREAMING in modes:
            symbols.append(f"{base}UnaryResponseContext")
        if modes & {StreamingMode.CLIENT_STREAMING, StreamingMode.BIDIRECTIONAL}:
            symbols.append(f"{base}AsyncRequestStream")
        return symbols

    def _swift_grpc_method_name(self, method: RpcMethod) -> str:
        return self.safe_member_name(method.name)

    def _request_type(self, method: RpcMethod) -> str:
        return self._named_type_reference(method.request_type)

    def _response_type(self, method: RpcMethod) -> str:
        return self._named_type_reference(method.response_type)

    def _check_swift_grpc_method_collisions(self, services: List[Service]) -> None:
        for service in services:
            seen: Dict[str, str] = {}
            for method in service.methods:
                swift_name = self._swift_grpc_method_name(method).strip("`")
                if swift_name in seen:
                    raise ValueError(
                        f"Swift gRPC method name collision in service {service.name}: "
                        f"{seen[swift_name]} and {method.name} both generate {swift_name}"
                    )
                seen[swift_name] = method.name

    def _generate_swift_service(self, service: Service) -> GeneratedFile:
        base = self._service_symbol(service)
        module = self.module_type_path()
        methods = service.methods
        modes = {streaming_mode(m) for m in methods}

        lines: List[str] = []
        lines.append(self.get_license_header("//"))
        lines.append("")
        # gRPC symbols are package-prefixed with underscores, matching grpc-swift.
        lines.append("// swiftlint:disable type_name")
        lines.append("")
        lines.append("import Foundation")
        lines.append("import GRPC")
        lines.append("import NIOCore")
        lines.append("import Fory")
        lines.append("")

        if methods:
            lines.extend(self._marshaller(module))
            lines.append("")
        lines.extend(self._metadata(base, service))
        lines.append("")
        lines.extend(self._adapters(base, modes))
        lines.extend(self._provider(base, service))
        lines.append("")
        lines.extend(self._async_provider(base, service))
        lines.append("")
        lines.extend(self._async_client(base, service))
        lines.append("")
        lines.append("// swiftlint:enable type_name")

        content = "\n".join(lines).rstrip() + "\n"
        package_path = (
            self.schema.package.replace(".", "/") if self.schema.package else ""
        )
        file_name = f"{self.to_pascal_case(service.name)}Grpc.swift"
        path = f"{package_path}/{file_name}" if package_path else file_name
        return GeneratedFile(path=path, content=content)

    def _marshaller(self, module: str) -> List[str]:
        # NIOCore.ByteBuffer is qualified because `import Fory` also exposes one.
        return [
            "// Internal Fory wire wrapper for gRPC request and response messages.",
            "private struct ForyMessage<Value: Serializer>: GRPCPayload {",
            "    var value: Value",
            "    init(_ value: Value) { self.value = value }",
            "    init(serializedByteBuffer buffer: inout NIOCore.ByteBuffer) throws {",
            "        let bytes = buffer.readBytes(length: buffer.readableBytes) ?? []",
            f"        self.value = try {module}.getFory().deserialize(Data(bytes))",
            "    }",
            "    func serialize(into buffer: inout NIOCore.ByteBuffer) throws {",
            f"        buffer.writeBytes(try {module}.getFory().serialize(value))",
            "    }",
            "}",
        ]

    def _metadata(self, base: str, service: Service) -> List[str]:
        full_name = self.get_grpc_service_name(service)
        lines = [f"enum {base}Metadata {{"]
        lines.append("    enum Methods {")
        for method in service.methods:
            name = self._swift_grpc_method_name(method)
            lines.append(f"        static let {name} = GRPCMethodDescriptor(")
            lines.append(f'            name: "{method.name}",')
            lines.append(
                f'            path: "{self.get_grpc_method_path(service, method)}",'
            )
            lines.append(f"            type: {self._call_type(method)})")
        lines.append("    }")
        method_refs = ", ".join(
            f"Methods.{self._swift_grpc_method_name(m)}" for m in service.methods
        )
        lines.append("    static let serviceDescriptor = GRPCServiceDescriptor(")
        lines.append(f'        name: "{service.name}",')
        lines.append(f'        fullName: "{full_name}",')
        lines.append(f"        methods: [{method_refs}])")
        lines.append("}")
        return lines

    def _call_type(self, method: RpcMethod) -> str:
        return {
            StreamingMode.UNARY: ".unary",
            StreamingMode.SERVER_STREAMING: ".serverStreaming",
            StreamingMode.CLIENT_STREAMING: ".clientStreaming",
            StreamingMode.BIDIRECTIONAL: ".bidirectionalStreaming",
        }[streaming_mode(method)]

    def _adapters(self, base: str, modes: Set[StreamingMode]) -> List[str]:
        streamed_response = bool(
            modes & {StreamingMode.SERVER_STREAMING, StreamingMode.BIDIRECTIONAL}
        )
        streamed_request = bool(
            modes & {StreamingMode.CLIENT_STREAMING, StreamingMode.BIDIRECTIONAL}
        )
        lines: List[str] = []
        if streamed_response:
            lines += self._streaming_response_context(base)
            lines.append("")
            lines += self._async_response_stream(base)
            lines.append("")
            lines += self._client_response_stream(base)
            lines.append("")
        if StreamingMode.CLIENT_STREAMING in modes:
            lines += self._unary_response_context(base)
            lines.append("")
        if streamed_request:
            lines += self._async_request_stream(base)
            lines.append("")
        return lines

    def _streaming_response_context(self, base: str) -> List[str]:
        return [
            f"public struct {base}StreamingResponseContext<Response: Serializer> {{",
            "    fileprivate let base: StreamingResponseCallContext<ForyMessage<Response>>",
            "    public var eventLoop: EventLoop { base.eventLoop }",
            "    @discardableResult",
            "    public func sendResponse(_ response: Response) -> EventLoopFuture<Void> {",
            "        base.sendResponse(ForyMessage(response))",
            "    }",
            "}",
        ]

    def _unary_response_context(self, base: str) -> List[str]:
        return [
            f"public struct {base}UnaryResponseContext<Response: Serializer> {{",
            "    fileprivate let base: UnaryResponseCallContext<ForyMessage<Response>>",
            "    public var eventLoop: EventLoop { base.eventLoop }",
            "    public func respond(_ response: Response) {",
            "        base.responsePromise.succeed(ForyMessage(response))",
            "    }",
            "}",
        ]

    def _async_response_stream(self, base: str) -> List[str]:
        return [
            _ASYNC_AVAILABLE,
            f"public struct {base}AsyncResponseStream<Response: Serializer> {{",
            "    fileprivate let base: GRPCAsyncResponseStreamWriter<ForyMessage<Response>>",
            "    public func send(_ response: Response) async throws {",
            "        try await base.send(ForyMessage(response))",
            "    }",
            "}",
        ]

    def _async_request_stream(self, base: str) -> List[str]:
        return [
            _ASYNC_AVAILABLE,
            f"public struct {base}AsyncRequestStream<Request: Serializer>: AsyncSequence {{",
            "    public typealias Element = Request",
            "    fileprivate let base: GRPCAsyncRequestStream<ForyMessage<Request>>",
            "    public struct AsyncIterator: AsyncIteratorProtocol {",
            "        fileprivate var base: GRPCAsyncRequestStream<ForyMessage<Request>>.AsyncIterator",
            "        public mutating func next() async throws -> Request? {",
            "            try await base.next()?.value",
            "        }",
            "    }",
            "    public func makeAsyncIterator() -> AsyncIterator {",
            "        AsyncIterator(base: base.makeAsyncIterator())",
            "    }",
            "}",
        ]

    def _client_response_stream(self, base: str) -> List[str]:
        return [
            _ASYNC_AVAILABLE,
            f"public struct {base}ResponseStream<Response: Serializer>: AsyncSequence {{",
            "    public typealias Element = Response",
            "    fileprivate let base: GRPCAsyncResponseStream<ForyMessage<Response>>",
            "    public struct AsyncIterator: AsyncIteratorProtocol {",
            "        fileprivate var base: GRPCAsyncResponseStream<ForyMessage<Response>>.AsyncIterator",
            "        public mutating func next() async throws -> Response? {",
            "            try await base.next()?.value",
            "        }",
            "    }",
            "    public func makeAsyncIterator() -> AsyncIterator {",
            "        AsyncIterator(base: base.makeAsyncIterator())",
            "    }",
            "}",
        ]

    def _provider(self, base: str, service: Service) -> List[str]:
        lines = [f"public protocol {base}Provider: CallHandlerProvider {{"]
        for method in service.methods:
            lines.extend(self._provider_requirement(base, method))
        lines.append("}")
        lines.append("")
        lines.append(f"extension {base}Provider {{")
        lines.append(
            f"    public var serviceName: Substring "
            f"{{ {base}Metadata.serviceDescriptor.fullName[...] }}"
        )
        lines.append("")
        lines.extend(self._handle_signature())
        lines.append("        switch name {")
        for method in service.methods:
            lines.extend(self._provider_handler_case(base, method))
        lines.append("        default: return nil")
        lines.append("        }")
        lines.append("    }")
        lines.append("}")
        return lines

    def _handle_signature(self) -> List[str]:
        return [
            "    public func handle(",
            "        method name: Substring,",
            "        context: CallHandlerContext",
            "    ) -> GRPCServerHandlerProtocol? {",
        ]

    def _provider_requirement(self, base: str, method: RpcMethod) -> List[str]:
        name = self._swift_grpc_method_name(method)
        req = self._request_type(method)
        res = self._response_type(method)
        mode = streaming_mode(method)
        if mode is StreamingMode.UNARY:
            return [
                f"    func {name}(request: {req}, context: StatusOnlyCallContext)",
                f"        -> EventLoopFuture<{res}>",
            ]
        if mode is StreamingMode.SERVER_STREAMING:
            return [
                f"    func {name}(request: {req}, "
                f"context: {base}StreamingResponseContext<{res}>)",
                "        -> EventLoopFuture<GRPCStatus>",
            ]
        if mode is StreamingMode.CLIENT_STREAMING:
            return [
                f"    func {name}(context: {base}UnaryResponseContext<{res}>)",
                f"        -> EventLoopFuture<(StreamEvent<{req}>) -> Void>",
            ]
        return [
            f"    func {name}(context: {base}StreamingResponseContext<{res}>)",
            f"        -> EventLoopFuture<(StreamEvent<{req}>) -> Void>",
        ]

    def _provider_handler_case(self, base: str, method: RpcMethod) -> List[str]:
        name = self._swift_grpc_method_name(method)
        req = self._request_type(method)
        res = self._response_type(method)
        mode = streaming_mode(method)
        head = [
            f'        case "{method.name}":',
            f"            return {self._server_handler(mode)}(",
            "                context: context,",
            f"                requestDeserializer: GRPCPayloadDeserializer<ForyMessage<{req}>>(),",
            f"                responseSerializer: GRPCPayloadSerializer<ForyMessage<{res}>>(),",
            "                interceptors: [],",
        ]
        if mode is StreamingMode.UNARY:
            head.append(
                f"                userFunction: {{ req, ctx in "
                f"self.{name}(request: req.value, context: ctx).map {{ ForyMessage($0) }} }})"
            )
        elif mode is StreamingMode.SERVER_STREAMING:
            head += [
                "                userFunction: { req, ctx in",
                f"                    self.{name}(",
                "                        request: req.value,",
                f"                        context: {base}StreamingResponseContext(base: ctx))",
                "                })",
            ]
        elif mode is StreamingMode.CLIENT_STREAMING:
            head.extend(
                self._client_stream_observer(base, name, req, "UnaryResponseContext")
            )
        else:
            head.extend(
                self._client_stream_observer(
                    base, name, req, "StreamingResponseContext"
                )
            )
        return head

    def _client_stream_observer(
        self, base: str, name: str, req: str, ctx_kind: str
    ) -> List[str]:
        return [
            "                observerFactory: { ctx in",
            f"                    self.{name}(context: {base}{ctx_kind}(base: ctx))"
            ".map { observer in",
            f"                        {{ (event: StreamEvent<ForyMessage<{req}>>) in",
            "                            switch event {",
            "                            case .message(let wrapped): "
            "observer(.message(wrapped.value))",
            "                            case .end: observer(.end)",
            "                            @unknown default: break",
            "                            }",
            "                        }",
            "                    }",
            "                })",
        ]

    def _server_handler(self, mode: StreamingMode) -> str:
        return {
            StreamingMode.UNARY: "UnaryServerHandler",
            StreamingMode.SERVER_STREAMING: "ServerStreamingServerHandler",
            StreamingMode.CLIENT_STREAMING: "ClientStreamingServerHandler",
            StreamingMode.BIDIRECTIONAL: "BidirectionalStreamingServerHandler",
        }[mode]

    def _async_provider(self, base: str, service: Service) -> List[str]:
        lines = [
            _ASYNC_AVAILABLE,
            f"public protocol {base}AsyncProvider: CallHandlerProvider, Sendable {{",
        ]
        for method in service.methods:
            lines.extend(self._async_provider_requirement(base, method))
        lines.append("}")
        lines.append("")
        lines.append(_ASYNC_AVAILABLE)
        lines.append(f"extension {base}AsyncProvider {{")
        lines.append(
            f"    public var serviceName: Substring "
            f"{{ {base}Metadata.serviceDescriptor.fullName[...] }}"
        )
        lines.append("")
        lines.extend(self._handle_signature())
        lines.append("        switch name {")
        for method in service.methods:
            lines.extend(self._async_provider_handler_case(base, method))
        lines.append("        default: return nil")
        lines.append("        }")
        lines.append("    }")
        lines.append("}")
        return lines

    def _async_provider_requirement(self, base: str, method: RpcMethod) -> List[str]:
        name = self._swift_grpc_method_name(method)
        req = self._request_type(method)
        res = self._response_type(method)
        mode = streaming_mode(method)
        if mode is StreamingMode.UNARY:
            return [
                f"    func {name}(request: {req}, context: GRPCAsyncServerCallContext)"
                f" async throws -> {res}",
            ]
        if mode is StreamingMode.SERVER_STREAMING:
            return [
                f"    func {name}(",
                f"        request: {req},",
                f"        responseStream: {base}AsyncResponseStream<{res}>,",
                "        context: GRPCAsyncServerCallContext",
                "    ) async throws",
            ]
        if mode is StreamingMode.CLIENT_STREAMING:
            return [
                f"    func {name}(",
                f"        requestStream: {base}AsyncRequestStream<{req}>,",
                "        context: GRPCAsyncServerCallContext",
                f"    ) async throws -> {res}",
            ]
        return [
            f"    func {name}(",
            f"        requestStream: {base}AsyncRequestStream<{req}>,",
            f"        responseStream: {base}AsyncResponseStream<{res}>,",
            "        context: GRPCAsyncServerCallContext",
            "    ) async throws",
        ]

    def _async_provider_handler_case(self, base: str, method: RpcMethod) -> List[str]:
        name = self._swift_grpc_method_name(method)
        req = self._request_type(method)
        res = self._response_type(method)
        mode = streaming_mode(method)
        head = [
            f'        case "{method.name}":',
            "            return GRPCAsyncServerHandler(",
            "                context: context,",
            f"                requestDeserializer: GRPCPayloadDeserializer<ForyMessage<{req}>>(),",
            f"                responseSerializer: GRPCPayloadSerializer<ForyMessage<{res}>>(),",
            "                interceptors: [],",
        ]
        if mode is StreamingMode.UNARY:
            head.append(
                f"                wrapping: {{ ForyMessage("
                f"try await self.{name}(request: $0.value, context: $1)) }})"
            )
        elif mode is StreamingMode.SERVER_STREAMING:
            head += [
                "                wrapping: {",
                f"                    try await self.{name}(",
                "                        request: $0.value,",
                f"                        responseStream: {base}AsyncResponseStream(base: $1),",
                "                        context: $2)",
                "                })",
            ]
        elif mode is StreamingMode.CLIENT_STREAMING:
            head += [
                "                wrapping: {",
                f"                    ForyMessage(try await self.{name}(",
                f"                        requestStream: {base}AsyncRequestStream(base: $0),",
                "                        context: $1))",
                "                })",
            ]
        else:
            head += [
                "                wrapping: {",
                f"                    try await self.{name}(",
                f"                        requestStream: {base}AsyncRequestStream(base: $0),",
                f"                        responseStream: {base}AsyncResponseStream(base: $1),",
                "                        context: $2)",
                "                })",
            ]
        return head

    def _async_client(self, base: str, service: Service) -> List[str]:
        lines = [
            _ASYNC_AVAILABLE,
            f"public struct {base}AsyncClient: GRPCClient {{",
            "    public var channel: GRPCChannel",
            "    public var defaultCallOptions: CallOptions",
            "    public init(channel: GRPCChannel, defaultCallOptions: CallOptions = CallOptions()) {",
            "        self.channel = channel",
            "        self.defaultCallOptions = defaultCallOptions",
            "    }",
        ]
        for method in service.methods:
            lines.append("")
            lines.extend(self._async_client_method(base, method))
        lines.append("}")
        return lines

    def _async_client_method(self, base: str, method: RpcMethod) -> List[str]:
        name = self._swift_grpc_method_name(method)
        req = self._request_type(method)
        res = self._response_type(method)
        path = f"{base}Metadata.Methods.{name}.path"
        mode = streaming_mode(method)
        if mode is StreamingMode.UNARY:
            return [
                f"    public func {name}(_ request: {req}) async throws -> {res} {{",
                f"        let response: ForyMessage<{res}> = try await performAsyncUnaryCall(",
                f"            path: {path},",
                "            request: ForyMessage(request), callOptions: defaultCallOptions)",
                "        return response.value",
                "    }",
            ]
        if mode is StreamingMode.SERVER_STREAMING:
            return [
                f"    public func {name}(_ request: {req}) -> {base}ResponseStream<{res}> {{",
                f"        {base}ResponseStream(base: performAsyncServerStreamingCall(",
                f"            path: {path},",
                "            request: ForyMessage(request), callOptions: defaultCallOptions))",
                "    }",
            ]
        if mode is StreamingMode.CLIENT_STREAMING:
            return [
                f"    public func {name}<S: AsyncSequence & Sendable>(_ requests: S)"
                f" async throws -> {res}",
                f"    where S.Element == {req} {{",
                f"        let response: ForyMessage<{res}> = try await performAsyncClientStreamingCall(",
                f"            path: {path},",
                "            requests: requests.map { ForyMessage($0) }, callOptions: defaultCallOptions)",
                "        return response.value",
                "    }",
            ]
        return [
            f"    public func {name}<S: AsyncSequence & Sendable>(_ requests: S)"
            f" -> {base}ResponseStream<{res}>",
            f"    where S.Element == {req} {{",
            f"        {base}ResponseStream(base: performAsyncBidirectionalStreamingCall(",
            f"            path: {path},",
            "            requests: requests.map { ForyMessage($0) }, callOptions: defaultCallOptions))",
            "    }",
        ]
