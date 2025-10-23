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

import atexit
import keyword
import linecache
import os
import re
import uuid
from typing import List, Callable, Union

from pyfory.resolver import NULL_FLAG, NOT_NULL_VALUE_FLAG
from pyfory.error import CompileError


_type_mapping = {
    bool: ("write_bool", "read_bool", "write_nullable_pybool", "read_nullable_pybool"),
    int: (
        "write_varint64",
        "read_varint64",
        "write_nullable_pyint64",
        "read_nullable_pyint64",
    ),
    float: (
        "write_double",
        "read_double",
        "write_nullable_pyfloat64",
        "read_nullable_pyfloat64",
    ),
    str: ("write_string", "read_string", "write_nullable_pystr", "read_nullable_pystr"),
}


def gen_write_nullable_basic_stmts(
    buffer: str,
    value: str,
    type_: type,
) -> List[str]:
    methods = _type_mapping[type_]
    from pyfory import ENABLE_FORY_CYTHON_SERIALIZATION

    if ENABLE_FORY_CYTHON_SERIALIZATION:
        return [f"{methods[2]}({buffer}, {value})"]
    return [
        f"if {value} is None:",
        f"    {buffer}.write_int8({NULL_FLAG})",
        "else: ",
        f"    {buffer}.write_int8({NOT_NULL_VALUE_FLAG})",
        f"    {buffer}.{methods[0]}({value})",
    ]


def gen_read_nullable_basic_stmts(
    buffer: str,
    type_: type,
    set_action: Callable[[str], str],
) -> List[str]:
    methods = _type_mapping[type_]
    from pyfory import ENABLE_FORY_CYTHON_SERIALIZATION

    if ENABLE_FORY_CYTHON_SERIALIZATION:
        return [set_action(f"{methods[3]}({buffer})")]

    read_value = f"{buffer}.{methods[1]}()"
    return [
        f"if {buffer}.read_int8() == {NULL_FLAG}:",
        f"    {set_action('None')}",
        "else: ",
        f"    {set_action(read_value)}",
    ]


def _sanitize_function_name(name: str) -> str:
    """
    Sanitize function names by replacing invalid characters with valid ones.
    This is needed because function names with special characters like angle brackets
    are not valid Python syntax.
    """
    # 1) Replace every non‐identifier character with underscore
    sanitized = re.sub(r"[^0-9A-Za-z_]", "_", name)
    # 2) Prevent leading digit
    if re.match(r"^\d", sanitized):
        sanitized = "_" + sanitized
    # 3) Avoid plain keywords
    if keyword.iskeyword(sanitized):
        sanitized = "_" + sanitized
    return sanitized


def compile_function(
    function_name: str,
    params: List[str],
    stmts: List[str],
    context: dict,
):
    from pyfory import ENABLE_FORY_CYTHON_SERIALIZATION

    if ENABLE_FORY_CYTHON_SERIALIZATION:
        from pyfory import serialization

        context["write_nullable_pybool"] = serialization.write_nullable_pybool
        context["read_nullable_pybool"] = serialization.read_nullable_pybool
        context["write_nullable_pyint64"] = serialization.write_nullable_pyint64
        context["read_nullable_pyint64"] = serialization.read_nullable_pyint64
        context["write_nullable_pyfloat64"] = serialization.write_nullable_pyfloat64
        context["read_nullable_pyfloat64"] = serialization.read_nullable_pyfloat64
        context["write_nullable_pystr"] = serialization.write_nullable_pystr
        context["read_nullable_pystr"] = serialization.read_nullable_pystr
    stmts = [f"{ident(statement)}" for statement in stmts]
    # Sanitize the function name to ensure it is valid Python syntax
    sanitized_function_name = _sanitize_function_name(function_name)
    stmts.insert(0, f"def {sanitized_function_name}({', '.join(params)}):")
    stmts = [f"{statement}  # line {idx + 1}" for idx, statement in enumerate(stmts)]
    code = "\n".join(stmts)
    filename = _generate_filename(function_name)
    code_dir = _get_code_dir()
    if code_dir:
        filename = os.path.join(code_dir, filename)
        with open(filename, "w") as f:
            f.write(code)
            f.flush()
        if _delete_code_on_exit():
            atexit.register(os.remove, filename)
    try:
        compiled = compile(code, filename, "exec")
    except Exception as e:
        raise CompileError(f"Failed to compile code:\n{code}") from e
    exec(compiled, context, context)
    # See https://stackoverflow.com/questions/64879414/how-does-attrs-fool-the-debugger-to-step-into-auto-generated-code # noqa: E501
    # In order of debuggers like PDB being able to step through the code,
    # we add a fake linecache entry.
    linecache.cache[filename] = (
        len(code),
        None,
        code.splitlines(True),
        filename,
    )
    # Use the sanitized function name to retrieve the function from context
    sanitized_function_name = _sanitize_function_name(function_name)
    return code, context[sanitized_function_name]


# Based on https://github.com/python-attrs/attrs/blob/32fb12789e5cba4b2e71c09e47196b10763ddd7d/src/attr/_make.py#L1863 # noqa: E501
def _generate_filename(func_name):
    """
    Create a "filename" suitable for a function being generated.
    """
    # Sanitize the function name for filename
    sanitized_name = _sanitize_function_name(func_name)
    unique_id = uuid.uuid4()
    extra = "0"
    count = 1

    while True:
        filename = f"fory_generated_{sanitized_name}_{extra}.py"
        # To handle concurrency we essentially "reserve" our spot in
        # the linecache with a dummy line.  The caller can then
        # set this value correctly.
        cache_line = (1, None, [str(unique_id)], filename)
        if linecache.cache.setdefault(filename, cache_line) == cache_line:
            return filename

        # Looks like this spot is taken. Try again.
        count += 1
        extra = "{0}".format(count)


def _get_code_dir():
    code_dir = os.environ.get("FORY_CODE_DIR")
    if code_dir is not None and not os.path.exists(code_dir):
        os.makedirs(code_dir)
    return code_dir


def _delete_code_on_exit():
    return os.environ.get("DELETE_CODE_ON_EXIT", "True").lower() in ("true", "1")


def ident_lines(lines: Union[List[str], str]):
    is_str = type(lines) is str
    if is_str:
        lines = lines.split("\n")
    lines = [ident(line) for line in lines]
    return lines if not is_str else "\n".join(lines)


def ident(line: str):
    assert type(line) is str, type(line)
    return " " * 4 + line
