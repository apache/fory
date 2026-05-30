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
from typing import List

from fory_compiler.generators.base import GeneratedFile
from fory_compiler.ir.ast import RpcMethod, Service


class DartServiceGeneratorMixin:
    """Generates Dart gRPC service companions (unary RPCs only)."""

    def generate_services(self) -> List[GeneratedFile]:
        local_services = [
            service
            for service in self.schema.services
            if not self.is_imported_type(service)
        ]
        if not local_services:
            return []
        self.check_dart_streaming_unsupported(local_services)
        self.check_dart_grpc_service_collisions(local_services)
        self.check_dart_grpc_method_collisions(local_services)
        return [self.generate_grpc_module(local_services)]

    def check_dart_streaming_unsupported(self, services: List[Service]) -> None:
        offenders = []
        for service in services:
            for method in service.methods:
                if method.client_streaming or method.server_streaming:
                    offenders.append(f"{service.name}.{method.name}")
        if offenders:
            joined = "\n  - " + "\n  - ".join(offenders)
            raise ValueError(
                "Dart gRPC generator does not yet support streaming RPCs;\n"
                "remove `stream` from the following methods or omit --grpc for dart:"
                + joined
            )

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

    def generate_grpc_module(self, services: List[Service]) -> GeneratedFile:
        """Emit a grpc-dart companion module for schema services."""
        models_output = Path(self.output_file_path())  # e.g. "demo/greeter/demo_greeter.dart"
        models_stem = models_output.stem  # e.g. "demo_greeter"
        grpc_path = str(models_output.with_name(f"{models_stem}_grpc.dart"))

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
        lines.append(f"import '{models_stem}.dart' as _models;")
        lines.append("")
        lines.append(
            "// grpc-dart Service self-registers via $methods; "
            "no separate registration helper needed."
        )
        lines.append("")
        lines.append("List<int> _serialize<T>(T value) =>")
        lines.append("    _models.ForyRegistration.getFory().serialize(value);")
        lines.append("")
        lines.append("T _deserialize<T>(List<int> bytes) {")
        lines.append(
            "  final u8 = bytes is Uint8List ? bytes : Uint8List.fromList(bytes);"
        )
        lines.append("  return _models.ForyRegistration.getFory().deserialize<T>(u8);")
        lines.append("}")
        lines.append("")

        for service in services:
            lines.extend(self.generate_dart_grpc_client(service))
            lines.append("")
            lines.extend(self.generate_dart_grpc_service_base(service))
            lines.append("")

        return GeneratedFile(path=grpc_path, content="\n".join(lines))

    def generate_dart_grpc_client(self, service: Service) -> List[str]:
        lines: List[str] = []
        lines.append(f"class {service.name}Client extends Client {{")
        for method in service.methods:
            method_const = f"_${self.dart_grpc_method_name(method)}"
            req_t = f"_models.{method.request_type.name}"
            res_t = f"_models.{method.response_type.name}"
            full_path = self.get_grpc_method_path(service, method)
            lines.append(
                f"  static final {method_const} = "
                f"ClientMethod<{req_t}, {res_t}>("
            )
            lines.append(f"    '{full_path}',")
            lines.append("    _serialize,")
            lines.append("    _deserialize,")
            lines.append("  );")
            lines.append("")
        lines.append(
            f"  {service.name}Client(super.channel, "
            "{super.options, super.interceptors});"
        )
        for method in service.methods:
            method_const = f"_${self.dart_grpc_method_name(method)}"
            req_t = f"_models.{method.request_type.name}"
            res_t = f"_models.{method.response_type.name}"
            method_name = self.dart_grpc_method_name(method)
            lines.append("")
            lines.append(f"  ResponseFuture<{res_t}> {method_name}(")
            lines.append(f"    {req_t} request, {{")
            lines.append("    CallOptions? options,")
            lines.append("  }) {")
            lines.append(
                f"    return $createUnaryCall({method_const}, request, options: options);"
            )
            lines.append("  }")
        lines.append("}")
        return lines

    def generate_dart_grpc_service_base(self, service: Service) -> List[str]:
        lines: List[str] = []
        lines.append(f"abstract class {service.name}ServiceBase extends Service {{")
        lines.append("  @override")
        lines.append(
            f"  String get $name => '{self.get_grpc_service_name(service)}';"
        )
        lines.append("")
        lines.append(f"  {service.name}ServiceBase() {{")
        for method in service.methods:
            req_t = f"_models.{method.request_type.name}"
            res_t = f"_models.{method.response_type.name}"
            method_name = self.dart_grpc_method_name(method)
            lines.append(
                f"    $addMethod(ServiceMethod<{req_t}, {res_t}>("
            )
            lines.append(f"      '{method.name}',")
            lines.append(f"      {method_name}_Pre,")
            lines.append("      false,")
            lines.append("      false,")
            lines.append(
                f"      (List<int> value) => _deserialize<{req_t}>(value),"
            )
            lines.append(f"      ({res_t} value) => _serialize(value),")
            lines.append("    ));")
        lines.append("  }")
        lines.append("")
        for idx, method in enumerate(service.methods):
            req_t = f"_models.{method.request_type.name}"
            res_t = f"_models.{method.response_type.name}"
            method_name = self.dart_grpc_method_name(method)
            lines.append(
                "  // protoc_plugin parity: _Pre shim awaits the request future,"
            )
            lines.append("  // then delegates to the user-overridable method.")
            lines.append(f"  Future<{res_t}> {method_name}_Pre(")
            lines.append("    ServiceCall $call,")
            lines.append(f"    Future<{req_t}> $request,")
            lines.append("  ) async {")
            lines.append(f"    return {method_name}($call, await $request);")
            lines.append("  }")
            lines.append("")
            lines.append(f"  Future<{res_t}> {method_name}(")
            lines.append("    ServiceCall call,")
            lines.append(f"    {req_t} request,")
            lines.append("  );")
            if idx != len(service.methods) - 1:
                lines.append("")
        lines.append("}")
        return lines

    def dart_grpc_method_name(self, method: RpcMethod) -> str:
        return self.safe_identifier(self.to_camel_case(method.name))
