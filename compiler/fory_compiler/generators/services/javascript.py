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

"""JavaScript gRPC service generator helpers."""

from typing import Dict, List, Set, Union as TypingUnion

from fory_compiler.generators.base import GeneratedFile
from fory_compiler.generators.services.base import StreamingMode, streaming_mode
from fory_compiler.ir.ast import Message, NamedType, RpcMethod, Service, Union


RootType = TypingUnion[Message, Union]
CLASS_METHOD_NAMES = {"constructor"}
SERVICE_MEMBER_NAMES = CLASS_METHOD_NAMES | {
    "call",
    "checkOptionalUnaryResponseArguments",
    "client",
    "close",
    "getChannel",
    "hostname",
    "makeBidiStreamRequest",
    "makeClientStreamRequest",
    "makeServerStreamRequest",
    "makeUnaryRequest",
    "then",
    "waitForReady",
    "wireFormat",
}
WEB_HELPER_TYPE_NAMES = {
    "ForyGrpcWebClientOptions",
    "ForyGrpcWebWireFormat",
    "GrpcWebMessageType",
}


class JavaScriptServiceGeneratorMixin:
    """Generates JavaScript Node.js and gRPC-Web service companions."""

    def generate_services(self) -> List[GeneratedFile]:
        local_services = [
            service
            for service in self.schema.services
            if not self.is_imported_type(service)
        ]
        if not local_services:
            return []
        self._validate_javascript_grpc_services(local_services)
        files: List[GeneratedFile] = []
        if self.options.grpc:
            self._validate_service_exports(local_services, "node")
            self._validate_model_imports(local_services, "node")
            files.append(self._generate_node_grpc_module(local_services))
        if getattr(self.options, "grpc_web", False):
            self._validate_grpc_web_services(local_services)
            self._validate_service_exports(local_services, "web")
            self._validate_model_imports(local_services, "web")
            files.append(self._generate_grpc_web_module(local_services))
        return files

    def _validate_javascript_grpc_services(self, services: List[Service]) -> None:
        for service in services:
            used_methods: Dict[str, str] = {}
            used_paths: Dict[str, str] = {}
            for method in service.methods:
                method_name = self._method_name(method)
                previous_method = used_methods.get(method_name)
                if previous_method is not None:
                    raise ValueError(
                        f"JavaScript gRPC method name collision in service "
                        f"{service.name}: {previous_method} and {method.name} "
                        f"both generate {method_name}"
                    )
                used_methods[method_name] = method.name

                path_key = self._method_path_key(method)
                previous_path = used_paths.get(path_key)
                if previous_path is not None:
                    raise ValueError(
                        f"JavaScript gRPC method path key collision in service "
                        f"{service.name}: {previous_path} and {method.name} "
                        f"both generate {path_key}"
                    )
                used_paths[path_key] = method.name

                self._resolve_rpc_root(method.request_type)
                self._resolve_rpc_root(method.response_type)

    def _validate_service_exports(self, services: List[Service], target: str) -> None:
        used_exports: Dict[str, str] = {}
        for service in services:
            for export in self._service_exports(service, target):
                previous = used_exports.get(export)
                if previous is not None:
                    raise ValueError(
                        "JavaScript gRPC service export collision: "
                        f"{previous} and {service.name} both generate {export}"
                    )
                used_exports[export] = service.name

    def _service_exports(self, service: Service, target: str) -> List[str]:
        service_name = self.to_pascal_case(service.name)
        exports = [
            self._service_constant_name(service),
            self._method_paths_constant_name(service),
        ]
        if target == "node":
            exports.extend(
                [
                    f"{service_name}Handlers",
                    f"create{service_name}ServiceDefinition",
                    f"add{service_name}Service",
                    f"{service_name}Client",
                    f"create{service_name}Client",
                ]
            )
        else:
            exports.extend(
                [
                    f"{service_name}WebClient",
                    f"create{service_name}WebClient",
                ]
            )
            if self._has_unary_method(service):
                exports.extend(
                    [
                        f"{service_name}WebPromiseClient",
                        f"create{service_name}WebPromiseClient",
                    ]
                )
        return exports

    def _validate_model_imports(self, services: List[Service], target: str) -> None:
        service_exports = {
            export
            for service in services
            for export in self._service_exports(service, target)
        }
        if target == "web":
            service_exports.update(WEB_HELPER_TYPE_NAMES)
        owners: Dict[str, str] = {}
        for root in self._service_roots(services):
            owner = (
                self._module_path_for_type(root) if self.is_imported_type(root) else "."
            )
            for name in self._model_import_names(root):
                previous = owners.get(name)
                if previous is not None and previous != owner:
                    raise ValueError(
                        "JavaScript gRPC model import collision: "
                        f"{previous} and {owner} both provide {name}"
                    )
                owners[name] = owner
                if name in service_exports:
                    raise ValueError(
                        "JavaScript gRPC model import collides with service "
                        f"export: {name}"
                    )

    def _validate_grpc_web_services(self, services: List[Service]) -> None:
        for service in services:
            for method in service.methods:
                mode = streaming_mode(method)
                if mode in (
                    StreamingMode.CLIENT_STREAMING,
                    StreamingMode.BIDIRECTIONAL,
                ):
                    raise ValueError(
                        "JavaScript gRPC-Web does not support client-streaming "
                        f"or bidirectional methods: {service.name}.{method.name}"
                    )

    def _has_unary_method(self, service: Service) -> bool:
        return any(not method.server_streaming for method in service.methods)

    def _generate_node_grpc_module(self, services: List[Service]) -> GeneratedFile:
        root_indexes = self._root_index_map(services)
        lines = self._service_file_prelude("node")
        lines.append('import * as grpc from "@grpc/grpc-js";')
        lines.extend(self._generate_model_imports(services))
        lines.append("")
        shared_bindings = self._generate_shared_codec_bindings(services, root_indexes)
        if shared_bindings:
            lines.extend(shared_bindings)
            lines.append("")
        lines.extend(self._generate_node_buffer_helper())
        lines.append("")
        lines.extend(self._generate_node_grpc_serializers(services, root_indexes))
        lines.append("")
        for service in services:
            lines.extend(self._generate_node_service(service, root_indexes))
            lines.append("")
        return GeneratedFile(
            path=f"{self.get_module_name()}_grpc.ts",
            content="\n".join(lines).rstrip() + "\n",
        )

    def _generate_grpc_web_module(self, services: List[Service]) -> GeneratedFile:
        root_indexes = self._root_index_map(services)
        lines = self._service_file_prelude("web")
        lines.append('import * as grpcWeb from "grpc-web";')
        lines.extend(self._generate_model_imports(services))
        lines.append("")
        lines.extend(
            [
                'export type ForyGrpcWebWireFormat = "grpcweb" | "grpcwebtext";',
                "",
                "export interface ForyGrpcWebClientOptions {",
                "  unaryInterceptors?: grpcWeb.UnaryInterceptor<unknown, unknown>[];",
                "  streamInterceptors?: grpcWeb.StreamInterceptor<unknown, unknown>[];",
                "  suppressCorsPreflight?: boolean;",
                "  withCredentials?: boolean;",
                "  wireFormat?: ForyGrpcWebWireFormat;",
                "}",
                "",
            ]
        )
        shared_bindings = self._generate_shared_codec_bindings(services, root_indexes)
        if shared_bindings:
            lines.extend(shared_bindings)
            lines.append("")
        lines.extend(self._generate_grpc_web_type_bindings(services, root_indexes))
        lines.append("")
        lines.extend(self._generate_grpc_web_helpers(services))
        lines.append("")
        for service_index, service in enumerate(services):
            lines.extend(
                self._generate_grpc_web_service(service, service_index, root_indexes)
            )
            lines.append("")
        return GeneratedFile(
            path=f"{self.get_module_name()}_grpc_web.ts",
            content="\n".join(lines).rstrip() + "\n",
        )

    def _service_file_prelude(self, target: str) -> List[str]:
        lines = [self.get_license_header("//"), ""]
        lines.append(
            f"// {target} gRPC bindings for package: {self.package or 'default'}"
        )
        return lines

    def _generate_model_imports(self, services: List[Service]) -> List[str]:
        current_types: Set[str] = set()
        imported_types: Dict[str, Set[str]] = {}
        for root in self._service_roots(services):
            names = self._model_import_names(root)
            if self.is_imported_type(root):
                module = self._module_path_for_type(root)
                imported_types.setdefault(module, set()).update(names)
            else:
                current_types.update(names)

        lines: List[str] = []
        if current_types:
            lines.append(
                f'import {{ {", ".join(sorted(current_types))} }} from "./{self.get_module_name()}";'
            )
        for module, names in sorted(imported_types.items()):
            lines.append(f'import {{ {", ".join(sorted(names))} }} from "{module}";')
        return lines

    def _service_roots(self, services: List[Service]) -> List[RootType]:
        roots: List[RootType] = []
        seen: Set[int] = set()
        for service in services:
            for method in service.methods:
                for named in (method.request_type, method.response_type):
                    root = self._resolve_rpc_root(named)
                    if id(root) in seen:
                        continue
                    seen.add(id(root))
                    roots.append(root)
        return roots

    def _root_index_map(self, services: List[Service]) -> Dict[int, int]:
        return {
            id(root): index for index, root in enumerate(self._service_roots(services))
        }

    def _resolve_rpc_root(self, named_type: NamedType) -> RootType:
        resolved = self._resolve_named_type(named_type.name)
        if isinstance(resolved, (Message, Union)):
            return resolved
        raise ValueError(
            f"JavaScript gRPC root type {named_type.name} must resolve to a message or union"
        )

    def _module_path_for_type(self, type_def: RootType) -> str:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        imported_schema = self._load_schema(file_path) if file_path else None
        if imported_schema is None:
            return f"./{self.get_module_name()}"
        return f"./{self._module_name_for_schema(imported_schema)}"

    def _ts_type_name(self, type_def: RootType) -> str:
        return self._ts_type_names.get(
            id(type_def), self.safe_type_identifier(type_def.name)
        )

    def _ts_import_name(self, type_def: RootType) -> str:
        return self._ts_type_name(type_def).split(".", 1)[0]

    def _model_import_names(self, type_def: RootType) -> List[str]:
        return [
            self._ts_import_name(type_def),
            self._root_serialize_helper_name(type_def),
            self._root_deserialize_helper_name(type_def),
        ]

    def _serializer_name(self, type_def: RootType, root_indexes: Dict[int, int]) -> str:
        return self._root_serialize_helper_name(type_def)

    def _grpc_serializer_name(
        self, type_def: RootType, root_indexes: Dict[int, int]
    ) -> str:
        return f"{self._serializer_name(type_def, root_indexes)}Grpc"

    def _deserializer_name(
        self, type_def: RootType, root_indexes: Dict[int, int]
    ) -> str:
        return self._root_deserialize_helper_name(type_def)

    def _grpc_web_type_name(
        self, type_def: RootType, root_indexes: Dict[int, int]
    ) -> str:
        return f"root{root_indexes[id(type_def)]}GrpcWebType"

    def _generate_shared_codec_bindings(
        self, services: List[Service], root_indexes: Dict[int, int]
    ) -> List[str]:
        return []

    def _generate_node_buffer_helper(self) -> List[str]:
        return [
            "const toGrpcBuffer = (bytes: Uint8Array): Buffer => {",
            "  if (Buffer.isBuffer(bytes)) {",
            "    return bytes;",
            "  }",
            "  return Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);",
            "};",
        ]

    def _generate_node_grpc_serializers(
        self, services: List[Service], root_indexes: Dict[int, int]
    ) -> List[str]:
        lines: List[str] = []
        for root in self._service_roots(services):
            ts_type = self._ts_type_name(root)
            lines.append(
                f"const {self._grpc_serializer_name(root, root_indexes)} = (value: {ts_type}): Buffer => "
                f"toGrpcBuffer({self._serializer_name(root, root_indexes)}(value));"
            )
        return lines

    def _generate_node_service(
        self, service: Service, root_indexes: Dict[int, int]
    ) -> List[str]:
        lines: List[str] = []
        service_name = self.to_pascal_case(service.name)
        lines.append(
            f'export const {self._service_constant_name(service)} = "{self.get_grpc_service_name(service)}";'
        )
        lines.append(f"export const {self._method_paths_constant_name(service)} = {{")
        for method in service.methods:
            lines.append(
                f'  {self._method_path_key(method)}: "{self.get_grpc_method_path(service, method)}",'
            )
        lines.append("} as const;")
        lines.append("")
        lines.extend(self._generate_node_handlers(service))
        lines.append("")
        lines.extend(self._generate_node_service_definition(service, root_indexes))
        lines.append("")
        lines.append(
            f"export function add{service_name}Service(server: grpc.Server, "
            f"handlers: {service_name}Handlers): void {{"
        )
        lines.append(
            f"  server.addService(create{service_name}ServiceDefinition(), handlers);"
        )
        lines.append("}")
        lines.append("")
        lines.extend(self._generate_node_client(service, root_indexes))
        return lines

    def _generate_node_handlers(self, service: Service) -> List[str]:
        lines = [
            f"export interface {self.to_pascal_case(service.name)}Handlers extends grpc.UntypedServiceImplementation {{"
        ]
        for method in service.methods:
            req = self._ts_type_name(self._resolve_rpc_root(method.request_type))
            res = self._ts_type_name(self._resolve_rpc_root(method.response_type))
            handler_type = {
                StreamingMode.UNARY: "grpc.handleUnaryCall",
                StreamingMode.SERVER_STREAMING: "grpc.handleServerStreamingCall",
                StreamingMode.CLIENT_STREAMING: "grpc.handleClientStreamingCall",
                StreamingMode.BIDIRECTIONAL: "grpc.handleBidiStreamingCall",
            }[streaming_mode(method)]
            lines.append(
                f"  {self._method_name(method)}: {handler_type}<{req}, {res}>;"
            )
        lines.append("}")
        return lines

    def _generate_node_service_definition(
        self, service: Service, root_indexes: Dict[int, int]
    ) -> List[str]:
        service_name = self.to_pascal_case(service.name)
        lines = [
            f"export function create{service_name}ServiceDefinition(): "
            "grpc.ServiceDefinition<grpc.UntypedServiceImplementation> {",
            "  return {",
        ]
        for method in service.methods:
            request_root = self._resolve_rpc_root(method.request_type)
            response_root = self._resolve_rpc_root(method.response_type)
            mode = streaming_mode(method)
            lines.append(f"    {self._method_name(method)}: {{")
            lines.append(
                f"      path: {self._method_paths_constant_name(service)}.{self._method_path_key(method)},"
            )
            lines.append(
                f"      requestStream: {'true' if method.client_streaming else 'false'},"
            )
            lines.append(
                f"      responseStream: {'true' if method.server_streaming else 'false'},"
            )
            lines.append(
                f"      requestSerialize: {self._grpc_serializer_name(request_root, root_indexes)},"
            )
            lines.append(
                f"      requestDeserialize: {self._deserializer_name(request_root, root_indexes)},"
            )
            lines.append(
                f"      responseSerialize: {self._grpc_serializer_name(response_root, root_indexes)},"
            )
            lines.append(
                f"      responseDeserialize: {self._deserializer_name(response_root, root_indexes)},"
            )
            # grpc-js falls back to implementation[originalName] when the
            # service-definition key is missing. Keep this aligned with the
            # escaped handler key so raw names such as constructor do not bind
            # Object.prototype members.
            lines.append(f'      originalName: "{self._method_name(method)}",')
            lines.append("    },")
            if mode is None:
                raise AssertionError("unreachable")
        lines.append("  };")
        lines.append("}")
        return lines

    def _generate_node_client(
        self, service: Service, root_indexes: Dict[int, int]
    ) -> List[str]:
        service_name = self.to_pascal_case(service.name)
        lines = [f"export class {service_name}Client extends grpc.Client {{"]
        lines.extend(
            [
                "  constructor(",
                "    address: string,",
                "    credentials: grpc.ChannelCredentials,",
                "    options?: Partial<grpc.ClientOptions>,",
                "  ) {",
                "    super(address, credentials, options);",
                "  }",
                "",
            ]
        )
        for index, method in enumerate(service.methods):
            lines.extend(
                self._generate_node_client_method(service, method, root_indexes)
            )
            if index != len(service.methods) - 1:
                lines.append("")
        lines.append("}")
        lines.append("")
        lines.append(
            f"export function create{service_name}Client("
            "address: string, credentials: grpc.ChannelCredentials, "
            f"options?: Partial<grpc.ClientOptions>): {service_name}Client {{"
        )
        lines.append(
            f"  return new {service_name}Client(address, credentials, options);"
        )
        lines.append("}")
        return lines

    def _generate_node_client_method(
        self, service: Service, method: RpcMethod, root_indexes: Dict[int, int]
    ) -> List[str]:
        mode = streaming_mode(method)
        req = self._ts_type_name(self._resolve_rpc_root(method.request_type))
        res = self._ts_type_name(self._resolve_rpc_root(method.response_type))
        name = self._method_name(method)
        path = f"{self._method_paths_constant_name(service)}.{self._method_path_key(method)}"
        req_ser = self._grpc_serializer_name(
            self._resolve_rpc_root(method.request_type), root_indexes
        )
        res_deser = self._deserializer_name(
            self._resolve_rpc_root(method.response_type), root_indexes
        )
        lines: List[str] = []
        if mode is StreamingMode.UNARY:
            lines.extend(
                [
                    f"  {name}(request: {req}, callback: grpc.requestCallback<{res}>): grpc.ClientUnaryCall;",
                    f"  {name}(request: {req}, metadata: grpc.Metadata, callback: grpc.requestCallback<{res}>): grpc.ClientUnaryCall;",
                    f"  {name}(request: {req}, metadata: grpc.Metadata, options: grpc.CallOptions, callback: grpc.requestCallback<{res}>): grpc.ClientUnaryCall;",
                    f"  {name}(",
                    f"    request: {req},",
                    f"    metadataOrCallback: grpc.Metadata | grpc.requestCallback<{res}>,",
                    f"    optionsOrCallback?: grpc.CallOptions | grpc.requestCallback<{res}>,",
                    f"    callback?: grpc.requestCallback<{res}>,",
                    "  ): grpc.ClientUnaryCall {",
                    "    const metadata = metadataOrCallback instanceof grpc.Metadata",
                    "      ? metadataOrCallback",
                    "      : new grpc.Metadata();",
                    '    const options = optionsOrCallback && typeof optionsOrCallback !== "function"',
                    "      ? optionsOrCallback",
                    "      : {};",
                    '    const responseCallback = (typeof metadataOrCallback === "function"',
                    "      ? metadataOrCallback",
                    '      : typeof optionsOrCallback === "function"',
                    "        ? optionsOrCallback",
                    "        : callback)!;",
                    "    return this.makeUnaryRequest(",
                    f"      {path},",
                    f"      {req_ser},",
                    f"      {res_deser},",
                    "      request,",
                    "      metadata,",
                    "      options,",
                    "      responseCallback,",
                    "    );",
                    "  }",
                ]
            )
        elif mode is StreamingMode.SERVER_STREAMING:
            lines.extend(
                [
                    f"  {name}(request: {req}, metadata?: grpc.Metadata, options?: grpc.CallOptions): grpc.ClientReadableStream<{res}> {{",
                    "    return this.makeServerStreamRequest(",
                    f"      {path},",
                    f"      {req_ser},",
                    f"      {res_deser},",
                    "      request,",
                    "      metadata ?? new grpc.Metadata(),",
                    "      options,",
                    "    );",
                    "  }",
                ]
            )
        elif mode is StreamingMode.CLIENT_STREAMING:
            lines.extend(
                [
                    f"  {name}(callback: grpc.requestCallback<{res}>): grpc.ClientWritableStream<{req}>;",
                    f"  {name}(metadata: grpc.Metadata, callback: grpc.requestCallback<{res}>): grpc.ClientWritableStream<{req}>;",
                    f"  {name}(metadata: grpc.Metadata, options: grpc.CallOptions, callback: grpc.requestCallback<{res}>): grpc.ClientWritableStream<{req}>;",
                    f"  {name}(",
                    f"    metadataOrCallback: grpc.Metadata | grpc.requestCallback<{res}>,",
                    f"    optionsOrCallback?: grpc.CallOptions | grpc.requestCallback<{res}>,",
                    f"    callback?: grpc.requestCallback<{res}>,",
                    f"  ): grpc.ClientWritableStream<{req}> {{",
                    "    const metadata = metadataOrCallback instanceof grpc.Metadata",
                    "      ? metadataOrCallback",
                    "      : new grpc.Metadata();",
                    '    const options = optionsOrCallback && typeof optionsOrCallback !== "function"',
                    "      ? optionsOrCallback",
                    "      : {};",
                    '    const responseCallback = (typeof metadataOrCallback === "function"',
                    "      ? metadataOrCallback",
                    '      : typeof optionsOrCallback === "function"',
                    "        ? optionsOrCallback",
                    "        : callback)!;",
                    "    return this.makeClientStreamRequest(",
                    f"      {path},",
                    f"      {req_ser},",
                    f"      {res_deser},",
                    "      metadata,",
                    "      options,",
                    "      responseCallback,",
                    "    );",
                    "  }",
                ]
            )
        else:
            lines.extend(
                [
                    f"  {name}(metadata?: grpc.Metadata, options?: grpc.CallOptions): grpc.ClientDuplexStream<{req}, {res}> {{",
                    "    return this.makeBidiStreamRequest(",
                    f"      {path},",
                    f"      {req_ser},",
                    f"      {res_deser},",
                    "      metadata ?? new grpc.Metadata(),",
                    "      options,",
                    "    );",
                    "  }",
                ]
            )
        return lines

    def _generate_grpc_web_type_bindings(
        self, services: List[Service], root_indexes: Dict[int, int]
    ) -> List[str]:
        # grpc-web's MethodDescriptor type requires constructor tokens, but
        # generated Fory JavaScript roots are interfaces. The runtime uses the
        # supplied byte serializers below, so Object is only a descriptor token.
        lines = [
            "type GrpcWebMessageType<T> = new (...args: unknown[]) => T;",
        ]
        for root in self._service_roots(services):
            lines.append(
                f"const {self._grpc_web_type_name(root, root_indexes)} = "
                f"Object as unknown as GrpcWebMessageType<{self._ts_type_name(root)}>;"
            )
        return lines

    def _generate_grpc_web_helpers(self, services: List[Service]) -> List[str]:
        return [
            "const toGrpcWebOptions = (",
            "  options: ForyGrpcWebClientOptions | undefined,",
            "  wireFormat: ForyGrpcWebWireFormat,",
            "): grpcWeb.GrpcWebClientBaseOptions => {",
            "  return {",
            '    format: wireFormat === "grpcwebtext" ? "text" : "binary",',
            "    unaryInterceptors: options?.unaryInterceptors,",
            "    streamInterceptors: options?.streamInterceptors,",
            "    suppressCorsPreflight: options?.suppressCorsPreflight,",
            "    withCredentials: options?.withCredentials,",
            "  };",
            "};",
        ]

    def _grpc_web_default_format(self, service: Service) -> str:
        return (
            "grpcwebtext"
            if any(method.server_streaming for method in service.methods)
            else "grpcweb"
        )

    def _grpc_web_descriptor_name(self, service_index: int, method_index: int) -> str:
        return f"methodDescriptor{service_index}_{method_index}"

    def _generate_grpc_web_service(
        self, service: Service, service_index: int, root_indexes: Dict[int, int]
    ) -> List[str]:
        lines: List[str] = []
        lines.append(
            f'export const {self._service_constant_name(service)} = "{self.get_grpc_service_name(service)}";'
        )
        lines.append(f"export const {self._method_paths_constant_name(service)} = {{")
        for method in service.methods:
            lines.append(
                f'  {self._method_path_key(method)}: "{self.get_grpc_method_path(service, method)}",'
            )
        lines.append("} as const;")
        lines.append("")
        for method_index, method in enumerate(service.methods):
            lines.extend(
                self._generate_grpc_web_descriptor(
                    service, method, service_index, method_index, root_indexes
                )
            )
            lines.append("")
        lines.extend(self._generate_grpc_web_callback_client(service, service_index))
        if self._has_unary_method(service):
            lines.append("")
            lines.extend(self._generate_grpc_web_promise_client(service, service_index))
        return lines

    def _generate_grpc_web_descriptor(
        self,
        service: Service,
        method: RpcMethod,
        service_index: int,
        method_index: int,
        root_indexes: Dict[int, int],
    ) -> List[str]:
        request_root = self._resolve_rpc_root(method.request_type)
        response_root = self._resolve_rpc_root(method.response_type)
        descriptor = self._grpc_web_descriptor_name(service_index, method_index)
        method_type = "SERVER_STREAMING" if method.server_streaming else "UNARY"
        return [
            f"const {descriptor} = new grpcWeb.MethodDescriptor<",
            f"  {self._ts_type_name(request_root)},",
            f"  {self._ts_type_name(response_root)}",
            ">(",
            f"  {self._method_paths_constant_name(service)}.{self._method_path_key(method)},",
            f"  grpcWeb.MethodType.{method_type},",
            f"  {self._grpc_web_type_name(request_root, root_indexes)},",
            f"  {self._grpc_web_type_name(response_root, root_indexes)},",
            f"  {self._serializer_name(request_root, root_indexes)},",
            f"  {self._deserializer_name(response_root, root_indexes)},",
            ");",
        ]

    def _generate_grpc_web_callback_client(
        self, service: Service, service_index: int
    ) -> List[str]:
        service_name = self.to_pascal_case(service.name)
        default_format = self._grpc_web_default_format(service)
        lines = [
            f"export class {service_name}WebClient {{",
            "  private readonly client: grpcWeb.GrpcWebClientBase;",
            "  private readonly hostname: string;",
            "  private readonly wireFormat: ForyGrpcWebWireFormat;",
            "",
            "  constructor(",
            "    hostname: string,",
            "    options?: ForyGrpcWebClientOptions,",
            "  ) {",
            f'    this.wireFormat = options?.wireFormat ?? "{default_format}";',
            "    this.client = new grpcWeb.GrpcWebClientBase(",
            "      toGrpcWebOptions(options, this.wireFormat),",
            "    );",
            '    this.hostname = hostname.replace(/\\/+$/, "");',
            "  }",
            "",
        ]
        for index, method in enumerate(service.methods):
            lines.extend(
                self._generate_grpc_web_callback_method(
                    service, method, service_index, index
                )
            )
            if index != len(service.methods) - 1:
                lines.append("")
        lines.append("}")
        lines.append("")
        lines.append(
            f"export function create{service_name}WebClient("
            "hostname: string, options?: ForyGrpcWebClientOptions"
            f"): {service_name}WebClient {{"
        )
        lines.append(f"  return new {service_name}WebClient(hostname, options);")
        lines.append("}")
        return lines

    def _generate_grpc_web_callback_method(
        self, service: Service, method: RpcMethod, service_index: int, method_index: int
    ) -> List[str]:
        request_root = self._resolve_rpc_root(method.request_type)
        response_root = self._resolve_rpc_root(method.response_type)
        req = self._ts_type_name(request_root)
        res = self._ts_type_name(response_root)
        descriptor = self._grpc_web_descriptor_name(service_index, method_index)
        name = self._method_name(method)
        if method.server_streaming:
            return [
                f"  {name}(request: {req}, metadata?: grpcWeb.Metadata): grpcWeb.ClientReadableStream<{res}> {{",
                '    if (this.wireFormat === "grpcweb") {',
                '      throw new Error("grpcweb binary mode does not support server streaming");',
                "    }",
                "    return this.client.serverStreaming(",
                f"      this.hostname + {self._method_paths_constant_name(service)}.{self._method_path_key(method)},",
                "      request,",
                "      metadata ?? {},",
                f"      {descriptor},",
                "    );",
                "  }",
            ]
        return [
            f"  {name}(",
            f"    request: {req},",
            "    metadata: grpcWeb.Metadata | null,",
            f"    callback: (error: grpcWeb.RpcError, response: {res}) => void,",
            f"  ): grpcWeb.ClientReadableStream<{res}> {{",
            "    return this.client.rpcCall(",
            f"      this.hostname + {self._method_paths_constant_name(service)}.{self._method_path_key(method)},",
            "      request,",
            "      metadata ?? {},",
            f"      {descriptor},",
            "      callback,",
            "    );",
            "  }",
        ]

    def _generate_grpc_web_promise_client(
        self, service: Service, service_index: int
    ) -> List[str]:
        service_name = self.to_pascal_case(service.name)
        default_format = self._grpc_web_default_format(service)
        lines = [
            f"export class {service_name}WebPromiseClient {{",
            "  private readonly client: grpcWeb.GrpcWebClientBase;",
            "  private readonly hostname: string;",
            "",
            "  constructor(",
            "    hostname: string,",
            "    options?: ForyGrpcWebClientOptions,",
            "  ) {",
            f'    const wireFormat = options?.wireFormat ?? "{default_format}";',
            "    this.client = new grpcWeb.GrpcWebClientBase(",
            "      toGrpcWebOptions(options, wireFormat),",
            "    );",
            '    this.hostname = hostname.replace(/\\/+$/, "");',
            "  }",
            "",
        ]
        promise_methods = [
            (index, method)
            for index, method in enumerate(service.methods)
            if not method.server_streaming
        ]
        for index, (method_index, method) in enumerate(promise_methods):
            lines.extend(
                self._generate_grpc_web_promise_method(
                    service, method, service_index, method_index
                )
            )
            if index != len(promise_methods) - 1:
                lines.append("")
        lines.append("}")
        lines.append("")
        lines.append(
            f"export function create{service_name}WebPromiseClient("
            "hostname: string, options?: ForyGrpcWebClientOptions"
            f"): {service_name}WebPromiseClient {{"
        )
        lines.append(f"  return new {service_name}WebPromiseClient(hostname, options);")
        lines.append("}")
        return lines

    def _generate_grpc_web_promise_method(
        self, service: Service, method: RpcMethod, service_index: int, method_index: int
    ) -> List[str]:
        request_root = self._resolve_rpc_root(method.request_type)
        response_root = self._resolve_rpc_root(method.response_type)
        req = self._ts_type_name(request_root)
        res = self._ts_type_name(response_root)
        descriptor = self._grpc_web_descriptor_name(service_index, method_index)
        name = self._method_name(method)
        return [
            f"  {name}(",
            f"    request: {req},",
            "    metadata?: grpcWeb.Metadata,",
            f"  ): Promise<{res}> {{",
            "    return this.client.thenableCall(",
            f"      this.hostname + {self._method_paths_constant_name(service)}.{self._method_path_key(method)},",
            "      request,",
            "      metadata ?? {},",
            f"      {descriptor},",
            "    );",
            "  }",
        ]

    def _service_constant_name(self, service: Service) -> str:
        return f"{self.to_upper_snake_case(service.name)}_SERVICE_NAME"

    def _method_paths_constant_name(self, service: Service) -> str:
        return f"{self.to_upper_snake_case(service.name)}_METHOD_PATHS"

    def _method_name(self, method: RpcMethod) -> str:
        name = self.safe_identifier(self.to_camel_case(method.name))
        if name in SERVICE_MEMBER_NAMES:
            return f"{name}_"
        return name

    def _method_path_key(self, method: RpcMethod) -> str:
        return self.safe_member_name(f"{method.name}_path")
