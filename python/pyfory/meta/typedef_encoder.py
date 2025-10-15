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

from collections import Counter

from pyfory.meta.typedef import (
    FieldInfo,
    TypeDef,
    build_field_infos,
    SMALL_NUM_FIELDS_THRESHOLD,
    REGISTER_BY_NAME_FLAG,
    FIELD_NAME_SIZE_THRESHOLD,
    BIG_NAME_THRESHOLD,
    COMPRESS_META_FLAG,
    HAS_FIELDS_META_FLAG,
    META_SIZE_MASKS,
    NUM_HASH_BITS,
    FIELD_NAME_ENCODINGS,
    NAMESPACE_ENCODINGS,
    TYPE_NAME_ENCODINGS,
)
from pyfory.meta.metastring import MetaStringEncoder

from pyfory._util import Buffer
from pyfory.lib.mmh3 import hash_buffer


# Meta string encoders
NAMESPACE_ENCODER = MetaStringEncoder(".", "_")
TYPENAME_ENCODER = MetaStringEncoder("$", "_")
FIELD_NAME_ENCODER = MetaStringEncoder("$", "_")


def encode_typedef(type_resolver, cls):
    """
    Encode the typedef of the type for xlang serialization.

    Args:
        type_resolver: The type resolver.
        cls: The class to encode.

    Returns:
        The encoded TypeDef.
    """
    field_infos = build_field_infos(type_resolver, cls)

    # Check for duplicate field names
    field_names = [field_info.name for field_info in field_infos]
    duplicate_field_names = [name for name, count in Counter(field_names).items() if count > 1]
    if duplicate_field_names:
        # TODO: handle duplicate field names for inheritance in future
        raise ValueError(f"Duplicate field names: {duplicate_field_names}")

    buffer = Buffer.allocate(64)

    # Write meta header
    header = len(field_infos)
    if len(field_infos) >= SMALL_NUM_FIELDS_THRESHOLD:
        header = SMALL_NUM_FIELDS_THRESHOLD
        if type_resolver.is_registered_by_name(cls):
            header |= REGISTER_BY_NAME_FLAG
        buffer.write_uint8(header)
        buffer.write_varuint32(len(field_infos) - SMALL_NUM_FIELDS_THRESHOLD)
    else:
        if type_resolver.is_registered_by_name(cls):
            header |= REGISTER_BY_NAME_FLAG
        buffer.write_uint8(header)

    # Write type info
    if type_resolver.is_registered_by_name(cls):
        namespace, typename = type_resolver.get_registered_name(cls)
        write_namespace(buffer, namespace)
        write_typename(buffer, typename)
        # Use the actual type_id from the resolver, not a generic one
        type_id = type_resolver.get_registered_id(cls)
    else:
        assert type_resolver.is_registered_by_id(cls), "Class must be registered by name or id"
        type_id = type_resolver.get_registered_id(cls)
        buffer.write_varuint32(type_id)

    # Write fields info
    write_fields_info(type_resolver, buffer, field_infos)

    # Get the encoded binary
    binary = buffer.to_bytes()

    # Compress if beneficial
    compressed_binary = type_resolver.get_meta_compressor().compress(binary)
    is_compressed = len(compressed_binary) < len(binary)
    if is_compressed:
        binary = compressed_binary
    # Prepend header
    binary = prepend_header(binary, is_compressed, len(field_infos) > 0)
    # Extract namespace and typename
    if type_resolver.is_registered_by_name(cls):
        namespace, typename = type_resolver.get_registered_name(cls)
    else:
        splits = cls.__name__.rsplit(".", 1)
        if len(splits) == 1:
            splits.insert(0, "")
        namespace, typename = splits

    result = TypeDef(namespace, typename, cls, type_id, field_infos, binary, is_compressed)
    return result


def prepend_header(buffer: bytes, is_compressed: bool, has_fields_meta: bool):
    """Prepend header to the buffer."""
    meta_size = len(buffer)
    hash = hash_buffer(buffer, 47)[0]
    hash <<= 64 - NUM_HASH_BITS
    header = abs(hash) & 0x7FFFFFFFFFFFFFFF  # Ensure it fits in 63 bits
    if is_compressed:
        header |= COMPRESS_META_FLAG

    if has_fields_meta:
        header |= HAS_FIELDS_META_FLAG

    header |= min(meta_size, META_SIZE_MASKS)
    result = Buffer.allocate(meta_size + 8)
    result.write_int64(header)
    if meta_size > META_SIZE_MASKS:
        result.write_varuint32(meta_size - META_SIZE_MASKS)

    result.write_bytes(buffer)
    return result.to_bytes()


def write_namespace(buffer: Buffer, namespace: str):
    """Write namespace using meta string encoding."""
    # - Package name encoding(omitted when class is registered):
    #    - encoding algorithm: `UTF8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL`
    #    - Header: `6 bits size | 2 bits encoding flags`.
    #      The `6 bits size: 0~63`  will be used to indicate size `0~62`,
    #      the value `63` the size need more byte to read, the encoding will encode `size - 62` as a varint next.
    meta_string = NAMESPACE_ENCODER.encode(namespace, NAMESPACE_ENCODINGS)
    write_meta_string(buffer, meta_string, NAMESPACE_ENCODINGS.index(meta_string.encoding))


def write_typename(buffer: Buffer, typename: str):
    """Write typename using meta string encoding."""
    # - Class name encoding(omitted when class is registered):
    #     - encoding algorithm:
    # `UTF8/LOWER_UPPER_DIGIT_SPECIAL/FIRST_TO_LOWER_SPECIAL/ALL_TO_LOWER_SPECIAL`
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
        buffer.write_varuint32(length - BIG_NAME_THRESHOLD)
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
    """Write a single field info to the buffer."""
    # header: 2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag
    header = 0
    if field_info.field_type.is_nullable:
        header |= 0b10
    if field_info.field_type.is_tracking_ref:
        header |= 0b1
    encoding = FIELD_NAME_ENCODER.compute_encoding(field_info.name, FIELD_NAME_ENCODINGS)
    meta_string = FIELD_NAME_ENCODER.encode_with_encoding(field_info.name, encoding)
    field_name_binary_size = len(meta_string.encoded_data) - 1
    encoding_flags = FIELD_NAME_ENCODINGS.index(meta_string.encoding)
    header |= encoding_flags << 6
    if field_name_binary_size >= FIELD_NAME_SIZE_THRESHOLD:
        header |= 0b00111100
        buffer.write_uint8(header)
        buffer.write_varuint32(field_name_binary_size - FIELD_NAME_SIZE_THRESHOLD)
    else:
        header |= field_name_binary_size << 2
        buffer.write_uint8(header)

    # Write field type info
    field_info.field_type.xwrite(buffer, False)

    # TODO: support tag id
    buffer.write_bytes(meta_string.encoded_data)
