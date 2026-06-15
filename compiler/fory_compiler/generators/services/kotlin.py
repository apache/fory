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

"""Kotlin gRPC service generator helpers."""

from typing import Dict, List

from fory_compiler.generators.base import GeneratedFile
from fory_compiler.generators.services.base import StreamingMode, streaming_mode
from fory_compiler.ir.ast import NamedType, RpcMethod, Service


class KotlinServiceGeneratorMixin:
    """Generates grpc-kotlin coroutine service companions."""

    def generate_services(self) -> List[GeneratedFile]:
        """Generate Kotlin gRPC service companions for local service definitions."""
        local_services = [
            service
            for service in self.schema.services
            if not self.is_imported_type(service)
        ]
        if not local_services:
            return []
        self.check_kotlin_grpc_service_collisions(local_services)
        self.check_kotlin_grpc_method_collisions(local_services)
        return [
            self.generate_kotlin_grpc_service_file(service)
            for service in local_services
        ]

    def check_kotlin_grpc_service_collisions(self, services: List[Service]) -> None:
        generated_paths: Dict[str, str] = {}
        for path, owner in self.kotlin_generated_output_paths(include_services=True):
            prior = generated_paths.get(path)
            if prior is not None:
                raise ValueError(
                    "Kotlin generated file path collision; rename schema files, schema "
                    f"types, or services, or use distinct Kotlin packages. Collisions: "
                    f"{path}: {prior}, {owner}"
                )
            generated_paths[path] = owner

        seen_service_classes: Dict[str, str] = {}
        for service in services:
            service_class = self.kotlin_grpc_owner_name(service)
            prior = seen_service_classes.get(service_class)
            if prior is not None:
                raise ValueError(
                    f"Kotlin gRPC service class collision: {prior} and {service.name} "
                    f"both generate {service_class}"
                )
            seen_service_classes[service_class] = service.name

    def check_kotlin_grpc_method_collisions(self, services: List[Service]) -> None:
        server_reserved = {"bindService", "context"}
        stub_reserved = {"build", "channel", "callOptions"}
        for service in services:
            seen_functions: Dict[str, str] = {}
            seen_descriptors: Dict[str, str] = {}
            seen_backing_fields: Dict[str, str] = {}
            for method in service.methods:
                function_name = self.kotlin_grpc_method_name(method)
                descriptor = self.kotlin_grpc_method_property(method)
                backing_field = self.kotlin_grpc_method_backing_field(method)
                if function_name in server_reserved or function_name in stub_reserved:
                    raise ValueError(
                        f"Kotlin gRPC method name collision in service {service.name}: "
                        f"{method.name} generates reserved member {function_name}"
                    )
                for seen, key, label in (
                    (seen_functions, function_name, "method"),
                    (seen_descriptors, descriptor, "method descriptor"),
                    (
                        seen_backing_fields,
                        backing_field,
                        "method descriptor backing field",
                    ),
                ):
                    prior = seen.get(key)
                    if prior is not None:
                        raise ValueError(
                            f"Kotlin gRPC {label} name collision in service {service.name}: "
                            f"{prior} and {method.name} both generate {key}"
                        )
                    seen[key] = method.name

    def generate_kotlin_grpc_service_file(self, service: Service) -> GeneratedFile:
        imports = set()
        lines = self.source_header(imports, needs_unsigned_opt_in=False)
        owner = self.kotlin_grpc_owner_name(service)
        lines.append(f"public object {owner} {{")
        lines.append(
            f'    public const val SERVICE_NAME: String = "{self.get_grpc_service_name(service)}"'
        )
        lines.append("")
        lines.append(
            f"    private val FORY: org.apache.fory.ThreadSafeFory = {self.get_module_name()}.getFory()"
        )
        lines.append("")
        lines.append(
            "    private val serviceDescriptorValue: io.grpc.ServiceDescriptor by lazy {"
        )
        lines.append("        io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)")
        for method in service.methods:
            lines.append(
                f"            .addMethod({self.kotlin_grpc_method_property(method)})"
            )
        lines.append("            .build()")
        lines.append("    }")
        lines.append("")
        lines.append("    @JvmStatic")
        lines.append("    public val serviceDescriptor: io.grpc.ServiceDescriptor")
        lines.append("        get() = serviceDescriptorValue")
        lines.append("")
        for method in service.methods:
            lines.extend(self.generate_kotlin_grpc_method_descriptor(method))
        lines.extend(self.generate_kotlin_grpc_service_base(service))
        lines.extend(self.generate_kotlin_grpc_client_stub(service))
        lines.extend(self.generate_kotlin_grpc_marshaller())
        lines.append("}")
        return GeneratedFile(
            path=self.kotlin_grpc_service_file_path(service),
            content="\n".join(lines) + "\n",
        )

    def generate_kotlin_grpc_method_descriptor(self, method: RpcMethod) -> List[str]:
        request_type = self.kotlin_grpc_type(method.request_type)
        response_type = self.kotlin_grpc_type(method.response_type)
        property_name = self.kotlin_grpc_method_property(method)
        backing_field = self.kotlin_grpc_method_backing_field(method)
        method_type = self.kotlin_grpc_method_type(method)
        lines = [
            f"    private val {backing_field}: io.grpc.MethodDescriptor<{request_type}, {response_type}> by lazy {{",
            f"        io.grpc.MethodDescriptor.newBuilder<{request_type}, {response_type}>()",
            f"            .setType(io.grpc.MethodDescriptor.MethodType.{method_type})",
            "            .setFullMethodName(",
            "                io.grpc.MethodDescriptor.generateFullMethodName(",
            f'                    SERVICE_NAME, "{method.name}",',
            "                ),",
            "            )",
            "            .setSampledToLocalTracing(true)",
            f"            .setRequestMarshaller(marshaller({request_type}::class.java))",
            f"            .setResponseMarshaller(marshaller({response_type}::class.java))",
            "            .build()",
            "    }",
            "",
            "    @JvmStatic",
            f"    public val {property_name}: io.grpc.MethodDescriptor<{request_type}, {response_type}>",
            f"        get() = {backing_field}",
            "",
        ]
        return lines

    def generate_kotlin_grpc_service_base(self, service: Service) -> List[str]:
        base_name = self.kotlin_grpc_service_base_name(service)
        lines = [
            f"    public abstract class {base_name}(",
            "        coroutineContext: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,",
            "    ) : io.grpc.kotlin.AbstractCoroutineServerImpl(coroutineContext) {",
        ]
        for method in service.methods:
            lines.extend(self.generate_kotlin_grpc_server_method(service, method))
        lines.extend(self.generate_kotlin_grpc_bind_service(service))
        lines.append("    }")
        lines.append("")
        return lines

    def generate_kotlin_grpc_server_method(
        self, service: Service, method: RpcMethod
    ) -> List[str]:
        function_name = self.safe_identifier(self.kotlin_grpc_method_name(method))
        request_type = self.kotlin_grpc_type(method.request_type)
        response_type = self.kotlin_grpc_type(method.response_type)
        if method.client_streaming:
            parameter = f"requests: kotlinx.coroutines.flow.Flow<{request_type}>"
        else:
            parameter = f"request: {request_type}"
        return_type = (
            f"kotlinx.coroutines.flow.Flow<{response_type}>"
            if method.server_streaming
            else response_type
        )
        suspend_modifier = "" if method.server_streaming else "suspend "
        return [
            "",
            f"        public open {suspend_modifier}fun {function_name}({parameter}): {return_type} =",
            "            throw io.grpc.StatusException(",
            "                io.grpc.Status.UNIMPLEMENTED.withDescription(",
            f'                    "Method {self.get_grpc_service_name(service)}/{method.name} is unimplemented",',
            "                ),",
            "            )",
        ]

    def generate_kotlin_grpc_bind_service(self, service: Service) -> List[str]:
        lines = [
            "",
            "        final override fun bindService(): io.grpc.ServerServiceDefinition =",
            "            io.grpc.ServerServiceDefinition.builder(serviceDescriptor)",
        ]
        for method in service.methods:
            call = self.kotlin_grpc_server_call(method)
            function_name = self.safe_identifier(self.kotlin_grpc_method_name(method))
            descriptor = self.kotlin_grpc_method_property(method)
            lines.extend(
                [
                    "                .addMethod(",
                    f"                    io.grpc.kotlin.ServerCalls.{call}(",
                    "                        context = this.context,",
                    f"                        descriptor = {descriptor},",
                    f"                        implementation = ::{function_name},",
                    "                    ),",
                    "                )",
                ]
            )
        lines.append("                .build()")
        return lines

    def generate_kotlin_grpc_client_stub(self, service: Service) -> List[str]:
        stub_name = self.kotlin_grpc_client_stub_name(service)
        lines = [
            f"    public class {stub_name} @JvmOverloads constructor(",
            "        channel: io.grpc.Channel,",
            "        callOptions: io.grpc.CallOptions = io.grpc.CallOptions.DEFAULT,",
            f"    ) : io.grpc.kotlin.AbstractCoroutineStub<{stub_name}>(channel, callOptions) {{",
            f"        override fun build(channel: io.grpc.Channel, callOptions: io.grpc.CallOptions): {stub_name} =",
            f"            {stub_name}(channel, callOptions)",
        ]
        for method in service.methods:
            lines.extend(self.generate_kotlin_grpc_client_method(method))
        lines.append("    }")
        lines.append("")
        return lines

    def generate_kotlin_grpc_client_method(self, method: RpcMethod) -> List[str]:
        function_name = self.safe_identifier(self.kotlin_grpc_method_name(method))
        request_type = self.kotlin_grpc_type(method.request_type)
        response_type = self.kotlin_grpc_type(method.response_type)
        if method.client_streaming:
            parameter = f"requests: kotlinx.coroutines.flow.Flow<{request_type}>"
            argument = "requests"
        else:
            parameter = f"request: {request_type}"
            argument = "request"
        return_type = (
            f"kotlinx.coroutines.flow.Flow<{response_type}>"
            if method.server_streaming
            else response_type
        )
        suspend_modifier = "" if method.server_streaming else "suspend "
        call = self.kotlin_grpc_client_call(method)
        descriptor = self.kotlin_grpc_method_property(method)
        return [
            "",
            f"        public {suspend_modifier}fun {function_name}(",
            f"            {parameter},",
            "            headers: io.grpc.Metadata = io.grpc.Metadata(),",
            f"        ): {return_type} =",
            f"            io.grpc.kotlin.ClientCalls.{call}(",
            "                channel,",
            f"                {descriptor},",
            f"                {argument},",
            "                callOptions,",
            "                headers,",
            "            )",
        ]

    def generate_kotlin_grpc_marshaller(self) -> List[str]:
        return [
            "    private fun <T : Any> marshaller(type: Class<T>): io.grpc.MethodDescriptor.Marshaller<T> =",
            "        ForyMarshaller(FORY, type)",
            "",
            "    private class ForyMarshaller<T : Any>(",
            "        private val fory: org.apache.fory.ThreadSafeFory,",
            "        private val type: Class<T>,",
            "    ) : io.grpc.MethodDescriptor.Marshaller<T> {",
            "        override fun stream(value: T): java.io.InputStream {",
            "            try {",
            "                return KnownLengthByteArrayInputStream(fory.serialize(value))",
            "            } catch (e: RuntimeException) {",
            '                throw internalError("Fory serialization failed", e)',
            "            }",
            "        }",
            "",
            "        override fun parse(stream: java.io.InputStream): T {",
            "            try {",
            "                return fory.deserialize(readBytes(stream), type)",
            "            } catch (e: java.io.IOException) {",
            '                throw internalError("Fory deserialization failed", e)',
            "            } catch (e: RuntimeException) {",
            '                throw internalError("Fory deserialization failed", e)',
            "            }",
            "        }",
            "",
            "        private fun internalError(description: String, cause: Throwable): io.grpc.StatusRuntimeException =",
            "            io.grpc.Status.INTERNAL.withDescription(description).withCause(cause).asRuntimeException()",
            "",
            "        private fun readBytes(stream: java.io.InputStream): ByteArray {",
            "            if (stream is io.grpc.KnownLength) {",
            "                val size = stream.available()",
            "                val bytes = ByteArray(size)",
            "                var offset = 0",
            "                while (offset < size) {",
            "                    val read = stream.read(bytes, offset, size - offset)",
            "                    if (read == -1) {",
            '                        throw java.io.EOFException("Expected $size bytes, got $offset")',
            "                    }",
            "                    offset += read",
            "                }",
            "                return bytes",
            "            }",
            "            return readUnknownLengthBytes(stream)",
            "        }",
            "",
            "        private fun readUnknownLengthBytes(stream: java.io.InputStream): ByteArray {",
            "            val out = java.io.ByteArrayOutputStream()",
            "            val buffer = ByteArray(8192)",
            "            while (true) {",
            "                val read = stream.read(buffer)",
            "                if (read == -1) {",
            "                    break",
            "                }",
            "                out.write(buffer, 0, read)",
            "            }",
            "            return out.toByteArray()",
            "        }",
            "",
            "        private class KnownLengthByteArrayInputStream(bytes: ByteArray) :",
            "            java.io.ByteArrayInputStream(bytes),",
            "            io.grpc.KnownLength",
            "    }",
        ]

    def kotlin_grpc_type(self, named_type: NamedType) -> str:
        type_name = self.schema.resolve_type_name(named_type.name)
        return self.resolve_kotlin_type_name(type_name, None)

    def kotlin_grpc_owner_name(self, service: Service) -> str:
        return f"{service.name}GrpcKt"

    def kotlin_grpc_service_base_name(self, service: Service) -> str:
        return f"{service.name}CoroutineImplBase"

    def kotlin_grpc_client_stub_name(self, service: Service) -> str:
        return f"{service.name}CoroutineStub"

    def kotlin_grpc_service_file_path(self, service: Service) -> str:
        path = self.get_kotlin_package_path()
        filename = f"{self.kotlin_grpc_owner_name(service)}.kt"
        return f"{path}/{filename}" if path else filename

    def kotlin_grpc_method_name(self, method: RpcMethod) -> str:
        return self.to_camel_case(method.name)

    def kotlin_grpc_method_property(self, method: RpcMethod) -> str:
        return f"{self.kotlin_grpc_method_name(method)}Method"

    def kotlin_grpc_method_backing_field(self, method: RpcMethod) -> str:
        return f"{self.kotlin_grpc_method_name(method)}MethodValue"

    def kotlin_grpc_method_type(self, method: RpcMethod) -> str:
        mode = streaming_mode(method)
        if mode is StreamingMode.BIDIRECTIONAL:
            return "BIDI_STREAMING"
        if mode is StreamingMode.CLIENT_STREAMING:
            return "CLIENT_STREAMING"
        if mode is StreamingMode.SERVER_STREAMING:
            return "SERVER_STREAMING"
        return "UNARY"

    def kotlin_grpc_client_call(self, method: RpcMethod) -> str:
        mode = streaming_mode(method)
        if mode is StreamingMode.BIDIRECTIONAL:
            return "bidiStreamingRpc"
        if mode is StreamingMode.CLIENT_STREAMING:
            return "clientStreamingRpc"
        if mode is StreamingMode.SERVER_STREAMING:
            return "serverStreamingRpc"
        return "unaryRpc"

    def kotlin_grpc_server_call(self, method: RpcMethod) -> str:
        mode = streaming_mode(method)
        if mode is StreamingMode.BIDIRECTIONAL:
            return "bidiStreamingServerMethodDefinition"
        if mode is StreamingMode.CLIENT_STREAMING:
            return "clientStreamingServerMethodDefinition"
        if mode is StreamingMode.SERVER_STREAMING:
            return "serverStreamingServerMethodDefinition"
        return "unaryServerMethodDefinition"
