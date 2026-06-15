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

"""C# gRPC service companion generator."""

import re
from typing import Dict, List, Set, Tuple

from fory_compiler.generators.base import GeneratedFile
from fory_compiler.generators.services.base import StreamingMode, streaming_mode
from fory_compiler.ir.ast import Service


class CSharpServiceMixin:
    """Generates C# gRPC service companions."""

    def generate_services(self) -> List[GeneratedFile]:
        services = [s for s in self.schema.services if not self.is_imported_type(s)]
        if not services:
            return []
        self._validate_csharp_services(services)
        return [self._generate_csharp_service(service) for service in services]

    def _generate_csharp_service(self, service: Service) -> GeneratedFile:
        namespace_name = self.get_csharp_namespace()
        service_name = self.safe_type_identifier(service.name)
        marshallers = self._csharp_marshallers(service)

        lines: List[str] = []
        lines.append(self.get_license_header("//"))
        lines.append("#pragma warning disable 8981")
        lines.append("")
        lines.append("using grpc = global::Grpc.Core;")
        lines.append("")
        lines.append(f"namespace {namespace_name};")
        lines.append("")
        lines.append(f"public static partial class {service_name}")
        lines.append("{")
        if service.methods:
            module_name = self.safe_type_identifier(self.get_module_class_name())
            lines.append(
                f'{self.indent_str}static readonly string __ServiceName = "{self.get_grpc_service_name(service)}";'
            )
            lines.append(
                f"{self.indent_str}private static readonly global::Apache.Fory.ThreadSafeFory __Fory ="
            )
            lines.append(f"{self.indent_str * 2}{module_name}.GetFory();")
            lines.append("")
            lines.extend(self._generate_marshallers(marshallers))
            lines.append("")
            lines.extend(self._generate_methods(service, marshallers))
            lines.append("")
        lines.extend(self._generate_server_base(service))
        lines.append("")
        lines.extend(self._generate_client(service))
        lines.append("")
        lines.extend(self._generate_bind_service(service))
        if service.methods:
            lines.append("")
            lines.extend(self._generate_cold_helpers())
        lines.append("}")

        file_name = f"{service.name}Grpc.cs"
        ns_path = self._namespace_path(namespace_name)
        path = f"{ns_path}/{file_name}" if ns_path else file_name
        return GeneratedFile(path=path, content="\n".join(lines))

    def _csharp_marshallers(
        self, service: Service
    ) -> Dict[str, Tuple[str, str, str, str]]:
        marshallers: Dict[str, Tuple[str, str, str, str]] = {}
        used_names: Set[str] = set()
        for method in service.methods:
            for named_type in (method.request_type, method.response_type):
                type_ref = self._named_type_reference(named_type)
                if type_ref in marshallers:
                    continue
                suffix = self._private_identifier(type_ref)
                if suffix in used_names:
                    index = 2
                    candidate = f"{suffix}_{index}"
                    while candidate in used_names:
                        index += 1
                        candidate = f"{suffix}_{index}"
                    suffix = candidate
                used_names.add(suffix)
                marshaller = f"__Marshaller_{suffix}"
                serialize = f"__Serialize_{suffix}"
                deserialize = f"__Deserialize_{suffix}"
                marshallers[type_ref] = (marshaller, serialize, deserialize, type_ref)
        return marshallers

    def _generate_marshallers(
        self, marshallers: Dict[str, Tuple[str, str, str, str]]
    ) -> List[str]:
        lines: List[str] = []
        for _, (marshaller, serialize, deserialize, type_ref) in marshallers.items():
            lines.append(
                f"{self.indent_str}static readonly grpc::Marshaller<{type_ref}> {marshaller} ="
            )
            lines.append(
                f"{self.indent_str * 2}grpc::Marshallers.Create({serialize}, {deserialize});"
            )
            lines.append("")
            lines.append(
                f"{self.indent_str}private static void {serialize}({type_ref} value, grpc::SerializationContext context)"
            )
            lines.append(f"{self.indent_str}{{")
            lines.append(f"{self.indent_str * 2}try")
            lines.append(f"{self.indent_str * 2}{{")
            lines.append(
                f"{self.indent_str * 3}context.Complete(__Fory.Serialize<{type_ref}>(in value));"
            )
            lines.append(f"{self.indent_str * 2}}}")
            lines.append(f"{self.indent_str * 2}catch (global::System.Exception error)")
            lines.append(f"{self.indent_str * 2}{{")
            lines.append(f"{self.indent_str * 3}__ThrowSerializeError(error);")
            lines.append(f"{self.indent_str * 2}}}")
            lines.append(f"{self.indent_str}}}")
            lines.append("")
            lines.append(
                f"{self.indent_str}private static {type_ref} {deserialize}(grpc::DeserializationContext context)"
            )
            lines.append(f"{self.indent_str}{{")
            lines.append(f"{self.indent_str * 2}try")
            lines.append(f"{self.indent_str * 2}{{")
            lines.append(
                f"{self.indent_str * 3}global::System.Buffers.ReadOnlySequence<byte> body = context.PayloadAsReadOnlySequence();"
            )
            lines.append(
                f"{self.indent_str * 3}{type_ref} value = __Fory.Deserialize<{type_ref}>(ref body);"
            )
            lines.append(f"{self.indent_str * 3}if (!body.IsEmpty)")
            lines.append(f"{self.indent_str * 3}{{")
            lines.append(
                f"{self.indent_str * 4}return __ThrowTrailingBody<{type_ref}>();"
            )
            lines.append(f"{self.indent_str * 3}}}")
            lines.append(f"{self.indent_str * 3}return value;")
            lines.append(f"{self.indent_str * 2}}}")
            lines.append(
                f"{self.indent_str * 2}catch (global::System.Exception error) when (error is not grpc::RpcException)"
            )
            lines.append(f"{self.indent_str * 2}{{")
            lines.append(
                f"{self.indent_str * 3}return __ThrowDeserializeError<{type_ref}>(error);"
            )
            lines.append(f"{self.indent_str * 2}}}")
            lines.append(f"{self.indent_str}}}")
            lines.append("")
        if lines:
            lines.pop()
        return lines

    def _generate_methods(
        self,
        service: Service,
        marshallers: Dict[str, Tuple[str, str, str, str]],
    ) -> List[str]:
        lines: List[str] = []
        for method in service.methods:
            request_type = self._named_type_reference(method.request_type)
            response_type = self._named_type_reference(method.response_type)
            request_marshaller = marshallers[request_type][0]
            response_marshaller = marshallers[response_type][0]
            descriptor = self._method_field_name(method.name)
            grpc_type = self._method_type(method)
            lines.append(
                f"{self.indent_str}static readonly grpc::Method<{request_type}, {response_type}> {descriptor} ="
            )
            lines.append(
                f"{self.indent_str * 2}new grpc::Method<{request_type}, {response_type}>("
            )
            lines.append(f"{self.indent_str * 3}{grpc_type},")
            lines.append(f"{self.indent_str * 3}__ServiceName,")
            lines.append(f'{self.indent_str * 3}"{method.name}",')
            lines.append(f"{self.indent_str * 3}{request_marshaller},")
            lines.append(f"{self.indent_str * 3}{response_marshaller});")
            lines.append("")
        if lines:
            lines.pop()
        return lines

    def _generate_server_base(self, service: Service) -> List[str]:
        service_name = self.safe_type_identifier(service.name)
        base_name = self._base_type_name(service)
        lines = [
            f'{self.indent_str}[grpc::BindServiceMethod(typeof({service_name}), "BindService")]',
            f"{self.indent_str}public abstract partial class {base_name}",
            f"{self.indent_str}{{",
        ]
        for method in service.methods:
            lines.append("")
            lines.extend(self._server_base_method(method))
        lines.append(f"{self.indent_str}}}")
        return lines

    def _server_base_method(self, method) -> List[str]:
        request_type = self._named_type_reference(method.request_type)
        response_type = self._named_type_reference(method.response_type)
        method_name = self._public_method_name(method.name)
        mode = streaming_mode(method)
        lines: List[str] = []
        if mode is StreamingMode.UNARY:
            lines.append(
                f"{self.indent_str * 2}public virtual global::System.Threading.Tasks.Task<{response_type}> {method_name}("
            )
            lines.append(f"{self.indent_str * 3}{request_type} request,")
            lines.append(f"{self.indent_str * 3}grpc::ServerCallContext context)")
            lines.append(f"{self.indent_str * 2}{{")
            lines.append(
                f"{self.indent_str * 3}return __ThrowUnimplemented<{response_type}>();"
            )
        elif mode is StreamingMode.SERVER_STREAMING:
            lines.append(
                f"{self.indent_str * 2}public virtual global::System.Threading.Tasks.Task {method_name}("
            )
            lines.append(f"{self.indent_str * 3}{request_type} request,")
            lines.append(
                f"{self.indent_str * 3}grpc::IServerStreamWriter<{response_type}> responseStream,"
            )
            lines.append(f"{self.indent_str * 3}grpc::ServerCallContext context)")
            lines.append(f"{self.indent_str * 2}{{")
            lines.append(f"{self.indent_str * 3}return __ThrowUnimplemented();")
        elif mode is StreamingMode.CLIENT_STREAMING:
            lines.append(
                f"{self.indent_str * 2}public virtual global::System.Threading.Tasks.Task<{response_type}> {method_name}("
            )
            lines.append(
                f"{self.indent_str * 3}grpc::IAsyncStreamReader<{request_type}> requestStream,"
            )
            lines.append(f"{self.indent_str * 3}grpc::ServerCallContext context)")
            lines.append(f"{self.indent_str * 2}{{")
            lines.append(
                f"{self.indent_str * 3}return __ThrowUnimplemented<{response_type}>();"
            )
        else:
            lines.append(
                f"{self.indent_str * 2}public virtual global::System.Threading.Tasks.Task {method_name}("
            )
            lines.append(
                f"{self.indent_str * 3}grpc::IAsyncStreamReader<{request_type}> requestStream,"
            )
            lines.append(
                f"{self.indent_str * 3}grpc::IServerStreamWriter<{response_type}> responseStream,"
            )
            lines.append(f"{self.indent_str * 3}grpc::ServerCallContext context)")
            lines.append(f"{self.indent_str * 2}{{")
            lines.append(f"{self.indent_str * 3}return __ThrowUnimplemented();")
        lines.append(f"{self.indent_str * 2}}}")
        return lines

    def _generate_client(self, service: Service) -> List[str]:
        client_name = self._client_type_name(service)
        lines = [
            f"{self.indent_str}public partial class {client_name} : grpc::ClientBase<{client_name}>",
            f"{self.indent_str}{{",
            f"{self.indent_str * 2}public {client_name}(grpc::ChannelBase channel) : base(channel)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 2}}}",
            "",
            f"{self.indent_str * 2}public {client_name}(grpc::CallInvoker callInvoker) : base(callInvoker)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 2}}}",
            "",
            f"{self.indent_str * 2}protected {client_name}() : base()",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 2}}}",
            "",
            f"{self.indent_str * 2}protected {client_name}(ClientBaseConfiguration configuration) : base(configuration)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 2}}}",
        ]
        for method in service.methods:
            lines.append("")
            lines.extend(self._client_methods(method))
        lines.append("")
        lines.append(
            f"{self.indent_str * 2}protected override {client_name} NewInstance(ClientBaseConfiguration configuration)"
        )
        lines.append(f"{self.indent_str * 2}{{")
        lines.append(f"{self.indent_str * 3}return new {client_name}(configuration);")
        lines.append(f"{self.indent_str * 2}}}")
        lines.append(f"{self.indent_str}}}")
        return lines

    def _client_methods(self, method) -> List[str]:
        request_type = self._named_type_reference(method.request_type)
        response_type = self._named_type_reference(method.response_type)
        method_name = self._public_method_name(method.name)
        descriptor = self._method_field_name(method.name)
        mode = streaming_mode(method)
        lines: List[str] = []
        if mode is StreamingMode.UNARY:
            lines.extend(
                self._unary_client_methods(
                    method_name, descriptor, request_type, response_type
                )
            )
        elif mode is StreamingMode.SERVER_STREAMING:
            lines.extend(
                self._server_stream_client_methods(
                    method_name, descriptor, request_type, response_type
                )
            )
        elif mode is StreamingMode.CLIENT_STREAMING:
            lines.extend(
                self._client_stream_client_methods(
                    method_name, descriptor, request_type, response_type
                )
            )
        else:
            lines.extend(
                self._duplex_client_methods(
                    method_name, descriptor, request_type, response_type
                )
            )
        return lines

    def _unary_client_methods(
        self, method_name: str, descriptor: str, request_type: str, response_type: str
    ) -> List[str]:
        async_name = self._append_identifier(method_name, "Async")
        return [
            f"{self.indent_str * 2}public virtual {response_type} {method_name}({request_type} request, grpc::Metadata? headers = null, global::System.DateTime? deadline = null, global::System.Threading.CancellationToken cancellationToken = default)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 3}return {method_name}(request, new grpc::CallOptions(headers, deadline, cancellationToken));",
            f"{self.indent_str * 2}}}",
            "",
            f"{self.indent_str * 2}public virtual {response_type} {method_name}({request_type} request, grpc::CallOptions options)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 3}return CallInvoker.BlockingUnaryCall({descriptor}, null, options, request);",
            f"{self.indent_str * 2}}}",
            "",
            f"{self.indent_str * 2}public virtual grpc::AsyncUnaryCall<{response_type}> {async_name}({request_type} request, grpc::Metadata? headers = null, global::System.DateTime? deadline = null, global::System.Threading.CancellationToken cancellationToken = default)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 3}return {async_name}(request, new grpc::CallOptions(headers, deadline, cancellationToken));",
            f"{self.indent_str * 2}}}",
            "",
            f"{self.indent_str * 2}public virtual grpc::AsyncUnaryCall<{response_type}> {async_name}({request_type} request, grpc::CallOptions options)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 3}return CallInvoker.AsyncUnaryCall({descriptor}, null, options, request);",
            f"{self.indent_str * 2}}}",
        ]

    def _server_stream_client_methods(
        self, method_name: str, descriptor: str, request_type: str, response_type: str
    ) -> List[str]:
        return [
            f"{self.indent_str * 2}public virtual grpc::AsyncServerStreamingCall<{response_type}> {method_name}({request_type} request, grpc::Metadata? headers = null, global::System.DateTime? deadline = null, global::System.Threading.CancellationToken cancellationToken = default)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 3}return {method_name}(request, new grpc::CallOptions(headers, deadline, cancellationToken));",
            f"{self.indent_str * 2}}}",
            "",
            f"{self.indent_str * 2}public virtual grpc::AsyncServerStreamingCall<{response_type}> {method_name}({request_type} request, grpc::CallOptions options)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 3}return CallInvoker.AsyncServerStreamingCall({descriptor}, null, options, request);",
            f"{self.indent_str * 2}}}",
        ]

    def _client_stream_client_methods(
        self, method_name: str, descriptor: str, request_type: str, response_type: str
    ) -> List[str]:
        return [
            f"{self.indent_str * 2}public virtual grpc::AsyncClientStreamingCall<{request_type}, {response_type}> {method_name}(grpc::Metadata? headers = null, global::System.DateTime? deadline = null, global::System.Threading.CancellationToken cancellationToken = default)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 3}return {method_name}(new grpc::CallOptions(headers, deadline, cancellationToken));",
            f"{self.indent_str * 2}}}",
            "",
            f"{self.indent_str * 2}public virtual grpc::AsyncClientStreamingCall<{request_type}, {response_type}> {method_name}(grpc::CallOptions options)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 3}return CallInvoker.AsyncClientStreamingCall({descriptor}, null, options);",
            f"{self.indent_str * 2}}}",
        ]

    def _duplex_client_methods(
        self, method_name: str, descriptor: str, request_type: str, response_type: str
    ) -> List[str]:
        return [
            f"{self.indent_str * 2}public virtual grpc::AsyncDuplexStreamingCall<{request_type}, {response_type}> {method_name}(grpc::Metadata? headers = null, global::System.DateTime? deadline = null, global::System.Threading.CancellationToken cancellationToken = default)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 3}return {method_name}(new grpc::CallOptions(headers, deadline, cancellationToken));",
            f"{self.indent_str * 2}}}",
            "",
            f"{self.indent_str * 2}public virtual grpc::AsyncDuplexStreamingCall<{request_type}, {response_type}> {method_name}(grpc::CallOptions options)",
            f"{self.indent_str * 2}{{",
            f"{self.indent_str * 3}return CallInvoker.AsyncDuplexStreamingCall({descriptor}, null, options);",
            f"{self.indent_str * 2}}}",
        ]

    def _generate_bind_service(self, service: Service) -> List[str]:
        base_name = self._base_type_name(service)
        builder_lines = [
            f"{self.indent_str}public static grpc::ServerServiceDefinition BindService({base_name} serviceImpl)",
            f"{self.indent_str}{{",
            f"{self.indent_str * 2}global::System.ArgumentNullException.ThrowIfNull(serviceImpl);",
            f"{self.indent_str * 2}return grpc::ServerServiceDefinition.CreateBuilder()",
        ]
        for index, method in enumerate(service.methods):
            suffix = ".Build();" if index == len(service.methods) - 1 else ""
            builder_lines.append(
                f"{self.indent_str * 3}.AddMethod({self._method_field_name(method.name)}, serviceImpl.{self._public_method_name(method.name)}){suffix}"
            )
        if not service.methods:
            builder_lines[-1] = (
                f"{self.indent_str * 2}return grpc::ServerServiceDefinition.CreateBuilder().Build();"
            )
        builder_lines.append(f"{self.indent_str}}}")
        builder_lines.append("")
        builder_lines.append(
            f"{self.indent_str}public static void BindService(grpc::ServiceBinderBase serviceBinder, {base_name}? serviceImpl)"
        )
        builder_lines.append(f"{self.indent_str}{{")
        builder_lines.append(
            f"{self.indent_str * 2}global::System.ArgumentNullException.ThrowIfNull(serviceBinder);"
        )
        for method in service.methods:
            builder_lines.extend(self._binder_method(method))
        builder_lines.append(f"{self.indent_str}}}")
        return builder_lines

    def _binder_method(self, method) -> List[str]:
        request_type = self._named_type_reference(method.request_type)
        response_type = self._named_type_reference(method.response_type)
        delegate_type = {
            StreamingMode.UNARY: "UnaryServerMethod",
            StreamingMode.SERVER_STREAMING: "ServerStreamingServerMethod",
            StreamingMode.CLIENT_STREAMING: "ClientStreamingServerMethod",
            StreamingMode.BIDIRECTIONAL: "DuplexStreamingServerMethod",
        }[streaming_mode(method)]
        return [
            f"{self.indent_str * 2}serviceBinder.AddMethod(",
            f"{self.indent_str * 3}{self._method_field_name(method.name)},",
            f"{self.indent_str * 3}serviceImpl == null",
            f"{self.indent_str * 4}? null",
            f"{self.indent_str * 4}: new grpc::{delegate_type}<{request_type}, {response_type}>(serviceImpl.{self._public_method_name(method.name)}));",
        ]

    def _generate_cold_helpers(self) -> List[str]:
        no_inline = (
            "global::System.Runtime.CompilerServices.MethodImpl("
            "global::System.Runtime.CompilerServices.MethodImplOptions.NoInlining)"
        )
        return [
            f"{self.indent_str}[{no_inline}]",
            f"{self.indent_str}private static void __ThrowSerializeError(global::System.Exception error)",
            f"{self.indent_str}{{",
            f'{self.indent_str * 2}throw new grpc::RpcException(new grpc::Status(grpc::StatusCode.Internal, $"Fory gRPC serialization failed: {{error.Message}}"));',
            f"{self.indent_str}}}",
            "",
            f"{self.indent_str}[{no_inline}]",
            f"{self.indent_str}private static T __ThrowDeserializeError<T>(global::System.Exception error)",
            f"{self.indent_str}{{",
            f'{self.indent_str * 2}throw new grpc::RpcException(new grpc::Status(grpc::StatusCode.Internal, $"Fory gRPC deserialization failed: {{error.Message}}"));',
            f"{self.indent_str}}}",
            "",
            f"{self.indent_str}[{no_inline}]",
            f"{self.indent_str}private static T __ThrowTrailingBody<T>()",
            f"{self.indent_str}{{",
            f'{self.indent_str * 2}throw new grpc::RpcException(new grpc::Status(grpc::StatusCode.Internal, "Fory gRPC message contained trailing bytes"));',
            f"{self.indent_str}}}",
            "",
            f"{self.indent_str}[{no_inline}]",
            f"{self.indent_str}private static global::System.Threading.Tasks.Task<T> __ThrowUnimplemented<T>()",
            f"{self.indent_str}{{",
            f'{self.indent_str * 2}throw new grpc::RpcException(new grpc::Status(grpc::StatusCode.Unimplemented, ""));',
            f"{self.indent_str}}}",
            "",
            f"{self.indent_str}[{no_inline}]",
            f"{self.indent_str}private static global::System.Threading.Tasks.Task __ThrowUnimplemented()",
            f"{self.indent_str}{{",
            f'{self.indent_str * 2}throw new grpc::RpcException(new grpc::Status(grpc::StatusCode.Unimplemented, ""));',
            f"{self.indent_str}}}",
        ]

    def _validate_csharp_services(self, services: List[Service]) -> None:
        namespace_name = self.get_csharp_namespace()
        top_level_types = self._top_level_type_names(namespace_name)
        module_names = self._module_names(namespace_name)
        service_names: Dict[str, str] = {}
        for service in services:
            service_name = self.safe_type_identifier(service.name)
            prior = service_names.get(service_name)
            if prior is not None:
                raise ValueError(
                    f"C# gRPC service class collision: {prior} and {service.name} both generate {service_name}"
                )
            service_names[service_name] = service.name
            if service_name in top_level_types:
                raise ValueError(
                    f"C# gRPC service class {service_name} conflicts with generated type in namespace {namespace_name}"
                )
            if service_name in module_names:
                raise ValueError(
                    f"C# gRPC service class {service_name} conflicts with generated module in namespace {namespace_name}"
                )
            if service_name in {"BindService", "NewInstance"}:
                raise ValueError(
                    f"C# gRPC service class {service.name} conflicts with generated member {service_name}"
                )
            self._validate_method_names(service)

    def _validate_method_names(self, service: Service) -> None:
        client_name = self._client_type_name(service).lstrip("@")
        base_name = self._base_type_name(service).lstrip("@")
        fixed = {"BindService", "NewInstance", client_name, base_name}
        client_members: Dict[str, str] = {}
        base_members: Dict[str, str] = {}
        for method in service.methods:
            method_name = self._public_method_name(method.name)
            raw_name = method_name.lstrip("@")
            if raw_name in fixed:
                raise ValueError(
                    f"C# gRPC method {service.name}.{method.name} conflicts with generated member {raw_name}"
                )
            self._reserve_member(base_members, raw_name, method.name, service.name)
            self._reserve_member(client_members, raw_name, method.name, service.name)
            if streaming_mode(method) is StreamingMode.UNARY:
                async_name = self._append_identifier(method_name, "Async").lstrip("@")
                self._reserve_member(
                    client_members, async_name, f"{method.name} async", service.name
                )

    def _reserve_member(
        self, members: Dict[str, str], name: str, source: str, service_name: str
    ) -> None:
        prior = members.get(name)
        if prior is not None:
            raise ValueError(
                f"C# gRPC method name collision in service {service_name}: {prior} and {source} both generate {name}"
            )
        members[name] = source

    def _top_level_type_names(self, namespace_name: str) -> Set[str]:
        names: Set[str] = set()
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if self._type_namespace(type_def) == namespace_name:
                names.add(self.safe_type_identifier(type_def.name))
        return names

    def _module_names(self, namespace_name: str) -> Set[str]:
        names = {self.safe_type_identifier(self.get_module_class_name())}
        for module_namespace, module_name in self._collect_imported_modules():
            if module_namespace == namespace_name:
                names.add(self.safe_type_identifier(module_name))
        return names

    def _method_type(self, method) -> str:
        return {
            StreamingMode.UNARY: "grpc::MethodType.Unary",
            StreamingMode.SERVER_STREAMING: "grpc::MethodType.ServerStreaming",
            StreamingMode.CLIENT_STREAMING: "grpc::MethodType.ClientStreaming",
            StreamingMode.BIDIRECTIONAL: "grpc::MethodType.DuplexStreaming",
        }[streaming_mode(method)]

    def _public_method_name(self, name: str) -> str:
        return self.safe_member_name(name)

    def _method_field_name(self, name: str) -> str:
        return f"__Method_{self._private_identifier(name)}"

    def _client_type_name(self, service: Service) -> str:
        return self.safe_type_identifier(f"{service.name}Client")

    def _base_type_name(self, service: Service) -> str:
        return self.safe_type_identifier(f"{service.name}Base")

    def _private_identifier(self, value: str) -> str:
        identifier = re.sub(r"[^0-9A-Za-z_]", "_", value.replace("global::", ""))
        identifier = re.sub(r"_+", "_", identifier).strip("_")
        if not identifier or identifier[0].isdigit():
            identifier = f"_{identifier}"
        return identifier

    def _append_identifier(self, identifier: str, suffix: str) -> str:
        if identifier.startswith("@"):
            return f"@{identifier[1:]}{suffix}"
        return f"{identifier}{suffix}"
