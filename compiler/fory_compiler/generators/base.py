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

"""Base class for code generators."""

from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

from fory_compiler.ir.ast import (
    Schema,
    FieldType,
    PrimitiveType,
    NamedType,
    ListType,
    MapType,
)


@dataclass
class GeneratedFile:
    """A generated source file."""

    path: str
    content: str


@dataclass
class GeneratorOptions:
    """Options for code generation."""

    output_dir: Path
    package_override: Optional[str] = None
    go_nested_type_style: Optional[str] = None
    grpc: bool = False


class BaseGenerator(ABC):
    """Base class for language-specific code generators."""

    # Override in subclasses
    language_name: str = "base"
    file_extension: str = ".txt"

    def __init__(self, schema: Schema, options: GeneratorOptions):
        self.schema = schema
        self.options = options
        self.indent_str = "    "  # 4 spaces by default

    @property
    def package(self) -> Optional[str]:
        """Get the package name."""
        return self.options.package_override or self.schema.package

    @abstractmethod
    def generate(self) -> List[GeneratedFile]:
        """Generate code and return a list of generated files."""
        pass

    def generate_services(self) -> List[GeneratedFile]:
        """Generate service-related code (e.g. gRPC stubs).

        Base implementation returns empty list. Subclasses should override
        if they support service generation.
        """
        return []

    @abstractmethod
    def generate_type(self, field_type: FieldType, nullable: bool = False) -> str:
        """Generate the type string for a field type."""
        pass

    def indent(self, text: str, level: int = 1) -> str:
        """Indent text by the given number of levels."""
        prefix = self.indent_str * level
        lines = text.split("\n")
        return "\n".join(prefix + line if line else line for line in lines)

    def to_pascal_case(self, name: str) -> str:
        """Convert name to PascalCase.

        Handles various input formats:
        - snake_case -> PascalCase (device_tier -> DeviceTier)
        - UPPER_SNAKE_CASE -> PascalCase (DEVICE_TIER -> DeviceTier)
        - camelCase -> PascalCase (deviceTier -> DeviceTier)
        - ALLCAPS -> Allcaps (UNKNOWN -> Unknown)
        """
        if not name:
            return name

        # Handle snake_case and UPPER_SNAKE_CASE
        if "_" in name:
            return "".join(word.capitalize() for word in name.lower().split("_"))

        # Handle all uppercase single word (e.g., UNKNOWN -> Unknown)
        if name.isupper():
            return name.capitalize()

        # Handle already PascalCase or camelCase
        return name[0].upper() + name[1:]

    def to_camel_case(self, name: str) -> str:
        """Convert name to camelCase."""
        pascal = self.to_pascal_case(name)
        if not pascal:
            return pascal
        return pascal[0].lower() + pascal[1:]

    def to_snake_case(self, name: str) -> str:
        """Convert name to snake_case.

        Handles acronyms properly:
        - DeviceTier -> device_tier
        - HTTPStatus -> http_status
        - XMLParser -> xml_parser
        - HTMLToText -> html_to_text
        """
        if not name:
            return name
        result = []
        for i, char in enumerate(name):
            if char.isupper():
                # Add underscore before uppercase if:
                # 1. Not at the start
                # 2. Previous char is lowercase, OR
                # 3. Next char exists and is lowercase (handles acronyms like HTTP->Status)
                if i > 0:
                    prev_lower = name[i - 1].islower()
                    next_lower = (i + 1 < len(name)) and name[i + 1].islower()
                    if prev_lower or next_lower:
                        result.append("_")
                result.append(char.lower())
            else:
                result.append(char)
        return "".join(result)

    def to_upper_snake_case(self, name: str) -> str:
        """Convert name to UPPER_SNAKE_CASE."""
        return self.to_snake_case(name).upper()

    def write_files(self, files: List[GeneratedFile]):
        """Write generated files to disk."""
        for file in files:
            path = self.options.output_dir / file.path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(file.content)

    def strip_enum_prefix(self, enum_name: str, value_name: str) -> str:
        """Strip the enum name prefix from an enum value name.

        For protobuf-style enums where values are prefixed with the enum name
        in UPPER_SNAKE_CASE, strip the prefix to get cleaner scoped enum values.

        Example:
            enum_name="DeviceTier", value_name="DEVICE_TIER_UNKNOWN" -> "UNKNOWN"
            enum_name="DeviceTier", value_name="DEVICE_TIER_TIER1" -> "TIER1"
            enum_name="DeviceTier", value_name="DEVICE_TIER_1" -> "DEVICE_TIER_1" (keeps original, "1" is invalid)

        The prefix is only stripped if the remainder is a valid identifier
        (starts with a letter).

        Args:
            enum_name: The enum type name (e.g., "DeviceTier")
            value_name: The enum value name (e.g., "DEVICE_TIER_UNKNOWN")

        Returns:
            The stripped value name, or original if stripping would yield an invalid name
        """
        # Convert enum name to UPPER_SNAKE_CASE prefix
        prefix = self.to_upper_snake_case(enum_name) + "_"

        # Check if value_name starts with the prefix
        if not value_name.startswith(prefix):
            return value_name

        # Get the remainder after stripping prefix
        remainder = value_name[len(prefix) :]

        # Check if remainder is a valid identifier (starts with letter)
        if not remainder or not remainder[0].isalpha():
            return value_name

        return remainder

    def format_type_id_comment(self, type_def, comment_prefix: str) -> Optional[str]:
        """Format a type id comment for a message/union."""
        type_id = getattr(type_def, "type_id", None)
        if type_id is None:
            return None
        if getattr(type_def, "id_generated", False):
            source = getattr(type_def, "id_source", None) or "unknown"
            return f"{comment_prefix} Type ID {type_id} is generated from {source}"
        return f"{comment_prefix} Type ID {type_id} is specified manually."

    def should_register_by_id(self, type_def) -> bool:
        """Return True if a type should be registered by numeric ID."""
        type_id = getattr(type_def, "type_id", None)
        return type_id is not None

    def get_effective_evolving(self, message) -> bool:
        """Return effective evolving flag for a message."""
        if message is None:
            return True
        if "evolving" in message.options:
            return bool(message.options.get("evolving"))
        file_default = self.schema.get_option("evolving")
        if file_default is None:
            return True
        return bool(file_default)

    def format_idl_type(self, field_type: FieldType) -> str:
        """Return an IDL-style type name for display purposes."""
        if isinstance(field_type, PrimitiveType):
            return field_type.kind.value
        if isinstance(field_type, NamedType):
            return field_type.name
        if isinstance(field_type, ListType):
            element = self.format_idl_type(field_type.element_type)
            return f"list<{element}>"
        if isinstance(field_type, MapType):
            key = self.format_idl_type(field_type.key_type)
            value = self.format_idl_type(field_type.value_type)
            return f"map<{key}, {value}>"
        return "object"

    def get_license_header(self, comment_prefix: str = "//") -> str:
        """Get the Apache license header."""
        lines = [
            "Licensed to the Apache Software Foundation (ASF) under one",
            "or more contributor license agreements.  See the NOTICE file",
            "distributed with this work for additional information",
            "regarding copyright ownership.  The ASF licenses this file",
            "to you under the Apache License, Version 2.0 (the",
            '"License"); you may not use this file except in compliance',
            "with the License.  You may obtain a copy of the License at",
            "",
            "  http://www.apache.org/licenses/LICENSE-2.0",
            "",
            "Unless required by applicable law or agreed to in writing,",
            "software distributed under the License is distributed on an",
            '"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY',
            "KIND, either express or implied.  See the License for the",
            "specific language governing permissions and limitations",
            "under the License.",
            "",
            "This file is generated by Apache Fory compiler.",
        ]
        return "\n".join(
            f"{comment_prefix} {line}" if line else comment_prefix for line in lines
        )
