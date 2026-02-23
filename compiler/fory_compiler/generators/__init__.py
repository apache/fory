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

"""Code generators for different target languages."""

from fory_compiler.generators.base import BaseGenerator
from fory_compiler.generators.java import JavaGenerator
from fory_compiler.generators.python import PythonGenerator
from fory_compiler.generators.cpp import CppGenerator
from fory_compiler.generators.rust import RustGenerator
from fory_compiler.generators.go import GoGenerator
from fory_compiler.generators.csharp import CSharpGenerator
from fory_compiler.generators.typescript import TypeScriptGenerator

GENERATORS = {
    "java": JavaGenerator,
    "python": PythonGenerator,
    "cpp": CppGenerator,
    "rust": RustGenerator,
    "go": GoGenerator,
    "csharp": CSharpGenerator,
    "typescript": TypeScriptGenerator,
}

__all__ = [
    "BaseGenerator",
    "JavaGenerator",
    "PythonGenerator",
    "CppGenerator",
    "RustGenerator",
    "GoGenerator",
    "CSharpGenerator",
    "TypeScriptGenerator",
    "GENERATORS",
]
