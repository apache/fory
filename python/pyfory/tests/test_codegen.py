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

import os
import textwrap
import uuid

from pyfory import codegen


def test_debug_compiled():
    code = textwrap.dedent(
        """
    def _debug_compiled(x):
        print(x)
        print(x)
        return x
    """
    )[1:]
    unique_filename = f"_debug_compiled_{uuid.uuid4()}.py"
    with open(unique_filename, "w") as f:
        f.write(code)
    compiled = compile(code, unique_filename, "exec")
    context = {}
    exec(compiled, context, context)
    _debug_compiled = context["_debug_compiled"]
    assert _debug_compiled(2) == 2
    os.remove(unique_filename)


def test_compile_function():
    code, func = codegen.compile_function("test_compile_function", ["x"], ["print(1)", "print(2)", "return x"], {})
    print(code)
    assert func(100) == 100
