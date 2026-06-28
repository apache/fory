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

"""Scala gRPC service generator helpers."""

from __future__ import annotations

from typing import Dict, List

from fory_compiler.generators.base import GeneratedFile
from fory_compiler.generators.services.base import StreamingMode, streaming_mode
from fory_compiler.ir.ast import NamedType, RpcMethod, Service


class ScalaServiceGeneratorMixin:
    """Generates Scala gRPC service companions."""

    def generate_services(self) -> List[GeneratedFile]:
        """Generate Scala gRPC service companions for local service definitions."""
        local_services = [
            service
            for service in self.schema.services
            if not self.is_imported_type(service)
        ]
        if not local_services:
            return []
        self.check_scala_grpc_service_collisions(local_services)
        self.check_scala_grpc_method_collisions(local_services)
        return [
            self.generate_scala_grpc_service_file(service) for service in local_services
        ]

    def scala_generated_output_paths(
        self, include_services: bool = False
    ) -> List[tuple[str, str]]:
        """Return generated Scala paths for the current schema graph."""
        from fory_compiler.generators.scala import scala_output_paths

        outputs: List[tuple[str, str]] = []
        for index, (_source, schema) in enumerate(self._schema_graph()):
            outputs.extend(
                scala_output_paths(
                    schema,
                    local_only=index == 0,
                    include_services=include_services,
                )
            )
        return outputs

    def check_scala_grpc_service_collisions(self, services: List[Service]) -> None:
        generated_paths: Dict[str, str] = {}
        for path, owner in self.scala_generated_output_paths(include_services=True):
            prior = generated_paths.get(path)
            if prior is not None:
                raise ValueError(
                    "Scala generated file path collision; rename schema files, "
                    "schema types, or services, or use distinct Scala packages. "
                    f"Collisions: {path}: {prior}, {owner}"
                )
            generated_paths[path] = owner

        seen_service_objects: Dict[str, str] = {}
        for service in services:
            owner = self.scala_grpc_owner_name(service)
            prior = seen_service_objects.get(owner)
            if prior is not None:
                raise ValueError(
                    f"Scala gRPC service object collision: {prior} and "
                    f"{service.name} both generate {owner}"
                )
            seen_service_objects[owner] = service.name

    def check_scala_grpc_method_collisions(self, services: List[Service]) -> None:
        top_level_reserved = {
            "SERVICE_NAME",
            "FORY",
            "completePromise",
            "internalError",
            "interrupted",
            "marshaller",
            "newClient",
            "readBytes",
            "readUnknownLengthBytes",
            "serviceDescriptor",
            "serviceDescriptorValue",
            "unimplemented",
        }
        helper_names = {
            "ForyMarshaller",
            "KnownLengthByteArrayInputStream",
            "RpcFutureAdapter",
            "RpcIteratorAdapter",
        }
        for service in services:
            seen_top_level: Dict[str, str] = {}
            seen_service_methods: Dict[str, str] = {}
            seen_client_methods: Dict[str, str] = {}
            for method in service.methods:
                base_name = self.scala_grpc_method_name(method)
                top_level_names = [
                    self.scala_grpc_method_descriptor(method),
                    self.scala_grpc_method_backing_field(method),
                ]
                service_method_names = [base_name]
                client_method_names = [base_name]
                mode = streaming_mode(method)
                if mode in (StreamingMode.UNARY, StreamingMode.SERVER_STREAMING):
                    client_method_names.append(
                        self.scala_grpc_blocking_method_name(method)
                    )
                if mode is StreamingMode.UNARY:
                    client_method_names.append(
                        self.scala_grpc_future_method_name(method)
                    )
                for name in top_level_names:
                    if name in top_level_reserved or name in helper_names:
                        raise ValueError(
                            f"Scala gRPC method name collision in service "
                            f"{service.name}: {method.name} generates reserved "
                            f"member {name}"
                        )
                    prior = seen_top_level.get(name)
                    if prior is not None:
                        raise ValueError(
                            f"Scala gRPC method name collision in service "
                            f"{service.name}: {prior} and {method.name} both "
                            f"generate top-level member {name}"
                        )
                    seen_top_level[name] = method.name
                for name in service_method_names:
                    prior = seen_service_methods.get(name)
                    if prior is not None:
                        raise ValueError(
                            f"Scala gRPC method name collision in service "
                            f"{service.name}: {prior} and {method.name} both "
                            f"generate service method {name}"
                        )
                    seen_service_methods[name] = method.name
                for name in client_method_names:
                    prior = seen_client_methods.get(name)
                    if prior is not None:
                        raise ValueError(
                            f"Scala gRPC method name collision in service "
                            f"{service.name}: {prior} and {method.name} both "
                            f"generate client method {name}"
                        )
                    seen_client_methods[name] = method.name

    def generate_scala_grpc_service_file(self, service: Service) -> GeneratedFile:
        imports = {"org.apache.fory.scala.rpc.{RpcFuture, RpcIterator}"}
        lines = self.source_header(imports)
        owner = self.scala_grpc_owner_name(service)
        lines.append(f"object {owner} {{")
        lines.append(
            f'    final val SERVICE_NAME: String = "{self.scala_string_literal(self.get_grpc_service_name(service))}"'
        )
        lines.append("")
        lines.append(
            "    private val FORY: org.apache.fory.ThreadSafeFory = "
            f"{self.get_module_name()}.getFory"
        )
        lines.append("")
        lines.extend(self.generate_scala_grpc_service_descriptor(service))
        for method in service.methods:
            lines.extend(self.generate_scala_grpc_method_descriptor(method))
        lines.extend(self.generate_scala_grpc_factories(service))
        lines.extend(self.generate_scala_grpc_service_base(service))
        lines.extend(self.generate_scala_grpc_client(service))
        lines.extend(self.generate_scala_grpc_marshaller())
        lines.append("}")
        return self.source_file(owner, lines)

    def generate_scala_grpc_service_descriptor(self, service: Service) -> List[str]:
        lines = [
            "    private lazy val serviceDescriptorValue: io.grpc.ServiceDescriptor =",
            "        io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)",
        ]
        for method in service.methods:
            lines.append(
                f"            .addMethod({self.scala_grpc_method_descriptor(method)})"
            )
        lines.extend(
            [
                "            .build()",
                "",
                "    def serviceDescriptor: io.grpc.ServiceDescriptor =",
                "        serviceDescriptorValue",
                "",
            ]
        )
        return lines

    def generate_scala_grpc_method_descriptor(self, method: RpcMethod) -> List[str]:
        request_type = self.scala_grpc_type(method.request_type)
        response_type = self.scala_grpc_type(method.response_type)
        descriptor = self.scala_grpc_method_descriptor(method)
        backing_field = self.scala_grpc_method_backing_field(method)
        method_type = self.scala_grpc_method_type(method)
        return [
            f"    private lazy val {backing_field}: io.grpc.MethodDescriptor[{request_type}, {response_type}] =",
            "        io.grpc.MethodDescriptor",
            f"            .newBuilder[{request_type}, {response_type}]()",
            f"            .setType(io.grpc.MethodDescriptor.MethodType.{method_type})",
            "            .setFullMethodName(",
            "                io.grpc.MethodDescriptor.generateFullMethodName(",
            "                    SERVICE_NAME,",
            f'                    "{self.scala_string_literal(method.name)}"))',
            "            .setSampledToLocalTracing(true)",
            f"            .setRequestMarshaller(marshaller(classOf[{request_type}]))",
            f"            .setResponseMarshaller(marshaller(classOf[{response_type}]))",
            "            .build()",
            "",
            f"    def {descriptor}: io.grpc.MethodDescriptor[{request_type}, {response_type}] =",
            f"        {backing_field}",
            "",
        ]

    def generate_scala_grpc_factories(self, service: Service) -> List[str]:
        client = self.scala_grpc_client_name(service)
        return [
            f"    def newClient(channel: io.grpc.Channel): {client} =",
            f"        new {client}(channel, io.grpc.CallOptions.DEFAULT)",
            "",
            "    def newClient(",
            "        channel: io.grpc.Channel,",
            f"        callOptions: io.grpc.CallOptions): {client} =",
            f"        new {client}(channel, callOptions)",
            "",
        ]

    def generate_scala_grpc_service_base(self, service: Service) -> List[str]:
        base_name = self.scala_grpc_service_base_name(service)
        lines = [f"    abstract class {base_name} extends io.grpc.BindableService {{"]
        for method in service.methods:
            lines.extend(self.generate_scala_grpc_server_method(method))
        lines.extend(self.generate_scala_grpc_bind_service(service))
        lines.append("    }")
        lines.append("")
        return lines

    def generate_scala_grpc_server_method(self, method: RpcMethod) -> List[str]:
        method_name = self.safe_identifier(self.scala_grpc_method_name(method))
        request_type = self.scala_grpc_type(method.request_type)
        response_type = self.scala_grpc_type(method.response_type)
        descriptor = self.scala_grpc_method_descriptor(method)
        mode = streaming_mode(method)
        lines: List[str] = [""]
        if mode is StreamingMode.UNARY:
            lines.extend(
                [
                    f"        def {method_name}(request: {request_type}): {response_type} =",
                    f'            throw unimplemented("{self.scala_string_literal(method.name)}")',
                    "",
                    "        def "
                    f"{method_name}(request: {request_type}, "
                    f"responseObserver: io.grpc.stub.StreamObserver[{response_type}]): Unit = {{",
                    "            try {",
                    f"                val response = {method_name}(request)",
                    "                responseObserver.onNext(response)",
                    "                responseObserver.onCompleted()",
                    "            } catch {",
                    "                case e: io.grpc.StatusException =>",
                    "                    responseObserver.onError(e)",
                    "                case e: io.grpc.StatusRuntimeException =>",
                    "                    responseObserver.onError(e)",
                    "                case scala.util.control.NonFatal(e) =>",
                    "                    responseObserver.onError(",
                    f'                        internalError("Scala gRPC method {self.scala_string_literal(method.name)} failed", e))',
                    "            }",
                    "        }",
                ]
            )
        elif mode is StreamingMode.SERVER_STREAMING:
            lines.extend(
                [
                    "        def "
                    f"{method_name}(request: {request_type}, "
                    f"responseObserver: io.grpc.stub.StreamObserver[{response_type}]): Unit =",
                    "            io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(",
                    f"                {descriptor}, responseObserver)",
                ]
            )
        else:
            lines.extend(
                [
                    f"        def {method_name}(",
                    f"            responseObserver: io.grpc.stub.StreamObserver[{response_type}]",
                    f"        ): io.grpc.stub.StreamObserver[{request_type}] =",
                    "            io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(",
                    f"                {descriptor}, responseObserver)",
                ]
            )
        return lines

    def generate_scala_grpc_bind_service(self, service: Service) -> List[str]:
        lines = [
            "",
            "        final override def bindService(): io.grpc.ServerServiceDefinition = {",
            "            val builder = io.grpc.ServerServiceDefinition.builder(serviceDescriptor)",
        ]
        for method in service.methods:
            lines.extend(self.generate_scala_grpc_bind_method(service, method))
        lines.extend(["            builder.build()", "        }"])
        return lines

    def generate_scala_grpc_bind_method(
        self, service: Service, method: RpcMethod
    ) -> List[str]:
        base_name = self.scala_grpc_service_base_name(service)
        method_name = self.safe_identifier(self.scala_grpc_method_name(method))
        request_type = self.scala_grpc_type(method.request_type)
        response_type = self.scala_grpc_type(method.response_type)
        descriptor = self.scala_grpc_method_descriptor(method)
        mode = streaming_mode(method)
        if mode is StreamingMode.UNARY:
            call = "asyncUnaryCall"
            trait_name = "UnaryMethod"
            invoke = [
                f"                            {base_name}.this.{method_name}(request, responseObserver)",
            ]
        elif mode is StreamingMode.SERVER_STREAMING:
            call = "asyncServerStreamingCall"
            trait_name = "ServerStreamingMethod"
            invoke = [
                f"                            {base_name}.this.{method_name}(request, responseObserver)",
            ]
        elif mode is StreamingMode.CLIENT_STREAMING:
            call = "asyncClientStreamingCall"
            trait_name = "ClientStreamingMethod"
            invoke = [
                f"                            {base_name}.this.{method_name}(responseObserver)",
            ]
        else:
            call = "asyncBidiStreamingCall"
            trait_name = "BidiStreamingMethod"
            invoke = [
                f"                            {base_name}.this.{method_name}(responseObserver)",
            ]
        if mode in (StreamingMode.UNARY, StreamingMode.SERVER_STREAMING):
            return [
                "            builder.addMethod(",
                f"                {descriptor},",
                f"                io.grpc.stub.ServerCalls.{call}(",
                f"                    new io.grpc.stub.ServerCalls.{trait_name}[{request_type}, {response_type}] {{",
                "                        override def invoke(",
                f"                            request: {request_type},",
                f"                            responseObserver: io.grpc.stub.StreamObserver[{response_type}]): Unit =",
                *invoke,
                "                    }))",
                "",
            ]
        return [
            "            builder.addMethod(",
            f"                {descriptor},",
            f"                io.grpc.stub.ServerCalls.{call}(",
            f"                    new io.grpc.stub.ServerCalls.{trait_name}[{request_type}, {response_type}] {{",
            "                        override def invoke(",
            f"                            responseObserver: io.grpc.stub.StreamObserver[{response_type}]",
            f"                        ): io.grpc.stub.StreamObserver[{request_type}] =",
            *invoke,
            "                    }))",
            "",
        ]

    def generate_scala_grpc_client(self, service: Service) -> List[str]:
        client = self.scala_grpc_client_name(service)
        owner = self.scala_grpc_owner_name(service)
        lines = [
            f"    final class {client} private[{owner}] (",
            "        channel: io.grpc.Channel,",
            "        callOptions: io.grpc.CallOptions)",
            f"        extends io.grpc.stub.AbstractStub[{client}](channel, callOptions) {{",
            "        protected override def build(",
            "            channel: io.grpc.Channel,",
            f"            callOptions: io.grpc.CallOptions): {client} =",
            f"            new {client}(channel, callOptions)",
        ]
        for method in service.methods:
            lines.extend(self.generate_scala_grpc_client_method(method))
        lines.append("    }")
        lines.append("")
        return lines

    def generate_scala_grpc_client_method(self, method: RpcMethod) -> List[str]:
        method_name = self.safe_identifier(self.scala_grpc_method_name(method))
        request_type = self.scala_grpc_type(method.request_type)
        response_type = self.scala_grpc_type(method.response_type)
        descriptor = self.scala_grpc_method_descriptor(method)
        mode = streaming_mode(method)
        lines: List[str] = [""]
        if mode is StreamingMode.UNARY:
            future_method = self.scala_grpc_future_method_name(method)
            blocking_method = self.scala_grpc_blocking_method_name(method)
            lines.extend(
                [
                    f"        def {method_name}(request: {request_type}): RpcFuture[{response_type}] =",
                    "            new RpcFutureAdapter(",
                    "                io.grpc.stub.ClientCalls.futureUnaryCall(",
                    f"                    getChannel().newCall({descriptor}, getCallOptions()),",
                    "                    request))",
                    "",
                    "        def "
                    f"{method_name}(request: {request_type}, "
                    f"responseObserver: io.grpc.stub.StreamObserver[{response_type}]): Unit =",
                    "            io.grpc.stub.ClientCalls.asyncUnaryCall(",
                    f"                getChannel().newCall({descriptor}, getCallOptions()),",
                    "                request,",
                    "                responseObserver)",
                    "",
                    f"        def {blocking_method}(request: {request_type}): {response_type} =",
                    "            io.grpc.stub.ClientCalls.blockingUnaryCall(",
                    f"                getChannel(), {descriptor}, getCallOptions(), request)",
                    "",
                    f"        def {future_method}(",
                    f"            request: {request_type}): com.google.common.util.concurrent.ListenableFuture[{response_type}] =",
                    "            io.grpc.stub.ClientCalls.futureUnaryCall(",
                    f"                getChannel().newCall({descriptor}, getCallOptions()),",
                    "                request)",
                ]
            )
        elif mode is StreamingMode.SERVER_STREAMING:
            blocking_method = self.scala_grpc_blocking_method_name(method)
            lines.extend(
                [
                    f"        def {method_name}(request: {request_type}): RpcIterator[{response_type}] =",
                    "            new RpcIteratorAdapter(",
                    f"                getChannel().newCall({descriptor}, getCallOptions()),",
                    "                request)",
                    "",
                    "        def "
                    f"{method_name}(request: {request_type}, "
                    f"responseObserver: io.grpc.stub.StreamObserver[{response_type}]): Unit =",
                    "            io.grpc.stub.ClientCalls.asyncServerStreamingCall(",
                    f"                getChannel().newCall({descriptor}, getCallOptions()),",
                    "                request,",
                    "                responseObserver)",
                    "",
                    f"        def {blocking_method}(request: {request_type}): java.util.Iterator[{response_type}] =",
                    "            io.grpc.stub.ClientCalls.blockingServerStreamingCall(",
                    f"                getChannel(), {descriptor}, getCallOptions(), request)",
                ]
            )
        elif mode is StreamingMode.CLIENT_STREAMING:
            lines.extend(
                [
                    f"        def {method_name}(",
                    f"            responseObserver: io.grpc.stub.StreamObserver[{response_type}]",
                    f"        ): io.grpc.stub.StreamObserver[{request_type}] =",
                    "            io.grpc.stub.ClientCalls.asyncClientStreamingCall(",
                    f"                getChannel().newCall({descriptor}, getCallOptions()),",
                    "                responseObserver)",
                ]
            )
        else:
            lines.extend(
                [
                    f"        def {method_name}(",
                    f"            responseObserver: io.grpc.stub.StreamObserver[{response_type}]",
                    f"        ): io.grpc.stub.StreamObserver[{request_type}] =",
                    "            io.grpc.stub.ClientCalls.asyncBidiStreamingCall(",
                    f"                getChannel().newCall({descriptor}, getCallOptions()),",
                    "                responseObserver)",
                ]
            )
        return lines

    def generate_scala_grpc_marshaller(self) -> List[str]:
        return [
            "    private final class RpcFutureAdapter[A](",
            "        delegate: com.google.common.util.concurrent.ListenableFuture[A])",
            "        extends RpcFuture[A] {",
            "        @volatile private var futureView: scala.concurrent.Future[A] = null",
            "",
            "        override def asFuture: scala.concurrent.Future[A] = {",
            "            var local = futureView",
            "            if (local eq null) {",
            "                this.synchronized {",
            "                    local = futureView",
            "                    if (local eq null) {",
            "                        val promise = scala.concurrent.Promise[A]()",
            "                        delegate.addListener(",
            "                            new Runnable {",
            "                                override def run(): Unit =",
            "                                    completePromise(delegate, promise)",
            "                            },",
            "                            com.google.common.util.concurrent.MoreExecutors.directExecutor())",
            "                        local = promise.future",
            "                        futureView = local",
            "                    }",
            "                }",
            "            }",
            "            local",
            "        }",
            "",
            "        override def cancel(): Boolean = delegate.cancel(true)",
            "",
            "        override def isCancelled: Boolean = delegate.isCancelled",
            "",
            "        override def isDone: Boolean = delegate.isDone",
            "    }",
            "",
            "    private final class RpcIteratorAdapter[ReqT <: AnyRef, RespT <: AnyRef](",
            "        call: io.grpc.ClientCall[ReqT, RespT],",
            "        request: ReqT)",
            "        extends RpcIterator[RespT] {",
            "        private[this] val lock = new Object",
            "        private[this] var nextValue: RespT = null.asInstanceOf[RespT]",
            "        private[this] var hasValue = false",
            "        private[this] var completed = false",
            "        private[this] var closed = false",
            "        private[this] var cancelled = false",
            "        private[this] var failure: Throwable = null",
            "",
            "        start(request)",
            "",
            "        override def hasNext: Boolean = {",
            "                var cancelCall = false",
            "                var thrown: Throwable = null",
            "                val result = lock.synchronized {",
            "                    cancelCall = awaitValueLocked()",
            "                    if (hasValue) true",
            "                    else if (failure ne null) {",
            "                        thrown = failure",
            "                        false",
            "                    } else false",
            "                }",
            "                if (cancelCall) {",
            '                    call.cancel("RpcIterator interrupted", thrown)',
            "                }",
            "                if (thrown ne null) {",
            "                    throw thrown",
            "                }",
            "                result",
            "            }",
            "",
            "        override def next(): RespT = {",
            "            var requestMore = false",
            "            var cancelCall = false",
            "            var thrown: Throwable = null",
            "            var empty = false",
            "            val result = lock.synchronized {",
            "                cancelCall = awaitValueLocked()",
            "                if (!hasValue) {",
            "                    if (failure ne null) {",
            "                        thrown = failure",
            "                    } else {",
            "                        empty = true",
            "                    }",
            "                    null.asInstanceOf[RespT]",
            "                } else {",
            "                    val value = nextValue",
            "                    nextValue = null.asInstanceOf[RespT]",
            "                    hasValue = false",
            "                    requestMore = !closed && (failure eq null)",
            "                    value",
            "                }",
            "            }",
            "            if (cancelCall) {",
            '                call.cancel("RpcIterator interrupted", thrown)',
            "            }",
            "            if (thrown ne null) {",
            "                throw thrown",
            "            }",
            "            if (empty) {",
            '                throw new NoSuchElementException("next on empty RpcIterator")',
            "            }",
            "            if (requestMore) {",
            "                call.request(1)",
            "            }",
            "            result",
            "        }",
            "",
            "        override def cancel(): Unit = {",
            "            var shouldCancel = false",
            "            lock.synchronized {",
            "                if (!cancelled && !completed) {",
            "                    cancelled = true",
            "                    shouldCancel = true",
            "                }",
            "                if (!closed) {",
            "                    closed = true",
            "                    hasValue = false",
            "                    nextValue = null.asInstanceOf[RespT]",
            "                    lock.notifyAll()",
            "                }",
            "            }",
            "            if (shouldCancel) {",
            '                call.cancel("RpcIterator cancelled", null)',
            "            }",
            "        }",
            "",
            "        override def isClosed: Boolean =",
            "            lock.synchronized { closed }",
            "",
            "        private def start(request: ReqT): Unit = {",
            "            call.start(",
            "                new io.grpc.ClientCall.Listener[RespT] {",
            "                    override def onMessage(message: RespT): Unit =",
            "                        lock.synchronized {",
            "                            if (!closed) {",
            "                                if (hasValue) {",
            "                                    failure = io.grpc.Status.INTERNAL",
            '                                        .withDescription("Received response without iterator demand")',
            "                                        .asRuntimeException()",
            "                                    closed = true",
            "                                } else {",
            "                                    nextValue = message",
            "                                    hasValue = true",
            "                                }",
            "                                lock.notifyAll()",
            "                            }",
            "                        }",
            "",
            "                    override def onClose(",
            "                        status: io.grpc.Status,",
            "                        trailers: io.grpc.Metadata): Unit =",
            "                        lock.synchronized {",
            "                            if (!status.isOk && !cancelled) {",
            "                                failure = status.asRuntimeException(trailers)",
            "                            } else {",
            "                                completed = true",
            "                            }",
            "                            closed = true",
            "                            lock.notifyAll()",
            "                        }",
            "                },",
            "                new io.grpc.Metadata())",
            "            call.request(1)",
            "            call.sendMessage(request)",
            "            call.halfClose()",
            "        }",
            "",
            "        private def awaitValueLocked(): Boolean = {",
            "            var cancelCall = false",
            "            while (!hasValue && !completed && (failure eq null) && !closed) {",
            "                try {",
            "                    lock.wait()",
            "                } catch {",
            "                    case e: InterruptedException =>",
            "                        failure = interrupted(e)",
            "                        closed = true",
            "                        cancelled = true",
            "                        cancelCall = true",
            "                }",
            "            }",
            "            cancelCall",
            "        }",
            "    }",
            "",
            "    private def completePromise[A](",
            "        delegate: com.google.common.util.concurrent.ListenableFuture[A],",
            "        promise: scala.concurrent.Promise[A]): Unit =",
            "        try {",
            "            promise.success(delegate.get())",
            "        } catch {",
            "            case e: java.util.concurrent.CancellationException =>",
            "                promise.failure(e)",
            "            case e: java.util.concurrent.ExecutionException if e.getCause ne null =>",
            "                promise.failure(e.getCause)",
            "            case e: InterruptedException =>",
            "                Thread.currentThread().interrupt()",
            "                promise.failure(e)",
            "            case scala.util.control.NonFatal(e) =>",
            "                promise.failure(e)",
            "        }",
            "",
            "    private def marshaller[T <: AnyRef](",
            "        typ: Class[T]): io.grpc.MethodDescriptor.Marshaller[T] =",
            "        new ForyMarshaller(FORY, typ)",
            "",
            "    private final class ForyMarshaller[T <: AnyRef](",
            "        fory: org.apache.fory.ThreadSafeFory,",
            "        typ: Class[T])",
            "        extends io.grpc.MethodDescriptor.Marshaller[T] {",
            "        override def stream(value: T): java.io.InputStream =",
            "            try {",
            "                new KnownLengthByteArrayInputStream(fory.serialize(value))",
            "            } catch {",
            "                case scala.util.control.NonFatal(e) =>",
            '                    throw internalError("Fory serialization failed", e)',
            "            }",
            "",
            "        override def parse(stream: java.io.InputStream): T =",
            "            try {",
            "                fory.deserialize(readBytes(stream), typ)",
            "            } catch {",
            "                case e: java.io.IOException =>",
            '                    throw internalError("Fory deserialization failed", e)',
            "                case scala.util.control.NonFatal(e) =>",
            '                    throw internalError("Fory deserialization failed", e)',
            "            }",
            "    }",
            "",
            "    private final class KnownLengthByteArrayInputStream(bytes: Array[Byte])",
            "        extends java.io.ByteArrayInputStream(bytes)",
            "        with io.grpc.KnownLength",
            "",
            "    private def readBytes(stream: java.io.InputStream): Array[Byte] = {",
            "        if (stream.isInstanceOf[io.grpc.KnownLength]) {",
            "            val size = stream.available()",
            "            val bytes = new Array[Byte](size)",
            "            var offset = 0",
            "            while (offset < size) {",
            "                val read = stream.read(bytes, offset, size - offset)",
            "                if (read == -1) {",
            "                    throw new java.io.EOFException(",
            '                        "Expected " + size + " bytes, got " + offset)',
            "                }",
            "                offset += read",
            "            }",
            "            bytes",
            "        } else {",
            "            readUnknownLengthBytes(stream)",
            "        }",
            "    }",
            "",
            "    private def readUnknownLengthBytes(stream: java.io.InputStream): Array[Byte] = {",
            "        val out = new java.io.ByteArrayOutputStream()",
            "        val buffer = new Array[Byte](8192)",
            "        var read = stream.read(buffer)",
            "        while (read != -1) {",
            "            out.write(buffer, 0, read)",
            "            read = stream.read(buffer)",
            "        }",
            "        out.toByteArray()",
            "    }",
            "",
            "    private def internalError(",
            "        description: String,",
            "        cause: Throwable): io.grpc.StatusRuntimeException =",
            "        io.grpc.Status.INTERNAL",
            "            .withDescription(description)",
            "            .withCause(cause)",
            "            .asRuntimeException()",
            "",
            "    private def interrupted(e: InterruptedException): io.grpc.StatusRuntimeException = {",
            "        Thread.currentThread().interrupt()",
            "        io.grpc.Status.CANCELLED",
            '            .withDescription("Interrupted while waiting for RPC response")',
            "            .withCause(e)",
            "            .asRuntimeException()",
            "    }",
            "",
            "    private def unimplemented(methodName: String): io.grpc.StatusRuntimeException =",
            "        io.grpc.Status.UNIMPLEMENTED",
            '            .withDescription("Method " + SERVICE_NAME + "/" + methodName + " is unimplemented")',
            "            .asRuntimeException()",
            "",
        ]

    def scala_grpc_type(self, named_type: NamedType) -> str:
        type_name = self.schema.resolve_type_name(named_type.name)
        return self.resolve_scala_type_name(type_name, None)

    def scala_grpc_owner_name(self, service: Service) -> str:
        return f"{service.name}Grpc"

    def scala_grpc_service_base_name(self, service: Service) -> str:
        return f"{service.name}ImplBase"

    def scala_grpc_client_name(self, service: Service) -> str:
        return f"{service.name}Client"

    def scala_grpc_method_name(self, method: RpcMethod) -> str:
        return self.to_camel_case(method.name)

    def scala_grpc_method_descriptor(self, method: RpcMethod) -> str:
        return f"{self.scala_grpc_method_name(method)}Method"

    def scala_grpc_method_backing_field(self, method: RpcMethod) -> str:
        return f"{self.scala_grpc_method_name(method)}MethodValue"

    def scala_grpc_blocking_method_name(self, method: RpcMethod) -> str:
        return f"{self.scala_grpc_method_name(method)}Blocking"

    def scala_grpc_future_method_name(self, method: RpcMethod) -> str:
        return f"{self.scala_grpc_method_name(method)}Future"

    def scala_grpc_method_type(self, method: RpcMethod) -> str:
        mode = streaming_mode(method)
        if mode is StreamingMode.BIDIRECTIONAL:
            return "BIDI_STREAMING"
        if mode is StreamingMode.CLIENT_STREAMING:
            return "CLIENT_STREAMING"
        if mode is StreamingMode.SERVER_STREAMING:
            return "SERVER_STREAMING"
        return "UNARY"

    def scala_string_literal(self, value: str) -> str:
        return (
            value.replace("\\", "\\\\")
            .replace('"', '\\"')
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        )
