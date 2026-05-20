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

"""Java gRPC service generator helpers."""

from typing import List, Optional, Set

from fory_compiler.generators.base import GeneratedFile
from fory_compiler.ir.ast import NamedType, RpcMethod, Schema, Service


class JavaServiceGeneratorMixin:
    """Generates Java gRPC service companions."""

    def generate_services(self) -> List[GeneratedFile]:
        """Generate Java gRPC service classes for local service definitions."""
        local_services = [
            service
            for service in self.schema.services
            if not self.is_imported_type(service)
        ]
        if not local_services:
            return []

        outer_classname = self.get_java_outer_classname()
        if self.get_java_multiple_files():
            outer_classname = None
        self.check_java_grpc_service_collisions(local_services, outer_classname)
        self.check_java_grpc_method_collisions(local_services)
        return [
            self.generate_grpc_service_file(service, outer_classname)
            for service in local_services
        ]

    def check_java_grpc_service_collisions(
        self, services: List[Service], outer_classname: Optional[str]
    ) -> None:
        generated_type_names = self.same_package_imported_java_file_names()
        if outer_classname:
            generated_type_names.add(outer_classname)
        else:
            generated_type_names.update(
                enum.name
                for enum in self.schema.enums
                if not self.is_imported_type(enum)
            )
            generated_type_names.update(
                union.name
                for union in self.schema.unions
                if not self.is_imported_type(union)
            )
            generated_type_names.update(
                message.name
                for message in self.schema.messages
                if not self.is_imported_type(message)
            )
        for service in services:
            class_name = f"{service.name}Grpc"
            if class_name in generated_type_names:
                raise ValueError(
                    f"Java gRPC service class {class_name} conflicts with a generated type; "
                    "rename the service or type"
                )

    def same_package_imported_java_file_names(self) -> Set[str]:
        java_package = self.get_java_package()
        generated_names: Set[str] = set()
        for _source, schema in self._schema_graph()[1:]:
            if self._java_package_for_schema(schema) != java_package:
                continue
            generated_names.update(self.java_type_file_names(schema))
            generated_names.update(f"{service.name}Grpc" for service in schema.services)
        return generated_names

    def java_type_file_names(self, schema: Schema) -> Set[str]:
        outer_classname = schema.get_option("java_outer_classname")
        multiple_files = schema.get_option("java_multiple_files") is True
        if outer_classname and not multiple_files:
            return {outer_classname}
        return {
            type_def.name
            for type_def in [*schema.enums, *schema.unions, *schema.messages]
        }

    def check_java_grpc_method_collisions(self, services: List[Service]) -> None:
        for service in services:
            seen_descriptors = {}
            seen_methods = {}
            seen_ids = {}
            for method in service.methods:
                descriptor = f"get{self.to_pascal_case(method.name)}Method"
                java_method = self.java_grpc_method_name(method)
                method_id = f"METHODID_{self.to_upper_snake_case(method.name)}"
                for seen, key, label in (
                    (seen_descriptors, descriptor, "method descriptor"),
                    (seen_methods, java_method, "Java method"),
                    (seen_ids, method_id, "method id"),
                ):
                    if key in seen:
                        raise ValueError(
                            f"Java gRPC {label} name collision in service {service.name}: "
                            f"{seen[key]} and {method.name} both generate {key}"
                        )
                    seen[key] = method.name

    def generate_grpc_service_file(
        self, service: Service, outer_classname: Optional[str]
    ) -> GeneratedFile:
        """Generate one grpc-java-style service class."""
        java_package = self.get_java_package()
        class_name = f"{service.name}Grpc"
        lines: List[str] = []

        lines.append(self.get_license_header())
        lines.append("")
        if java_package:
            lines.append(f"package {java_package};")
            lines.append("")

        imports = [
            "java.io.ByteArrayInputStream",
            "java.io.ByteArrayOutputStream",
            "java.io.IOException",
            "java.io.InputStream",
            "java.util.Iterator",
            "org.apache.fory.ThreadSafeFory",
        ]
        for imp in imports:
            lines.append(f"import {imp};")
        lines.append("")

        lines.append(f"public final class {class_name} {{")
        lines.append(f"    private {class_name}() {{")
        lines.append("    }")
        lines.append("")
        lines.append(
            f'    public static final String SERVICE_NAME = "{self.get_grpc_service_name(service)}";'
        )
        lines.append("")
        lines.append(
            f"    private static final ThreadSafeFory FORY = {self.get_module_class_name()}.getFory();"
        )
        lines.append("")

        for method in service.methods:
            lines.extend(
                self.generate_java_grpc_method_descriptor(
                    method, class_name, outer_classname
                )
            )

        lines.extend(self.generate_java_grpc_stub_factories(service))
        lines.extend(self.generate_java_grpc_service_base(service, outer_classname))
        lines.extend(self.generate_java_grpc_async_stub(service, outer_classname))
        lines.extend(self.generate_java_grpc_blocking_stub(service, outer_classname))
        lines.extend(self.generate_java_grpc_future_stub(service, outer_classname))
        lines.extend(self.generate_java_grpc_bind_service(service, outer_classname))
        lines.extend(self.generate_java_grpc_method_handlers(service, outer_classname))
        lines.extend(self.generate_java_grpc_marshaller())

        lines.append("}")
        lines.append("")

        path = self.get_java_package_path()
        if path:
            path = f"{path}/{class_name}.java"
        else:
            path = f"{class_name}.java"
        return GeneratedFile(path=path, content="\n".join(lines))

    def generate_java_grpc_method_descriptor(
        self, method: RpcMethod, class_name: str, outer_classname: Optional[str]
    ) -> List[str]:
        request_type = self.generate_java_grpc_type(
            method.request_type, outer_classname
        )
        response_type = self.generate_java_grpc_type(
            method.response_type, outer_classname
        )
        method_suffix = self.to_pascal_case(method.name)
        method_field = f"get{method_suffix}Method"
        method_type = self.grpc_java_method_type(method)
        lines = []
        lines.append(
            f"    private static volatile io.grpc.MethodDescriptor<{request_type}, {response_type}> {method_field};"
        )
        lines.append("")
        lines.append(
            f"    public static io.grpc.MethodDescriptor<{request_type}, {response_type}> {method_field}() {{"
        )
        lines.append(
            f"        io.grpc.MethodDescriptor<{request_type}, {response_type}> local = {method_field};"
        )
        lines.append("        if (local == null) {")
        lines.append(f"            synchronized ({class_name}.class) {{")
        lines.append(f"                local = {method_field};")
        lines.append("                if (local == null) {")
        lines.append(
            "                    local = io.grpc.MethodDescriptor.<"
            f"{request_type}, {response_type}>newBuilder()"
        )
        lines.append(
            f"                        .setType(io.grpc.MethodDescriptor.MethodType.{method_type})"
        )
        lines.append(
            "                        .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName("
        )
        lines.append(f'                            SERVICE_NAME, "{method.name}"))')
        lines.append("                        .setSampledToLocalTracing(true)")
        lines.append(
            f"                        .setRequestMarshaller(marshaller({request_type}.class))"
        )
        lines.append(
            f"                        .setResponseMarshaller(marshaller({response_type}.class))"
        )
        lines.append("                        .build();")
        lines.append(f"                    {method_field} = local;")
        lines.append("                }")
        lines.append("            }")
        lines.append("        }")
        lines.append("        return local;")
        lines.append("    }")
        lines.append("")
        return lines

    def generate_java_grpc_stub_factories(self, service: Service) -> List[str]:
        lines = []
        lines.append(
            f"    public static {service.name}Stub newStub(io.grpc.Channel channel) {{"
        )
        lines.append(
            f"        io.grpc.stub.AbstractStub.StubFactory<{service.name}Stub> factory ="
        )
        lines.append(
            f"            new io.grpc.stub.AbstractStub.StubFactory<{service.name}Stub>() {{"
        )
        lines.append("                @Override")
        lines.append(
            f"                public {service.name}Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {{"
        )
        lines.append(
            f"                    return new {service.name}Stub(channel, callOptions);"
        )
        lines.append("                }")
        lines.append("            };")
        lines.append(f"        return {service.name}Stub.newStub(factory, channel);")
        lines.append("    }")
        lines.append("")
        lines.append(
            f"    public static {service.name}BlockingStub newBlockingStub(io.grpc.Channel channel) {{"
        )
        lines.append(
            f"        io.grpc.stub.AbstractStub.StubFactory<{service.name}BlockingStub> factory ="
        )
        lines.append(
            f"            new io.grpc.stub.AbstractStub.StubFactory<{service.name}BlockingStub>() {{"
        )
        lines.append("                @Override")
        lines.append(
            f"                public {service.name}BlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {{"
        )
        lines.append(
            f"                    return new {service.name}BlockingStub(channel, callOptions);"
        )
        lines.append("                }")
        lines.append("            };")
        lines.append(
            f"        return {service.name}BlockingStub.newStub(factory, channel);"
        )
        lines.append("    }")
        lines.append("")
        lines.append(
            f"    public static {service.name}FutureStub newFutureStub(io.grpc.Channel channel) {{"
        )
        lines.append(
            f"        io.grpc.stub.AbstractStub.StubFactory<{service.name}FutureStub> factory ="
        )
        lines.append(
            f"            new io.grpc.stub.AbstractStub.StubFactory<{service.name}FutureStub>() {{"
        )
        lines.append("                @Override")
        lines.append(
            f"                public {service.name}FutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {{"
        )
        lines.append(
            f"                    return new {service.name}FutureStub(channel, callOptions);"
        )
        lines.append("                }")
        lines.append("            };")
        lines.append(
            f"        return {service.name}FutureStub.newStub(factory, channel);"
        )
        lines.append("    }")
        lines.append("")
        return lines

    def generate_java_grpc_service_base(
        self, service: Service, outer_classname: Optional[str]
    ) -> List[str]:
        lines = []
        lines.append(
            f"    public abstract static class {service.name}ImplBase implements io.grpc.BindableService {{"
        )
        for method in service.methods:
            java_name = self.java_grpc_method_name(method)
            request_type = self.generate_java_grpc_type(
                method.request_type, outer_classname
            )
            response_type = self.generate_java_grpc_type(
                method.response_type, outer_classname
            )
            method_getter = f"get{self.to_pascal_case(method.name)}Method()"
            lines.append("")
            if method.client_streaming:
                lines.append(
                    f"        public io.grpc.stub.StreamObserver<{request_type}> {java_name}("
                )
                lines.append(
                    f"                io.grpc.stub.StreamObserver<{response_type}> responseObserver) {{"
                )
                lines.append(
                    f"            return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall({method_getter}, responseObserver);"
                )
                lines.append("        }")
            else:
                lines.append(f"        public void {java_name}({request_type} request,")
                lines.append(
                    f"                io.grpc.stub.StreamObserver<{response_type}> responseObserver) {{"
                )
                lines.append(
                    f"            io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall({method_getter}, responseObserver);"
                )
                lines.append("        }")
        lines.append("")
        lines.append("        @Override")
        lines.append(
            "        public final io.grpc.ServerServiceDefinition bindService() {"
        )
        lines.append(f"            return {service.name}Grpc.bindService(this);")
        lines.append("        }")
        lines.append("    }")
        lines.append("")
        return lines

    def generate_java_grpc_async_stub(
        self, service: Service, outer_classname: Optional[str]
    ) -> List[str]:
        lines = []
        lines.append(
            f"    public static final class {service.name}Stub extends io.grpc.stub.AbstractAsyncStub<{service.name}Stub> {{"
        )
        lines.append(
            f"        private {service.name}Stub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {{"
        )
        lines.append("            super(channel, callOptions);")
        lines.append("        }")
        lines.append("")
        lines.append("        @Override")
        lines.append(
            f"        protected {service.name}Stub build(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {{"
        )
        lines.append(
            f"            return new {service.name}Stub(channel, callOptions);"
        )
        lines.append("        }")
        for method in service.methods:
            lines.extend(
                self.generate_java_grpc_client_method(method, outer_classname, "async")
            )
        lines.append("    }")
        lines.append("")
        return lines

    def generate_java_grpc_blocking_stub(
        self, service: Service, outer_classname: Optional[str]
    ) -> List[str]:
        lines = []
        lines.append(
            f"    public static final class {service.name}BlockingStub extends io.grpc.stub.AbstractBlockingStub<{service.name}BlockingStub> {{"
        )
        lines.append(
            f"        private {service.name}BlockingStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {{"
        )
        lines.append("            super(channel, callOptions);")
        lines.append("        }")
        lines.append("")
        lines.append("        @Override")
        lines.append(
            f"        protected {service.name}BlockingStub build(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {{"
        )
        lines.append(
            f"            return new {service.name}BlockingStub(channel, callOptions);"
        )
        lines.append("        }")
        for method in service.methods:
            if method.client_streaming:
                continue
            lines.extend(
                self.generate_java_grpc_client_method(
                    method, outer_classname, "blocking"
                )
            )
        lines.append("    }")
        lines.append("")
        return lines

    def generate_java_grpc_future_stub(
        self, service: Service, outer_classname: Optional[str]
    ) -> List[str]:
        lines = []
        lines.append(
            f"    public static final class {service.name}FutureStub extends io.grpc.stub.AbstractFutureStub<{service.name}FutureStub> {{"
        )
        lines.append(
            f"        private {service.name}FutureStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {{"
        )
        lines.append("            super(channel, callOptions);")
        lines.append("        }")
        lines.append("")
        lines.append("        @Override")
        lines.append(
            f"        protected {service.name}FutureStub build(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {{"
        )
        lines.append(
            f"            return new {service.name}FutureStub(channel, callOptions);"
        )
        lines.append("        }")
        for method in service.methods:
            if method.client_streaming or method.server_streaming:
                continue
            lines.extend(
                self.generate_java_grpc_client_method(method, outer_classname, "future")
            )
        lines.append("    }")
        lines.append("")
        return lines

    def generate_java_grpc_client_method(
        self, method: RpcMethod, outer_classname: Optional[str], stub_kind: str
    ) -> List[str]:
        java_name = self.java_grpc_method_name(method)
        request_type = self.generate_java_grpc_type(
            method.request_type, outer_classname
        )
        response_type = self.generate_java_grpc_type(
            method.response_type, outer_classname
        )
        method_getter = f"get{self.to_pascal_case(method.name)}Method()"
        lines = [
            "",
        ]
        if stub_kind == "async":
            if method.client_streaming:
                lines.append(
                    f"        public io.grpc.stub.StreamObserver<{request_type}> {java_name}("
                )
                lines.append(
                    f"                io.grpc.stub.StreamObserver<{response_type}> responseObserver) {{"
                )
                call = (
                    "asyncBidiStreamingCall"
                    if method.server_streaming
                    else "asyncClientStreamingCall"
                )
                lines.append(
                    f"            return io.grpc.stub.ClientCalls.{call}("
                    f"getChannel().newCall({method_getter}, getCallOptions()), responseObserver);"
                )
                lines.append("        }")
            else:
                lines.append(f"        public void {java_name}({request_type} request,")
                lines.append(
                    f"                io.grpc.stub.StreamObserver<{response_type}> responseObserver) {{"
                )
                call = (
                    "asyncServerStreamingCall"
                    if method.server_streaming
                    else "asyncUnaryCall"
                )
                lines.append(
                    f"            io.grpc.stub.ClientCalls.{call}("
                    f"getChannel().newCall({method_getter}, getCallOptions()), request, responseObserver);"
                )
                lines.append("        }")
        elif stub_kind == "blocking":
            if method.server_streaming:
                lines.append(
                    f"        public Iterator<{response_type}> {java_name}({request_type} request) {{"
                )
                lines.append(
                    f"            return io.grpc.stub.ClientCalls.blockingServerStreamingCall("
                    f"getChannel(), {method_getter}, getCallOptions(), request);"
                )
            else:
                lines.append(
                    f"        public {response_type} {java_name}({request_type} request) {{"
                )
                lines.append(
                    f"            return io.grpc.stub.ClientCalls.blockingUnaryCall("
                    f"getChannel(), {method_getter}, getCallOptions(), request);"
                )
            lines.append("        }")
        elif stub_kind == "future":
            lines.append(
                f"        public com.google.common.util.concurrent.ListenableFuture<{response_type}> {java_name}("
            )
            lines.append(f"                {request_type} request) {{")
            lines.append(
                f"            return io.grpc.stub.ClientCalls.futureUnaryCall("
                f"getChannel().newCall({method_getter}, getCallOptions()), request);"
            )
            lines.append("        }")
        return lines

    def generate_java_grpc_bind_service(
        self, service: Service, outer_classname: Optional[str]
    ) -> List[str]:
        lines = []
        lines.append(
            f"    private static io.grpc.ServerServiceDefinition bindService({service.name}ImplBase serviceImpl) {{"
        )
        lines.append(
            "        io.grpc.ServerServiceDefinition.Builder builder ="
            " io.grpc.ServerServiceDefinition.builder(SERVICE_NAME);"
        )
        for method in service.methods:
            request_type = self.generate_java_grpc_type(
                method.request_type, outer_classname
            )
            response_type = self.generate_java_grpc_type(
                method.response_type, outer_classname
            )
            method_getter = f"get{self.to_pascal_case(method.name)}Method()"
            method_id = f"METHODID_{self.to_upper_snake_case(method.name)}"
            if method.client_streaming and method.server_streaming:
                call = "asyncBidiStreamingCall"
            elif method.client_streaming:
                call = "asyncClientStreamingCall"
            elif method.server_streaming:
                call = "asyncServerStreamingCall"
            else:
                call = "asyncUnaryCall"
            lines.append(
                f"        builder.addMethod({method_getter}, io.grpc.stub.ServerCalls.{call}("
            )
            lines.append(
                f"            new MethodHandlers<{request_type}, {response_type}>(serviceImpl, {method_id})));"
            )
        lines.append("        return builder.build();")
        lines.append("    }")
        lines.append("")
        return lines

    def generate_java_grpc_method_handlers(
        self, service: Service, outer_classname: Optional[str]
    ) -> List[str]:
        lines = []
        for index, method in enumerate(service.methods):
            lines.append(
                f"    private static final int METHODID_{self.to_upper_snake_case(method.name)} = {index};"
            )
        lines.append("")
        lines.append('    @SuppressWarnings("unchecked")')
        lines.append("    private static final class MethodHandlers<Req, Resp>")
        lines.append(
            "            implements io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,"
        )
        lines.append(
            "                io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,"
        )
        lines.append(
            "                io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,"
        )
        lines.append(
            "                io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {"
        )
        lines.append(f"        private final {service.name}ImplBase serviceImpl;")
        lines.append("        private final int methodId;")
        lines.append("")
        lines.append(
            f"        MethodHandlers({service.name}ImplBase serviceImpl, int methodId) {{"
        )
        lines.append("            this.serviceImpl = serviceImpl;")
        lines.append("            this.methodId = methodId;")
        lines.append("        }")
        lines.append("")
        lines.append("        @Override")
        lines.append(
            "        public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {"
        )
        lines.append("            switch (methodId) {")
        for method in service.methods:
            if method.client_streaming:
                continue
            java_name = self.java_grpc_method_name(method)
            request_type = self.generate_java_grpc_type(
                method.request_type, outer_classname
            )
            response_type = self.generate_java_grpc_type(
                method.response_type, outer_classname
            )
            method_id = f"METHODID_{self.to_upper_snake_case(method.name)}"
            lines.append(f"                case {method_id}:")
            lines.append(
                f"                    serviceImpl.{java_name}(({request_type}) request,"
            )
            lines.append(
                f"                        (io.grpc.stub.StreamObserver<{response_type}>) responseObserver);"
            )
            lines.append("                    break;")
        lines.append("                default:")
        lines.append("                    throw new AssertionError();")
        lines.append("            }")
        lines.append("        }")
        lines.append("")
        lines.append("        @Override")
        lines.append("        public io.grpc.stub.StreamObserver<Req> invoke(")
        lines.append(
            "                io.grpc.stub.StreamObserver<Resp> responseObserver) {"
        )
        lines.append("            switch (methodId) {")
        for method in service.methods:
            if not method.client_streaming:
                continue
            java_name = self.java_grpc_method_name(method)
            request_type = self.generate_java_grpc_type(
                method.request_type, outer_classname
            )
            response_type = self.generate_java_grpc_type(
                method.response_type, outer_classname
            )
            method_id = f"METHODID_{self.to_upper_snake_case(method.name)}"
            lines.append(f"                case {method_id}:")
            lines.append(
                f"                    return (io.grpc.stub.StreamObserver<Req>) serviceImpl.{java_name}("
            )
            lines.append(
                f"                        (io.grpc.stub.StreamObserver<{response_type}>) responseObserver);"
            )
        lines.append("                default:")
        lines.append("                    throw new AssertionError();")
        lines.append("            }")
        lines.append("        }")
        lines.append("    }")
        lines.append("")
        return lines

    def generate_java_grpc_marshaller(self) -> List[str]:
        lines = []
        lines.append(
            "    private static <T> io.grpc.MethodDescriptor.Marshaller<T> marshaller(Class<T> type) {"
        )
        lines.append("        return new ForyMarshaller<T>(FORY, type);")
        lines.append("    }")
        lines.append("")
        lines.append(
            "    private static final class ForyMarshaller<T> implements io.grpc.MethodDescriptor.Marshaller<T> {"
        )
        lines.append("        private final ThreadSafeFory fory;")
        lines.append("        private final Class<T> type;")
        lines.append("")
        lines.append("        ForyMarshaller(ThreadSafeFory fory, Class<T> type) {")
        lines.append("            this.fory = fory;")
        lines.append("            this.type = type;")
        lines.append("        }")
        lines.append("")
        lines.append("        @Override")
        lines.append("        public InputStream stream(T value) {")
        lines.append("            try {")
        lines.append(
            "                return new KnownLengthByteArrayInputStream(fory.serialize(value));"
        )
        lines.append("            } catch (RuntimeException e) {")
        lines.append(
            '                throw io.grpc.Status.INTERNAL.withDescription("Fory serialization failed")'
        )
        lines.append("                    .withCause(e).asRuntimeException();")
        lines.append("            }")
        lines.append("        }")
        lines.append("")
        lines.append("        @Override")
        lines.append("        public T parse(InputStream stream) {")
        lines.append("            try {")
        lines.append(
            "                return fory.deserialize(readBytes(stream), type);"
        )
        lines.append("            } catch (IOException | RuntimeException e) {")
        lines.append(
            '                throw io.grpc.Status.INTERNAL.withDescription("Fory deserialization failed")'
        )
        lines.append("                    .withCause(e).asRuntimeException();")
        lines.append("            }")
        lines.append("        }")
        lines.append("    }")
        lines.append("")
        lines.append(
            "    private static final class KnownLengthByteArrayInputStream extends ByteArrayInputStream"
        )
        lines.append("            implements io.grpc.KnownLength {")
        lines.append("        KnownLengthByteArrayInputStream(byte[] bytes) {")
        lines.append("            super(bytes);")
        lines.append("        }")
        lines.append("    }")
        lines.append("")
        lines.append(
            "    private static byte[] readBytes(InputStream stream) throws IOException {"
        )
        lines.append("        if (stream instanceof io.grpc.KnownLength) {")
        lines.append("            int size = stream.available();")
        lines.append("            byte[] bytes = new byte[size];")
        lines.append("            int offset = 0;")
        lines.append("            while (offset < size) {")
        lines.append(
            "                int read = stream.read(bytes, offset, size - offset);"
        )
        lines.append("                if (read == -1) {")
        lines.append(
            '                    throw new java.io.EOFException("Expected " + size + " bytes, got " + offset);'
        )
        lines.append("                }")
        lines.append("                offset += read;")
        lines.append("            }")
        lines.append("            return bytes;")
        lines.append("        }")
        lines.append("        ByteArrayOutputStream out = new ByteArrayOutputStream();")
        lines.append("        byte[] buffer = new byte[8192];")
        lines.append("        int read;")
        lines.append("        while ((read = stream.read(buffer)) != -1) {")
        lines.append("            out.write(buffer, 0, read);")
        lines.append("        }")
        lines.append("        return out.toByteArray();")
        lines.append("    }")
        lines.append("")
        return lines

    def grpc_java_method_type(self, method: RpcMethod) -> str:
        if method.client_streaming and method.server_streaming:
            return "BIDI_STREAMING"
        if method.client_streaming:
            return "CLIENT_STREAMING"
        if method.server_streaming:
            return "SERVER_STREAMING"
        return "UNARY"

    def safe_java_identifier(self, identifier: str) -> str:
        if identifier in self.JAVA_RESERVED_IDENTIFIERS:
            return f"{identifier}_"
        return identifier

    def java_grpc_method_name(self, method: RpcMethod) -> str:
        return self.safe_java_identifier(self.to_camel_case(method.name))

    def generate_java_grpc_type(
        self, named_type: NamedType, outer_classname: Optional[str]
    ) -> str:
        type_ref = self.schema.resolve_type_name(named_type.name)
        type_def = self.schema.get_type(type_ref)
        if type_def is not None and self.is_imported_type(type_def):
            schema = self._load_schema(
                getattr(getattr(type_def, "location", None), "file", None)
            )
            if schema is not None:
                imported_outer = schema.get_option("java_outer_classname")
                multiple_files = schema.get_option("java_multiple_files") is True
                if imported_outer and not multiple_files:
                    type_ref = f"{imported_outer}.{type_ref}"
            java_package = self._java_package_for_type(type_def)
            if java_package:
                return f"{java_package}.{type_ref}"
            return type_ref
        if outer_classname:
            return f"{outer_classname}.{type_ref}"
        return type_ref
