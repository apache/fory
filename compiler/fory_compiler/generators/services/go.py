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

"""Go gRPC service code generator."""

from typing import List
from fory_compiler.generators.services.base import ImportTracker, StreamingMode, streaming_mode
from fory_compiler.generators.base import GeneratedFile
from fory_compiler.ir.ast import RpcMethod, Service


class GoServiceGeneratorMixin:
    """Generates Go gRPC service stubs."""

    def generate_services(self) -> List[GeneratedFile]:
        local_services = [s for s in self.schema.services if not self.is_imported_type(s)]
        if not local_services:
            return []
        return [self._generate_grpc_file(s) for s in local_services]

    def _generate_grpc_file(self, service: Service) -> GeneratedFile:
        lines: List[str] = []
        tracker = ImportTracker()

        # License header
        lines.append(self.get_license_header("//"))
        lines.append("")

        # Package declaration
        lines.append(f"package {self.get_package_name()}")
        lines.append("")

        # Imports
        # save the placeholder index for now
        import_placeholder_index = len(lines)





        # after all the service code gets generated, insert the import block at the placeholder index
        import_lines = self._build_import_block(tracker)
        for i, line in enumerate(import_lines):
            lines.insert(import_placeholder_index + i, line)

        return GeneratedFile(
            path=f"{self.get_file_name()}_grpc.go",
            content="\n".join(lines)
        )

    def _build_import_block(self, tracker: ImportTracker) -> List[str]:
        imports = [
            '"context"',
            '"google.golang.org/grpc"',
            '"google.golang.org/grpc/codes"',
            '"google.golang.org/grpc/status"',
        ]
        
        for path in tracker.go_imports():
            imports.append(f'"{path}"')
        
        sorted_imports = sorted(set(imports))       # deduplicate and sort the imports

        lines = ["import ("]
        for imp in sorted_imports:
            lines.append(f"\t{imp}")
        lines.append(")")
        lines.append("")

        return lines
