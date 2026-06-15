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

"""Kotlin schema IDL generator."""

from __future__ import annotations

from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.generators.services.kotlin import KotlinServiceGeneratorMixin
from fory_compiler.ir.ast import (
    ArrayType,
    Enum,
    Field,
    FieldType,
    ListType,
    MapType,
    Message,
    NamedType,
    PrimitiveType,
    Schema,
    Union,
)
from fory_compiler.ir.construction import analyze_shapes
from fory_compiler.ir.types import PrimitiveKind


def _to_pascal_case(name: str) -> str:
    if not name:
        return name
    if "_" in name:
        return "".join(word.capitalize() for word in name.lower().split("_"))
    if name.isupper():
        return name.capitalize()
    return name[0].upper() + name[1:]


def kotlin_package_for_schema(schema: Schema) -> Optional[str]:
    """Return the Kotlin source package for a schema."""
    return schema.get_option("kotlin_package") or schema.package


def kotlin_module_prefix(schema: Schema) -> str:
    """Return the Kotlin schema module prefix for a schema."""
    if schema.source_file and not schema.source_file.startswith("<"):
        cleaned = "".join(
            char if char.isascii() and (char.isalnum() or char == "_") else "_"
            for char in Path(schema.source_file).stem
        )
        prefix = _to_pascal_case(cleaned) if cleaned else "Schema"
        if not prefix or not (prefix[0].isalpha() or prefix[0] == "_"):
            prefix = f"Schema{prefix}"
        return prefix
    if schema.package:
        return _to_pascal_case(schema.package.split(".")[-1])
    package = kotlin_package_for_schema(schema)
    if package:
        return _to_pascal_case(package.split(".")[-1])
    return ""


def kotlin_module_name(schema: Schema) -> str:
    """Return the Kotlin schema module class name."""
    prefix = kotlin_module_prefix(schema)
    return f"{prefix}ForyModule" if prefix else "ForyModule"


def kotlin_module_file_path(schema: Schema) -> str:
    """Return the generated Kotlin schema module path."""
    package = kotlin_package_for_schema(schema)
    package_path = package.replace(".", "/") if package else ""
    name = kotlin_module_name(schema)
    return f"{package_path}/{name}.kt" if package_path else f"{name}.kt"


def _kotlin_source_path(schema: Schema, type_name: str) -> str:
    package = kotlin_package_for_schema(schema)
    package_path = package.replace(".", "/") if package else ""
    filename = f"{type_name}.kt"
    return f"{package_path}/{filename}" if package_path else filename


def _kotlin_type_name(type_def: object, owners: List[Message]) -> str:
    if not owners:
        return type_def.name
    return "".join(owner.name for owner in owners) + type_def.name


def _is_schema_local(type_def: object, schema: Schema) -> bool:
    if not schema.source_file or schema.source_file.startswith("<"):
        return True
    location = getattr(type_def, "location", None)
    file_path = getattr(location, "file", None) if location else None
    if not file_path:
        return True
    try:
        return Path(file_path).resolve() == Path(schema.source_file).resolve()
    except Exception:
        return file_path == schema.source_file


def kotlin_output_paths(
    schema: Schema,
    local_only: bool = False,
    include_services: bool = False,
) -> List[Tuple[str, str]]:
    """Return generated Kotlin output paths and their owning schema elements."""
    outputs: List[Tuple[str, str]] = []

    def add_type(type_def: object, owners: List[Message], kind: str) -> None:
        if local_only and not _is_schema_local(type_def, schema):
            return
        name = _kotlin_type_name(type_def, owners)
        outputs.append((_kotlin_source_path(schema, name), kind))

    def add_message(message: Message, owners: List[Message]) -> None:
        add_type(message, owners, f"message {message.name}")
        current_owners = [*owners, message]
        for enum in message.nested_enums:
            add_type(enum, current_owners, f"enum {enum.name}")
        for union in message.nested_unions:
            add_type(union, current_owners, f"union {union.name}")
        for nested in message.nested_messages:
            add_message(nested, current_owners)

    for enum in schema.enums:
        add_type(enum, [], f"enum {enum.name}")
    for union in schema.unions:
        add_type(union, [], f"union {union.name}")
    for message in schema.messages:
        add_message(message, [])
    outputs.append((kotlin_module_file_path(schema), "schema module"))
    if include_services:
        for service in schema.services:
            if local_only and not _is_schema_local(service, schema):
                continue
            outputs.append(
                (
                    _kotlin_source_path(schema, f"{service.name}GrpcKt"),
                    f"service {service.name}",
                )
            )
    return outputs


