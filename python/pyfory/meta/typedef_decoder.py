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

"""
TypeDef decoder for xlang serialization.

This module implements the decoding of TypeDef objects according to the xlang serialization specification.
"""

from dataclasses import make_dataclass
from typing import List, Any
from pyfory.serialization import Buffer
from pyfory.meta.typedef import TypeDef, FieldInfo, FieldType
from pyfory.meta.typedef import (
    SMALL_NUM_FIELDS_THRESHOLD,
    REGISTER_BY_NAME_FLAG,
    FIELD_NAME_SIZE_THRESHOLD,
    BIG_NAME_THRESHOLD,
    COMPRESS_META_FLAG,
    HAS_FIELDS_META_FLAG,
    META_SIZE_MASKS,
    FIELD_NAME_ENCODINGS,
    NAMESPACE_ENCODINGS,
    TYPE_NAME_ENCODINGS,
    FIELD_NAME_ENCODING_TAG_ID,
    TAG_ID_SIZE_THRESHOLD,
)
from pyfory.types import TypeId
from pyfory._fory import NO_USER_TYPE_ID
from pyfory.meta.metastring import MetaStringDecoder, Encoding


MAX_GENERATED_CLASSES = 1000
MAX_FIELDS_PER_CLASS = 256
_generated_class_count = 0


# Meta string decoders
NAMESPACE_DECODER = MetaStringDecoder(".", "_")
TYPENAME_DECODER = MetaStringDecoder("$", "_")
FIELD_NAME_DECODER = MetaStringDecoder("$", "_")


def skip_typedef(buffer: Buffer, header) -> None:
    """
    Skip a TypeDef from the buffer.
    """
    # Extract components from header
    meta_size = header & META_SIZE_MASKS
    # If meta size is at maximum, read additional size
    if meta_size == META_SIZE_MASKS:
        meta_size += buffer.read_var_uint32()
    # Read meta data
    buffer.read_bytes(meta_size)


def decode_typedef(buffer: Buffer, resolver, header=None) -> TypeDef:
    """
    Decode a TypeDef from the buffer.

    Args:
        buffer: The buffer containing the encoded TypeDef.
        resolver: The type resolver.

    Returns:
        The decoded TypeDef.
    """
    global _generated_class_count

    # Read global binary header
    if header is None:
        header = buffer.read_int64()

    # Extract components from header
    meta_size = header & META_SIZE_MASKS
    has_fields_meta = (header & HAS_FIELDS_META_FLAG) != 0
    is_compressed = (header & COMPRESS_META_FLAG) != 0

    # If meta size is at maximum, read additional size
    if meta_size == META_SIZE_MASKS:
        meta_size += buffer.read_var_uint32()

    # Read meta data
    meta_data = buffer.read_bytes(meta_size)

    # Decompress if needed
    if is_compressed:
        meta_data = resolver.get_meta_compressor().decompress(meta_data)

    # Create a new buffer for meta data
    meta_buffer = Buffer(meta_data)

    # Read meta header
    meta_header = meta_buffer.read_uint8()

    # Extract number of fields
    num_fields = meta_header & 0b11111
    if num_fields == SMALL_NUM_FIELDS_THRESHOLD:
        num_fields += meta_buffer.read_var_uint32()

    # Check field count limit
    if num_fields > MAX_FIELDS_PER_CLASS:
        raise ValueError(
            f"Class has {num_fields} fields, exceeding the maximum allowed {MAX_FIELDS_PER_CLASS} fields. This may indicate malicious data."
        )

    # Check if registered by name
    is_registered_by_name = (meta_header & REGISTER_BY_NAME_FLAG) != 0

    type_cls = None
    user_type_id = NO_USER_TYPE_ID
    # Read type info
    if is_registered_by_name:
        namespace = read_namespace(meta_buffer)
        typename = read_typename(meta_buffer)
        # Look up the type_id from namespace and typename
        type_info = resolver.get_type_info_by_name(namespace, typename)
        if type_info:
            type_id = type_info.type_id
            type_cls = type_info.cls
        else:
            # Fallback to COMPATIBLE_STRUCT if not found
            type_id = TypeId.COMPATIBLE_STRUCT
    else:
        type_id = meta_buffer.read_uint8()
        user_type_id = meta_buffer.read_var_uint32()
        if resolver.is_registered_by_id(type_id=type_id, user_type_id=user_type_id):
            type_info = resolver.get_type_info_by_id(type_id, user_type_id=user_type_id)
            type_cls = type_info.cls
            namespace = type_info.decode_namespace()
            typename = type_info.decode_typename()
        else:
            namespace = "fory"
            typename = f"UnknownStruct{user_type_id if user_type_id != NO_USER_TYPE_ID else type_id}"
    name = namespace + "." + typename if namespace else typename
    # Read fields info if present
    field_infos = []
    if has_fields_meta:
        field_infos = read_fields_info(meta_buffer, resolver, name, num_fields)
    if type_cls is None:
        # Check generated class count limit
        if _generated_class_count >= MAX_GENERATED_CLASSES:
            raise ValueError(
                f"Exceeded maximum number of dynamically generated classes ({MAX_GENERATED_CLASSES}). "
                "This may indicate malicious data causing memory issues."
            )
        _generated_class_count += 1
        # Generate dynamic dataclass from field definitions
        field_definitions = [(field_info.name, Any) for field_info in field_infos]
        # Use a valid Python identifier for class name
        class_name = typename.replace(".", "_").replace("$", "_")
        type_cls = make_dataclass(class_name, field_definitions)

    # Create TypeDef object
    type_def = TypeDef(
        namespace,
        typename,
        type_cls,
        type_id,
        field_infos,
        meta_data,
        is_compressed,
        user_type_id=user_type_id,
    )
    return type_def


