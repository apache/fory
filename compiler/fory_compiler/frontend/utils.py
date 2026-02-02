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

"""Frontend utilities for parsing IDL files and resolving imports."""

from pathlib import Path
from typing import List, Optional

from fory_compiler.frontend.fdl import FDLFrontend
from fory_compiler.frontend.fbs import FBSFrontend
from fory_compiler.frontend.proto import ProtoFrontend
from fory_compiler.ir.ast import Schema


def get_frontend(file_path: Path):
    """Select the correct frontend for a file."""
    frontends = [FDLFrontend(), ProtoFrontend(), FBSFrontend()]
    for frontend in frontends:
        if frontend.supports_file(file_path):
            return frontend
    raise ValueError(f"Unsupported file extension: {file_path.suffix}")


def parse_idl_file(file_path: Path) -> Schema:
    """Parse a single IDL file and return its schema."""
    frontend = get_frontend(file_path)
    return frontend.parse_file(file_path)


def resolve_import_path(
    import_stmt: str,
    importing_file: Path,
    import_paths: List[Path],
) -> Optional[Path]:
    """
    Resolve an import path by searching in multiple directories.

    Search order:
    1. Relative to the importing file's directory
    2. Each import path in order (from -I / --proto_path / --import_path)
    """
    # First, try relative to the importing file
    relative_path = (importing_file.parent / import_stmt).resolve()
    if relative_path.exists():
        return relative_path

    # Then try each import path
    for search_path in import_paths:
        candidate = (search_path / import_stmt).resolve()
        if candidate.exists():
            return candidate

    return None
