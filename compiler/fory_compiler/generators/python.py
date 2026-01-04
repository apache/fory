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

from typing import List, Set

from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.parser.ast import (
    Schema,
    Message,
    Enum,
    Field,
    FieldType,
    PrimitiveType,
    PrimitiveKind,
    NamedType,
    ListType,
    MapType,
)


class PythonGenerator(BaseGenerator):
    """Generates Python dataclasses with pyfory type hints."""

    language_name = "python"
    file_extension = ".py"

    # Mapping from FDL primitive types to Python types
    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "bool",
        PrimitiveKind.INT8: "pyfory.Int8Type",
        PrimitiveKind.INT16: "pyfory.Int16Type",
        PrimitiveKind.INT32: "pyfory.Int32Type",
        PrimitiveKind.INT64: "int",
        PrimitiveKind.FLOAT32: "pyfory.Float32Type",
        PrimitiveKind.FLOAT64: "float",
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
        PrimitiveKind.INT64: "np.int64",
        PrimitiveKind.FLOAT32: "np.float32",
        PrimitiveKind.FLOAT64: "np.float64",
    }

    # Default values for primitive types
    DEFAULT_VALUES = {
        PrimitiveKind.BOOL: "False",
        PrimitiveKind.INT8: "0",
        PrimitiveKind.INT16: "0",
        PrimitiveKind.INT32: "0",
        PrimitiveKind.INT64: "0",
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
        imports.add("from dataclasses import dataclass")
        imports.add("from enum import IntEnum")
        imports.add("from typing import Dict, List, Optional")
        imports.add("import pyfory")

        for message in self.schema.messages:
            self.collect_message_imports(message, imports)

        # License header
        lines.append(self.get_license_header("#"))
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
            self.collect_imports(field.field_type, imports)
        for nested_msg in message.nested_messages:
            self.collect_message_imports(nested_msg, imports)

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

    def generate_message(self, message: Message, indent: int = 0) -> List[str]:
        """Generate a Python dataclass with nested types."""
        lines = []
        ind = "    " * indent

        lines.append(f"{ind}@dataclass")
        lines.append(f"{ind}class {message.name}:")

        # Generate nested enums first (they need to be defined before fields reference them)
        for nested_enum in message.nested_enums:
            for line in self.generate_enum(nested_enum, indent=indent + 1):
                lines.append(line)
            lines.append("")

        # Generate nested messages
        for nested_msg in message.nested_messages:
            for line in self.generate_message(nested_msg, indent=indent + 1):
                lines.append(line)
            lines.append("")

        # Generate fields
        if not message.fields and not message.nested_enums and not message.nested_messages:
            lines.append(f"{ind}    pass")
            return lines

        for field in message.fields:
            field_lines = self.generate_field(field)
            for line in field_lines:
                lines.append(f"{ind}    {line}")

        # If there are nested types but no fields, add pass to avoid empty class body issues
        if not message.fields and (message.nested_enums or message.nested_messages):
            lines.append(f"{ind}    pass")

        return lines

    def generate_field(self, field: Field) -> List[str]:
        """Generate a dataclass field."""
        lines = []

        python_type = self.generate_type(field.field_type, field.optional)
        field_name = self.to_snake_case(field.name)
        default = self.get_default_value(field.field_type, field.optional)

        lines.append(f"{field_name}: {python_type} = {default}")

        return lines

    def generate_type(self, field_type: FieldType, nullable: bool = False) -> str:
        """Generate Python type hint."""
        if isinstance(field_type, PrimitiveType):
            base_type = self.PRIMITIVE_MAP[field_type.kind]
            if nullable:
                return f"Optional[{base_type}]"
            return base_type

        elif isinstance(field_type, NamedType):
            if nullable:
                return f"Optional[{field_type.name}]"
            return field_type.name

        elif isinstance(field_type, ListType):
            # Use numpy array for numeric primitive types
            if isinstance(field_type.element_type, PrimitiveType):
                if field_type.element_type.kind in self.NUMPY_DTYPE_MAP:
                    return "np.ndarray"
            element_type = self.generate_type(field_type.element_type, False)
            return f"List[{element_type}]"

        elif isinstance(field_type, MapType):
            key_type = self.generate_type(field_type.key_type, False)
            value_type = self.generate_type(field_type.value_type, False)
            return f"Dict[{key_type}, {value_type}]"

        return "object"

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

    def collect_imports(self, field_type: FieldType, imports: Set[str]):
        """Collect required imports for a field type."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind in (PrimitiveKind.DATE, PrimitiveKind.TIMESTAMP):
                imports.add("import datetime")

        elif isinstance(field_type, ListType):
            # Add numpy import for numeric primitive arrays
            if isinstance(field_type.element_type, PrimitiveType):
                if field_type.element_type.kind in self.NUMPY_DTYPE_MAP:
                    imports.add("import numpy as np")
                    return
            self.collect_imports(field_type.element_type, imports)

        elif isinstance(field_type, MapType):
            self.collect_imports(field_type.key_type, imports)
            self.collect_imports(field_type.value_type, imports)

    def generate_registration(self) -> List[str]:
        """Generate the Fory registration function."""
        lines = []

        func_name = f"register_{self.get_module_name()}_types"
        lines.append(f"def {func_name}(fory: pyfory.Fory):")

        if not self.schema.enums and not self.schema.messages:
            lines.append("    pass")
            return lines

        # Register enums (top-level)
        for enum in self.schema.enums:
            self.generate_enum_registration(lines, enum, "")

        # Register messages (including nested types)
        for message in self.schema.messages:
            self.generate_message_registration(lines, message, "")

        return lines

    def generate_enum_registration(self, lines: List[str], enum: Enum, parent_path: str):
        """Generate registration code for an enum."""
        # In Python, nested class references use Outer.Inner syntax
        class_ref = f"{parent_path}.{enum.name}" if parent_path else enum.name
        type_name = class_ref.replace(".", "_") if parent_path else enum.name

        if enum.type_id is not None:
            lines.append(f"    fory.register_type({class_ref}, type_id={enum.type_id})")
        else:
            ns = self.package or "default"
            lines.append(f'    fory.register_type({class_ref}, namespace="{ns}", typename="{type_name}")')

    def generate_message_registration(self, lines: List[str], message: Message, parent_path: str):
        """Generate registration code for a message and its nested types."""
        # In Python, nested class references use Outer.Inner syntax
        class_ref = f"{parent_path}.{message.name}" if parent_path else message.name
        type_name = class_ref.replace(".", "_") if parent_path else message.name

        if message.type_id is not None:
            lines.append(f"    fory.register_type({class_ref}, type_id={message.type_id})")
        else:
            ns = self.package or "default"
            lines.append(f'    fory.register_type({class_ref}, namespace="{ns}", typename="{type_name}")')

        # Register nested enums
        for nested_enum in message.nested_enums:
            self.generate_enum_registration(lines, nested_enum, class_ref)

        # Register nested messages
        for nested_msg in message.nested_messages:
            self.generate_message_registration(lines, nested_msg, class_ref)