def read_namespace(buffer: Buffer) -> str:
    """Read namespace from the buffer."""
    return read_meta_string(buffer, NAMESPACE_DECODER, NAMESPACE_ENCODINGS)


def read_typename(buffer: Buffer) -> str:
    """Read typename from the buffer."""
    return read_meta_string(buffer, TYPENAME_DECODER, TYPE_NAME_ENCODINGS)


def read_meta_string(buffer: Buffer, decoder: MetaStringDecoder, encodings: List[Encoding]) -> str:
    """Read a big meta string (namespace/typename) from the buffer using 6-bit size field."""
    # Read encoding and length combined in first byte
    header = buffer.read_uint8()

    # Extract encoding (2 bits) and size (6 bits)
    encoding_value = header & 0b11
    size_value = (header >> 2) & 0b111111

    encoding = encodings[encoding_value]

    # Read length - same logic as encoder
    length = 0
    if size_value >= BIG_NAME_THRESHOLD:
        length = size_value - BIG_NAME_THRESHOLD + buffer.read_var_uint32()
    else:
        length = size_value

    # Read encoded data
    if length > 0:
        encoded_data = buffer.read_bytes(length)
        return decoder.decode(encoded_data, encoding)
    else:
        return ""


def read_fields_info(buffer: Buffer, resolver, defined_class: str, num_fields: int) -> List[FieldInfo]:
    """Read field information from the buffer."""
    field_infos = []
    for _ in range(num_fields):
        field_info = read_field_info(buffer, resolver, defined_class)
        field_infos.append(field_info)
    return field_infos


def read_field_info(buffer: Buffer, resolver, defined_class: str) -> FieldInfo:
    """Read a single field info from the buffer.

    Field header format (8 bits) - aligned with Java TypeDefDecoder (for xlang):
    - bit 0: ref tracking flag
    - bit 1: nullable flag
    - bits 2-5: size (4 bits, 0-14 inline, 15 = overflow)
    - bits 6-7: encoding type (0b00-10 = field name, 0b11 = TAG_ID)

    For TAG_ID encoding:
    - size field contains tag_id (0-14 inline, 15 = overflow)
    - No field name bytes to read

    For field name encoding:
    - size field contains (encoded_size - 1)
    - Type info followed by field name meta string bytes
    """
    # Read field header
    header = buffer.read_uint8()

    # Extract common flags from bits 0-1
    is_tracking_ref = (header & 0b01) != 0
    is_nullable = (header & 0b10) != 0

    # Extract size (bits 2-5) and encoding type (bits 6-7)
    size_or_tag = (header >> 2) & 0b1111
    encoding_type = (header >> 6) & 0b11

    if encoding_type == FIELD_NAME_ENCODING_TAG_ID:
        # TAG_ID encoding
        if size_or_tag >= TAG_ID_SIZE_THRESHOLD:
            tag_id = TAG_ID_SIZE_THRESHOLD + buffer.read_var_uint32()
        else:
            tag_id = size_or_tag

        # Read field type info (no field name to read for TAG_ID)
        xtype_id = buffer.read_uint8()
        field_type = FieldType.read_with_type(buffer, resolver, xtype_id, is_nullable, is_tracking_ref)

        # For TAG_ID encoding, use tag_id as field name placeholder
        field_name = f"__tag_{tag_id}__"
        return FieldInfo(field_name, field_type, defined_class, tag_id)
    else:
        # Field name encoding
        field_name_size = size_or_tag
        if field_name_size >= FIELD_NAME_SIZE_THRESHOLD:
            field_name_size = FIELD_NAME_SIZE_THRESHOLD + buffer.read_var_uint32()
        field_name_size += 1  # Add 1 to convert from (size-1) to actual size
        encoding = FIELD_NAME_ENCODINGS[encoding_type]

        # Read field type info BEFORE field name (matching Java TypeDefDecoder order)
        xtype_id = buffer.read_uint8()
        field_type = FieldType.read_with_type(buffer, resolver, xtype_id, is_nullable, is_tracking_ref)

        # Read field name meta string
        # Keep the wire field name as-is; TypeDef._resolve_field_names_from_tag_ids()
        # will handle matching against the Python class's field names (which may be
        # snake_case or camelCase depending on Python conventions used)
        field_name_bytes = buffer.read_bytes(field_name_size)
        field_name = FIELD_NAME_DECODER.decode(field_name_bytes, encoding)
        return FieldInfo(field_name, field_type, defined_class, -1)
