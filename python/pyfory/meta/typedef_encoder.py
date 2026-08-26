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


from pyfory.meta.typedef import (
    FieldInfo,
    TypeDef,
    build_field_infos,
    SMALL_NUM_FIELDS_THRESHOLD,
    REGISTER_BY_NAME_FLAG,
    COMPATIBLE_TYPEDEF_FLAG,
    STRUCT_TYPEDEF_FLAG,
    FIELD_NAME_SIZE_THRESHOLD,
    BIG_NAME_THRESHOLD,
    COMPRESS_META_FLAG,
    META_SIZE_MASKS,
    FIELD_NAME_ENCODINGS,
    NAMESPACE_ENCODINGS,
    TYPE_NAME_ENCODINGS,
    FIELD_NAME_ENCODING_TAG_ID,
    TAG_ID_SIZE_THRESHOLD,
    is_struct_typedef_kind,
    xlang_non_struct_kind_code,
    _typedef_header_hash,
)
from pyfory.meta.metastring import MetaStringEncoder
from pyfory._fory import NO_USER_TYPE_ID
from pyfory.field import MAX_FIELD_ID
from pyfory.types import TypeId

from pyfory.serialization import Buffer


# Meta string encoders
NAMESPACE_ENCODER = MetaStringEncoder(".", "_")
TYPENAME_ENCODER = MetaStringEncoder("$", "_")
FIELD_NAME_ENCODER = MetaStringEncoder("$", "_")


def encode_typedef(type_resolver, cls, include_fields: bool = True):
    """
    Encode the typedef of the type for xlang serialization.

    Args:
        type_resolver: The type resolver.
        cls: The class to encode.

    Returns:
        The encoded TypeDef.
    """
    type_id, user_type_id = type_resolver.get_registered_type_ids(cls)
    if include_fields and is_struct_typedef_kind(type_id):
        field_infos = build_field_infos(type_resolver, cls)
    else:
        field_infos = []

    buffer = Buffer.allocate(64)

    # Write kind header
    if is_struct_typedef_kind(type_id):
        num_fields = len(field_infos)
        header = STRUCT_TYPEDEF_FLAG | min(num_fields, SMALL_NUM_FIELDS_THRESHOLD)
        if type_id in {TypeId.COMPATIBLE_STRUCT, TypeId.NAMED_COMPATIBLE_STRUCT}:
            header |= COMPATIBLE_TYPEDEF_FLAG
        if type_resolver.is_registered_by_name(cls):
            header |= REGISTER_BY_NAME_FLAG
        if num_fields >= SMALL_NUM_FIELDS_THRESHOLD:
            buffer.write_uint8(header)
            buffer.write_var_uint32(num_fields - SMALL_NUM_FIELDS_THRESHOLD)
        else:
            buffer.write_uint8(header)
    else:
        if field_infos:
            raise ValueError(f"Non-struct TypeDef {type_id} cannot carry field metadata")
        buffer.write_uint8(xlang_non_struct_kind_code(type_id))

    # Write type info
    if type_resolver.is_registered_by_name(cls):
        namespace, typename = type_resolver.get_registered_name(cls)
        write_namespace(buffer, namespace)
        write_typename(buffer, typename)
    else:
        assert type_resolver.is_registered_by_id(cls=cls), "Class must be registered by name or id"
        if user_type_id in {None, NO_USER_TYPE_ID}:
            raise ValueError(f"user_type_id required for type_id {type_id}")
        buffer.write_var_uint32(user_type_id)

    # Write fields info
    write_fields_info(type_resolver, buffer, field_infos)

    # Get the encoded binary (only the written portion, not the full buffer)
    binary = buffer.to_bytes(0, buffer.get_writer_index())

    is_compressed = False
    # Prepend header
    binary = prepend_header(binary, is_compressed)
    # Extract namespace and typename
    if type_resolver.is_registered_by_name(cls):
        namespace, typename = type_resolver.get_registered_name(cls)
    else:
        splits = cls.__name__.rsplit(".", 1)
        if len(splits) == 1:
            splits.insert(0, "")
        namespace, typename = splits

    result = TypeDef(
        namespace,
        typename,
        cls,
        type_id,
        field_infos,
        binary,
        is_compressed,
        user_type_id=user_type_id,
    )
    return result


def prepend_header(buffer: bytes, is_compressed: bool):
    """Prepend header to the buffer."""
    meta_size = len(buffer)
    header_low_bits = min(meta_size, META_SIZE_MASKS)
    if is_compressed:
        header_low_bits |= COMPRESS_META_FLAG

    header = _typedef_header_hash(buffer, header_low_bits) | header_low_bits
    if header >= (1 << 63):
        header -= 1 << 64
    result = Buffer.allocate(meta_size + 8)
    result.write_int64(header)
    if meta_size >= META_SIZE_MASKS:
        result.write_var_uint32(meta_size - META_SIZE_MASKS)

    result.write_bytes(buffer)
    return result.to_bytes(0, result.get_writer_index())