class KotlinGenerator(KotlinServiceGeneratorMixin, BaseGenerator):
    """Generates Kotlin models for Fory Schema IDL."""

    language_name = "kotlin"
    file_extension = ".kt"

    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "Boolean",
        PrimitiveKind.INT8: "Byte",
        PrimitiveKind.INT16: "Short",
        PrimitiveKind.INT32: "Int",
        PrimitiveKind.INT64: "Long",
        PrimitiveKind.UINT8: "UByte",
        PrimitiveKind.UINT16: "UShort",
        PrimitiveKind.UINT32: "UInt",
        PrimitiveKind.UINT64: "ULong",
        PrimitiveKind.FLOAT16: "Float16",
        PrimitiveKind.BFLOAT16: "BFloat16",
        PrimitiveKind.FLOAT32: "Float",
        PrimitiveKind.FLOAT64: "Double",
        PrimitiveKind.STRING: "String",
        PrimitiveKind.BYTES: "ByteArray",
        PrimitiveKind.DATE: "LocalDate",
        PrimitiveKind.TIMESTAMP: "Instant",
        PrimitiveKind.DURATION: "Duration",
        PrimitiveKind.DECIMAL: "BigDecimal",
        PrimitiveKind.ANY: "Any?",
    }

    ARRAY_MAP = {
        PrimitiveKind.BOOL: "BooleanArray",
        PrimitiveKind.INT8: "ByteArray",
        PrimitiveKind.INT16: "ShortArray",
        PrimitiveKind.INT32: "IntArray",
        PrimitiveKind.INT64: "LongArray",
        PrimitiveKind.UINT8: "UByteArray",
        PrimitiveKind.UINT16: "UShortArray",
        PrimitiveKind.UINT32: "UIntArray",
        PrimitiveKind.UINT64: "ULongArray",
        PrimitiveKind.FLOAT16: "Float16Array",
        PrimitiveKind.BFLOAT16: "BFloat16Array",
        PrimitiveKind.FLOAT32: "FloatArray",
        PrimitiveKind.FLOAT64: "DoubleArray",
    }

    RESERVED = {
        "as",
        "break",
        "class",
        "continue",
        "do",
        "else",
        "false",
        "for",
        "fun",
        "if",
        "in",
        "interface",
        "is",
        "null",
        "object",
        "package",
        "return",
        "super",
        "this",
        "throw",
        "true",
        "try",
        "typealias",
        "typeof",
        "val",
        "var",
        "when",
        "while",
    }

    def __init__(self, schema: Schema, options):
        super().__init__(schema, options)
        self._schema_cache: Dict[Path, Schema] = {}
        self._validate_import_packages()
        self._validate_output_paths()
        self._construction_shapes = analyze_shapes(schema)

    def generate(self) -> List[GeneratedFile]:
        files: List[GeneratedFile] = []
        for enum in self.schema.enums:
            if not self.is_imported_type(enum):
                files.append(self.generate_enum_file(enum, []))
        for union in self.schema.unions:
            if not self.is_imported_type(union):
                files.append(self.generate_union_file(union, []))
        for message in self.schema.messages:
            if not self.is_imported_type(message):
                files.extend(self.generate_message_files(message, []))
        files.append(self.generate_module_file())
        return files

    @property
    def kotlin_package(self) -> Optional[str]:
        return kotlin_package_for_schema(self.schema)

    def get_kotlin_package_path(self) -> str:
        package = self.kotlin_package
        return package.replace(".", "/") if package else ""

    def get_module_name(self) -> str:
        return self._module_name(self.schema)

    def generate_enum_file(
        self, enum: Enum, parent_stack: List[Message]
    ) -> GeneratedFile:
        imports = {"org.apache.fory.annotation.ForyEnumId"}
        lines = self.source_header(imports, needs_unsigned_opt_in=False)
        comment = self.format_type_id_comment(enum, "//")
        if comment:
            lines.append(comment)
        lines.extend(self.generate_enum(enum, parent_stack))
        return self.source_file(self.type_name(enum, parent_stack), lines)

    def generate_union_file(
        self, union: Union, parent_stack: List[Message]
    ) -> GeneratedFile:
        imports = {
            "org.apache.fory.annotation.ForyCase",
            "org.apache.fory.annotation.ForyUnion",
            "org.apache.fory.annotation.ForyUnknownCase",
            "org.apache.fory.type.union.UnknownCase",
        }
        self.collect_union_imports(union, imports)
        lines = self.source_header(imports, self.uses_unsigned_union(union))
        comment = self.format_type_id_comment(union, "//")
        if comment:
            lines.append(comment)
        lines.extend(self.generate_union(union, parent_stack))
        return self.source_file(self.type_name(union, parent_stack), lines)

    def generate_message_files(
        self, message: Message, parent_stack: List[Message]
    ) -> List[GeneratedFile]:
        files: List[GeneratedFile] = []
        imports = {
            "org.apache.fory.annotation.ForyField",
            "org.apache.fory.annotation.ForyStruct",
        }
        self.collect_message_imports(message, imports)
        current_stack = [*parent_stack, message]
        lines = self.source_header(imports, self.uses_unsigned_message(message))
        comment = self.format_type_id_comment(message, "//")
        if comment:
            lines.append(comment)
        lines.extend(self.generate_message(message, parent_stack))
        files.append(self.source_file(self.type_name(message, parent_stack), lines))
        for enum in message.nested_enums:
            files.append(self.generate_enum_file(enum, current_stack))
        for union in message.nested_unions:
            files.append(self.generate_union_file(union, current_stack))
        for nested in message.nested_messages:
            files.extend(self.generate_message_files(nested, current_stack))
        return files

    def source_header(
        self, imports: Set[str], needs_unsigned_opt_in: bool
    ) -> List[str]:
        lines = [self.get_license_header(), ""]
        if needs_unsigned_opt_in:
            lines.append("@file:OptIn(ExperimentalUnsignedTypes::class)")
            lines.append("")
        package = self.kotlin_package
        if package:
            lines.append(f"package {package}")
            lines.append("")
        for item in sorted(imports):
            lines.append(f"import {item}")
        if imports:
            lines.append("")
        return lines

    def source_file(self, type_name: str, lines: List[str]) -> GeneratedFile:
        path = self.get_kotlin_package_path()
        filename = f"{type_name}.kt"
        file_path = f"{path}/{filename}" if path else filename
        return GeneratedFile(path=file_path, content="\n".join(lines) + "\n")

    def generate_enum(self, enum: Enum, parent_stack: List[Message]) -> List[str]:
        lines = [f"public enum class {self.type_name(enum, parent_stack)} {{"]
        for index, value in enumerate(enum.values):
            suffix = "," if index < len(enum.values) - 1 else ";"
            value_name = self.safe_identifier(
                self.to_pascal_case(self.strip_enum_prefix(enum.name, value.name))
            )
            lines.append(f"    @ForyEnumId({value.value})")
            lines.append(f"    {value_name}{suffix}")
        lines.append("}")
        return lines

    def generate_union(self, union: Union, parent_stack: List[Message]) -> List[str]:
        union_name = self.type_name(union, parent_stack)
        lines = ["@ForyUnion", f"public sealed class {union_name} {{"]
        lines.append("    @ForyUnknownCase")
        lines.append("    public data class Unknown(")
        lines.append("        public val value: UnknownCase,")
        lines.append(f"    ) : {union_name}()")
        for field in union.fields:
            lines.append("")
            lines.append(f"    @ForyCase(id = {field.number})")
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
            field_type = self.qualify_union_payload_type(
                field.field_type, field_type, case_name, parent_stack
            )
            lines.append(
                f"    public data class {case_name}(public val value: {field_type}) : {union_name}()"
            )
        lines.append("}")
        return lines

    def generate_message(
        self, message: Message, parent_stack: List[Message]
    ) -> List[str]:
        shape = self._construction_shapes.get(
            self.construction_key(parent_stack, message)
        )
        if shape is not None and shape.cycle_owned:
            return self.generate_normal_class(message, parent_stack)
        return self.generate_data_class(message, parent_stack)

    def generate_data_class(
        self, message: Message, parent_stack: List[Message]
    ) -> List[str]:
        lines = [
            "@ForyStruct",
            f"public data class {self.type_name(message, parent_stack)}(",
        ]
        current_stack = [*parent_stack, message]
        for field in message.fields:
            lines.extend(self.generate_constructor_property(field, current_stack))
        lines.append(") {")
        lines.extend(self.generate_to_bytes(1))
        lines.extend(self.generate_from_bytes(message, parent_stack, 1))
        lines.append("}")
        return lines

    def generate_constructor_property(
        self, field: Field, parent_stack: List[Message]
    ) -> List[str]:
        lines: List[str] = []
        field_type = self.generate_type(
            field.field_type,
            nullable=field.optional,
            element_optional=field.element_optional,
            element_ref=field.element_ref,
            top_level_ref=False,
            parent_stack=parent_stack,
        )
        if field.ref and self.is_ref_target_type(field.field_type, parent_stack):
            lines.append("    @Ref")
        lines.append(f"    @field:ForyField(id = {field.number})")
        lines.append(
            f"    public val {self.safe_identifier(self.to_camel_case(field.name))}: {field_type},"
        )
        lines.append("")
        return lines

    def generate_normal_class(
        self, message: Message, parent_stack: List[Message]
    ) -> List[str]:
        current_stack = [*parent_stack, message]
        lines = [
            "@ForyStruct",
            f"public class {self.type_name(message, parent_stack)}() {{",
        ]
        for field in message.fields:
            field_type = self.generate_type(
                field.field_type,
                nullable=field.optional,
                element_optional=field.element_optional,
                element_ref=field.element_ref,
                top_level_ref=False,
                parent_stack=current_stack,
            )
            if field.ref and self.is_ref_target_type(field.field_type, current_stack):
                lines.append("    @Ref")
            lines.append(f"    @ForyField(id = {field.number})")
            field_name = self.safe_identifier(self.to_camel_case(field.name))
            default_value = self.default_value(field)
            if default_value is None:
                lines.append(f"    public lateinit var {field_name}: {field_type}")
            else:
                lines.append(
                    f"    public var {field_name}: {field_type} = {default_value}"
                )
            lines.append("")
        lines.extend(self.generate_to_bytes(1))
        lines.extend(self.generate_from_bytes(message, parent_stack, 1))
        lines.append("}")
        return lines

    def generate_to_bytes(self, indent: int) -> List[str]:
        ind = "    " * indent
        module = self.get_module_name()
        return [
            f"{ind}public fun toBytes(): ByteArray = {module}.getFory().serialize(this)",
            "",
        ]

    def generate_from_bytes(
        self, message: Message, parent_stack: List[Message], indent: int
    ) -> List[str]:
        ind = "    " * indent
        module = self.get_module_name()
        name = self.type_name(message, parent_stack)
        return [
            f"{ind}public companion object {{",
            f"{ind}    public fun fromBytes(bytes: ByteArray): {name} =",
            f"{ind}        {module}.getFory().deserialize(bytes, {name}::class.java)",
            f"{ind}}}",
        ]

    def generate_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        element_optional: bool = False,
        element_ref: bool = False,
        top_level_ref: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        if (
            isinstance(field_type, PrimitiveType)
            and field_type.kind == PrimitiveKind.ANY
        ):
            return "Any?"
        base = self._generate_non_optional_type(
            field_type,
            element_optional,
            element_ref,
            parent_stack,
        )
        if top_level_ref and self.is_ref_target_type(field_type, parent_stack):
            base = f"@Ref {base}"
        if nullable and not base.endswith("?"):
            return f"{base}?"
        return base

    def _generate_non_optional_type(
        self,
        field_type: FieldType,
        element_optional: bool = False,
        element_ref: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        if isinstance(field_type, PrimitiveType):
            kotlin_type = self.PRIMITIVE_MAP[field_type.kind]
            return self.apply_primitive_annotation(kotlin_type, field_type)
        if isinstance(field_type, NamedType):
            return self.resolve_kotlin_type_name(field_type.name, parent_stack)
        if isinstance(field_type, ListType):
            element = self.generate_type(
                field_type.element_type,
                nullable=element_optional or field_type.element_optional,
                element_optional=False,
                element_ref=False,
                top_level_ref=element_ref or field_type.element_ref,
                parent_stack=parent_stack,
            )
            return f"List<{element}>"
        if isinstance(field_type, ArrayType):
            return self.generate_array_type(field_type.element_type)
        if isinstance(field_type, MapType):
            key = self.generate_type(field_type.key_type, parent_stack=parent_stack)
            value = self.generate_type(
                field_type.value_type,
                nullable=field_type.value_optional,
                top_level_ref=field_type.value_ref,
                parent_stack=parent_stack,
            )
            return f"Map<{key}, {value}>"
        return "Any?"

    def apply_primitive_annotation(
        self, kotlin_type: str, field_type: PrimitiveType
    ) -> str:
        annotation = self.get_integer_annotation(field_type)
        if annotation is not None:
            return f"@{annotation} {kotlin_type}"
        return kotlin_type

    def get_integer_annotation(self, field_type: PrimitiveType) -> Optional[str]:
        kind = field_type.kind
        modifier = field_type.encoding_modifier
        if kind in (PrimitiveKind.INT32, PrimitiveKind.UINT32):
            if modifier == "fixed":
                return "Fixed"
            return None
        if kind in (PrimitiveKind.INT64, PrimitiveKind.UINT64):
            if modifier == "fixed":
                return "Fixed"
            if modifier == "tagged":
                return "Tagged"
            return None
        return None

    def generate_array_type(self, field_type: FieldType) -> str:
        generated = self.array_carrier_type(field_type)
        if (
            isinstance(field_type, PrimitiveType)
            and field_type.kind == PrimitiveKind.INT8
        ):
            return f"@ArrayType {generated}"
        return generated

    def array_carrier_type(self, field_type: FieldType) -> str:
        if not isinstance(field_type, PrimitiveType):
            return f"List<{self._generate_non_optional_type(field_type)}>"
        return self.ARRAY_MAP[field_type.kind]

    def generate_module_file(self) -> GeneratedFile:
        imports = {
            "org.apache.fory.Fory",
            "org.apache.fory.ForyModule",
            "org.apache.fory.ThreadSafeFory",
            "org.apache.fory.kotlin.ForyKotlin",
            "org.apache.fory.serializer.kotlin.KotlinSerializers",
        }
        lines = self.source_header(imports, needs_unsigned_opt_in=False)
        class_name = self.get_module_name()
        lines.append(f"public object {class_name} : ForyModule {{")
        lines.append("    private val fory: ThreadSafeFory by lazy {")
        lines.append("        ForyKotlin.builder()")
        lines.append("            .withXlang(true)")
        lines.append("            .withCompatible(true)")
        lines.append("            .withRefTracking(true)")
        lines.append("            .withModule(this)")
        lines.append("            .buildThreadSafeFory()")
        lines.append("    }")
        lines.append("")
        lines.append("    internal fun getFory(): ThreadSafeFory = fory")
        lines.append("")
        lines.append("    override fun install(fory: Fory) {")
        for package, registration in self._imported_regs():
            if package:
                lines.append(f"        fory.register({package}.{registration})")
            else:
                lines.append(f"        fory.register({registration})")
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

    def generate_type_registration(
        self,
        lines: List[str],
        type_def,
        owner_path: Optional[str] = None,
        type_only: bool = False,
    ) -> None:
        class_ref = self.type_name(type_def, self.owner_path_stack(owner_path))
        namespace = self.schema.package or "default"
        if owner_path:
            namespace = f"{namespace}.{owner_path}"
        method = "registerType" if type_only else "register"
        if isinstance(type_def, Enum):
            method = "registerEnum"
        if isinstance(type_def, Union):
            method = "registerUnion"
        if self.should_register_by_id(type_def):
            lines.append(
                f"        KotlinSerializers.{method}(fory, {class_ref}::class.java, {type_def.type_id}L)"
            )
        else:
            lines.append(
                f'        KotlinSerializers.{method}(fory, {class_ref}::class.java, "{namespace}", "{type_def.name}")'
            )

    def serializer_registration(
        self, lines: List[str], type_def, owner_path: Optional[str] = None
    ) -> None:
        class_ref = self.type_name(type_def, self.owner_path_stack(owner_path))
        lines.append(
            f"        KotlinSerializers.registerSerializer(fory, {class_ref}::class.java)"
        )

    def registration_order(self) -> List[Tuple[object, Optional[str]]]:
        entries: List[Tuple[object, Optional[str], List[Message]]] = []

        def message_path(messages: List[Message]) -> str:
            return ".".join(message.name for message in messages)

        def add_message(message: Message, parent_stack: List[Message]) -> None:
            owner_path = message_path(parent_stack) or None
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

    def resolve_kotlin_type_name(
        self, name: str, parent_stack: Optional[List[Message]]
    ) -> str:
        if "." in name:
            root_name = name.split(".", 1)[0]
            named_type = self.schema.get_type(root_name)
            if named_type is not None and self.is_imported_type(named_type):
                package = self._kotlin_package_for_type(named_type)
                if package and package != self.kotlin_package:
                    return f"{package}.{name.replace('.', '')}"
            return name.replace(".", "")
        if parent_stack:
            for index in range(len(parent_stack) - 1, -1, -1):
                owner = parent_stack[index]
                nested = owner.get_nested_type(name)
                if nested is not None:
                    return self.type_name(nested, parent_stack[: index + 1])
        named_type = self.schema.get_type(name)
        if named_type is not None and self.is_imported_type(named_type):
            package = self._kotlin_package_for_type(named_type)
            if package and package != self.kotlin_package:
                return f"{package}.{self.type_name(named_type, [])}"
        return name

    def qualify_union_payload_type(
        self,
        field_type: FieldType,
        rendered_type: str,
        case_name: str,
        parent_stack: Optional[List[Message]],
    ) -> str:
        if rendered_type != case_name:
            return rendered_type
        if isinstance(field_type, NamedType) and self.kotlin_package:
            return f"{self.kotlin_package}.{rendered_type}"
        if isinstance(field_type, ArrayType) and isinstance(
            field_type.element_type, PrimitiveType
        ):
            if field_type.element_type.kind == PrimitiveKind.FLOAT16:
                return "org.apache.fory.type.Float16Array"
            if field_type.element_type.kind == PrimitiveKind.BFLOAT16:
                return "org.apache.fory.type.BFloat16Array"
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
            and not self.kotlin_package
        ):
            return f"{case_name}Case"
        return case_name

    def type_name(self, type_def: object, parent_stack: List[Message]) -> str:
        if not parent_stack:
            return type_def.name
        return "".join(owner.name for owner in parent_stack) + type_def.name

    def owner_path_stack(self, owner_path: Optional[str]) -> List[Message]:
        if not owner_path:
            return []
        names = owner_path.split(".")
        stack: List[Message] = []
        current: Optional[Message] = None
        for name in names:
            next_type = (
                self.schema.get_type(name)
                if current is None
                else current.get_nested_type(name)
            )
            if not isinstance(next_type, Message):
                return []
            stack.append(next_type)
            current = next_type
        return stack

    def current_stack(
        self, parent_stack: Optional[List[Message]], message: Message
    ) -> List[Message]:
        return [*(parent_stack or []), message]

    def construction_key(
        self, parent_stack: Optional[List[Message]], message: Message
    ) -> str:
        return ".".join([*[owner.name for owner in parent_stack or []], message.name])

    def default_value(self, field: Field) -> Optional[str]:
        if field.optional:
            return "null"
        return self.default_value_for_type(field.field_type)

    def default_value_for_type(self, field_type: FieldType) -> Optional[str]:
        if isinstance(field_type, PrimitiveType):
            defaults = {
                PrimitiveKind.BOOL: "false",
                PrimitiveKind.INT8: "0",
                PrimitiveKind.INT16: "0",
                PrimitiveKind.INT32: "0",
                PrimitiveKind.INT64: "0L",
                PrimitiveKind.UINT8: "0u",
                PrimitiveKind.UINT16: "0u",
                PrimitiveKind.UINT32: "0u",
                PrimitiveKind.UINT64: "0uL",
                PrimitiveKind.FLOAT32: "0.0f",
                PrimitiveKind.FLOAT64: "0.0",
                PrimitiveKind.STRING: '""',
                PrimitiveKind.BYTES: "ByteArray(0)",
                PrimitiveKind.DURATION: "Duration.ZERO",
                PrimitiveKind.ANY: "null",
            }
            return defaults.get(field_type.kind)
        if isinstance(field_type, ListType):
            return "emptyList()"
        if isinstance(field_type, ArrayType):
            generated = self.array_carrier_type(field_type.element_type)
            if generated.endswith("Array"):
                return f"{generated}(0)"
            return "null"
        if isinstance(field_type, MapType):
            return "emptyMap()"
        return None

    def has_int8_array(self, field_type: FieldType) -> bool:
        if isinstance(field_type, ArrayType):
            return (
                isinstance(field_type.element_type, PrimitiveType)
                and field_type.element_type.kind == PrimitiveKind.INT8
            ) or self.has_int8_array(field_type.element_type)
        if isinstance(field_type, ListType):
            return self.has_int8_array(field_type.element_type)
        if isinstance(field_type, MapType):
            return self.has_int8_array(field_type.key_type) or self.has_int8_array(
                field_type.value_type
            )
        return False

    def collect_message_imports(self, message: Message, imports: Set[str]) -> None:
        for field in message.fields:
            self.collect_type_imports(field.field_type, imports)
            if field.ref or self.field_type_has_ref(field.field_type):
                imports.add("org.apache.fory.annotation.Ref")
            if self.has_int8_array(field.field_type):
                imports.add("org.apache.fory.annotation.ArrayType")

    def collect_union_imports(self, union: Union, imports: Set[str]) -> None:
        for field in union.fields:
            self.collect_type_imports(field.field_type, imports)
            if field.ref or self.field_type_has_ref(field.field_type):
                imports.add("org.apache.fory.annotation.Ref")
            if self.has_int8_array(field.field_type):
                imports.add("org.apache.fory.annotation.ArrayType")

    def collect_type_imports(self, field_type: FieldType, imports: Set[str]) -> None:
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.DATE:
                imports.add("java.time.LocalDate")
            elif field_type.kind == PrimitiveKind.TIMESTAMP:
                imports.add("java.time.Instant")
            elif field_type.kind == PrimitiveKind.DURATION:
                imports.add("kotlin.time.Duration")
            elif field_type.kind == PrimitiveKind.DECIMAL:
                imports.add("java.math.BigDecimal")
            elif field_type.kind == PrimitiveKind.FLOAT16:
                imports.add("org.apache.fory.type.Float16")
            elif field_type.kind == PrimitiveKind.BFLOAT16:
                imports.add("org.apache.fory.type.BFloat16")
            self.collect_integer_imports(field_type, imports)
            return
        if isinstance(field_type, ListType):
            self.collect_type_imports(field_type.element_type, imports)
            return
        if isinstance(field_type, ArrayType):
            if isinstance(field_type.element_type, PrimitiveType):
                if field_type.element_type.kind == PrimitiveKind.FLOAT16:
                    imports.add("org.apache.fory.type.Float16Array")
                elif field_type.element_type.kind == PrimitiveKind.BFLOAT16:
                    imports.add("org.apache.fory.type.BFloat16Array")
            else:
                self.collect_type_imports(field_type.element_type, imports)
            return
        if isinstance(field_type, MapType):
            self.collect_type_imports(field_type.key_type, imports)
            self.collect_type_imports(field_type.value_type, imports)

    def collect_integer_imports(
        self, field_type: PrimitiveType, imports: Set[str]
    ) -> None:
        if self.get_integer_annotation(field_type) == "Fixed":
            imports.add("org.apache.fory.kotlin.Fixed")
        elif self.get_integer_annotation(field_type) == "Tagged":
            imports.add("org.apache.fory.kotlin.Tagged")

    def uses_unsigned_message(self, message: Message) -> bool:
        return any(
            self.field_type_uses_unsigned(field.field_type) for field in message.fields
        )

    def uses_unsigned_union(self, union: Union) -> bool:
        return any(
            self.field_type_uses_unsigned(field.field_type) for field in union.fields
        )

    def field_type_uses_unsigned(self, field_type: FieldType) -> bool:
        if isinstance(field_type, PrimitiveType):
            return field_type.kind in (
                PrimitiveKind.UINT8,
                PrimitiveKind.UINT16,
                PrimitiveKind.UINT32,
                PrimitiveKind.UINT64,
            )
        if isinstance(field_type, ListType):
            return self.field_type_uses_unsigned(field_type.element_type)
        if isinstance(field_type, ArrayType):
            return self.field_type_uses_unsigned(field_type.element_type)
        if isinstance(field_type, MapType):
            return self.field_type_uses_unsigned(
                field_type.key_type
            ) or self.field_type_uses_unsigned(field_type.value_type)
        return False

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
        return isinstance(
            self.resolve_named_type(field_type.name, parent_stack or []),
            (Message, Union),
        )

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

    def _schema_kotlin_package(self, schema: Schema) -> Optional[str]:
        return kotlin_package_for_schema(schema)

    def _validate_import_packages(self) -> None:
        schemas = self._schema_graph()
        packages = {self._schema_kotlin_package(schema) for _, schema in schemas}
        if None not in packages or len(packages) <= 1:
            return
        details = ", ".join(
            f"{source} uses {self._schema_kotlin_package(schema) or '<default>'}"
            for source, schema in schemas
        )
        raise ValueError(
            "Kotlin imports cannot mix default-package schemas with named "
            f"Kotlin packages; {details}"
        )

    def _validate_output_paths(self) -> None:
        schemas = self._schema_graph()
        outputs: Dict[str, List[str]] = {}
        for source, schema in schemas:
            local_only = schema is self.schema
            for path, owner in kotlin_output_paths(
                schema,
                local_only=local_only,
                include_services=self.options.grpc,
            ):
                outputs.setdefault(path, []).append(f"{source} {owner}")
        collisions = {
            path: sources for path, sources in outputs.items() if len(sources) > 1
        }
        if not collisions:
            return
        details = ", ".join(
            f"{path}: {', '.join(sources)}"
            for path, sources in sorted(collisions.items())
        )
        raise ValueError(
            "Kotlin generated file path collision; rename schema files or schema "
            f"types, or use distinct Kotlin packages. Collisions: {details}"
        )

    def kotlin_generated_output_paths(
        self, include_services: bool = False
    ) -> List[Tuple[str, str]]:
        outputs: List[Tuple[str, str]] = []
        for source, schema in self._schema_graph():
            local_only = schema is self.schema
            for path, owner in kotlin_output_paths(
                schema,
                local_only=local_only,
                include_services=include_services,
            ):
                outputs.append((path, f"{source} {owner}"))
        return outputs

    def _kotlin_package_for_schema(self, schema: Schema) -> Optional[str]:
        return self._schema_kotlin_package(schema)

    def _module_name(self, schema: Schema) -> str:
        return kotlin_module_name(schema)

    def _kotlin_package_for_type(self, type_def: object) -> Optional[str]:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        schema = self._load_schema(file_path)
        if schema is None:
            return None
        return self._kotlin_package_for_schema(schema)

    def _imported_regs(self) -> List[tuple[str, str]]:
        registrations: set[tuple[str, str]] = set()
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(type_def):
                continue
            package = self._kotlin_package_for_type(type_def)
            schema = self._load_schema(
                getattr(getattr(type_def, "location", None), "file", None)
            )
            if schema is None:
                continue
            registrations.add((package or "", self._module_name(schema)))
        return sorted(registrations)

    def safe_identifier(self, name: str) -> str:
        return f"`{name}`" if name in self.RESERVED else name
