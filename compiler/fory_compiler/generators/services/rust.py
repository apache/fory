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

"""Rust gRPC service generator helpers."""

from typing import Dict, List, Optional, Set, Tuple

from fory_compiler.generators.base import GeneratedFile
from fory_compiler.ir.ast import (
    ArrayType,
    Field,
    FieldType,
    ListType,
    MapType,
    Message,
    NamedType,
    PrimitiveType,
    RpcMethod,
    Service,
    Union,
)


class RustServiceGeneratorMixin:
    """Generates Rust gRPC service companions."""

    def generate_services(self) -> List[GeneratedFile]:
        """Generate Rust grpc service companion module."""
        local_services = [
            service
            for service in self.schema.services
            if not self.is_imported_type(service)
        ]
        if not local_services:
            return []
        self.validate_grpc_payload_safe(local_services)
        self.allocate_grpc_service_identifiers(local_services)
        return [
            self.generate_service_api_module(local_services),
            self.generate_service_grpc_module(local_services),
        ]

    def allocate_grpc_service_identifiers(self, services: List[Service]) -> None:
        """Allocate sanitized identifiers used by generated gRPC code."""
        self._ensure_name_caches(self.schema)
        schema_id = id(self.schema)
        if schema_id in self._named_service_schema_ids:
            return
        used_traits: Dict[str, str] = {}
        used_modules: Dict[str, str] = {}
        used_constants: Dict[str, str] = {}
        for service in services:
            service_key = self._cache_key(service)
            self._service_trait_identifier_cache[service_key] = (
                self._allocate_scoped_identifier(
                    self.to_pascal_case(service.name),
                    used_traits,
                    "Rust gRPC service traits",
                    service.name,
                )
            )
            self._service_client_module_identifier_cache[service_key] = (
                self._allocate_scoped_identifier(
                    f"{self.to_snake_case(service.name)}_client",
                    used_modules,
                    "Rust gRPC service modules",
                    f"{service.name} client module",
                )
            )
            self._service_server_module_identifier_cache[service_key] = (
                self._allocate_scoped_identifier(
                    f"{self.to_snake_case(service.name)}_server",
                    used_modules,
                    "Rust gRPC service modules",
                    f"{service.name} server module",
                )
            )
            self._service_name_constant_identifier_cache[service_key] = (
                self._allocate_scoped_identifier(
                    f"{self.to_upper_snake_case(service.name)}_SERVICE_NAME",
                    used_constants,
                    "Rust gRPC service constants",
                    service.name,
                )
            )
            used_methods: Dict[str, str] = {}
            used_stream_types: Dict[str, str] = {}
            method_names: Dict[Tuple[object, ...], str] = {}
            stream_types: Dict[Tuple[object, ...], str] = {}
            path_constants: Dict[Tuple[object, ...], str] = {}
            for method in service.methods:
                method_key = self._cache_key(method)
                method_name = self.to_snake_case(method.name)
                # Client stub code uses `new` and `connect` as helper method names.
                if method_name in {"connect", "new"}:
                    method_name = f"{method_name}_"
                method_names[method_key] = self._allocate_scoped_identifier(
                    method_name,
                    used_methods,
                    f"Rust gRPC service {service.name} methods",
                    method.name,
                )
                if method.server_streaming:
                    stream_types[method_key] = self._allocate_scoped_identifier(
                        f"{self.to_pascal_case(method.name)}Stream",
                        used_stream_types,
                        f"Rust gRPC service {service.name} stream types",
                        method.name,
                    )
                path_constants[method_key] = self._allocate_scoped_identifier(
                    f"{self.to_upper_snake_case(service.name)}_"
                    f"{self.to_upper_snake_case(method.name)}_PATH",
                    used_constants,
                    "Rust gRPC service constants",
                    f"{service.name}.{method.name}",
                )
            self._rpc_method_identifier_cache[service_key] = method_names
            self._rpc_stream_type_identifier_cache[service_key] = stream_types
            self._rpc_path_constant_identifier_cache[service_key] = path_constants
        self._named_service_schema_ids.add(schema_id)

    def grpc_payload_root_names(self, services: List[Service]) -> Set[str]:
        names: Set[str] = set()
        for service in services:
            for method in service.methods:
                names.add(method.request_type.name)
                names.add(method.response_type.name)
        return names

    def validate_grpc_payload_safe(self, services: List[Service]) -> None:
        # Make sure all gRPC payload types are thread-safe. For now only reject pointer with `thread_safe=false`.
        reachable: Set[int] = set()

        def reject_explicit_non_thread_safe_ref(
            path: List[str], description: str, ref_options: dict
        ) -> None:
            if ref_options.get("thread_safe_pointer") is False:
                raise ValueError(
                    f"Rust gRPC payload type {'.'.join(path)} uses non-thread-safe {description}; "
                    "remove thread_safe=false or use the default thread-safe pointer"
                )

        def visit_named(
            type_name: str,
            path: List[str],
            parent_stack: Optional[List[Message]] = None,
        ) -> None:
            resolved = self.resolve_named_type(type_name, parent_stack)
            if resolved is None:
                return
            key = id(resolved)
            if key in reachable:
                return
            reachable.add(key)
            owner_stack = self._parent_stack_for_type(resolved)
            if isinstance(resolved, Message):
                lineage = owner_stack + [resolved]
                for field in resolved.fields:
                    visit_field(field, path + [field.name], lineage)
            elif isinstance(resolved, Union):
                for field in resolved.fields:
                    visit_field(
                        field,
                        path + [self.to_pascal_case(field.name)],
                        owner_stack,
                    )

        def visit_field(
            field: Field,
            path: List[str],
            parent_stack: Optional[List[Message]],
        ) -> None:
            if field.ref:
                reject_explicit_non_thread_safe_ref(path, "ref", field.ref_options)
            if isinstance(field.field_type, ListType) and field.element_ref:
                reject_explicit_non_thread_safe_ref(
                    path, "list element ref", field.element_ref_options
                )
            visit_field_type(field.field_type, path, parent_stack)

        def visit_field_type(
            field_type: FieldType,
            path: List[str],
            parent_stack: Optional[List[Message]],
        ) -> None:
            if isinstance(field_type, PrimitiveType):
                return
            if isinstance(field_type, NamedType):
                visit_named(field_type.name, path + [field_type.name], parent_stack)
            elif isinstance(field_type, ListType):
                if field_type.element_ref:
                    reject_explicit_non_thread_safe_ref(
                        path, "list element ref", field_type.element_ref_options
                    )
                visit_field_type(field_type.element_type, path, parent_stack)
            elif isinstance(field_type, ArrayType):
                visit_field_type(field_type.element_type, path, parent_stack)
            elif isinstance(field_type, MapType):
                if field_type.value_ref:
                    reject_explicit_non_thread_safe_ref(
                        path, "map value ref", field_type.value_ref_options
                    )
                visit_field_type(field_type.key_type, path, parent_stack)
                visit_field_type(field_type.value_type, path, parent_stack)

        for root in self.grpc_payload_root_names(services):
            visit_named(root, [root])

    def generate_service_api_module(self, services: List[Service]) -> GeneratedFile:
        """Generate Rust service API module (service.rs)."""
        lines: List[str] = []
        lines.append(self.get_license_header("//"))
        lines.append("")
        for i, service in enumerate(services):
            if i > 0:
                lines.append("")
            lines.extend(self.generate_service_trait(service))
            lines.append("")
            lines.extend(self.generate_service_constants(service))
        return GeneratedFile(
            path=f"{self.get_module_name()}_service.rs",
            content="\n".join(lines).rstrip() + "\n",
        )

    def generate_service_trait(self, service: Service) -> List[str]:
        """Generate tonic service trait."""
        service_key = self._cache_key(service)
        trait_name = self._service_trait_identifier_cache[service_key]
        lines: List[str] = []
        lines.append("#[::tonic::async_trait]")
        lines.append(
            f"pub trait {trait_name}: ::std::marker::Send + ::std::marker::Sync + 'static {{"
        )
        for i, method in enumerate(service.methods):
            if i > 0:
                lines.append("")
            lines.extend(
                self.indent_lines(
                    self.generate_grpc_method_signature(service, method), 1
                )
            )
        lines.append("}")
        return lines

    def generate_grpc_method_signature(
        self, service: Service, method: RpcMethod
    ) -> List[str]:
        """Generate tonic service trait method signature."""
        service_key = self._cache_key(service)
        method_key = self._cache_key(method)
        method_name = self._rpc_method_identifier_cache[service_key][method_key]
        request_type = self.service_type_path(method.request_type)
        response_type = self.service_type_path(method.response_type)
        lines: List[str] = []
        if method.client_streaming:
            request_arg_type = f"::tonic::Request<::tonic::Streaming<{request_type}>>"
        else:
            request_arg_type = f"::tonic::Request<{request_type}>"
        if method.server_streaming:
            stream_type = self._rpc_stream_type_identifier_cache[service_key][
                method_key
            ]
            response_return_type = f"::tonic::Response<Self::{stream_type}>"
            lines.extend(
                [
                    f"type {stream_type}: ::tonic::codegen::tokio_stream::Stream<",
                    f"    Item = ::std::result::Result<{response_type}, ::tonic::Status>,",
                    "> + ::std::marker::Send + 'static;",
                    "",
                ]
            )
        else:
            response_return_type = f"::tonic::Response<{response_type}>"
        lines.extend(
            [
                f"async fn {method_name}(",
                "    &self,",
                f"    request: {request_arg_type},",
                ") -> ::std::result::Result<",
                f"    {response_return_type},",
                "    ::tonic::Status,",
                ">;",
            ]
        )
        return lines

    def service_type_path(self, named_type: NamedType) -> str:
        """Get Rust path for a gRPC request or response type."""
        resolved = self.resolve_named_type(named_type.name, parent_stack=None)
        if resolved is None:
            raise ValueError(f"Unknown gRPC payload type {named_type.name!r}")
        type_path = self.get_type_path(resolved, self._parent_stack_for_type(resolved))
        if self.is_imported_type(resolved):
            module = self._module_name_for_type(resolved)
            return f"crate::{module}::{type_path}"
        module = self.get_top_level_module_identifier(self.package)
        return f"crate::{module}::{type_path}"

    def generate_service_constants(self, service: Service) -> List[str]:
        """Generate service and gRPC path constants."""
        # e.g. `pub const GREETER_SERVICE_NAME: &str = "demo.greeter.Greeter";` and
        # `pub const GREETER_SAY_HELLO_PATH: &str = "/demo.greeter.Greeter/SayHello";`
        lines: List[str] = []
        service_key = self._cache_key(service)
        service_name_const = self._service_name_constant_identifier_cache[service_key]
        service_name = self.get_grpc_service_name(service)
        lines.append(
            f"pub const {service_name_const}: &str = "
            f"{self.rust_string_literal(service_name)};"
        )
        for method in service.methods:
            method_key = self._cache_key(method)
            rpc_path_const = self._rpc_path_constant_identifier_cache[service_key][
                method_key
            ]
            lines.append(
                f"pub const {rpc_path_const}: &str = "
                f"{self.rust_string_literal(self.get_grpc_method_path(service, method))};"
            )
        return lines

    def generate_service_grpc_module(self, services: List[Service]) -> GeneratedFile:
        """Generate Rust service tonic binding module (service_grpc.rs)."""
        lines: List[str] = []
        lines.append(self.get_license_header("//"))
        lines.append("")
        lines.extend(self.generate_grpc_codec_module())
        payload_impls = self.generate_grpc_payload_impls(services)
        if payload_impls:
            lines.append("")
            lines.extend(payload_impls)
        for service in services:
            lines.append("")
            lines.extend(self.generate_grpc_client_module(service))
            lines.append("")
            lines.extend(self.generate_grpc_server_module(service))
        return GeneratedFile(
            path=f"{self.get_module_name()}_service_grpc.rs",
            content="\n".join(lines).rstrip() + "\n",
        )

    def generate_grpc_codec_module(self) -> List[str]:
        """Generate Fory-backed codec used by Rust gRPC stubs, instead of relying on a separate crate."""
        return [
            "pub mod codec {",
            "    pub trait ForyGrpcPayload: Sized + ::std::marker::Send + 'static {",
            "        fn encode_fory_payload(",
            "            &self,",
            "        ) -> ::std::result::Result<::std::vec::Vec<u8>, ::fory::Error>;",
            "",
            "        fn decode_fory_payload(",
            "            payload: &[u8],",
            "        ) -> ::std::result::Result<Self, ::fory::Error>;",
            "    }",
            "",
            "    #[derive(Debug, Clone)]",
            "    pub struct ForyCodec<Encode, Decode> {",
            "        marker: ::std::marker::PhantomData<(Encode, Decode)>,",
            "    }",
            "",
            "    impl<Encode, Decode> ForyCodec<Encode, Decode> {",
            "        pub fn new() -> Self {",
            "            Self {",
            "                marker: ::std::marker::PhantomData,",
            "            }",
            "        }",
            "    }",
            "",
            "    impl<Encode, Decode> ::std::default::Default for ForyCodec<Encode, Decode> {",
            "        fn default() -> Self {",
            "            Self::new()",
            "        }",
            "    }",
            "",
            "    impl<Encode, Decode> ::tonic::codec::Codec for ForyCodec<Encode, Decode>",
            "    where",
            "        Encode: ForyGrpcPayload,",
            "        Decode: ForyGrpcPayload,",
            "    {",
            "        type Encode = Encode;",
            "        type Decode = Decode;",
            "        type Encoder = ForyEncoder<Encode>;",
            "        type Decoder = ForyDecoder<Decode>;",
            "",
            "        fn encoder(&mut self) -> Self::Encoder {",
            "            ForyEncoder::default()",
            "        }",
            "",
            "        fn decoder(&mut self) -> Self::Decoder {",
            "            ForyDecoder::default()",
            "        }",
            "    }",
            "",
            "    #[derive(Debug, Clone)]",
            "    pub struct ForyEncoder<T> {",
            "        marker: ::std::marker::PhantomData<T>,",
            "    }",
            "",
            "    impl<T> ::std::default::Default for ForyEncoder<T> {",
            "        fn default() -> Self {",
            "            Self {",
            "                marker: ::std::marker::PhantomData,",
            "            }",
            "        }",
            "    }",
            "",
            "    #[derive(Debug, Clone)]",
            "    pub struct ForyDecoder<T> {",
            "        marker: ::std::marker::PhantomData<T>,",
            "    }",
            "",
            "    impl<T> ::std::default::Default for ForyDecoder<T> {",
            "        fn default() -> Self {",
            "            Self {",
            "                marker: ::std::marker::PhantomData,",
            "            }",
            "        }",
            "    }",
            "",
            "    fn fory_error_to_tonic_status(error: ::fory::Error) -> ::tonic::Status {",
            "        ::tonic::Status::internal(error.to_string())",
            "    }",
            "",
            "    impl<T> ::tonic::codec::Encoder for ForyEncoder<T>",
            "    where",
            "        T: ForyGrpcPayload,",
            "    {",
            "        type Item = T;",
            "        type Error = ::tonic::Status;",
            "",
            "        fn encode(",
            "            &mut self,",
            "            item: Self::Item,",
            "            dst: &mut ::tonic::codec::EncodeBuf<'_>,",
            "        ) -> ::std::result::Result<(), Self::Error> {",
            "            let bytes = item.encode_fory_payload().map_err(fory_error_to_tonic_status)?;",
            "            ::tonic::codec::EncodeBuf::reserve(dst, bytes.len());",
            "            ::bytes::BufMut::put_slice(dst, &bytes);",
            "            Ok(())",
            "        }",
            "    }",
            "",
            "    impl<T> ::tonic::codec::Decoder for ForyDecoder<T>",
            "    where",
            "        T: ForyGrpcPayload,",
            "    {",
            "        type Item = T;",
            "        type Error = ::tonic::Status;",
            "",
            "        fn decode(",
            "            &mut self,",
            "            src: &mut ::tonic::codec::DecodeBuf<'_>,",
            "        ) -> ::std::result::Result<::std::option::Option<Self::Item>, Self::Error> {",
            "            let len = ::bytes::Buf::remaining(src);",
            "            if len == 0 {",
            "                return Ok(None);",
            "            }",
            "",
            "            let chunk = ::bytes::Buf::chunk(src);",
            "            if chunk.len() == len {",
            "                let result =",
            "                    T::decode_fory_payload(chunk).map_err(fory_error_to_tonic_status);",
            "                ::bytes::Buf::advance(src, len);",
            "                return result.map(Some);",
            "            }",
            "",
            "            let payload = ::bytes::Buf::copy_to_bytes(src, len);",
            "            T::decode_fory_payload(&payload)",
            "                .map(Some)",
            "                .map_err(fory_error_to_tonic_status)",
            "        }",
            "    }",
            "}",
        ]

    def generate_grpc_payload_impls(self, services: List[Service]) -> List[str]:
        """Generate `ForyGrpcPayload` trait impls for all gRPC payload types."""
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
        """Generate `ForyGrpcPayload` trait impl for one gRPC request or response type."""
        return [
            f"impl codec::ForyGrpcPayload for {type_path} {{",
            "    fn encode_fory_payload(&self) -> ::std::result::Result<::std::vec::Vec<u8>, ::fory::Error> {",
            "        self.to_bytes()",
            "    }",
            "",
            "    fn decode_fory_payload(payload: &[u8]) -> ::std::result::Result<Self, ::fory::Error> {",
            "        Self::from_bytes(payload)",
            "    }",
            "}",
        ]

    def generate_grpc_client_module(self, service: Service) -> List[str]:
        """Generate tonic client module for one service."""
        lines: List[str] = []
        service_key = self._cache_key(service)
        client_module = self._service_client_module_identifier_cache[service_key]
        lines.append(f"pub mod {client_module} {{")
        lines.extend(self.indent_lines(self.generate_grpc_client_struct(service), 1))
        lines.append("")
        lines.extend(
            self.indent_lines(self.generate_grpc_client_connect_impl(service), 1)
        )
        lines.append("")
        lines.extend(self.indent_lines(self.generate_grpc_client_impl(service), 1))
        lines.append("}")
        return lines

    def generate_grpc_client_struct(self, service: Service) -> List[str]:
        """Generate tonic client wrapper struct."""
        service_key = self._cache_key(service)
        client_name = f"{self._service_trait_identifier_cache[service_key]}Client"
        return [
            "#[derive(Debug, Clone)]",
            f"pub struct {client_name}<T> {{",
            "    inner: ::tonic::client::Grpc<T>,",
            "}",
        ]

    def generate_grpc_client_connect_impl(self, service: Service) -> List[str]:
        """Generate tonic channel connect constructor."""
        service_key = self._cache_key(service)
        client_name = f"{self._service_trait_identifier_cache[service_key]}Client"
        return [
            f"impl {client_name}<::tonic::transport::Channel> {{",
            "    pub async fn connect<D>(",
            "        dst: D,",
            "    ) -> ::std::result::Result<Self, ::tonic::transport::Error>",
            "    where",
            "        D: ::std::convert::TryInto<::tonic::transport::Endpoint>,",
            "        D::Error: Into<::tonic::codegen::StdError>,",
            "    {",
            "        let conn = ::tonic::transport::Endpoint::new(dst)?.connect().await?;",
            "        Ok(Self::new(conn))",
            "    }",
            "}",
        ]

    def generate_grpc_client_impl(self, service: Service) -> List[str]:
        """Generate generic tonic client implementation."""
        service_key = self._cache_key(service)
        client_name = f"{self._service_trait_identifier_cache[service_key]}Client"
        lines: List[str] = [
            f"impl<T> {client_name}<T>",
            "where",
            "    T: ::tonic::client::GrpcService<::tonic::body::Body>,",
            "    T::Error: Into<::tonic::codegen::StdError>,",
            "    T::ResponseBody: ::tonic::codegen::Body<Data = ::tonic::codegen::Bytes>",
            "        + ::std::marker::Send",
            "        + 'static,",
            "    <T::ResponseBody as ::tonic::codegen::Body>::Error:",
            "        Into<::tonic::codegen::StdError> + ::std::marker::Send,",
            "{",
            "    pub fn new(inner: T) -> Self {",
            "        let inner = ::tonic::client::Grpc::new(inner);",
            "        Self { inner }",
            "    }",
        ]
        for method in service.methods:
            lines.append("")
            lines.extend(
                self.indent_lines(self.generate_grpc_client_method(service, method), 1)
            )
        lines.append("}")
        return lines

    def generate_grpc_client_method(
        self, service: Service, method: RpcMethod
    ) -> List[str]:
        """Generate a tonic client method."""
        service_key = self._cache_key(service)
        method_key = self._cache_key(method)
        method_name = self._rpc_method_identifier_cache[service_key][method_key]
        request_type = self.service_type_path(method.request_type)
        response_type = self.service_type_path(method.response_type)
        if method.client_streaming:
            request_arg_type = (
                f"impl ::tonic::IntoStreamingRequest<Message = {request_type}>"
            )
            request_into_method = "into_streaming_request"
        else:
            request_arg_type = f"impl ::tonic::IntoRequest<{request_type}>"
            request_into_method = "into_request"
        if method.server_streaming:
            response_return_type = (
                f"::tonic::Response<::tonic::codec::Streaming<{response_type}>>"
            )
        else:
            response_return_type = f"::tonic::Response<{response_type}>"
        if method.client_streaming and method.server_streaming:
            grpc_call = "streaming"
        elif method.client_streaming:
            grpc_call = "client_streaming"
        elif method.server_streaming:
            grpc_call = "server_streaming"
        else:
            grpc_call = "unary"
        service_module = f"crate::{self.get_module_name()}_service"
        service_name_const = f"{service_module}::{self._service_name_constant_identifier_cache[service_key]}"
        rpc_path_const = f"{service_module}::{self._rpc_path_constant_identifier_cache[service_key][method_key]}"
        return [
            f"pub async fn {method_name}(",
            "    &mut self,",
            f"    request: {request_arg_type},",
            ") -> ::std::result::Result<",
            f"    {response_return_type},",
            "    ::tonic::Status,",
            "> {",
            "    self.inner.ready().await.map_err(|e| {",
            '        ::tonic::Status::unknown(format!("Service was not ready: {}", e.into()))',
            "    })?;",
            "    let codec = super::codec::ForyCodec::<",
            f"        {request_type},",
            f"        {response_type},",
            "    >::default();",
            "    let path = ::tonic::codegen::http::uri::PathAndQuery::from_static(",
            f"        {rpc_path_const},",
            "    );",
            f"    let mut req = request.{request_into_method}();",
            "    req.extensions_mut().insert(::tonic::codegen::GrpcMethod::new(",
            f"        {service_name_const},",
            f"        {self.rust_string_literal(method.name)},",
            "    ));",
            f"    self.inner.{grpc_call}(req, path, codec).await",
            "}",
        ]

    def generate_grpc_server_module(self, service: Service) -> List[str]:
        """Generate tonic server module for one service."""
        lines: List[str] = []
        service_key = self._cache_key(service)
        server_module = self._service_server_module_identifier_cache[service_key]
        lines.append(f"pub mod {server_module} {{")
        lines.extend(self.indent_lines(self.generate_grpc_server_struct(service), 1))
        lines.append("")
        lines.extend(self.indent_lines(self.generate_grpc_server_impl(service), 1))
        lines.append("")
        lines.extend(
            self.indent_lines(self.generate_grpc_server_service_impl(service), 1)
        )
        lines.append("")
        lines.extend(
            self.indent_lines(self.generate_grpc_server_clone_impl(service), 1)
        )
        lines.append("")
        lines.extend(
            self.indent_lines(self.generate_grpc_server_named_impl(service), 1)
        )
        lines.append("}")
        return lines

    def generate_grpc_server_struct(self, service: Service) -> List[str]:
        """Generate tonic server wrapper struct."""
        service_key = self._cache_key(service)
        server_name = f"{self._service_trait_identifier_cache[service_key]}Server"
        return [
            "#[derive(Debug)]",
            f"pub struct {server_name}<T> {{",
            "    inner: ::std::sync::Arc<T>,",
            "}",
        ]

    def generate_grpc_server_impl(self, service: Service) -> List[str]:
        """Generate constructors for the tonic server wrapper."""
        service_key = self._cache_key(service)
        server_name = f"{self._service_trait_identifier_cache[service_key]}Server"
        return [
            f"impl<T> {server_name}<T> {{",
            "    pub fn new(inner: T) -> Self {",
            "        Self::from_arc(::std::sync::Arc::new(inner))",
            "    }",
            "",
            "    pub fn from_arc(inner: ::std::sync::Arc<T>) -> Self {",
            "        Self { inner }",
            "    }",
            "}",
        ]

    def generate_grpc_server_service_impl(self, service: Service) -> List[str]:
        """Generate tonic Service impl for the server wrapper."""
        service_key = self._cache_key(service)
        service_trait_identifier = self._service_trait_identifier_cache[service_key]
        server_name = f"{service_trait_identifier}Server"
        service_trait = (
            f"crate::{self.get_module_name()}_service::{service_trait_identifier}"
        )
        lines: List[str] = [
            (
                f"impl<T, B> ::tonic::codegen::Service<"
                f"::tonic::codegen::http::Request<B>> for {server_name}<T>"
            ),
            "where",
            f"    T: {service_trait},",
            "    B: ::tonic::codegen::Body + ::std::marker::Send + 'static,",
            "    B::Error: Into<::tonic::codegen::StdError> + ::std::marker::Send + 'static,",
            "{",
            "    type Response = ::tonic::codegen::http::Response<::tonic::body::Body>;",
            "    type Error = ::std::convert::Infallible;",
            "    type Future = ::tonic::codegen::BoxFuture<Self::Response, Self::Error>;",
            "",
            "    fn poll_ready(",
            "        &mut self,",
            "        _cx: &mut ::std::task::Context<'_>,",
            "    ) -> ::std::task::Poll<::std::result::Result<(), Self::Error>> {",
            "        ::std::task::Poll::Ready(Ok(()))",
            "    }",
            "",
            (
                "    fn call("
                "&mut self, req: ::tonic::codegen::http::Request<B>"
                ") -> Self::Future {"
            ),
            "        match req.uri().path() {",
        ]
        for method in service.methods:
            lines.extend(
                self.indent_lines(self.generate_grpc_server_route(service, method), 3)
            )
        lines.extend(
            self.indent_lines(self.generate_grpc_server_unimplemented_route(), 3)
        )
        lines.extend(
            [
                "        }",
                "    }",
                "}",
            ]
        )
        return lines

    def generate_grpc_server_route(
        self, service: Service, method: RpcMethod
    ) -> List[str]:
        """Generate one tonic server route arm."""
        service_key = self._cache_key(service)
        method_key = self._cache_key(method)
        service_trait_identifier = self._service_trait_identifier_cache[service_key]
        method_name = self._rpc_method_identifier_cache[service_key][method_key]
        service_trait = (
            f"crate::{self.get_module_name()}_service::{service_trait_identifier}"
        )
        request_type = self.service_type_path(method.request_type)
        response_type = self.service_type_path(method.response_type)
        method_service_name_base = (
            method_name[2:] if method_name.startswith("r#") else method_name
        )
        method_service_name = f"{self.to_pascal_case(method_service_name_base)}Svc"
        rpc_path_const = f"crate::{self.get_module_name()}_service::{self._rpc_path_constant_identifier_cache[service_key][method_key]}"
        if method.client_streaming:
            request_arg_type = f"::tonic::Request<::tonic::Streaming<{request_type}>>"
        else:
            request_arg_type = f"::tonic::Request<{request_type}>"
        if method.client_streaming and method.server_streaming:
            tonic_service_trait = "StreamingService"
            grpc_call = "streaming"
        elif method.client_streaming:
            tonic_service_trait = "ClientStreamingService"
            grpc_call = "client_streaming"
        elif method.server_streaming:
            tonic_service_trait = "ServerStreamingService"
            grpc_call = "server_streaming"
        else:
            tonic_service_trait = "UnaryService"
            grpc_call = "unary"
        lines = [
            f"{rpc_path_const} => {{",
            (
                f"    struct {method_service_name}<T: {service_trait}>"
                "(pub ::std::sync::Arc<T>);"
            ),
            "",
            f"    impl<T: {service_trait}> ::tonic::server::{tonic_service_trait}<{request_type}>",
            f"        for {method_service_name}<T>",
            "    {",
            f"        type Response = {response_type};",
        ]
        if method.server_streaming:
            stream_type = self._rpc_stream_type_identifier_cache[service_key][
                method_key
            ]
            future_response_type = "Self::ResponseStream"
            lines.append(f"        type ResponseStream = T::{stream_type};")
        else:
            future_response_type = "Self::Response"
        lines.extend(
            [
                "        type Future = ::tonic::codegen::BoxFuture<",
                f"            ::tonic::Response<{future_response_type}>,",
                "            ::tonic::Status,",
                "        >;",
                "",
                "        fn call(",
                "            &mut self,",
                f"            request: {request_arg_type},",
                "        ) -> Self::Future {",
                "            let inner = ::std::sync::Arc::clone(&self.0);",
                "            let fut = async move {",
                f"                <T as {service_trait}>::{method_name}(&inner, request).await",
                "            };",
                "            ::std::boxed::Box::pin(fut)",
                "        }",
                "    }",
                "",
                "    let inner = self.inner.clone();",
                "    let fut = async move {",
                f"        let method = {method_service_name}(inner);",
                "        let codec = super::codec::ForyCodec::<",
                f"            {response_type},",
                f"            {request_type},",
                "        >::default();",
                "        let mut grpc = ::tonic::server::Grpc::new(codec);",
                f"        let res = grpc.{grpc_call}(method, req).await;",
                "        Ok(res)",
                "    };",
                "    ::std::boxed::Box::pin(fut)",
                "}",
            ]
        )
        return lines

    def generate_grpc_server_unimplemented_route(self) -> List[str]:
        """Generate fallback route for unknown gRPC paths."""
        return [
            "_ => ::std::boxed::Box::pin(async move {",
            "    let mut response = ::tonic::codegen::http::Response::new(",
            "        ::tonic::body::Body::default(),",
            "    );",
            "    let headers = response.headers_mut();",
            "    headers.insert(",
            "        ::tonic::Status::GRPC_STATUS,",
            "        (::tonic::Code::Unimplemented as i32).into(),",
            "    );",
            "    headers.insert(",
            "        ::tonic::codegen::http::header::CONTENT_TYPE,",
            "        ::tonic::metadata::GRPC_CONTENT_TYPE,",
            "    );",
            "    Ok(response)",
            "}),",
        ]

    def generate_grpc_server_clone_impl(self, service: Service) -> List[str]:
        """Generate Clone impl for the tonic server wrapper."""
        service_key = self._cache_key(service)
        server_name = f"{self._service_trait_identifier_cache[service_key]}Server"
        return [
            f"impl<T> ::std::clone::Clone for {server_name}<T> {{",
            "    fn clone(&self) -> Self {",
            "        Self {",
            "            inner: self.inner.clone(),",
            "        }",
            "    }",
            "}",
        ]

    def generate_grpc_server_named_impl(self, service: Service) -> List[str]:
        """Generate NamedService impl for the tonic server wrapper."""
        service_key = self._cache_key(service)
        server_name = f"{self._service_trait_identifier_cache[service_key]}Server"
        service_name_const = f"crate::{self.get_module_name()}_service::{self._service_name_constant_identifier_cache[service_key]}"
        return [
            f"pub const SERVICE_NAME: &str = {service_name_const};",
            "",
            f"impl<T> ::tonic::server::NamedService for {server_name}<T> {{",
            "    const NAME: &'static str = SERVICE_NAME;",
            "}",
        ]
