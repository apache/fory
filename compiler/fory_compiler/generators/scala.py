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

"""Scala 3 schema IDL generator."""

from __future__ import annotations

from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.ir.ast import (
    ArrayType,
    Enum,
    Field,
    FieldType,
    ListType,
    MapType,
    Message,
    NamedType,
    Schema,
    PrimitiveType,
    Union,
)
from fory_compiler.ir.construction import analyze_shapes
from fory_compiler.ir.types import PrimitiveKind


class ScalaGenerator(BaseGenerator):
    """Generates Scala 3 models with Fory macro-derived serializers."""

    language_name = "scala"
    file_extension = ".scala"

    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "Boolean",
        PrimitiveKind.INT8: "Byte",
        PrimitiveKind.INT16: "Short",
        PrimitiveKind.INT32: "Int",
        PrimitiveKind.INT64: "Long",
        PrimitiveKind.UINT8: "Int",
        PrimitiveKind.UINT16: "Int",
        PrimitiveKind.UINT32: "Long",
        PrimitiveKind.UINT64: "Long",
        PrimitiveKind.FLOAT16: "Float16",
        PrimitiveKind.BFLOAT16: "BFloat16",
        PrimitiveKind.FLOAT32: "Float",
        PrimitiveKind.FLOAT64: "Double",
        PrimitiveKind.STRING: "String",
        PrimitiveKind.BYTES: "Array[Byte]",
        PrimitiveKind.DATE: "LocalDate",
        PrimitiveKind.TIMESTAMP: "Instant",
        PrimitiveKind.DURATION: "Duration",
        PrimitiveKind.DECIMAL: "BigDecimal",
        PrimitiveKind.ANY: "AnyRef",
    }

    ARRAY_ELEMENT_MAP = {
        PrimitiveKind.BOOL: "Boolean",
        PrimitiveKind.INT8: "Byte",
        PrimitiveKind.INT16: "Short",
        PrimitiveKind.INT32: "Int",
        PrimitiveKind.INT64: "Long",
        PrimitiveKind.UINT8: "Byte",
        PrimitiveKind.UINT16: "Short",
        PrimitiveKind.UINT32: "Int",
        PrimitiveKind.UINT64: "Long",
        PrimitiveKind.FLOAT16: "Short",
        PrimitiveKind.BFLOAT16: "Short",
        PrimitiveKind.FLOAT32: "Float",
        PrimitiveKind.FLOAT64: "Double",
    }

    RESERVED = {
        "abstract",
        "case",
        "catch",
        "class",
        "def",
        "do",
        "else",
        "enum",
        "export",
        "extends",
        "false",
        "final",
        "finally",
        "for",
        "given",
        "if",
        "implicit",
        "import",
        "lazy",
        "match",
        "new",
        "null",
        "object",
        "override",
        "package",
        "private",
        "protected",
        "return",
        "sealed",
        "super",
        "then",
        "this",
        "throw",
        "trait",
        "true",
        "try",
        "type",
        "val",
        "var",
        "while",
        "with",
        "yield",
    }

    def __init__(self, schema, options):
        super().__init__(schema, options)
        self._validate_import_packages()
        self._construction_shapes = analyze_shapes(schema)

    def get_scala_package(self) -> Optional[str]:
        return self.schema.package

    def get_scala_package_path(self) -> str:
        package = self.get_scala_package()
        return package.replace(".", "/") if package else ""

    def get_module_name(self) -> str:
        return self._module_name(self.schema)

    def is_imported_type(self, type_def: object) -> bool:
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

    def _normalize_import_path(self, path_str: str) -> str:
        if not path_str:
            return path_str
        try:
            return str(Path(path_str).resolve())
        except Exception:
            return path_str

    def _scala_package_for_schema(self, schema: Schema) -> Optional[str]:
        return schema.package

    def _validate_import_packages(self) -> None:
        schemas = self._schema_graph()
        packages = {self._scala_package_for_schema(schema) for _, schema in schemas}
        if None not in packages or len(packages) <= 1:
            return
        details = ", ".join(
            f"{source} uses {self._scala_package_for_schema(schema) or '<default>'}"
            for source, schema in schemas
        )
        raise ValueError(
            "Scala imports cannot mix default-package schemas with named "
            f"Scala packages; {details}"
        )

    def _module_name(self, schema: Schema) -> str:
        package = self._scala_package_for_schema(schema)
        if package:
            return self.to_pascal_case(package.split(".")[-1]) + "ForyModule"
        prefix = self._module_prefix_from_source(schema)
        if prefix:
            return prefix + "ForyModule"
        return "ForyModule"

    def _module_prefix_from_source(self, schema: Schema) -> str:
        if not schema.source_file or schema.source_file.startswith("<"):
            return ""
        cleaned = "".join(
            char if char.isascii() and (char.isalnum() or char == "_") else "_"
            for char in Path(schema.source_file).stem
        )
        prefix = self.to_pascal_case(cleaned) if cleaned else "Schema"
        if not prefix or not (prefix[0].isalpha() or prefix[0] == "_"):
            prefix = f"Schema{prefix}"
        return prefix

    def _scala_package_for_type(self, type_def: object) -> Optional[str]:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        schema = self._load_schema(file_path)
        if schema is None:
            return None
        return self._scala_package_for_schema(schema)

    def _imported_regs(self) -> List[tuple[str, str]]:
        registrations: dict[tuple[str, str], tuple[str, str]] = {}
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(type_def):
                continue
            package = self._scala_package_for_type(type_def)
            schema = self._load_schema(
                getattr(getattr(type_def, "location", None), "file", None)
            )
            if schema is None:
                continue
            registration = (package or "", self._module_name(schema))
            registrations.setdefault(registration, registration)

        ordered: List[tuple[str, str]] = []
        used: Set[tuple[str, str]] = set()
        if self.schema.source_file:
            base_dir = Path(self.schema.source_file).resolve().parent
            for imp in self.schema.imports:
                candidate = self._normalize_import_path(
                    str((base_dir / imp.path).resolve())
                )
                schema = self._load_schema(candidate)
                if schema is None:
                    continue
                package = self._scala_package_for_schema(schema)
                registration = (package or "", self._module_name(schema))
                if registration in used:
                    continue
                ordered.append(registration)
                used.add(registration)
        for registration in sorted(registrations):
            if registration not in used:
                ordered.append(registration)
        return ordered

    def generate(self) -> List[GeneratedFile]:
        files: List[GeneratedFile] = []
        for enum in self.schema.enums:
            if self.is_imported_type(enum):
                continue
            files.append(self.generate_enum_file(enum))
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            files.append(self.generate_union_file(union))
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            files.append(self.generate_message_file(message))
        files.append(self.generate_module_file())
        return files

    def generate_enum_file(self, enum: Enum) -> GeneratedFile:
        lines = self.source_header({"org.apache.fory.annotation.ForyEnumId"})
        comment = self.format_type_id_comment(enum, "//")
        if comment:
            lines.append(comment)
        lines.extend(self.generate_enum(enum))
        return self.source_file(enum.name, lines)

    def generate_union_file(self, union: Union) -> GeneratedFile:
        imports = {
            "org.apache.fory.annotation.{ForyCase, ForyUnion, ForyUnknownCase}",
            "org.apache.fory.scala.ForySerializer",
            "org.apache.fory.`type`.union.UnknownCase",
        }
        self.collect_union_imports(union, imports)
        lines = self.source_header(imports)
        comment = self.format_type_id_comment(union, "//")
        if comment:
            lines.append(comment)
        lines.extend(self.generate_union(union, parent_stack=[]))
        return self.source_file(union.name, lines)

    def generate_message_file(self, message: Message) -> GeneratedFile:
        imports = {
            "org.apache.fory.annotation.{ForyField, ForyStruct}",
            "org.apache.fory.scala.ForySerializer",
        }
        self.collect_message_imports(message, imports)
        lines = self.source_header(imports)
        comment = self.format_type_id_comment(message, "//")
        if comment:
            lines.append(comment)
        lines.extend(self.generate_message(message, parent_stack=[]))
        return self.source_file(message.name, lines)

    def source_header(self, imports: Set[str]) -> List[str]:
        lines = [self.get_license_header(), ""]
        package = self.get_scala_package()
        if package:
            lines.append(f"package {package}")
            lines.append("")
        for item in sorted(imports):
            lines.append(f"import {item}")
        if imports:
            lines.append("")
        return lines

    def source_file(self, type_name: str, lines: List[str]) -> GeneratedFile:
        path = self.get_scala_package_path()
        file_path = f"{path}/{type_name}.scala" if path else f"{type_name}.scala"
        return GeneratedFile(path=file_path, content="\n".join(lines) + "\n")

    def generate_enum(self, enum: Enum, indent: int = 0) -> List[str]:
        ind = self.indent_str * indent
        lines = [f"{ind}enum {enum.name} {{"]
        for value in enum.values:
            case_name = self.safe_identifier(
                self.to_pascal_case(self.strip_enum_prefix(enum.name, value.name))
            )
            lines.append(f"{ind}    @ForyEnumId({value.value})")
            lines.append(f"{ind}    case {case_name}")
        lines.append(f"{ind}}}")
        lines.append("")
        return lines

    def generate_union(
        self,
        union: Union,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        ind = self.indent_str * indent
        lines = [
            f"{ind}@ForyUnion",
            f"{ind}enum {union.name} derives ForySerializer {{",
        ]
        lines.append(f"{ind}    @ForyUnknownCase")
        lines.append(f"{ind}    case Unknown(value: UnknownCase)")
        lines.append("")
        for field in union.fields:
            lines.append(f"{ind}    @ForyCase(id = {field.number})")
            case_name = self.to_pascal_case(field.name)
            field_type = self.generate_type(
                field.field_type,
                nullable=False,
                element_optional=field.element_optional,
                element_ref=field.element_ref,
                top_level_ref=field.ref,
                parent_stack=parent_stack,
            )
            case_name = self.union_case_name(field.field_type, field_type, case_name)
            field_type = self.qualify_union_payload_type(field_type, case_name)
            lines.append(f"{ind}    case {case_name}(value: {field_type})")
            lines.append("")
        lines.append(f"{ind}}}")
        lines.append("")
        return lines

    def generate_message(
        self,
        message: Message,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        shape = self._construction_shapes.get(
            self.construction_key(parent_stack, message), None
        )
        if shape is not None and shape.cycle_owned:
            return self.generate_normal_class(message, indent, parent_stack)
        return self.generate_case_class(message, indent, parent_stack)

    def generate_case_class(
        self,
        message: Message,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        ind = self.indent_str * indent
        current_stack = self.current_stack(parent_stack, message)
        lines = [f"{ind}@ForyStruct", f"{ind}final case class {message.name}("]
        for index, field in enumerate(message.fields):
            suffix = "," if index < len(message.fields) - 1 else ""
            lines.append(
                f"{ind}    {self.generate_parameter(field, current_stack)}{suffix}"
            )
        lines.append(f"{ind}) derives ForySerializer {{")
        lines.extend(self.generate_to_bytes(message, indent + 1))
        lines.append(f"{ind}}}")
        lines.append("")
        lines.extend(self.generate_companion(message, indent, current_stack))
        return lines

    def generate_normal_class(
        self,
        message: Message,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        ind = self.indent_str * indent
        current_stack = self.current_stack(parent_stack, message)
        lines = [
            f"{ind}@ForyStruct",
            f"{ind}final class {message.name}() derives ForySerializer {{",
        ]
        for field in message.fields:
            field_type = self.generate_type(
                field.field_type,
                nullable=field.optional,
                element_optional=field.element_optional,
                element_ref=field.element_ref,
                # Message fields own top-level ref metadata through the field
                # annotation below. Type-use @Ref is reserved for nested
                # element/value/payload refs so optional top-level refs do not
                # carry duplicate metadata like Option[Foo @Ref] plus @Ref.
                top_level_ref=False,
                parent_stack=current_stack,
            )
            if field.ref and self.is_ref_target_type(field.field_type, current_stack):
                lines.append(f"{ind}    @Ref")
            lines.append(f"{ind}    @ForyField(id = {field.number})")
            lines.append(
                f"{ind}    var {self.safe_identifier(self.to_camel_case(field.name))}: {field_type} = {self.default_value(field)}"
            )
            lines.append("")
        lines.extend(self.generate_to_bytes(message, indent + 1))
        lines.append(f"{ind}}}")
        lines.append("")
        lines.extend(self.generate_companion(message, indent, current_stack))
        return lines

    def generate_to_bytes(self, message: Message, indent: int) -> List[str]:
        ind = self.indent_str * indent
        module = self.get_module_name()
        return [
            f"{ind}def toBytes(): Array[Byte] =",
            f"{ind}    {module}.getFory.serialize(this)",
        ]

    def generate_companion(
        self, message: Message, indent: int, parent_stack: List[Message]
    ) -> List[str]:
        ind = self.indent_str * indent
        module = self.get_module_name()
        lines = [f"{ind}object {message.name} {{"]
        lines.append(f"{ind}    def fromBytes(bytes: Array[Byte]): {message.name} =")
        lines.append(
            f"{ind}        {module}.getFory.deserialize(bytes).asInstanceOf[{message.name}]"
        )
        if message.nested_enums or message.nested_unions or message.nested_messages:
            lines.append("")
        for enum in message.nested_enums:
            lines.extend(self.generate_enum(enum, indent + 1))
        for union in message.nested_unions:
            lines.extend(self.generate_union(union, indent + 1, parent_stack))
        for nested in message.nested_messages:
            lines.extend(self.generate_message(nested, indent + 1, parent_stack))
        lines.append(f"{ind}}}")
        lines.append("")
        return lines

    def generate_parameter(self, field: Field, parent_stack: List[Message]) -> str:
        field_name = self.safe_identifier(self.to_camel_case(field.name))
        field_type = self.generate_type(
            field.field_type,
            nullable=field.optional,
            element_optional=field.element_optional,
            element_ref=field.element_ref,
            # Message constructor parameters use @Ref on the parameter for
            # top-level ref metadata. Nested refs still flow through
            # element_ref/value_ref type-use annotations.
            top_level_ref=False,
            parent_stack=parent_stack,
        )
        ref_annotation = (
            "@Ref "
            if field.ref and self.is_ref_target_type(field.field_type, parent_stack)
            else ""
        )
        return f"{ref_annotation}@ForyField(id = {field.number}) {field_name}: {field_type}"

    def generate_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        element_optional: bool = False,
        element_ref: bool = False,
        top_level_ref: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        base = self._generate_non_optional_type(
            field_type, element_optional, element_ref, parent_stack
        )
        if top_level_ref and self.is_ref_target_type(field_type, parent_stack):
            base = self.apply_type_annotation(base, "Ref")
        return f"Option[{base}]" if nullable else base

    def _generate_non_optional_type(
        self,
        field_type: FieldType,
        element_optional: bool = False,
        element_ref: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        if isinstance(field_type, PrimitiveType):
            scala_type = self.PRIMITIVE_MAP[field_type.kind]
            return self.apply_primitive_annotation(scala_type, field_type)
        if isinstance(field_type, NamedType):
            return self.resolve_scala_type_name(field_type.name, parent_stack)
        if isinstance(field_type, ListType):
            element = self.generate_type(
                field_type.element_type,
                nullable=element_optional or field_type.element_optional,
                element_optional=False,
                element_ref=False,
                top_level_ref=element_ref or field_type.element_ref,
                parent_stack=parent_stack,
            )
            return f"List[{element}]"
        if isinstance(field_type, ArrayType):
            element = self.generate_array_element_type(field_type.element_type)
            return f"Array[{element}]"
        if isinstance(field_type, MapType):
            key = self.generate_type(field_type.key_type, parent_stack=parent_stack)
            value = self.generate_type(
                field_type.value_type,
                nullable=field_type.value_optional,
                top_level_ref=field_type.value_ref,
                parent_stack=parent_stack,
            )
            return f"Map[{key}, {value}]"
        return "AnyRef"

    def generate_array_element_type(self, field_type: FieldType) -> str:
        if not isinstance(field_type, PrimitiveType):
            return self._generate_non_optional_type(field_type)
        scala_type = self.ARRAY_ELEMENT_MAP[field_type.kind]
        annotation = self.get_array_element_annotation(field_type)
        if annotation is not None:
            return self.apply_type_annotation(scala_type, annotation)
        return scala_type

    def current_stack(
        self, parent_stack: Optional[List[Message]], message: Message
    ) -> List[Message]:
        return [*(parent_stack or []), message]

    def construction_key(
        self, parent_stack: Optional[List[Message]], message: Message
    ) -> str:
        return ".".join([*[owner.name for owner in parent_stack or []], message.name])

    def resolve_scala_type_name(
        self, name: str, parent_stack: Optional[List[Message]]
    ) -> str:
        if "." in name:
            root_name = name.split(".", 1)[0]
            named_type = self.schema.get_type(root_name)
            if named_type is not None and self.is_imported_type(named_type):
                package = self._scala_package_for_type(named_type)
                if package and package != self.get_scala_package():
                    return f"{package}.{name}"
            return name
        if parent_stack:
            for index in range(len(parent_stack) - 1, -1, -1):
                owner = parent_stack[index]
                if owner.get_nested_type(name) is not None:
                    return ".".join(
                        [message.name for message in parent_stack[: index + 1]] + [name]
                    )
        named_type = self.schema.get_type(name)
        if named_type is not None and self.is_imported_type(named_type):
            package = self._scala_package_for_type(named_type)
            if package and package != self.get_scala_package():
                return f"{package}.{name}"
        return name

    def qualify_union_payload_type(self, rendered_type: str, case_name: str) -> str:
        if rendered_type != case_name:
            return rendered_type
        package = self.get_scala_package()
        if package:
            return f"_root_.{package}.{rendered_type}"
        return rendered_type

    def union_case_name(
        self,
        field_type: FieldType,
        rendered_type: str,
        case_name: str,
    ) -> str:
        if (
            rendered_type == case_name
            and isinstance(field_type, NamedType)
            and not self.get_scala_package()
        ):
            return f"{case_name}Case"
        return case_name

    def apply_type_annotation(self, scala_type: str, annotation: str) -> str:
        return f"{scala_type} @{annotation}"

    def apply_primitive_annotation(
        self, scala_type: str, field_type: PrimitiveType
    ) -> str:
        annotation = self.get_integer_annotation(field_type)
        if annotation is not None:
            return self.apply_type_annotation(scala_type, annotation)
        return scala_type

    def get_integer_annotation(self, field_type: PrimitiveType) -> Optional[str]:
        kind = field_type.kind
        if kind == PrimitiveKind.INT32 and field_type.encoding_modifier == "fixed":
            return "Int32Type(encoding = Int32Encoding.FIXED)"
        if kind == PrimitiveKind.INT32 and field_type.encoding_modifier == "varint":
            return "Int32Type(encoding = Int32Encoding.VARINT)"
        if kind == PrimitiveKind.INT64 and field_type.encoding_modifier == "fixed":
            return "Int64Type(encoding = Int64Encoding.FIXED)"
        if kind == PrimitiveKind.INT64 and field_type.encoding_modifier == "varint":
            return "Int64Type(encoding = Int64Encoding.VARINT)"
        if kind == PrimitiveKind.INT64 and field_type.encoding_modifier == "tagged":
            return "Int64Type(encoding = Int64Encoding.TAGGED)"
        if kind == PrimitiveKind.UINT8:
            return "UInt8Type"
        if kind == PrimitiveKind.UINT16:
            return "UInt16Type"
        if kind == PrimitiveKind.UINT32 and field_type.encoding_modifier == "fixed":
            return "UInt32Type(encoding = Int32Encoding.FIXED)"
        if kind == PrimitiveKind.UINT32 and field_type.encoding_modifier == "varint":
            return "UInt32Type(encoding = Int32Encoding.VARINT)"
        if kind == PrimitiveKind.UINT32:
            return "UInt32Type"
        if kind == PrimitiveKind.UINT64 and field_type.encoding_modifier == "fixed":
            return "UInt64Type(encoding = Int64Encoding.FIXED)"
        if kind == PrimitiveKind.UINT64 and field_type.encoding_modifier == "varint":
            return "UInt64Type(encoding = Int64Encoding.VARINT)"
        if kind == PrimitiveKind.UINT64 and field_type.encoding_modifier == "tagged":
            return "UInt64Type(encoding = Int64Encoding.TAGGED)"
        if kind == PrimitiveKind.UINT64:
            return "UInt64Type"
        return None

    def get_array_element_annotation(self, field_type: PrimitiveType) -> Optional[str]:
        kind = field_type.kind
        if kind == PrimitiveKind.INT8:
            return "Int8Type"
        if kind == PrimitiveKind.UINT8:
            return "UInt8Type"
        if kind == PrimitiveKind.UINT16:
            return "UInt16Type"
        if kind == PrimitiveKind.UINT32:
            return "UInt32Type"
        if kind == PrimitiveKind.UINT64:
            return "UInt64Type"
        if kind == PrimitiveKind.FLOAT16:
            return "Float16Type"
        if kind == PrimitiveKind.BFLOAT16:
            return "BFloat16Type"
        return None

    def default_value(self, field: Field) -> str:
        if field.optional:
            return "None"
        return self.default_value_for_type(field.field_type)

    def default_value_for_type(self, field_type: FieldType) -> str:
        if isinstance(field_type, PrimitiveType):
            defaults = {
                PrimitiveKind.BOOL: "false",
                PrimitiveKind.INT8: "0.toByte",
                PrimitiveKind.INT16: "0.toShort",
                PrimitiveKind.INT32: "0",
                PrimitiveKind.INT64: "0L",
                PrimitiveKind.UINT8: "0",
                PrimitiveKind.UINT16: "0",
                PrimitiveKind.UINT32: "0L",
                PrimitiveKind.UINT64: "0L",
                PrimitiveKind.FLOAT32: "0.0f",
                PrimitiveKind.FLOAT64: "0.0d",
                PrimitiveKind.STRING: '""',
                PrimitiveKind.BYTES: "Array.emptyByteArray",
                PrimitiveKind.DECIMAL: "BigDecimal.ZERO",
            }
            return defaults.get(field_type.kind, "null")
        if isinstance(field_type, ListType):
            return "List.empty"
        if isinstance(field_type, ArrayType):
            return "Array.empty"
        if isinstance(field_type, MapType):
            return "Map.empty"
        return "null"

    def collect_message_imports(self, message: Message, imports: Set[str]) -> None:
        for field in message.fields:
            self.collect_type_imports(field.field_type, imports)
            if field.ref or self.field_type_has_ref(field.field_type):
                imports.add("org.apache.fory.annotation.Ref")
        for enum in message.nested_enums:
            imports.add("org.apache.fory.annotation.ForyEnumId")
        for union in message.nested_unions:
            imports.add(
                "org.apache.fory.annotation.{ForyCase, ForyUnion, ForyUnknownCase}"
            )
            imports.add("org.apache.fory.`type`.union.UnknownCase")
            self.collect_union_imports(union, imports)
        for nested in message.nested_messages:
            self.collect_message_imports(nested, imports)

    def collect_union_imports(self, union: Union, imports: Set[str]) -> None:
        for field in union.fields:
            self.collect_type_imports(field.field_type, imports)
            if field.ref or self.field_type_has_ref(field.field_type):
                imports.add("org.apache.fory.annotation.Ref")

    def collect_type_imports(self, field_type: FieldType, imports: Set[str]) -> None:
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.DATE:
                imports.add("java.time.LocalDate")
            elif field_type.kind == PrimitiveKind.TIMESTAMP:
                imports.add("java.time.Instant")
            elif field_type.kind == PrimitiveKind.DURATION:
                imports.add("java.time.Duration")
            elif field_type.kind == PrimitiveKind.DECIMAL:
                imports.add("java.math.BigDecimal")
            elif field_type.kind == PrimitiveKind.FLOAT16:
                imports.add("org.apache.fory.`type`.Float16")
            elif field_type.kind == PrimitiveKind.BFLOAT16:
                imports.add("org.apache.fory.`type`.BFloat16")
            self.collect_integer_imports(field_type, imports)
            return
        if isinstance(field_type, ListType):
            self.collect_type_imports(field_type.element_type, imports)
            return
        if isinstance(field_type, ArrayType):
            self.collect_type_imports(field_type.element_type, imports)
            if isinstance(field_type.element_type, PrimitiveType):
                self.collect_array_element_imports(field_type.element_type, imports)
            return
        if isinstance(field_type, MapType):
            self.collect_type_imports(field_type.key_type, imports)
            self.collect_type_imports(field_type.value_type, imports)

    def collect_integer_imports(self, field_type: FieldType, imports: Set[str]) -> None:
        if not isinstance(field_type, PrimitiveType):
            return
        kind = field_type.kind
        if kind == PrimitiveKind.INT32:
            if field_type.encoding_modifier in ("fixed", "varint"):
                imports.add("org.apache.fory.annotation.Int32Type")
                imports.add("org.apache.fory.config.Int32Encoding")
        elif kind == PrimitiveKind.INT64:
            if field_type.encoding_modifier in ("fixed", "varint", "tagged"):
                imports.add("org.apache.fory.annotation.Int64Type")
                imports.add("org.apache.fory.config.Int64Encoding")
        elif kind == PrimitiveKind.UINT8:
            imports.add("org.apache.fory.annotation.UInt8Type")
        elif kind == PrimitiveKind.UINT16:
            imports.add("org.apache.fory.annotation.UInt16Type")
        elif kind == PrimitiveKind.UINT32:
            imports.add("org.apache.fory.annotation.UInt32Type")
            if field_type.encoding_modifier in ("fixed", "varint"):
                imports.add("org.apache.fory.config.Int32Encoding")
        elif kind == PrimitiveKind.UINT64:
            imports.add("org.apache.fory.annotation.UInt64Type")
            if field_type.encoding_modifier in ("fixed", "varint", "tagged"):
                imports.add("org.apache.fory.config.Int64Encoding")

    def collect_array_element_imports(
        self, field_type: PrimitiveType, imports: Set[str]
    ) -> None:
        kind = field_type.kind
        if kind == PrimitiveKind.INT8:
            imports.add("org.apache.fory.annotation.Int8Type")
        elif kind == PrimitiveKind.UINT8:
            imports.add("org.apache.fory.annotation.UInt8Type")
        elif kind == PrimitiveKind.UINT16:
            imports.add("org.apache.fory.annotation.UInt16Type")
        elif kind == PrimitiveKind.UINT32:
            imports.add("org.apache.fory.annotation.UInt32Type")
        elif kind == PrimitiveKind.UINT64:
            imports.add("org.apache.fory.annotation.UInt64Type")
        elif kind == PrimitiveKind.FLOAT16:
            imports.add("org.apache.fory.annotation.Float16Type")
        elif kind == PrimitiveKind.BFLOAT16:
            imports.add("org.apache.fory.annotation.BFloat16Type")

    def field_type_has_ref(self, field_type: FieldType) -> bool:
        if isinstance(field_type, ListType):
            return field_type.element_ref or self.field_type_has_ref(
                field_type.element_type
            )
        if isinstance(field_type, MapType):
            return field_type.value_ref or self.field_type_has_ref(
                field_type.value_type
            )
        return False

    def is_ref_target_type(
        self, field_type: FieldType, parent_stack: Optional[List[Message]] = None
    ) -> bool:
        if not isinstance(field_type, NamedType):
            return False
        if "." in field_type.name or not parent_stack:
            return isinstance(self.schema.get_type(field_type.name), (Message, Union))
        for index in range(len(parent_stack) - 1, -1, -1):
            resolved = parent_stack[index].get_nested_type(field_type.name)
            if resolved is not None:
                return isinstance(resolved, (Message, Union))
        return isinstance(self.schema.get_type(field_type.name), (Message, Union))

    def generate_module_file(self) -> GeneratedFile:
        imports = {
            "org.apache.fory.{Fory, ThreadSafeFory}",
            "org.apache.fory.scala.{ForyScala, ForySerializer}",
            "org.apache.fory.serializer.scala.ScalaSerializers",
        }
        lines = self.source_header(imports)
        class_name = self.get_module_name()
        package_name = self.get_scala_package()
        if package_name:
            access = f"private[{package_name.split('.')[-1]}] "
        else:
            access = ""
        lines.append(f"object {class_name} extends org.apache.fory.ForyModule {{")
        lines.append("    private lazy val fory: ThreadSafeFory =")
        lines.append("        ForyScala.builder()")
        lines.append("            .withXlang(true)")
        lines.append("            .withCompatible(true)")
        lines.append("            .withRefTracking(true)")
        lines.append("            .withModule(this)")
        lines.append("            .buildThreadSafeFory()")
        lines.append("")
        lines.append(f"    {access}def getFory: ThreadSafeFory = fory")
        lines.append("")
        lines.append("    override def install(fory: Fory): Unit = {")
        imported_modules = self._imported_regs()
        for package, module in imported_modules:
            if package:
                lines.append(f"        fory.register({package}.{module})")
            else:
                lines.append(f"        fory.register({module})")
        registrations = self.registration_order()
        for type_def, owner_path in registrations:
            if isinstance(type_def, Message):
                self.generate_type_registration(
                    lines, type_def, owner_path, type_only=True
                )
        for type_def, owner_path in registrations:
            if isinstance(type_def, Message):
                self.serializer_registration(lines, type_def, owner_path)
            else:
                self.generate_type_registration(lines, type_def, owner_path)
        lines.append("    }")
        lines.append("}")
        return self.source_file(class_name, lines)

    def registration_order(self) -> List[Tuple[object, Optional[str]]]:
        entries: List[Tuple[object, Optional[str], List[Message]]] = []

        def message_path(messages: List[Message]) -> str:
            return ".".join(message.name for message in messages)

        def add_message(message: Message, parent_stack: List[Message]) -> None:
            owner_path = ".".join(owner.name for owner in parent_stack) or None
            entries.append((message, owner_path, parent_stack))
            current_stack = [*parent_stack, message]
            for enum in message.nested_enums:
                entries.append((enum, message_path(current_stack), current_stack))
            for union in message.nested_unions:
                entries.append((union, message_path(current_stack), current_stack))
            for nested in message.nested_messages:
                add_message(nested, current_stack)

        for enum in self.schema.enums:
            entries.append((enum, None, []))
        for union in self.schema.unions:
            entries.append((union, None, []))
        for message in self.schema.messages:
            add_message(message, [])

        local_entries: Dict[int, Tuple[object, Optional[str], List[Message]]] = {
            id(type_def): (type_def, owner_path, parent_stack)
            for type_def, owner_path, parent_stack in entries
            if not self.is_imported_type(type_def)
        }
        ordered: List[Tuple[object, Optional[str]]] = []
        visiting: Set[int] = set()
        visited: Set[int] = set()

        def visit(type_def: object) -> None:
            key = id(type_def)
            if key in visited or key not in local_entries:
                return
            if key in visiting:
                return
            visiting.add(key)
            _, _, parent_stack = local_entries[key]
            for dependency in self.registration_dependencies(type_def, parent_stack):
                visit(dependency)
            visiting.remove(key)
            visited.add(key)
            current, owner_path, _ = local_entries[key]
            ordered.append((current, owner_path))

        for type_def, _, _ in entries:
            visit(type_def)
        return ordered

    def registration_dependencies(
        self, type_def: object, parent_stack: List[Message]
    ) -> List[object]:
        dependencies: List[object] = []
        if isinstance(type_def, Message):
            lookup_stack = [*parent_stack, type_def]
            for field in type_def.fields:
                self.collect_reg_deps(field.field_type, lookup_stack, dependencies)
        elif isinstance(type_def, Union):
            for field in type_def.fields:
                self.collect_reg_deps(field.field_type, parent_stack, dependencies)
        return [dependency for dependency in dependencies if dependency is not type_def]

    def collect_reg_deps(
        self,
        field_type: FieldType,
        parent_stack: List[Message],
        dependencies: List[object],
    ) -> None:
        if isinstance(field_type, NamedType):
            dependency = self.resolve_named_type(field_type.name, parent_stack)
            if dependency is not None and dependency not in dependencies:
                dependencies.append(dependency)
            return
        if isinstance(field_type, ListType):
            self.collect_reg_deps(field_type.element_type, parent_stack, dependencies)
            return
        if isinstance(field_type, ArrayType):
            self.collect_reg_deps(field_type.element_type, parent_stack, dependencies)
            return
        if isinstance(field_type, MapType):
            self.collect_reg_deps(field_type.key_type, parent_stack, dependencies)
            self.collect_reg_deps(field_type.value_type, parent_stack, dependencies)

    def resolve_named_type(
        self, name: str, parent_stack: List[Message]
    ) -> Optional[object]:
        if "." in name:
            return self.schema.get_type(name)
        for index in range(len(parent_stack) - 1, -1, -1):
            nested = parent_stack[index].get_nested_type(name)
            if nested is not None:
                return nested
        return self.schema.get_type(name)

    def generate_type_registration(
        self,
        lines: List[str],
        type_def,
        owner_path: Optional[str] = None,
        type_only: bool = False,
    ) -> None:
        class_ref = f"{owner_path}.{type_def.name}" if owner_path else type_def.name
        namespace = self.schema.package or "default"
        type_name = type_def.name
        if owner_path:
            namespace = f"{namespace}.{owner_path}"
        if isinstance(type_def, Enum):
            if self.should_register_by_id(type_def):
                lines.append(
                    f"        ScalaSerializers.registerEnum(fory, classOf[{class_ref}], {type_def.type_id}L)"
                )
            else:
                lines.append(
                    f'        ScalaSerializers.registerEnum(fory, classOf[{class_ref}], "{namespace}", "{type_name}")'
                )
            return
        method = "registerType" if type_only else "register"
        if self.should_register_by_id(type_def):
            lines.append(
                f"        ForySerializer.{method}(fory, classOf[{class_ref}], {type_def.type_id}L)"
            )
        else:
            lines.append(
                f'        ForySerializer.{method}(fory, classOf[{class_ref}], "{namespace}", "{type_name}")'
            )

    def serializer_registration(
        self, lines: List[str], type_def, owner_path: Optional[str] = None
    ) -> None:
        class_ref = f"{owner_path}.{type_def.name}" if owner_path else type_def.name
        lines.append(
            f"        ForySerializer.registerSerializer(fory, classOf[{class_ref}])"
        )

    def safe_identifier(self, name: str) -> str:
        return f"`{name}`" if name in self.RESERVED else name
