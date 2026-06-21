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

"""Dart gRPC service generator helpers."""

from pathlib import Path
from typing import Dict, List, Set, Tuple

from fory_compiler.generators.base import GeneratedFile
from fory_compiler.ir.ast import RpcMethod, Service


class DartServiceGeneratorMixin:
    """Generates Dart gRPC service companions for all four RPC modes."""

    _DART_RESERVED_METHOD_NAMES = frozenset(
        {"toString", "hashCode", "noSuchMethod", "runtimeType"}
    )
    _DART_GRPC_SUPPORT_NAMES = frozenset(
        {
            "CallOptions",
            "Client",
            "ClientMethod",
            "Future",
            "List",
            "ResponseFuture",
            "ResponseStream",
            "Service",
            "ServiceCall",
            "ServiceMethod",
            "Stream",
            "String",
            "T",
            "Uint8List",
            "bytes",
            "call",
            "int",
            "options",
            "request",
            "u8",
            "value",
        }
    )

    def generate_services(self) -> List[GeneratedFile]:
        local_services = [
            service
            for service in self.schema.services
            if not self.is_imported_type(service)
        ]
        if not local_services:
            return []
        self.check_dart_grpc_service_collisions(local_services)
        self.check_dart_grpc_method_collisions(local_services)
        self.check_dart_grpc_reserved_method_names(local_services)
        return [self.generate_grpc_module(local_services)]

    def check_dart_grpc_service_collisions(self, services: List[Service]) -> None:
        generated_names = set(self._top_level_names())
        service_names = set()
        for service in services:
            for emitted in (f"{service.name}Client", f"{service.name}ServiceBase"):
                if emitted in generated_names or emitted in service_names:
                    raise ValueError(
                        f"Dart gRPC class {emitted} conflicts with a generated "
                        "type or another service; rename the service or type"
                    )
                service_names.add(emitted)

    def check_dart_grpc_method_collisions(self, services: List[Service]) -> None:
        for service in services:
            seen = {}
            for method in service.methods:
                emitted = self.dart_grpc_method_name(method)
                if emitted in seen:
                    raise ValueError(
                        f"Dart gRPC method name collision in service {service.name}: "
                        f"{seen[emitted]} and {method.name} both generate {emitted}"
                    )
                seen[emitted] = method.name

    def check_dart_grpc_reserved_method_names(self, services: List[Service]) -> None:
        offenders = []
        for service in services:
            for method in service.methods:
                emitted = self.dart_grpc_method_name(method)
                if emitted in self._DART_RESERVED_METHOD_NAMES:
                    offenders.append(f"{service.name}.{method.name} -> {emitted}")
        if offenders:
            joined = "\n  - " + "\n  - ".join(offenders)
            raise ValueError(
                "Dart gRPC method name collides with an inherited Dart member "
                "(Object/Client/Service) and would produce an invalid override; "
                "rename the RPC method:" + joined
            )

    def generate_grpc_module(self, services: List[Service]) -> GeneratedFile:
        """Emit a grpc-dart companion module for schema services."""
        models_output = Path(
            self.output_file_path()
        )  # e.g. "demo/greeter/greeter.dart"
        models_stem = models_output.stem  # e.g. "greeter"
        grpc_path = str(models_output.with_name(f"{models_stem}_grpc.dart"))

        self._grpc_model_alias = "_models"
        self._grpc_payload_imports: Dict[str, Tuple[str, str]] = {}
        self._grpc_used_import_aliases = self._dart_grpc_reserved_import_aliases(
            services
        )

        body: List[str] = []
        for service in services:
            body.extend(self.generate_dart_grpc_client(service))
            body.append("")
            body.extend(self.generate_dart_grpc_service_base(service))
            body.append("")

        lines: List[str] = []
        lines.append(self.get_license_header("//"))
        lines.append("")
        lines.append(
            "// ignore_for_file: camel_case_types, constant_identifier_names, "
            "non_constant_identifier_names"
        )
        lines.append("")
        lines.append("import 'dart:async';")
        lines.append("import 'dart:typed_data';")
        lines.append("")
        lines.append("import 'package:grpc/grpc.dart';")
        lines.append("")
        lines.append(f"import '{models_stem}.dart' as {self._grpc_model_alias};")
        for path, alias in sorted(self._grpc_payload_imports.values()):
            lines.append(f"import '{path}' as {alias};")
        lines.append("")
        lines.append(
            "// grpc-dart Service self-registers via $methods; "
            "no separate registration helper needed."
        )
        lines.append("")
        fory = f"{self._grpc_model_alias}.{self.module_type_name()}.getFory()"
        lines.append("List<int> _serialize<T>(T value) =>")
        lines.append(f"    {fory}.serialize(value, trackRef: true);")
        lines.append("")
        lines.append("T _deserialize<T>(List<int> bytes) {")
        lines.append(
            "  final u8 = bytes is Uint8List ? bytes : Uint8List.fromList(bytes);"
        )
        lines.append(f"  return {fory}.deserialize<T>(u8);")
        lines.append("}")
        lines.append("")
        lines.extend(body)

        return GeneratedFile(path=grpc_path, content="\n".join(lines))

    def _dart_grpc_type_ref(self, named_type) -> str:
        """Return the Dart reference for an RPC request/response type.

        Resolves through the Dart generator's type machinery so nested types
        use their flattened symbol (`Envelope.Request` -> `Envelope_Request`)
        and imported types use an alias-qualified reference plus an emitted
        import, instead of the raw IDL name.
        """
        type_def = self.resolve_type(named_type.name)
        if type_def is None:
            return f"{self._grpc_model_alias}.{named_type.name}"
        if self.is_imported_type(type_def):
            alias = self._dart_grpc_import_alias(type_def)
            return f"{alias}.{self.local_name(type_def)}"
        return f"{self._grpc_model_alias}.{self.local_name(type_def)}"

    def _dart_grpc_import_alias(self, type_def) -> str:
        file = type_def.location.file
        existing = self._grpc_payload_imports.get(file)
        if existing is not None:
            return existing[1]
        schema = self._load_schema(file)
        if schema is None:
            return self.ref_name(type_def)
        candidate = self.safe_identifier(
            schema.package.replace(".", "_") if schema.package else Path(file).stem
        )
        alias = self._dart_grpc_unique_import_alias(candidate)
        self._grpc_payload_imports[file] = (self._relative_import_path(schema), alias)
        return alias

    def _dart_grpc_unique_import_alias(self, candidate: str) -> str:
        alias = candidate
        index = 2
        while alias in self._grpc_used_import_aliases:
            alias = f"{candidate}_{index}"
            index += 1
        self._grpc_used_import_aliases.add(alias)
        return alias

    def _dart_grpc_reserved_import_aliases(self, services: List[Service]) -> Set[str]:
        aliases = set(self._DART_GRPC_SUPPORT_NAMES)
        aliases.update({self._grpc_model_alias, "_serialize", "_deserialize"})
        for service in services:
            aliases.add(f"{service.name}Client")
            aliases.add(f"{service.name}ServiceBase")
            for method in service.methods:
                method_name = self.dart_grpc_method_name(method)
                aliases.add(method_name)
                aliases.add(f"{method_name}_Pre")
        return aliases

    def generate_dart_grpc_client(self, service: Service) -> List[str]:
        lines: List[str] = []
        lines.append(f"class {service.name}Client extends Client {{")
        for method in service.methods:
            method_const = f"_${self.dart_grpc_method_name(method)}"
            req_t = self._dart_grpc_type_ref(method.request_type)
            res_t = self._dart_grpc_type_ref(method.response_type)
            full_path = self.get_grpc_method_path(service, method)
            lines.append(f"  static final {method_const} =")
            lines.append(f"      ClientMethod<{req_t}, {res_t}>(")
            lines.append(f"        '{full_path}',")
            lines.append("        _serialize,")
            lines.append("        _deserialize,")
            lines.append("      );")
            lines.append("")
        lines.append(
            f"  {service.name}Client(super.channel, "
            "{super.options, super.interceptors});"
        )
        for method in service.methods:
            lines.append("")
            lines.extend(self._dart_grpc_client_method(method))
        lines.append("}")
        return lines

    def _dart_grpc_client_method(self, method: RpcMethod) -> List[str]:
        streaming_request, streaming_response = self._dart_grpc_call_kind(method)
        method_const = f"_${self.dart_grpc_method_name(method)}"
        req_t = self._dart_grpc_type_ref(method.request_type)
        res_t = self._dart_grpc_type_ref(method.response_type)
        method_name = self.dart_grpc_method_name(method)

        return_type = (
            f"ResponseStream<{res_t}>"
            if streaming_response
            else (f"ResponseFuture<{res_t}>")
        )
        request_param = (
            f"Stream<{req_t}> request" if streaming_request else (f"{req_t} request")
        )

        if not streaming_request and not streaming_response:
            call_fn = "$createUnaryCall"
            request_arg = "request"
            single = ""
        else:
            call_fn = "$createStreamingCall"
            request_arg = "request" if streaming_request else "Stream.value(request)"
            # client-stream returns a single response; ResponseStream.single
            # adapts the streaming call to ResponseFuture<R>.
            single = "" if streaming_response else ".single"

        lines: List[str] = []
        lines.append(f"  {return_type} {method_name}(")
        lines.append(f"    {request_param}, {{")
        lines.append("    CallOptions? options,")
        lines.append("  }) {")
        lines.extend(
            self._dart_grpc_call_body(call_fn, method_const, request_arg, single)
        )
        lines.append("  }")
        return lines

    def _dart_grpc_call_body(
        self, call_fn: str, method_const: str, request_arg: str, single: str
    ) -> List[str]:
        """Emit `return <call>;`, wrapping when dart format would.

        dart format keeps the call on one line when it fits in 80 columns and
        otherwise wraps each argument onto its own line with a trailing comma.
        Matching that here keeps the emitted file format-clean.
        """
        single_line = (
            f"    return {call_fn}({method_const}, {request_arg}, "
            f"options: options){single};"
        )
        if len(single_line) <= 80:
            return [single_line]
        return [
            f"    return {call_fn}(",
            f"      {method_const},",
            f"      {request_arg},",
            "      options: options,",
            f"    ){single};",
        ]

    def _dart_grpc_call_kind(self, method: RpcMethod):
        """Return (streaming_request, streaming_response) for an RPC method."""
        return bool(method.client_streaming), bool(method.server_streaming)

    def generate_dart_grpc_service_base(self, service: Service) -> List[str]:
        lines: List[str] = []
        lines.append(f"abstract class {service.name}ServiceBase extends Service {{")
        lines.append("  @override")
        lines.append(f"  String get $name => '{self.get_grpc_service_name(service)}';")
        lines.append("")
        lines.append(f"  {service.name}ServiceBase() {{")
        for method in service.methods:
            streaming_request, streaming_response = self._dart_grpc_call_kind(method)
            req_t = self._dart_grpc_type_ref(method.request_type)
            res_t = self._dart_grpc_type_ref(method.response_type)
            method_name = self.dart_grpc_method_name(method)
            lines.append("    $addMethod(")
            lines.append(f"      ServiceMethod<{req_t}, {res_t}>(")
            lines.append(f"        '{method.name}',")
            lines.append(f"        {method_name}_Pre,")
            lines.append(f"        {str(streaming_request).lower()},")
            lines.append(f"        {str(streaming_response).lower()},")
            lines.append(f"        (List<int> value) => _deserialize<{req_t}>(value),")
            lines.append(f"        ({res_t} value) => _serialize(value),")
            lines.append("      ),")
            lines.append("    );")
        lines.append("  }")
        lines.append("")
        for idx, method in enumerate(service.methods):
            lines.extend(self._dart_grpc_service_method(method))
            if idx != len(service.methods) - 1:
                lines.append("")
        lines.append("}")
        return lines

    def _dart_grpc_service_method(self, method: RpcMethod) -> List[str]:
        streaming_request, streaming_response = self._dart_grpc_call_kind(method)
        req_t = self._dart_grpc_type_ref(method.request_type)
        res_t = self._dart_grpc_type_ref(method.response_type)
        method_name = self.dart_grpc_method_name(method)

        # grpc-dart hands the handler a Future<Q> for a single request and a
        # Stream<Q> for a client-streaming request, and consumes the handler's
        # return value directly as the response (a Future<R> for a single
        # response, a Stream<R> for a streaming response). The _Pre shim adapts
        # that handler signature to the user-overridable method:
        #   - single request  -> await $request before delegating
        #   - stream request   -> pass $request straight through
        #   - stream response  -> the shim is async* and yield*s the delegate
        user_return = f"Stream<{res_t}>" if streaming_response else f"Future<{res_t}>"
        user_param = f"Stream<{req_t}>" if streaming_request else req_t
        shim_param = f"Stream<{req_t}>" if streaming_request else f"Future<{req_t}>"
        request_arg = "$request" if streaming_request else "await $request"

        lines: List[str] = []
        lines.append(
            "  // protoc_plugin parity: _Pre shim adapts the grpc-dart handler"
        )
        lines.append("  // signature to the user-overridable method.")
        lines.append(f"  {user_return} {method_name}_Pre(")
        lines.append("    ServiceCall $call,")
        lines.append(f"    {shim_param} $request,")
        if streaming_response and not streaming_request:
            # server-stream: must await the single request, then stream results.
            lines.append("  ) async* {")
            lines.append(f"    yield* {method_name}($call, {request_arg});")
        elif not streaming_request:
            # unary: await the single request, return the single response.
            lines.append("  ) async {")
            lines.append(f"    return {method_name}($call, {request_arg});")
        else:
            # client-stream / bidi: pass the request stream straight through.
            lines.append("  ) {")
            lines.append(f"    return {method_name}($call, {request_arg});")
        lines.append("  }")
        lines.append("")
        lines.append(f"  {user_return} {method_name}(")
        lines.append("    ServiceCall call,")
        lines.append(f"    {user_param} request,")
        lines.append("  );")
        return lines

    def dart_grpc_method_name(self, method: RpcMethod) -> str:
        return self.safe_identifier(self.to_camel_case(method.name))
