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

import keyword
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.frontend.utils import parse_idl_file
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
    Schema,
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
        PrimitiveKind.ANY: "Any",
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
        PrimitiveKind.ANY: "None",
    }

    def safe_name(self, name: str) -> str:
        """Return a Python-safe identifier."""
        if keyword.iskeyword(name):
            return f"{name}_"
        return name

    def is_imported_type(self, type_def: object) -> bool:
        """Return True if a type definition comes from an imported IDL file."""
        if not self.schema.source_file:
            return False
        location = getattr(type_def, "location", None)
        if location is None or not location.file:
            return False
        try:
            return (
                Path(location.file).resolve() != Path(self.schema.source_file).resolve()
            )
        except Exception:
            return location.file != self.schema.source_file

    def split_imported_types(
        self, items: List[object]
    ) -> Tuple[List[object], List[object]]:
        imported: List[object] = []
        local: List[object] = []
        for item in items:
            if self.is_imported_type(item):
                imported.append(item)
            else:
                local.append(item)
        return imported, local

    def _load_schema(self, file_path: str) -> Optional[Schema]:
        if not file_path:
            return None
        if not hasattr(self, "_schema_cache"):
            self._schema_cache = {}
        cache: Dict[Path, Schema] = self._schema_cache
        path = Path(file_path).resolve()
        if path in cache:
            return cache[path]
        try:
            schema = parse_idl_file(path)
        except Exception:
            return None
        cache[path] = schema
        return schema

    def _module_name_for_schema(self, schema: Schema) -> str:
        if schema.package:
            return schema.package.replace(".", "_")
        return "generated"

    def _module_name_for_type(self, type_def: object) -> Optional[str]:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        schema = self._load_schema(file_path)
        if schema is None:
            return None
        return self._module_name_for_schema(schema)

    def _collect_imported_modules(self) -> List[str]:
        modules: Set[str] = set()
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(type_def):
                continue
            module = self._module_name_for_type(type_def)
            if module:
                modules.add(module)
        ordered: List[str] = []
        used: Set[str] = set()
        if self.schema.source_file:
            base_dir = Path(self.schema.source_file).resolve().parent
            for imp in self.schema.imports:
                candidate = (base_dir / imp.path).resolve()
                schema = self._load_schema(str(candidate))
                if schema is None:
                    continue
                module = self._module_name_for_schema(schema)
                if module in used:
                    continue
                ordered.append(module)
                used.add(module)
        for module in sorted(modules):
            if module in used:
                continue
            ordered.append(module)
        return ordered

    def generate_bytes_methods(self, return_type: str, indent: int) -> List[str]:
        ind = "    " * indent
        lines = []
        lines.append(f"{ind}    def to_bytes(self) -> bytes:")
        lines.append(f"{ind}        return _get_fory().serialize(self)")
        lines.append("")
        lines.append(f"{ind}    @classmethod")
        lines.append(f'{ind}    def from_bytes(cls, data: bytes) -> "{return_type}":')
        lines.append(f"{ind}        return _get_fory().deserialize(data)")
        lines.append("")
        lines.append(f"{ind}    def __bytes__(self) -> bytes:")
        lines.append(f"{ind}        return self.to_bytes()")
        lines.append("")
        return lines

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
        imports.add("from dataclasses import field")
        imports.add("from enum import Enum, IntEnum")
        imports.add("from typing import Dict, List, Optional, cast")
        imports.add("import pyfory")
        imports.add("import threading")
        if self.schema_has_ref_elements():
            imports.add("from pyfory import Ref")
        for module in self._collect_imported_modules():
            imports.add(f"import {module}")

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
            if self.is_imported_type(enum):
                continue
            lines.extend(self.generate_enum(enum))
            lines.append("")
            lines.append("")

        # Generate unions (top-level only)
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            lines.extend(self.generate_union(union))
            lines.append("")
            lines.append("")

        # Generate messages (including nested types)
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            lines.extend(self.generate_message(message, indent=0))
            lines.append("")
            lines.append("")

        # Generate registration function
        lines.extend(self.generate_registration())
        lines.append("")
        lines.extend(self.generate_fory_helpers())
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

        comment = self.format_type_id_comment(message, f"{ind}#")
        if comment:
            lines.append(comment)
        needs_repr = self.message_needs_safe_repr(message)
        decorator_args: List[str] = []
        if not self.get_effective_evolving(message):
            decorator_args.append("evolving=False")
        if needs_repr:
            decorator_args.append("repr=False")
        if decorator_args:
            lines.append(f"{ind}@pyfory.dataclass({', '.join(decorator_args)})")
        else:
            lines.append(f"{ind}@pyfory.dataclass")
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
        for field in message.fields:
            field_lines = self.generate_field(field, lineage)
            for line in field_lines:
                lines.append(f"{ind}    {line}")

        if (
            message.fields
            or message.nested_enums
            or message.nested_unions
            or message.nested_messages
        ):
            lines.append("")

        if needs_repr:
            lines.extend(self.generate_repr_method(message, indent=indent))
            lines.append("")

        return_type = ".".join([msg.name for msg in lineage])
        lines.extend(self.generate_bytes_methods(return_type, indent))

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

        comment = self.format_type_id_comment(union, f"{ind}#")
        if comment:
            lines.append(comment)
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
            method_name = self.safe_name(self.to_snake_case(field.name))
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
                safe_case = self.safe_name(self.to_snake_case(field.name))
                lines.append(
                    f'{ind}            raise TypeError("{union.name}.{safe_case}(...) requires {case_type}")'
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
            method_name = self.safe_name(self.to_snake_case(field.name))
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

        lines.extend(self.generate_bytes_methods(union_ref, indent))

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

        is_any = (
            isinstance(field.field_type, PrimitiveType)
            and field.field_type.kind == PrimitiveKind.ANY
        )
        nullable = field.optional or is_any
        python_type = self.generate_type(
            field.field_type,
            nullable,
            field.element_optional,
            field.element_ref,
            parent_stack,
        )
        field_name = self.safe_name(self.to_snake_case(field.name))
        default_factory = self.get_default_factory(field)
        default = self.get_default_value(field.field_type, field.optional)
        default_expr = default
        trailing_comment = ""
        if " # " in default:
            default_expr, comment = default.split(" # ", 1)
            trailing_comment = f"  # {comment}"

        tag_id = field.tag_id
        if tag_id is not None or field.ref or nullable:
            field_args = []
            if tag_id is not None:
                field_args.append(f"id={tag_id}")
            if nullable:
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

    def field_type_has_any(self, field_type: FieldType) -> bool:
        """Return True if field type or its children is any."""
        if isinstance(field_type, PrimitiveType):
            return field_type.kind == PrimitiveKind.ANY
        if isinstance(field_type, ListType):
            return self.field_type_has_any(field_type.element_type)
        if isinstance(field_type, MapType):
            return self.field_type_has_any(
                field_type.key_type
            ) or self.field_type_has_any(field_type.value_type)
        return False

    def field_has_ref(self, field: Field) -> bool:
        if field.ref:
            return True
        if isinstance(field.field_type, ListType):
            return field.element_ref
        if isinstance(field.field_type, MapType):
            return field.field_type.value_ref
        return False

    def field_needs_safe_repr(self, field: Field) -> bool:
        return self.field_has_ref(field) or self.field_type_has_any(field.field_type)

    def message_needs_safe_repr(self, message: Message) -> bool:
        return any(self.field_needs_safe_repr(field) for field in message.fields)

    def generate_repr_method(self, message: Message, indent: int = 0) -> List[str]:
        """Generate a repr method that avoids recursive ref expansion."""
        lines: List[str] = []
        ind = "    " * indent
        lines.append(f"{ind}    def __repr__(self) -> str:")
        if not message.fields:
            lines.append(f'{ind}        return "{message.name}()"')
            return lines
        lines.append(f"{ind}        parts = []")
        for field in message.fields:
            field_name = self.safe_name(self.to_snake_case(field.name))
            if self.field_needs_safe_repr(field):
                type_label = self.format_idl_type(field.field_type)
                placeholder = f"{type_label}(...)"
                placeholder_literal = repr(placeholder)
                expr = f"({placeholder_literal} if self.{field_name} is not None else 'None')"
            else:
                expr = f"repr(self.{field_name})"
            lines.append(f'{ind}        parts.append("{field_name}=" + {expr})')
        lines.append(f'{ind}        return "{message.name}(" + ", ".join(parts) + ")"')
        return lines

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
        element_ref: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Generate Python type hint."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.ANY:
                return "Any"
            base_type = self.PRIMITIVE_MAP[field_type.kind]
            if nullable:
                return f"Optional[{base_type}]"
            return base_type

        elif isinstance(field_type, NamedType):
            type_name = self.resolve_nested_type_name(field_type.name, parent_stack)
            named_type = self.schema.get_type(field_type.name)
            if named_type is not None and self.is_imported_type(named_type):
                module = self._module_name_for_type(named_type)
                if module:
                    type_name = f"{module}.{type_name}"
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
                            False,
                            False,
                            False,
                            parent_stack,
                        )
                        element_type = self.wrap_ref_type(
                            field_type.element_type,
                            element_type,
                            element_ref=element_ref,
                        )
                        if element_optional:
                            element_type = f"Optional[{element_type}]"
                        list_type = f"List[{element_type}]"
                else:
                    element_type = self.generate_type(
                        field_type.element_type,
                        False,
                        False,
                        False,
                        parent_stack,
                    )
                    element_type = self.wrap_ref_type(
                        field_type.element_type,
                        element_type,
                        element_ref=element_ref,
                    )
                    if element_optional:
                        element_type = f"Optional[{element_type}]"
                    list_type = f"List[{element_type}]"
            else:
                element_type = self.generate_type(
                    field_type.element_type,
                    False,
                    False,
                    False,
                    parent_stack,
                )
                element_type = self.wrap_ref_type(
                    field_type.element_type,
                    element_type,
                    element_ref=element_ref,
                )
                if element_optional:
                    element_type = f"Optional[{element_type}]"
                list_type = f"List[{element_type}]"
            if nullable:
                return f"Optional[{list_type}]"
            return list_type

        elif isinstance(field_type, MapType):
            key_type = self.generate_type(
                field_type.key_type, False, False, False, parent_stack
            )
            value_type = self.generate_type(
                field_type.value_type, False, False, False, parent_stack
            )
            value_type = self.wrap_ref_type(
                field_type.value_type,
                value_type,
                element_ref=field_type.value_ref,
            )
            map_type = f"Dict[{key_type}, {value_type}]"
            if nullable:
                return f"Optional[{map_type}]"
            return map_type

        return "object"

    def schema_has_ref_elements(self) -> bool:
        for message in self.schema.messages:
            if self.message_has_ref_elements(message):
                return True
        for union in self.schema.unions:
            for field in union.fields:
                if self.field_uses_ref_type(field):
                    return True
        return False

    def message_has_ref_elements(self, message: Message) -> bool:
        for field in message.fields:
            if self.field_uses_ref_type(field):
                return True
        for nested_msg in message.nested_messages:
            if self.message_has_ref_elements(nested_msg):
                return True
        for nested_union in message.nested_unions:
            for field in nested_union.fields:
                if self.field_uses_ref_type(field):
                    return True
        return False

    def field_uses_ref_type(self, field: Field) -> bool:
        if isinstance(field.field_type, ListType):
            return self.is_ref_target_type(field.field_type.element_type)
        if isinstance(field.field_type, MapType):
            return self.is_ref_target_type(field.field_type.value_type)
        return False

    def is_ref_target_type(self, field_type: FieldType) -> bool:
        if not isinstance(field_type, NamedType):
            return False
        resolved = self.schema.get_type(field_type.name)
        return isinstance(resolved, (Message, Union))

    def wrap_ref_type(
        self,
        field_type: FieldType,
        element_type: str,
        element_ref: bool,
    ) -> str:
        if not self.is_ref_target_type(field_type):
            return element_type
        if element_ref:
            return f"Ref[{element_type}]"
        return f"Ref[{element_type}, False]"

    def get_union_case_type(
        self, field: Field, parent_stack: Optional[List[Message]] = None
    ) -> str:
        """Return the Python type name for a union case."""
        return self.generate_type(
            field.field_type,
            nullable=False,
            element_optional=field.element_optional,
            element_ref=field.element_ref,
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
            elif field_type.kind == PrimitiveKind.ANY:
                imports.add("from typing import Any")

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

        elif isinstance(field_type, NamedType):
            named_type = self.schema.get_type(field_type.name)
            if named_type is not None and self.is_imported_type(named_type):
                module = self._module_name_for_type(named_type)
                if module:
                    imports.add(f"import {module}")

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
            if self.is_imported_type(enum):
                continue
            self.generate_enum_registration(lines, enum, "")

        # Register unions (top-level)
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            self.generate_union_registration(lines, union, "")

        # Register messages (including nested types)
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            self.generate_message_registration(lines, message, "")

        return lines

    def generate_fory_helpers(self) -> List[str]:
        lines = []
        lines.append("_fory_lock = threading.Lock()")
        lines.append("_threadsafe_fory = None")
        lines.append("")
        lines.append("def _create_fory() -> pyfory.Fory:")
        lines.append("    fory = pyfory.Fory(xlang=True, ref=True, compatible=True)")
        for module in self._collect_imported_modules():
            lines.append(f"    {module}.register_{module}_types(fory)")
        lines.append(f"    register_{self.get_module_name()}_types(fory)")
        lines.append("    return fory")
        lines.append("")
        lines.append("def _get_fory() -> pyfory.ThreadSafeFory:")
        lines.append("    global _threadsafe_fory")
        lines.append("    if _threadsafe_fory is None:")
        lines.append("        with _fory_lock:")
        lines.append("            if _threadsafe_fory is None:")
        lines.append(
            "                _threadsafe_fory = pyfory.ThreadSafeFory(fory_factory=_create_fory)"
        )
        lines.append("    return _threadsafe_fory")
        return lines

    def generate_enum_registration(
        self, lines: List[str], enum: Enum, parent_path: str
    ):
        """Generate registration code for an enum."""
        # In Python, nested class references use Outer.Inner syntax
        class_ref = f"{parent_path}.{enum.name}" if parent_path else enum.name
        type_name = class_ref if parent_path else enum.name
        if self.should_register_by_id(enum):
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
        if self.should_register_by_id(message):
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
        if self.should_register_by_id(union):
            lines.append(
                f"    fory.register_union({class_ref}, type_id={union.type_id}, serializer={serializer_ref}(fory))"
            )
        else:
            ns = self.package or "default"
            lines.append(
                f'    fory.register_union({class_ref}, namespace="{ns}", typename="{type_name}", serializer={serializer_ref}(fory))'
            )
