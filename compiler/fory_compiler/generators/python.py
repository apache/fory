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

"""Python code generator."""

from typing import List, Optional, Set

from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.ir.ast import (
    Message,
    Enum,
    Union,
    Field,
    FieldType,
    PrimitiveType,
    NamedType,
    ListType,
    MapType,
)
from fory_compiler.ir.types import PrimitiveKind


class PythonGenerator(BaseGenerator):
    """Generates Python dataclasses with pyfory type hints."""

    language_name = "python"
    file_extension = ".py"

    # Mapping from FDL primitive types to Python types
    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "bool",
        PrimitiveKind.INT8: "pyfory.int8",
        PrimitiveKind.INT16: "pyfory.int16",
        PrimitiveKind.INT32: "pyfory.fixed_int32",
        PrimitiveKind.VARINT32: "pyfory.int32",
        PrimitiveKind.INT64: "pyfory.fixed_int64",
        PrimitiveKind.VARINT64: "pyfory.int64",
        PrimitiveKind.TAGGED_INT64: "pyfory.tagged_int64",
        PrimitiveKind.UINT8: "pyfory.uint8",
        PrimitiveKind.UINT16: "pyfory.uint16",
        PrimitiveKind.UINT32: "pyfory.fixed_uint32",
        PrimitiveKind.VAR_UINT32: "pyfory.uint32",
        PrimitiveKind.UINT64: "pyfory.fixed_uint64",
        PrimitiveKind.VAR_UINT64: "pyfory.uint64",
        PrimitiveKind.TAGGED_UINT64: "pyfory.tagged_uint64",
        PrimitiveKind.FLOAT16: "pyfory.float32",
        PrimitiveKind.FLOAT32: "pyfory.float32",
        PrimitiveKind.FLOAT64: "pyfory.float64",
        PrimitiveKind.STRING: "str",
        PrimitiveKind.BYTES: "bytes",
        PrimitiveKind.DATE: "datetime.date",
        PrimitiveKind.TIMESTAMP: "datetime.datetime",
    }

    # Numpy dtype strings for primitive arrays
    NUMPY_DTYPE_MAP = {
        PrimitiveKind.BOOL: "np.bool_",
        PrimitiveKind.INT8: "np.int8",
        PrimitiveKind.INT16: "np.int16",
        PrimitiveKind.INT32: "np.int32",
        PrimitiveKind.VARINT32: "np.int32",
        PrimitiveKind.INT64: "np.int64",
        PrimitiveKind.VARINT64: "np.int64",
        PrimitiveKind.TAGGED_INT64: "np.int64",
        PrimitiveKind.UINT8: "np.uint8",
        PrimitiveKind.UINT16: "np.uint16",
        PrimitiveKind.UINT32: "np.uint32",
        PrimitiveKind.VAR_UINT32: "np.uint32",
        PrimitiveKind.UINT64: "np.uint64",
        PrimitiveKind.VAR_UINT64: "np.uint64",
        PrimitiveKind.TAGGED_UINT64: "np.uint64",
        PrimitiveKind.FLOAT16: "np.float32",
        PrimitiveKind.FLOAT32: "np.float32",
        PrimitiveKind.FLOAT64: "np.float64",
    }

    ARRAY_TYPE_HINTS = {
        PrimitiveKind.BOOL: "pyfory.bool_ndarray",
        PrimitiveKind.INT8: "pyfory.int8_ndarray",
        PrimitiveKind.INT16: "pyfory.int16_ndarray",
        PrimitiveKind.INT32: "pyfory.int32_ndarray",
        PrimitiveKind.VARINT32: "pyfory.int32_ndarray",
        PrimitiveKind.INT64: "pyfory.int64_ndarray",
        PrimitiveKind.VARINT64: "pyfory.int64_ndarray",
        PrimitiveKind.TAGGED_INT64: "pyfory.int64_ndarray",
        PrimitiveKind.UINT8: "pyfory.uint8_ndarray",
        PrimitiveKind.UINT16: "pyfory.uint16_ndarray",
        PrimitiveKind.UINT32: "pyfory.uint32_ndarray",
        PrimitiveKind.VAR_UINT32: "pyfory.uint32_ndarray",
        PrimitiveKind.UINT64: "pyfory.uint64_ndarray",
        PrimitiveKind.VAR_UINT64: "pyfory.uint64_ndarray",
        PrimitiveKind.TAGGED_UINT64: "pyfory.uint64_ndarray",
        PrimitiveKind.FLOAT16: "pyfory.float32_ndarray",
        PrimitiveKind.FLOAT32: "pyfory.float32_ndarray",
        PrimitiveKind.FLOAT64: "pyfory.float64_ndarray",
    }

    # Default values for primitive types
    DEFAULT_VALUES = {
        PrimitiveKind.BOOL: "False",
        PrimitiveKind.INT8: "0",
        PrimitiveKind.INT16: "0",
        PrimitiveKind.INT32: "0",
        PrimitiveKind.VARINT32: "0",
        PrimitiveKind.INT64: "0",
        PrimitiveKind.VARINT64: "0",
        PrimitiveKind.TAGGED_INT64: "0",
        PrimitiveKind.UINT8: "0",
        PrimitiveKind.UINT16: "0",
        PrimitiveKind.UINT32: "0",
        PrimitiveKind.VAR_UINT32: "0",
        PrimitiveKind.UINT64: "0",
        PrimitiveKind.VAR_UINT64: "0",
        PrimitiveKind.TAGGED_UINT64: "0",
        PrimitiveKind.FLOAT16: "0.0",
        PrimitiveKind.FLOAT32: "0.0",
        PrimitiveKind.FLOAT64: "0.0",
        PrimitiveKind.STRING: '""',
        PrimitiveKind.BYTES: 'b""',
        PrimitiveKind.DATE: "None",
        PrimitiveKind.TIMESTAMP: "None",
    }

    def generate(self) -> List[GeneratedFile]:
        """Generate Python files for the schema."""
        files = []

        # Generate a single module with all types
        files.append(self.generate_module())

        return files

    def get_module_name(self) -> str:
        """Get the Python module name."""
        if self.package:
            return self.package.replace(".", "_")
        return "generated"

    def generate_module(self) -> GeneratedFile:
        """Generate a Python module with all types."""
        lines = []
        imports: Set[str] = set()

        # Collect all imports
        imports.add("from dataclasses import dataclass, field")
        imports.add("from enum import Enum, IntEnum")
        imports.add("from typing import Dict, List, Optional, cast")
        imports.add("import pyfory")

        for message in self.schema.messages:
            self.collect_message_imports(message, imports)
        for union in self.schema.unions:
            self.collect_union_imports(union, imports)

        # License header
        lines.append(self.get_license_header("#"))
        lines.append("")
        lines.append("from __future__ import annotations")
        lines.append("")

        # Imports
        for imp in sorted(imports):
            lines.append(imp)
        lines.append("")
        lines.append("")

        # Generate enums (top-level only)
        for enum in self.schema.enums:
            lines.extend(self.generate_enum(enum))
            lines.append("")
            lines.append("")

        # Generate unions (top-level only)
        for union in self.schema.unions:
            lines.extend(self.generate_union(union))
            lines.append("")
            lines.append("")

        # Generate messages (including nested types)
        for message in self.schema.messages:
            lines.extend(self.generate_message(message, indent=0))
            lines.append("")
            lines.append("")

        # Generate registration function
        lines.extend(self.generate_registration())
        lines.append("")

        return GeneratedFile(
            path=f"{self.get_module_name()}.py",
            content="\n".join(lines),
        )

    def collect_message_imports(self, message: Message, imports: Set[str]):
        """Collect imports for a message and its nested types recursively."""
        for field in message.fields:
            self.collect_field_imports(field, imports)
        for nested_msg in message.nested_messages:
            self.collect_message_imports(nested_msg, imports)
        for nested_union in message.nested_unions:
            self.collect_union_imports(nested_union, imports)

    def collect_union_imports(self, union: Union, imports: Set[str]):
        """Collect imports for a union and its cases."""
        imports.add("from pyfory.union import Union, UnionSerializer")
        for field in union.fields:
            self.collect_field_imports(field, imports)

    def generate_enum(self, enum: Enum, indent: int = 0) -> List[str]:
        """Generate a Python IntEnum."""
        lines = []
        ind = "    " * indent
        lines.append(f"{ind}class {enum.name}(IntEnum):")

        # Enum values (strip prefix for scoped enums)
        for value in enum.values:
            stripped_name = self.strip_enum_prefix(enum.name, value.name)
            lines.append(f"{ind}    {stripped_name} = {value.value}")

        return lines

    def generate_message(
        self,
        message: Message,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Python dataclass with nested types."""
        lines = []
        ind = "    " * indent
        lineage = (parent_stack or []) + [message]

        lines.append(f"{ind}@dataclass")
        lines.append(f"{ind}class {message.name}:")

        # Generate nested enums first (they need to be defined before fields reference them)
        for nested_enum in message.nested_enums:
            for line in self.generate_enum(nested_enum, indent=indent + 1):
                lines.append(line)
            lines.append("")

        # Generate nested unions
        for nested_union in message.nested_unions:
            for line in self.generate_union(
                nested_union, indent=indent + 1, parent_stack=lineage
            ):
                lines.append(line)
            lines.append("")

        # Generate nested messages
        for nested_msg in message.nested_messages:
            for line in self.generate_message(
                nested_msg,
                indent=indent + 1,
                parent_stack=lineage,
            ):
                lines.append(line)
            lines.append("")

        # Generate fields
        if (
            not message.fields
            and not message.nested_enums
            and not message.nested_unions
            and not message.nested_messages
        ):
            lines.append(f"{ind}    pass")
            return lines

        for field in message.fields:
            field_lines = self.generate_field(field, lineage)
            for line in field_lines:
                lines.append(f"{ind}    {line}")

        # If there are nested types but no fields, add pass to avoid empty class body issues
        if not message.fields and (
            message.nested_enums or message.nested_unions or message.nested_messages
        ):
            lines.append(f"{ind}    pass")

        return lines

    def generate_union(
        self,
        union: Union,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Python tagged union."""
        lines: List[str] = []
        ind = "    " * indent
        parent_path = ""
        if parent_stack:
            parent_path = ".".join([msg.name for msg in parent_stack])
        case_enum = f"{union.name}Case"
        case_enum_ref = f"{parent_path}.{case_enum}" if parent_path else case_enum
        union_ref = f"{parent_path}.{union.name}" if parent_path else union.name

        lines.append(f"{ind}class {case_enum}(Enum):")
        for field in union.fields:
            case_name = self.to_upper_snake_case(field.name)
            lines.append(f"{ind}    {case_name} = {field.number}")
        lines.append("")

        lines.append(f"{ind}class {union.name}(Union):")
        lines.append(f'{ind}    __slots__ = ("_case",)')
        lines.append("")
        lines.append(
            f"{ind}    def __init__(self, case: {case_enum_ref}, value: object) -> None:"
        )
        lines.append(f"{ind}        super().__init__(case.value, value)")
        lines.append(f"{ind}        self._case = case")
        lines.append(f"{ind}        self._validate()")
        lines.append("")

        for field in union.fields:
            method_name = self.to_snake_case(field.name)
            case_name = self.to_upper_snake_case(field.name)
            case_type = self.get_union_case_type(field, parent_stack)
            lines.append(f"{ind}    @classmethod")
            lines.append(
                f'{ind}    def {method_name}(cls, v: {case_type}) -> "{union_ref}":'
            )
            lines.append(f"{ind}        return cls({case_enum_ref}.{case_name}, v)")
            lines.append("")

        lines.append(f"{ind}    @classmethod")
        lines.append(
            f'{ind}    def _from_case_id(cls, case_id: int, value: object) -> "{union_ref}":'
        )
        for field in union.fields:
            case_name = self.to_upper_snake_case(field.name)
            lines.append(
                f"{ind}        if case_id == {case_enum_ref}.{case_name}.value:"
            )
            lines.append(
                f"{ind}            return cls({case_enum_ref}.{case_name}, value)"
            )
        lines.append(
            f'{ind}        raise ValueError("unknown {union.name} case id: {{}}".format(case_id))'
        )
        lines.append("")

        lines.append(f"{ind}    def _validate(self) -> None:")
        has_checks = False
        for field in union.fields:
            case_name = self.to_upper_snake_case(field.name)
            case_type = self.get_union_case_type(field, parent_stack)
            check_expr = self.get_union_case_check(field, parent_stack)
            if check_expr:
                has_checks = True
                lines.append(
                    f"{ind}        if self._case == {case_enum_ref}.{case_name} and not {check_expr}:"
                )
                lines.append(
                    f'{ind}            raise TypeError("{union.name}.{self.to_snake_case(field.name)}(...) requires {case_type}")'
                )
        if not union.fields or not has_checks:
            lines.append(f"{ind}        pass")
        lines.append("")

        lines.append(f"{ind}    def case(self) -> {case_enum_ref}:")
        lines.append(f"{ind}        return self._case")
        lines.append("")
        lines.append(f"{ind}    def case_id(self) -> int:")
        lines.append(f"{ind}        return self._case_id")
        lines.append("")
        lines.append(f"{ind}    def __eq__(self, other: object) -> bool:")
        lines.append(f"{ind}        if not isinstance(other, {union_ref}):")
        lines.append(f"{ind}            return NotImplemented")
        lines.append(
            f"{ind}        return self._case == other._case and self._value == other._value"
        )
        lines.append("")

        for field in union.fields:
            case_name = self.to_upper_snake_case(field.name)
            method_name = self.to_snake_case(field.name)
            case_type = self.get_union_case_type(field, parent_stack)
            lines.append(f"{ind}    def is_{method_name}(self) -> bool:")
            lines.append(
                f"{ind}        return self._case == {case_enum_ref}.{case_name}"
            )
            lines.append("")
            lines.append(f"{ind}    def {method_name}_value(self) -> {case_type}:")
            lines.append(f"{ind}        if self._case != {case_enum_ref}.{case_name}:")
            lines.append(
                f'{ind}            raise ValueError("{union.name} is not {case_name.lower()}")'
            )
            lines.append(f"{ind}        return cast({case_type}, self._value)")
            lines.append("")
            lines.append(
                f"{ind}    def set_{method_name}(self, v: {case_type}) -> None:"
            )
            lines.append(f"{ind}        self._case = {case_enum_ref}.{case_name}")
            lines.append(
                f"{ind}        self._case_id = {case_enum_ref}.{case_name}.value"
            )
            lines.append(f"{ind}        self._value = v")
            lines.append(f"{ind}        self._validate()")
            lines.append("")

        lines.extend(self.generate_union_serializer(union, indent, parent_stack))

        return lines

    def generate_union_serializer(
        self,
        union: Union,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Python serializer for a union."""
        lines: List[str] = []
        ind = "    " * indent
        serializer_name = f"{union.name}Serializer"
        parent_path = ""
        if parent_stack:
            parent_path = ".".join([msg.name for msg in parent_stack])
        union_ref = f"{parent_path}.{union.name}" if parent_path else union.name

        lines.append(f"{ind}class {serializer_name}(UnionSerializer):")
        lines.append("")
        lines.append(f"{ind}    def __init__(self, fory: pyfory.Fory):")
        lines.append(f"{ind}        super().__init__(fory, {union_ref}, {{")
        for field in union.fields:
            case_type = self.get_union_case_type(field, parent_stack)
            lines.append(f"{ind}            {field.number}: {case_type},")
        lines.append(f"{ind}        }})")
        lines.append("")

        return lines

    def generate_field(
        self,
        field: Field,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a dataclass field."""
        lines = []

        python_type = self.generate_type(
            field.field_type,
            field.optional,
            field.element_optional,
            parent_stack,
        )
        field_name = self.to_snake_case(field.name)
        default_factory = self.get_default_factory(field)
        default = self.get_default_value(field.field_type, field.optional)
        default_expr = default
        trailing_comment = ""
        if " # " in default:
            default_expr, comment = default.split(" # ", 1)
            trailing_comment = f"  # {comment}"

        tag_id = field.tag_id
        if tag_id is not None or field.ref:
            field_args = []
            if tag_id is not None:
                field_args.append(f"id={tag_id}")
            if field.optional:
                field_args.append("nullable=True")
            if field.ref:
                field_args.append("ref=True")
            if default_factory is not None:
                field_args.append(f"default_factory={default_factory}")
            else:
                field_args.append(f"default={default_expr}")
            field_default = f"pyfory.field({', '.join(field_args)}){trailing_comment}"
        else:
            if default_factory is not None:
                field_default = f"field(default_factory={default_factory})"
            else:
                field_default = f"{default_expr}{trailing_comment}"

        lines.append(f"{field_name}: {python_type} = {field_default}")

        return lines

    def uses_numpy_array(self, field_type: ListType, element_optional: bool) -> bool:
        """Return True if a list should be represented as a numpy array."""
        if not isinstance(field_type.element_type, PrimitiveType):
            return False
        return (
            field_type.element_type.kind in self.ARRAY_TYPE_HINTS
            and not element_optional
        )

    def get_default_factory(self, field: Field) -> Optional[str]:
        """Get default factory name for list/map fields."""
        if field.optional:
            return None
        if isinstance(field.field_type, ListType):
            if self.uses_numpy_array(field.field_type, field.element_optional):
                return None
            return "list"
        if isinstance(field.field_type, MapType):
            return "dict"
        return None

    def generate_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        element_optional: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Generate Python type hint."""
        if isinstance(field_type, PrimitiveType):
            base_type = self.PRIMITIVE_MAP[field_type.kind]
            if nullable:
                return f"Optional[{base_type}]"
            return base_type

        elif isinstance(field_type, NamedType):
            type_name = self.resolve_nested_type_name(field_type.name, parent_stack)
            if nullable:
                return f"Optional[{type_name}]"
            return type_name

        elif isinstance(field_type, ListType):
            # Use numpy array for numeric primitive types
            if isinstance(field_type.element_type, PrimitiveType):
                if not element_optional:
                    kind = field_type.element_type.kind
                    if kind in self.ARRAY_TYPE_HINTS:
                        list_type = self.ARRAY_TYPE_HINTS[kind]
                    else:
                        element_type = self.generate_type(
                            field_type.element_type,
                            element_optional,
                            parent_stack,
                        )
                        list_type = f"List[{element_type}]"
                else:
                    element_type = self.generate_type(
                        field_type.element_type,
                        element_optional,
                        parent_stack,
                    )
                    list_type = f"List[{element_type}]"
            else:
                element_type = self.generate_type(
                    field_type.element_type,
                    element_optional,
                    parent_stack,
                )
                list_type = f"List[{element_type}]"
            if nullable:
                return f"Optional[{list_type}]"
            return list_type

        elif isinstance(field_type, MapType):
            key_type = self.generate_type(
                field_type.key_type, False, False, parent_stack
            )
            value_type = self.generate_type(
                field_type.value_type, False, False, parent_stack
            )
            map_type = f"Dict[{key_type}, {value_type}]"
            if nullable:
                return f"Optional[{map_type}]"
            return map_type

        return "object"

    def get_union_case_type(
        self, field: Field, parent_stack: Optional[List[Message]] = None
    ) -> str:
        """Return the Python type name for a union case."""
        return self.generate_type(
            field.field_type,
            nullable=False,
            element_optional=field.element_optional,
            parent_stack=parent_stack,
        )

    def get_union_case_check(
        self, field: Field, parent_stack: Optional[List[Message]] = None
    ) -> Optional[str]:
        """Return an isinstance expression to validate a union case value."""
        return self.get_union_case_runtime_check(field, parent_stack, "self._value")

    def get_union_case_runtime_check(
        self,
        field: Field,
        parent_stack: Optional[List[Message]],
        value_expr: str,
    ) -> Optional[str]:
        """Return an isinstance expression for a union case value expression."""
        if isinstance(field.field_type, PrimitiveType):
            base = self.PRIMITIVE_MAP[field.field_type.kind]
            if base.startswith("pyfory."):
                if "float" in base:
                    return f"isinstance({value_expr}, float)"
                return f"isinstance({value_expr}, int)"
            if base == "bool":
                return f"isinstance({value_expr}, bool)"
            if base == "str":
                return f"isinstance({value_expr}, str)"
            if base == "bytes":
                return f"isinstance({value_expr}, (bytes, bytearray))"
            if base == "datetime.date":
                return f"isinstance({value_expr}, datetime.date)"
            if base == "datetime.datetime":
                return f"isinstance({value_expr}, datetime.datetime)"
        if isinstance(field.field_type, NamedType):
            type_name = self.resolve_nested_type_name(
                field.field_type.name, parent_stack
            )
            return f"isinstance({value_expr}, {type_name})"
        return None

    def resolve_nested_type_name(
        self,
        type_name: str,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Resolve nested type names to fully-qualified references."""
        if "." in type_name or not parent_stack:
            return type_name

        for i in range(len(parent_stack) - 1, -1, -1):
            message = parent_stack[i]
            if message.get_nested_type(type_name) is not None:
                prefix = ".".join(parent.name for parent in parent_stack[: i + 1])
                return f"{prefix}.{type_name}"

        return type_name

    def get_default_value(self, field_type: FieldType, nullable: bool = False) -> str:
        """Get default value for a field."""
        if nullable:
            return "None"

        if isinstance(field_type, PrimitiveType):
            return self.DEFAULT_VALUES.get(field_type.kind, "None")

        elif isinstance(field_type, NamedType):
            return "None"

        elif isinstance(field_type, ListType):
            # Use numpy empty array for numeric types
            if isinstance(field_type.element_type, PrimitiveType):
                if field_type.element_type.kind in self.NUMPY_DTYPE_MAP:
                    dtype = self.NUMPY_DTYPE_MAP[field_type.element_type.kind]
                    return f"None  # Use np.array([], dtype={dtype}) to initialize"
            return "None"

        elif isinstance(field_type, MapType):
            return "None"

        return "None"

    def collect_imports(
        self,
        field_type: FieldType,
        imports: Set[str],
        element_optional: bool = False,
    ):
        """Collect required imports for a field type."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind in (PrimitiveKind.DATE, PrimitiveKind.TIMESTAMP):
                imports.add("import datetime")

        elif isinstance(field_type, ListType):
            # Add numpy import for primitive arrays
            if isinstance(field_type.element_type, PrimitiveType):
                if (
                    field_type.element_type.kind in self.ARRAY_TYPE_HINTS
                    and not element_optional
                ):
                    imports.add("import numpy as np")
                    return
            self.collect_imports(field_type.element_type, imports)

        elif isinstance(field_type, MapType):
            self.collect_imports(field_type.key_type, imports)
            self.collect_imports(field_type.value_type, imports)

    def collect_field_imports(self, field: Field, imports: Set[str]):
        """Collect imports for a field, including list modifiers."""
        self.collect_imports(field.field_type, imports, field.element_optional)

    def generate_registration(self) -> List[str]:
        """Generate the Fory registration function."""
        lines = []

        func_name = f"register_{self.get_module_name()}_types"
        lines.append(f"def {func_name}(fory: pyfory.Fory):")

        if (
            not self.schema.enums
            and not self.schema.messages
            and not self.schema.unions
        ):
            lines.append("    pass")
            return lines

        # Register enums (top-level)
        for enum in self.schema.enums:
            self.generate_enum_registration(lines, enum, "")

        # Register unions (top-level)
        for union in self.schema.unions:
            self.generate_union_registration(lines, union, "")

        # Register messages (including nested types)
        for message in self.schema.messages:
            self.generate_message_registration(lines, message, "")

        return lines

    def generate_enum_registration(
        self, lines: List[str], enum: Enum, parent_path: str
    ):
        """Generate registration code for an enum."""
        # In Python, nested class references use Outer.Inner syntax
        class_ref = f"{parent_path}.{enum.name}" if parent_path else enum.name
        type_name = class_ref if parent_path else enum.name

        if enum.type_id is not None:
            lines.append(f"    fory.register_type({class_ref}, type_id={enum.type_id})")
        else:
            ns = self.package or "default"
            lines.append(
                f'    fory.register_type({class_ref}, namespace="{ns}", typename="{type_name}")'
            )

    def generate_message_registration(
        self, lines: List[str], message: Message, parent_path: str
    ):
        """Generate registration code for a message and its nested types."""
        # In Python, nested class references use Outer.Inner syntax
        class_ref = f"{parent_path}.{message.name}" if parent_path else message.name
        type_name = class_ref if parent_path else message.name

        if message.type_id is not None:
            lines.append(
                f"    fory.register_type({class_ref}, type_id={message.type_id})"
            )
        else:
            ns = self.package or "default"
            lines.append(
                f'    fory.register_type({class_ref}, namespace="{ns}", typename="{type_name}")'
            )

        # Register nested enums
        for nested_enum in message.nested_enums:
            self.generate_enum_registration(lines, nested_enum, class_ref)

        # Register nested unions
        for nested_union in message.nested_unions:
            self.generate_union_registration(lines, nested_union, class_ref)

        # Register nested messages
        for nested_msg in message.nested_messages:
            self.generate_message_registration(lines, nested_msg, class_ref)

    def generate_union_registration(
        self, lines: List[str], union: Union, parent_path: str
    ):
        """Generate registration code for a union."""
        class_ref = f"{parent_path}.{union.name}" if parent_path else union.name
        type_name = class_ref if parent_path else union.name
        serializer_ref = (
            f"{parent_path}.{union.name}Serializer"
            if parent_path
            else f"{union.name}Serializer"
        )

        if union.type_id is not None:
            lines.append(
                f"    fory.register_union({class_ref}, type_id={union.type_id}, serializer={serializer_ref}(fory))"
            )
        else:
            ns = self.package or "default"
            lines.append(
                f'    fory.register_union({class_ref}, namespace="{ns}", typename="{type_name}", serializer={serializer_ref}(fory))'
            )
