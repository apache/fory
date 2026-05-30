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

    def generate_grpc_module(self, services: List[Service]) -> GeneratedFile:
        """Emit a grpc-dart companion module for schema services."""
        models_stem = Path(self.module_file_name()).stem  # e.g. "demo_greeter"
        grpc_path = f"{models_stem}_grpc.dart"

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

        return GeneratedFile(path=grpc_path, content="\n".join(lines))
