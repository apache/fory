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

"""C++ gRPC service generator helpers."""

import hashlib
import re
from typing import Dict, List, Set, Tuple

from fory_compiler.generators.base import GeneratedFile
from fory_compiler.generators.services.base import StreamingMode, streaming_mode
from fory_compiler.ir.ast import NamedType, RpcMethod, Service


class CppServiceGeneratorMixin:
    """Generates C++ gRPC service companions."""

    def generate_services(self) -> List[GeneratedFile]:
        """Generate C++ gRPC service companion module."""
        local_services = [
            service
            for service in self.schema.services
            if not self.is_imported_type(service)
        ]
        if not local_services:
            return []
        self.allocate_grpc_service_identifiers(local_services)
        return [
            self.generate_service_api_header(local_services),
            self.generate_service_grpc_header(local_services),
            self.generate_service_grpc_source(local_services),
        ]

    def allocate_grpc_service_identifiers(self, services: List[Service]) -> None:
        """Allocate sanitized identifiers used by generated gRPC code."""
        self._ensure_name_caches(self.schema)
        if not hasattr(self, "_named_service_schema_ids"):
            self._named_service_schema_ids: Set[int] = set()
            self._service_interface_identifier_cache: Dict[Tuple[object, ...], str] = {}
            self._service_stub_identifier_cache: Dict[Tuple[object, ...], str] = {}
            self._service_grpc_identifier_cache: Dict[Tuple[object, ...], str] = {}
            self._service_name_constant_identifier_cache: Dict[
                Tuple[object, ...], str
            ] = {}
            self._rpc_method_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
            self._rpc_path_constant_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
            self._rpc_method_member_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}

        schema_id = id(self.schema)
        if schema_id in self._named_service_schema_ids:
            return

        # Reserve the fixed service companion namespace in the schema scope.
        # e.g.:
        #
        #   namespace demo;
        #
        #   message service {
        #     string name = 1;
        #   }
        #
        #   service Greeter {
        #     rpc SayHello (service) returns (service);
        #   }
        #
        # This IDL itself is valid. But in our design, both `message service`
        # and `service Greeter` would take up the same `::demo::service` namespace.
        # We should detect this kind of collision and throw errors to the user.
        used_schema_names: Dict[str, str] = {}
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(type_def):
                identifier = self.get_type_identifier(type_def)
                used_schema_names[identifier] = f"model type {type_def.name!r}"
        self._allocate_scoped_identifier(
            "service",
            used_schema_names,
            "C++ gRPC schema namespace",
            "generated service namespace",
        )
        # For package name `demo`,
        # `used_service_names` checks collisions under `demo::service`,
        # `used_grpc_names` checks collisions under `demo::service::grpc`.
        used_service_names: Dict[str, str] = {}
        self._allocate_scoped_identifier(
            "grpc",
            used_service_names,
            "C++ gRPC service namespace",
            "generated gRPC transport namespace",
        )
        used_grpc_names: Dict[str, str] = {}
        for service in services:
            service_key = self._cache_key(service)
            self._service_interface_identifier_cache[service_key] = (
                self._allocate_scoped_identifier(
                    service.name,
                    used_service_names,
                    "C++ gRPC service namespace",
                    f"service interface {service.name}",
                )
            )
            self._service_stub_identifier_cache[service_key] = (
                self._allocate_scoped_identifier(
                    f"{service.name}Stub",
                    used_grpc_names,
                    "C++ gRPC transport namespace",
                    f"service stub {service.name}",
                )
            )
            self._service_grpc_identifier_cache[service_key] = (
                self._allocate_scoped_identifier(
                    f"{service.name}ServiceGrpc",
                    used_grpc_names,
                    "C++ gRPC transport namespace",
                    f"gRPC service wrapper {service.name}",
                )
            )
            self._service_name_constant_identifier_cache[service_key] = (
                self._allocate_scoped_identifier(
                    f"{self.to_pascal_case(service.name)}ServiceName",
                    used_service_names,
                    "C++ gRPC service namespace",
                    f"service name constant for {service.name}",
                )
            )
            stub_name = self._service_stub_identifier_cache[service_key]
            interface_name = self._service_interface_identifier_cache[service_key]

            # Even though C++ allows function overloading, at IDL level, method names in the same service cannot be identical,
            # since gRPC relies on their names to locate the handler.
            used_methods: Dict[str, str] = {}
            used_members: Dict[str, str] = {}
            reserved_method_names = {interface_name, stub_name}
            method_names: Dict[Tuple[object, ...], str] = {}
            path_constants: Dict[Tuple[object, ...], str] = {}
            rpc_members: Dict[Tuple[object, ...], str] = {}
            for method in service.methods:
                method_key = self._cache_key(method)
                method_names[method_key] = self._allocate_scoped_identifier(
                    method.name,
                    used_methods,
                    f"C++ gRPC service {service.name} methods",
                    method.name,
                    reserved_names=reserved_method_names,
                )
                method_name = method_names[method_key]
                # Client stub code uses `channel_` as a data member name.
                if method_name == "channel_" or method_name in used_members:
                    raise ValueError(
                        f"C++ name collision in gRPC stub {stub_name}: method "
                        f"{method.name!r} conflicts with member {method_name!r}"
                    )
                used_members[method_name] = method.name
                rpc_members[method_key] = self._allocate_scoped_identifier(
                    f"rpcmethod_{method_name}_",
                    used_members,
                    f"C++ gRPC stub {stub_name} members",
                    f"RPC method member for {service.name}.{method.name}",
                )
                path_constants[method_key] = self._allocate_scoped_identifier(
                    f"{self.to_pascal_case(service.name)}"
                    f"{self.to_pascal_case(method.name)}Path",
                    used_service_names,
                    "C++ gRPC service namespace",
                    f"RPC path constant for {service.name}.{method.name}",
                )
            self._rpc_method_identifier_cache[service_key] = method_names
            self._rpc_method_member_identifier_cache[service_key] = rpc_members
            self._rpc_path_constant_identifier_cache[service_key] = path_constants

        self._named_service_schema_ids.add(schema_id)

    def generate_service_api_header(self, services: List[Service]) -> GeneratedFile:
        """Generate C++ service API header file (service.h)."""
        path = f"{self.get_header_name()}_service.h"
        guard = self._cpp_header_guard(path)
        lines = self._cpp_license_lines()
        lines.extend(
            [
                "",
                f"#ifndef {guard}",
                f"#define {guard}",
                "",
                f'#include "{self.get_header_name()}.h"',
                "",
                "#include <grpcpp/server_context.h>",
                "#include <grpcpp/support/status.h>",
                "#include <grpcpp/support/sync_stream.h>",
                "",
            ]
        )
        service_namespace = self.service_namespace()
        lines.extend([f"namespace {service_namespace} {{", ""])
        for i, service in enumerate(services):
            if i > 0:
                lines.append("")
            lines.extend(self.generate_service_interface(service))
            lines.append("")
            lines.extend(self.generate_service_constants(service))
        lines.extend(["", f"}}  // namespace {service_namespace}"])
        lines.extend(["", f"#endif  // {guard}", ""])  # End header guard.
        return GeneratedFile(path=path, content="\n".join(lines))

    def generate_service_interface(self, service: Service) -> List[str]:
        """Generate gRPC service interface."""
        service_key = self._cache_key(service)
        interface_name = self._service_interface_identifier_cache[service_key]
        lines = [
            f"class {interface_name} {{",
            " public:",
        ]
        lines.extend(self.indent_lines([f"virtual ~{interface_name}() = default;"], 1))
        for i, method in enumerate(service.methods):
            if i > 0:
                lines.append("")
            lines.extend(
                self.indent_lines(
                    self.generate_grpc_method_signature(service, method), 1
                )
            )
        lines.append("};")
        return lines

    def generate_grpc_method_signature(
        self, service: Service, method: RpcMethod
    ) -> List[str]:
        """Generate gRPC service trait method signature."""
        service_key = self._cache_key(service)
        method_key = self._cache_key(method)
        method_name = self._rpc_method_identifier_cache[service_key][method_key]
        request_type = self.service_type_path(method.request_type)
        response_type = self.service_type_path(method.response_type)
        mode = streaming_mode(method)
        lines = [f"virtual ::grpc::Status {method_name}("]
        if mode == StreamingMode.UNARY:
            lines.extend(
                [
                    "    ::grpc::ServerContext* context,",
                    f"    const {request_type}* request,",
                    f"    {response_type}* response) = 0;",
                ]
            )
        elif mode == StreamingMode.CLIENT_STREAMING:
            lines.extend(
                [
                    "    ::grpc::ServerContext* context,",
                    f"    ::grpc::ServerReader<{request_type}>* reader,",
                    f"    {response_type}* response) = 0;",
                ]
            )
        elif mode == StreamingMode.SERVER_STREAMING:
            lines.extend(
                [
                    "    ::grpc::ServerContext* context,",
                    f"    const {request_type}* request,",
                    f"    ::grpc::ServerWriter<{response_type}>* writer) = 0;",
                ]
            )
        else:
            lines.extend(
                [
                    "    ::grpc::ServerContext* context,",
                    "    ::grpc::ServerReaderWriter<",
                    f"        {response_type}, {request_type}>* stream) = 0;",
                ]
            )
        return lines

    def service_type_path(self, named_type: NamedType) -> str:
        """Get C++ path for a gRPC request or response type."""
        resolved = self.schema.get_type(named_type.name)
        if resolved is None:
            raise ValueError(f"Unknown gRPC message type {named_type.name!r}")
        return self.generate_namespaced_type(
            named_type, parent_stack=None, global_qualify=True
        )

    def generate_service_constants(self, service: Service) -> List[str]:
        """Generate service and gRPC path constants."""
        lines: List[str] = []
        service_key = self._cache_key(service)
        service_name_const = self._service_name_constant_identifier_cache[service_key]
        service_name = self.get_grpc_service_name(service)
        lines.append(
            f'inline constexpr char {service_name_const}[] = "{service_name}";'
        )
        for method in service.methods:
            method_key = self._cache_key(method)
            rpc_path_const = self._rpc_path_constant_identifier_cache[service_key][
                method_key
            ]
            lines.append(
                f"inline constexpr char {rpc_path_const}[] = "
                f'"{self.get_grpc_method_path(service, method)}";'
            )
        return lines

    def generate_service_grpc_header(self, services: List[Service]) -> GeneratedFile:
        """Generate C++ service transport binding header file (service_grpc.h)."""
        path = f"{self.get_header_name()}_service_grpc.h"
        guard = self._cpp_header_guard(path)
        lines = self._cpp_license_lines()
        lines.extend(
            [
                "",
                f"#ifndef {guard}",
                f"#define {guard}",
                "",
                f'#include "{self.get_header_name()}_service.h"',
                "",
                "#include <cstddef>",
                "#include <cstdint>",
                "#include <memory>",
                "#include <utility>",
                "#include <vector>",
                "",
                "#include <grpc/event_engine/memory_allocator.h>",
                "#include <grpcpp/channel.h>",
                "#include <grpcpp/client_context.h>",
                "#include <grpcpp/impl/rpc_method.h>",
                "#include <grpcpp/impl/serialization_traits.h>",
                "#include <grpcpp/impl/service_type.h>",
                "#include <grpcpp/support/byte_buffer.h>",
                "#include <grpcpp/support/slice.h>",
                "#include <grpcpp/support/status.h>",
                "#include <grpcpp/support/stub_options.h>",
                "#include <grpcpp/support/sync_stream.h>",
                "",
            ]
        )
        lines.extend(self.generate_grpc_codec())
        payload_impls = self.generate_grpc_payload_impls(services)
        if payload_impls:
            lines.append("")
            lines.extend(payload_impls)
        grpc_namespace = f"{self.service_namespace()}::grpc"
        lines.extend(["", f"namespace {grpc_namespace} {{"])
        for service in services:
            lines.append("")
            lines.extend(self.generate_grpc_client_declaration(service))
            lines.append("")
            lines.extend(self.generate_grpc_server_declaration(service))
        lines.extend(["", f"}}  // namespace {grpc_namespace}"])
        lines.extend(["", f"#endif  // {guard}", ""])  # End header guard.
        return GeneratedFile(path=path, content="\n".join(lines))

    def generate_grpc_codec(self) -> List[str]:
        """Generate Fory-backed codec used by C++ gRPC stubs, instead of relying on a separate file."""
        return [
            "#ifndef FORY_GENERATED_GRPC_SERIALIZATION_TRAITS_",
            "#define FORY_GENERATED_GRPC_SERIALIZATION_TRAITS_",
            "",
            "namespace fory::grpc::detail {",
            "",
            "template <class Message>",
            "class ForyGrpcSerializationTraits {",
            " public:",
            "  static ::grpc::Status Serialize(",
            "      ::grpc_event_engine::experimental::MemoryAllocator* allocator,",
            "      const Message& message, ::grpc::ByteBuffer* buffer,",
            "      bool* own_buffer) {",
            "    (void)allocator;",
            "    return Serialize(message, buffer, own_buffer);",
            "  }",
            "",
            "  static ::grpc::Status Serialize(const Message& message,",
            "                                  ::grpc::ByteBuffer* buffer,",
            "                                  bool* own_buffer) {",
            "    if (buffer == nullptr || own_buffer == nullptr) {",
            "      return ::grpc::Status(::grpc::StatusCode::INTERNAL,",
            '                            "Missing gRPC serialization output");',
            "    }",
            "    auto result = message.to_bytes();",
            "    if (!result.ok()) {",
            "      return ::grpc::Status(::grpc::StatusCode::INTERNAL,",
            "                            result.error().to_string());",
            "    }",
            "    auto* bytes = new ::std::vector<::uint8_t>(",
            "        ::std::move(result).value());",
            "    ::grpc::Slice slice;",
            "    if (bytes->empty()) {",
            "      delete bytes;",
            "      slice = ::grpc::Slice(0);",
            "    } else {",
            "      slice = ::grpc::Slice(bytes->data(), bytes->size(),",
            "                            &ForyGrpcSerializationTraits::DestroyBytes,",
            "                            bytes);",
            "    }",
            "    ::grpc::ByteBuffer encoded(&slice, 1);",
            "    buffer->Swap(&encoded);",
            "    *own_buffer = true;",
            "    return ::grpc::Status::OK;",
            "  }",
            "",
            "  static ::grpc::Status Deserialize(::grpc::ByteBuffer* buffer,",
            "                                    Message* message) {",
            "    if (buffer == nullptr || message == nullptr || !buffer->Valid()) {",
            "      return ::grpc::Status(::grpc::StatusCode::INTERNAL,",
            '                            "Missing gRPC message bytes");',
            "    }",
            "    ::grpc::Slice slice;",
            "    auto status = buffer->TrySingleSlice(&slice);",
            "    if (!status.ok()) {",
            "      status = buffer->DumpToSingleSlice(&slice);",
            "      if (!status.ok()) {",
            "        return status;",
            "      }",
            "    }",
            "    auto result = Message::from_bytes(slice.begin(), slice.size());",
            "    buffer->Clear();",
            "    if (!result.ok()) {",
            "      return ::grpc::Status(::grpc::StatusCode::INTERNAL,",
            "                            result.error().to_string());",
            "    }",
            "    *message = ::std::move(result).value();",
            "    return ::grpc::Status::OK;",
            "  }",
            "",
            " private:",
            "  static void DestroyBytes(void* data) {",
            "    delete static_cast<::std::vector<::uint8_t>*>(data);",
            "  }",
            "};",
            "",
            "}  // namespace fory::grpc::detail",
            "",
            "#endif  // FORY_GENERATED_GRPC_SERIALIZATION_TRAITS_",
        ]

    def generate_grpc_payload_impls(self, services: List[Service]) -> List[str]:
        """Generate `SerializationTraits` specializations for all gRPC payload types."""
        lines: List[str] = []
        for i, type_path in enumerate(self.grpc_payload_type_paths(services)):
            if i > 0:
                lines.append("")
            lines.extend(self.generate_grpc_payload_impl(type_path))
        return lines

    def grpc_payload_type_paths(self, services: List[Service]) -> List[str]:
        """Get unique request and response type paths in service order."""
        seen: Set[str] = set()
        type_paths: List[str] = []
        for service in services:
            for method in service.methods:
                for named_type in (method.request_type, method.response_type):
                    type_path = self.service_type_path(named_type)
                    if type_path in seen:
                        continue
                    seen.add(type_path)
                    type_paths.append(type_path)
        return type_paths

    def generate_grpc_payload_impl(self, type_path: str) -> List[str]:
        """Generate `SerializationTraits` specializations for one gRPC request or response type."""
        # Each type-specific `grpc::SerializationTraits` specialization needs a
        # stable include guard because the same payload type can be referenced by
        # multiple generated service headers.
        # See the test_cpp_grpc_shared_message_specialization_guard case in test_service_codegen.py as an example.
        # C++ type paths such as `::demo::greeter::Hello` are not valid macro identifiers,
        # so the guard uses a readable sanitized prefix like `DEMO_GREETER_HELLO` plus a digest
        # of the original type path to avoid collisions.
        readable = re.sub(r"[^A-Za-z0-9]+", "_", type_path).strip("_").upper()
        # We use sha256 to compute the digest since its stability.
        digest = hashlib.sha256(type_path.encode("utf-8")).hexdigest()[:12].upper()
        guard = f"FORY_GENERATED_GRPC_TRAITS_{readable}_{digest}_"
        return [
            f"#ifndef {guard}",
            f"#define {guard}",
            "namespace grpc {",
            "template <>",
            f"class SerializationTraits<{type_path}, void>",
            "    : public ::fory::grpc::detail::ForyGrpcSerializationTraits<",
            f"          {type_path}> {{}};",
            "}  // namespace grpc",
            f"#endif  // {guard}",
        ]

    def generate_service_grpc_source(self, services: List[Service]) -> GeneratedFile:
        """Generate gRPC client and server transport definitions (service_grpc.cc)."""
        path = f"{self.get_header_name()}_service_grpc.cc"
        lines = self._cpp_license_lines()
        lines.extend(
            [
                "",
                f'#include "{self.get_header_name()}_service_grpc.h"',
                "",
                "#include <grpcpp/impl/client_unary_call.h>",
                "#include <grpcpp/impl/rpc_service_method.h>",
                "#include <grpcpp/support/method_handler.h>",
                "",
            ]
        )
        grpc_namespace = f"{self.service_namespace()}::grpc"
        lines.extend([f"namespace {grpc_namespace} {{", ""])
        for i, service in enumerate(services):
            if i > 0:
                lines.append("")
            lines.extend(self.generate_grpc_client_definition(service))
            lines.append("")
            lines.extend(self.generate_grpc_server_definition(service))
        lines.extend(["", f"}}  // namespace {grpc_namespace}"])
        lines.append("")
        return GeneratedFile(path=path, content="\n".join(lines))

    def generate_grpc_client_declaration(self, service: Service) -> List[str]:
        """Generate gRPC client declaration in service_grpc.h."""
        service_key = self._cache_key(service)
        stub_name = self._service_stub_identifier_cache[service_key]
        lines = [
            f"class {stub_name} final {{",
            " public:",
        ]
        lines.extend(
            self.indent_lines(
                [
                    f"static ::std::unique_ptr<{stub_name}> NewStub(",
                    "    const ::std::shared_ptr<::grpc::ChannelInterface>& channel,",
                    "    const ::grpc::StubOptions& options = ::grpc::StubOptions());",
                ],
                1,
            )
        )
        for method in service.methods:
            lines.append("")
            lines.extend(
                self.indent_lines(
                    self.generate_grpc_client_method_declaration(service, method), 1
                )
            )
        lines.extend(
            [
                "",
                " private:",
            ]
        )
        lines.extend(
            self.indent_lines(
                [
                    f"{stub_name}(",
                    "    const ::std::shared_ptr<::grpc::ChannelInterface>& channel,",
                    "    const ::grpc::StubOptions& options);",
                ],
                1,
            )
        )
        lines.append("")
        lines.extend(
            self.indent_lines(
                ["::std::shared_ptr<::grpc::ChannelInterface> channel_;"], 1
            )
        )
        for method in service.methods:
            member = self._rpc_method_member_identifier_cache[service_key][
                self._cache_key(method)
            ]
            lines.extend(
                self.indent_lines([f"::grpc::internal::RpcMethod {member};"], 1)
            )
        lines.append("};")
        return lines

    def generate_grpc_client_definition(self, service: Service) -> List[str]:
        """Generate gRPC client implementation in service_grpc.cc."""
        service_key = self._cache_key(service)
        stub_name = self._service_stub_identifier_cache[service_key]
        lines = [
            f"::std::unique_ptr<{stub_name}> {stub_name}::NewStub(",
            "    const ::std::shared_ptr<::grpc::ChannelInterface>& channel,",
            "    const ::grpc::StubOptions& options) {",
            f"  return ::std::unique_ptr<{stub_name}>(new {stub_name}(channel, options));",
            "}",
            "",
            f"{stub_name}::{stub_name}(",
            "    const ::std::shared_ptr<::grpc::ChannelInterface>& channel,",
            "    const ::grpc::StubOptions& options)",
            f"    : channel_(channel){',' if service.methods else ''}",
        ]
        for i, method in enumerate(service.methods):
            method_key = self._cache_key(method)
            member = self._rpc_method_member_identifier_cache[service_key][method_key]
            path_constant = self.service_path_constant_type_path(service, method)
            method_type = self._grpc_method_type(method)
            suffix = "," if i + 1 < len(service.methods) else ""
            lines.extend(
                [
                    f"      {member}({path_constant}, options.suffix_for_stats(),",
                    f"                 ::grpc::internal::RpcMethod::{method_type}, channel){suffix}",
                ]
            )
        lines.append("{}")
        for method in service.methods:
            lines.append("")
            lines.extend(self.generate_grpc_client_method_definition(service, method))
        return lines

    def generate_grpc_client_method_declaration(
        self, service: Service, method: RpcMethod
    ) -> List[str]:
        """Generate gRPC client method declaration in service_grpc.h."""
        service_key = self._cache_key(service)
        method_name = self._rpc_method_identifier_cache[service_key][
            self._cache_key(method)
        ]
        request_type = self.service_type_path(method.request_type)
        response_type = self.service_type_path(method.response_type)
        mode = streaming_mode(method)
        if mode == StreamingMode.UNARY:
            return [
                f"::grpc::Status {method_name}(",
                "    ::grpc::ClientContext* context,",
                f"    const {request_type}& request, {response_type}* response);",
            ]
        if mode == StreamingMode.CLIENT_STREAMING:
            return [
                f"::std::unique_ptr<::grpc::ClientWriter<{request_type}>> {method_name}(",
                "    ::grpc::ClientContext* context,",
                f"    {response_type}* response);",
            ]
        if mode == StreamingMode.SERVER_STREAMING:
            return [
                f"::std::unique_ptr<::grpc::ClientReader<{response_type}>> {method_name}(",
                "    ::grpc::ClientContext* context,",
                f"    const {request_type}& request);",
            ]
        return [
            "::std::unique_ptr<::grpc::ClientReaderWriter<",
            f"    {request_type}, {response_type}>> {method_name}(",
            "    ::grpc::ClientContext* context);",
        ]

    def generate_grpc_client_method_definition(
        self, service: Service, method: RpcMethod
    ) -> List[str]:
        """Generate gRPC client method implementation in service_grpc.cc."""
        service_key = self._cache_key(service)
        method_key = self._cache_key(method)
        stub_name = self._service_stub_identifier_cache[service_key]
        method_name = self._rpc_method_identifier_cache[service_key][method_key]
        member = self._rpc_method_member_identifier_cache[service_key][method_key]
        request_type = self.service_type_path(method.request_type)
        response_type = self.service_type_path(method.response_type)
        mode = streaming_mode(method)
        if mode == StreamingMode.UNARY:
            return [
                f"::grpc::Status {stub_name}::{method_name}(",
                "    ::grpc::ClientContext* context,",
                f"    const {request_type}& request, {response_type}* response) {{",
                "  return ::grpc::internal::BlockingUnaryCall<",
                f"      {request_type}, {response_type}>(",
                f"      channel_.get(), {member}, context, request, response);",
                "}",
            ]
        if mode == StreamingMode.CLIENT_STREAMING:
            return [
                f"::std::unique_ptr<::grpc::ClientWriter<{request_type}>>",
                f"{stub_name}::{method_name}(::grpc::ClientContext* context,",
                f"                         {response_type}* response) {{",
                f"  return ::std::unique_ptr<::grpc::ClientWriter<{request_type}>>(",
                f"      ::grpc::internal::ClientWriterFactory<{request_type}>::Create(",
                f"          channel_.get(), {member}, context, response));",
                "}",
            ]
        if mode == StreamingMode.SERVER_STREAMING:
            return [
                f"::std::unique_ptr<::grpc::ClientReader<{response_type}>>",
                f"{stub_name}::{method_name}(::grpc::ClientContext* context,",
                f"                         const {request_type}& request) {{",
                f"  return ::std::unique_ptr<::grpc::ClientReader<{response_type}>>(",
                f"      ::grpc::internal::ClientReaderFactory<{response_type}>::Create(",
                f"          channel_.get(), {member}, context, request));",
                "}",
            ]
        return [
            "::std::unique_ptr<::grpc::ClientReaderWriter<",
            f"    {request_type}, {response_type}>>",
            f"{stub_name}::{method_name}(::grpc::ClientContext* context) {{",
            "  return ::std::unique_ptr<::grpc::ClientReaderWriter<",
            f"      {request_type}, {response_type}>>(",
            "      ::grpc::internal::ClientReaderWriterFactory<",
            f"          {request_type}, {response_type}>::Create(",
            f"          channel_.get(), {member}, context));",
            "}",
        ]

    def generate_grpc_server_declaration(self, service: Service) -> List[str]:
        """Generate gRPC server declaration in service_grpc.h."""
        service_key = self._cache_key(service)
        interface_type = self.service_interface_type_path(service)
        grpc_name = self._service_grpc_identifier_cache[service_key]
        lines = [
            f"class {grpc_name} final : public ::grpc::Service {{",
            " public:",
        ]
        lines.extend(
            self.indent_lines(
                [
                    "// The caller owns impl; it must outlive this adapter and server.",
                    f"explicit {grpc_name}({interface_type}* impl);",
                ],
                1,
            )
        )
        lines.extend(["", " private:"])
        lines.extend(self.indent_lines([f"{interface_type}* impl_;"], 1))
        lines.append("};")
        return lines

    def generate_grpc_server_definition(self, service: Service) -> List[str]:
        """Generate gRPC server implementation in service_grpc.cc."""
        service_key = self._cache_key(service)
        interface_type = self.service_interface_type_path(service)
        grpc_name = self._service_grpc_identifier_cache[service_key]
        lines = [f"{grpc_name}::{grpc_name}({interface_type}* impl) : impl_(impl) {{"]
        for method in service.methods:
            lines.extend(
                self.indent_lines(self.generate_grpc_server_route(service, method), 1)
            )
        lines.append("}")
        return lines

    def generate_grpc_server_route(
        self, service: Service, method: RpcMethod
    ) -> List[str]:
        """Generate one RpcServiceMethod registration."""
        service_key = self._cache_key(service)
        method_key = self._cache_key(method)
        interface_type = self.service_interface_type_path(service)
        method_name = self._rpc_method_identifier_cache[service_key][method_key]
        path_constant = self.service_path_constant_type_path(service, method)
        request_type = self.service_type_path(method.request_type)
        response_type = self.service_type_path(method.response_type)
        method_type = self._grpc_method_type(method)
        mode = streaming_mode(method)
        lines = [
            "AddMethod(new ::grpc::internal::RpcServiceMethod(",
            f"    {path_constant}, ::grpc::internal::RpcMethod::{method_type},",
        ]
        if mode == StreamingMode.UNARY:
            lines.extend(
                [
                    "    new ::grpc::internal::RpcMethodHandler<",
                    f"        {interface_type}, {request_type}, {response_type}>(",
                    f"        []({interface_type}* service, ::grpc::ServerContext* context,",
                    f"           const {request_type}* request, {response_type}* response) {{",
                    f"          return service->{method_name}(context, request, response);",
                    "        },",
                ]
            )
        elif mode == StreamingMode.CLIENT_STREAMING:
            lines.extend(
                [
                    "    new ::grpc::internal::ClientStreamingHandler<",
                    f"        {interface_type}, {request_type}, {response_type}>(",
                    f"        []({interface_type}* service, ::grpc::ServerContext* context,",
                    f"           ::grpc::ServerReader<{request_type}>* reader,",
                    f"           {response_type}* response) {{",
                    f"          return service->{method_name}(context, reader, response);",
                    "        },",
                ]
            )
        elif mode == StreamingMode.SERVER_STREAMING:
            lines.extend(
                [
                    "    new ::grpc::internal::ServerStreamingHandler<",
                    f"        {interface_type}, {request_type}, {response_type}>(",
                    f"        []({interface_type}* service, ::grpc::ServerContext* context,",
                    f"           const {request_type}* request,",
                    f"           ::grpc::ServerWriter<{response_type}>* writer) {{",
                    f"          return service->{method_name}(context, request, writer);",
                    "        },",
                ]
            )
        else:
            lines.extend(
                [
                    "    new ::grpc::internal::BidiStreamingHandler<",
                    f"        {interface_type}, {request_type}, {response_type}>(",
                    f"        []({interface_type}* service, ::grpc::ServerContext* context,",
                    "           ::grpc::ServerReaderWriter<",
                    f"               {response_type}, {request_type}>* stream) {{",
                    f"          return service->{method_name}(context, stream);",
                    "        },",
                ]
            )
        lines.extend(["        impl_)));", ""])
        return lines

    def _grpc_method_type(self, method: RpcMethod) -> str:
        mode = streaming_mode(method)
        return {
            StreamingMode.UNARY: "NORMAL_RPC",
            StreamingMode.CLIENT_STREAMING: "CLIENT_STREAMING",
            StreamingMode.SERVER_STREAMING: "SERVER_STREAMING",
            StreamingMode.BIDIRECTIONAL: "BIDI_STREAMING",
        }[mode]

    def service_namespace(self) -> str:
        schema_namespace = self.get_namespace()
        if schema_namespace:
            return f"{schema_namespace}::service"
        return "service"

    def service_interface_type_path(self, service: Service) -> str:
        service_key = self._cache_key(service)
        interface_name = self._service_interface_identifier_cache[service_key]
        return f"::{self.service_namespace()}::{interface_name}"

    def service_path_constant_type_path(
        self, service: Service, method: RpcMethod
    ) -> str:
        service_key = self._cache_key(service)
        method_key = self._cache_key(method)
        constant = self._rpc_path_constant_identifier_cache[service_key][method_key]
        return f"::{self.service_namespace()}::{constant}"