def write_namespace(buffer: Buffer, namespace: str):
    """Write namespace using meta string encoding."""
    # - Package name encoding(omitted when class is registered):
    #    - encoding algorithm: `UTF_8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL`
    #    - Header: `6 bits size | 2 bits encoding flags`.
    #      The `6 bits size: 0~63`  will be used to indicate size `0~62`,
    #      the value `63` the size need more byte to read, the encoding will encode `size - 62` as a varint next.
    meta_string = NAMESPACE_ENCODER.encode(namespace, NAMESPACE_ENCODINGS)
    write_meta_string(buffer, meta_string, NAMESPACE_ENCODINGS.index(meta_string.encoding))


def write_typename(buffer: Buffer, typename: str):
    """Write typename using meta string encoding."""
    # - Class name encoding(omitted when class is registered):
    #     - encoding algorithm:
    # `UTF_8/LOWER_UPPER_DIGIT_SPECIAL/FIRST_TO_LOWER_SPECIAL/ALL_TO_LOWER_SPECIAL`
    #     - header: `6 bits size | 2 bits encoding flags`.
    #       The `6 bits size: 0~63`  will be used to indicate size `1~64`,
    #       the value `63` the size need more byte to read, the encoding will encode `size - 63` as a varint next.
    meta_string = TYPENAME_ENCODER.encode(typename, TYPE_NAME_ENCODINGS)
    write_meta_string(buffer, meta_string, TYPE_NAME_ENCODINGS.index(meta_string.encoding))


def write_meta_string(buffer: Buffer, meta_string, encoding_value: int):
    """Write a big meta string (namespace/typename) to the buffer using 6-bit size field."""
    # Write encoding and length combined in first byte
    length = len(meta_string.encoded_data)

    if length >= BIG_NAME_THRESHOLD:
        # Use threshold value and write additional length
        header = (BIG_NAME_THRESHOLD << 2) | encoding_value
        buffer.write_uint8(header)
        buffer.write_var_uint32(length - BIG_NAME_THRESHOLD)
    else:
        # Combine length and encoding in single byte
        header = (length << 2) | encoding_value
        buffer.write_uint8(header)

    # Write encoded data
    if meta_string.encoded_data:
        buffer.write_bytes(meta_string.encoded_data)


def write_fields_info(type_resolver, buffer: Buffer, field_infos: list):
    """Write field information to the buffer."""
    for field_info in field_infos:
        write_field_info(buffer, field_info)


def write_field_info(buffer: Buffer, field_info: FieldInfo):
    """Write a single field info to the buffer.

    Field header format (8 bits) - aligned with Java TypeDefEncoder (for xlang):
    - bit 0: ref tracking flag
    - bit 1: nullable flag
    - bits 2-5: size (4 bits, 0-14 inline, 15 = overflow)
    - bits 6-7: encoding type (0b00-10 = field name, 0b11 = TAG_ID)

    For TAG_ID encoding (when encoding = 0b11):
    - size field contains tag_id (0-14 inline, 15 = overflow)
    - No field name bytes to write

    For field name encoding (when encoding = 0b00-10):
    - size field contains (encoded_size - 1)
    - type info followed by field name meta string
    """
    # Build header flags (bits 0-1)
    header = 0
    if field_info.field_type.is_tracking_ref:
        header |= 0b01  # bit 0
    if field_info.field_type.is_nullable:
        header |= 0b10  # bit 1

    if field_info.uses_tag_id():
        # TAG_ID encoding (encoding = 0b11 at bits 6-7)
        tag_id = field_info.tag_id
        if tag_id > MAX_FIELD_ID:
            raise ValueError(f"tag_id must not exceed {MAX_FIELD_ID}, got {tag_id}")
        header |= FIELD_NAME_ENCODING_TAG_ID << 6  # encoding at bits 6-7

        if tag_id >= TAG_ID_SIZE_THRESHOLD:
            # Overflow: use 0b1111 and write extra varint
            header |= TAG_ID_SIZE_THRESHOLD << 2
            buffer.write_uint8(header)
            buffer.write_var_uint32(tag_id - TAG_ID_SIZE_THRESHOLD)
        else:
            # Inline tag_id (0-14)
            header |= tag_id << 2
            buffer.write_uint8(header)

        # Write field type info (no field name for TAG_ID)
        field_info.field_type.write(buffer, False)
    else:
        # Field name encoding
        encoding = FIELD_NAME_ENCODER.compute_encoding(field_info.name, FIELD_NAME_ENCODINGS)
        meta_string = FIELD_NAME_ENCODER.encode_with_encoding(field_info.name, encoding)
        # Store (length - 1) in size field, matching Java TypeDefEncoder
        field_name_binary_size = len(meta_string.encoded_data) - 1
        encoding_flags = FIELD_NAME_ENCODINGS.index(meta_string.encoding)
        header |= encoding_flags << 6  # encoding at bits 6-7

        if field_name_binary_size >= FIELD_NAME_SIZE_THRESHOLD:
            header |= FIELD_NAME_SIZE_THRESHOLD << 2
            buffer.write_uint8(header)
            buffer.write_var_uint32(field_name_binary_size - FIELD_NAME_SIZE_THRESHOLD)
        else:
            header |= field_name_binary_size << 2
            buffer.write_uint8(header)

        # Write field type info BEFORE field name (matching Java TypeDefEncoder order)
        field_info.field_type.write(buffer, False)

        # Write field name meta string
        buffer.write_bytes(meta_string.encoded_data)
