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

import enum
import logging
import os
from abc import ABC, abstractmethod
from typing import Union, Iterable, TypeVar

from pyfory.buffer import Buffer
from pyfory.resolver import (
    MapRefResolver,
    NoRefResolver,
    NULL_FLAG,
    NOT_NULL_VALUE_FLAG,
)
from pyfory.util import is_little_endian, set_bit, get_bit, clear_bit
from pyfory.type import TypeId
from pyfory.policy import DeserializationPolicy, DEFAULT_POLICY

try:
    import numpy as np
except ImportError:
    np = None


logger = logging.getLogger(__name__)


MAGIC_NUMBER = 0x62D4
DEFAULT_DYNAMIC_WRITE_META_STR_ID = -1
DYNAMIC_TYPE_ID = -1
USE_TYPE_NAME = 0
USE_TYPE_ID = 1
# preserve 0 as flag for type id not set in TypeInfo`
NO_TYPE_ID = 0
INT64_TYPE_ID = TypeId.INT64
FLOAT64_TYPE_ID = TypeId.FLOAT64
BOOL_TYPE_ID = TypeId.BOOL
STRING_TYPE_ID = TypeId.STRING
# `NOT_NULL_VALUE_FLAG` + `TYPE_ID << 1` in little-endian order
NOT_NULL_INT64_FLAG = NOT_NULL_VALUE_FLAG & 0b11111111 | (INT64_TYPE_ID << 8)
NOT_NULL_FLOAT64_FLAG = NOT_NULL_VALUE_FLAG & 0b11111111 | (FLOAT64_TYPE_ID << 8)
NOT_NULL_BOOL_FLAG = NOT_NULL_VALUE_FLAG & 0b11111111 | (BOOL_TYPE_ID << 8)
NOT_NULL_STRING_FLAG = NOT_NULL_VALUE_FLAG & 0b11111111 | (STRING_TYPE_ID << 8)
SMALL_STRING_THRESHOLD = 16


class Language(enum.Enum):
    XLANG = 0
    JAVA = 1
    PYTHON = 2
    CPP = 3
    GO = 4
    JAVA_SCRIPT = 5
    RUST = 6
    DART = 7


class BufferObject(ABC):
    """
    Fory binary representation of an object.
    Note: This class is used for zero-copy out-of-band serialization and shouldn't
     be used for any other cases.
    """

    @abstractmethod
    def total_bytes(self) -> int:
        """Total size for serialized bytes of an object."""

    @abstractmethod
    def write_to(self, stream):
        """
        Write serialized object to a writable stream.

        Args:
            stream: Any writable object with write() method (Buffer, file, BytesIO, etc.)
        """

    @abstractmethod
    def getbuffer(self) -> memoryview:
        """
        Return serialized data as memoryview for zero-copy access.

        Returns:
            memoryview: A memoryview of the serialized data. For objects with
                contiguous memory (bytes, bytearray, numpy arrays), this is zero-copy.
                For non-contiguous data, a copy may be created to ensure contiguity.
        """

    def raw(self):
        return memoryview(self.getbuffer())


class Fory:
    """
    High-performance cross-language serialization framework.

    Fory provides blazingly-fast serialization for Python objects with support for
    both Python-native mode and cross-language mode. It handles complex object graphs,
    reference tracking, and circular references automatically.

    In Python-native mode (xlang=False), Fory can serialize all Python objects
    including dataclasses, classes with custom serialization methods, and local
    functions/classes, making it a drop-in replacement for pickle.

    In cross-language mode (xlang=True), Fory serializes objects in a format that
    can be deserialized by other Fory-supported languages (Java, Go, Rust, C++, etc).

    Examples:
        >>> import pyfory
        >>> from dataclasses import dataclass
        >>>
        >>> @dataclass
        >>> class Person:
        ...     name: str
        ...     age: pyfory.int32
        >>>
        >>> # Python-native mode
        >>> fory = pyfory.Fory()
        >>> fory.register(Person)
        >>> data = fory.serialize(Person("Alice", 30))
        >>> person = fory.deserialize(data)
        >>>
        >>> # Cross-language mode
        >>> fory_xlang = pyfory.Fory(xlang=True)
        >>> fory_xlang.register(Person)
        >>> data = fory_xlang.serialize(Person("Bob", 25))

    See Also:
        ThreadSafeFory: Thread-safe wrapper for concurrent usage
    """

    __slots__ = (
        "language",
        "is_py",
        "compatible",
        "ref_tracking",
        "ref_resolver",
        "type_resolver",
        "serialization_context",
        "strict",
        "buffer",
        "_buffer_callback",
        "_buffers",
        "metastring_resolver",
        "_unsupported_callback",
        "_unsupported_objects",
        "_peer_language",
        "max_depth",
        "depth",
        "field_nullable",
        "policy",
    )

    def __init__(
        self,
        xlang: bool = False,
        ref: bool = False,
        strict: bool = True,
        compatible: bool = False,
        max_depth: int = 50,
        policy: DeserializationPolicy = None,
        field_nullable: bool = False,
        **kwargs,
    ):
        """
        Initialize a Fory serialization instance.

        Args:
            xlang: Enable cross-language serialization mode. When False (default), uses
                Python-native mode supporting all Python objects (dataclasses, __reduce__,
                local functions/classes). With ref=True and strict=False, serves as a
                drop-in replacement for pickle. When True, uses cross-language format
                compatible with other Fory languages (Java, Go, Rust, etc), but Python-
                specific features like functions and __reduce__ methods are not supported.

            ref: Enable reference tracking for shared and circular references. When enabled,
                duplicate objects are stored once and circular references are supported.
                Disabled by default for better performance.

            strict: Require type registration before serialization (default: True). When
                disabled, unknown types can be deserialized, which may be insecure if
                malicious code exists in __new__/__init__/__eq__/__hash__ methods.
                **WARNING**: Only disable in trusted environments. When disabling strict
                mode, you should provide a custom `policy` parameter to control which types
                are allowed. We are not responsible for security risks when this option
                is disabled without proper policy controls.

            compatible: Enable schema evolution for cross-language serialization. When
                enabled, supports forward/backward compatibility for dataclass field
                additions and removals.

            max_depth: Maximum nesting depth for deserialization (default: 50). Raises
                an exception if exceeded to prevent malicious deeply-nested data attacks.

            policy: Custom deserialization policy for security checks. When provided,
                it controls which types can be deserialized, overriding the default policy.
                **Strongly recommended** when strict=False to maintain security controls.

            field_nullable: Treat all dataclass fields as nullable in Python-native mode
                (xlang=False), regardless of Optional annotation. Ignored in cross-language
                mode.

        Example:
            >>> # Python-native mode with reference tracking
            >>> fory = Fory(ref=True)
            >>>
            >>> # Cross-language mode with schema evolution
            >>> fory = Fory(xlang=True, compatible=True)
        """
        self.language = Language.XLANG if xlang else Language.PYTHON
        if kwargs.get("language") is not None:
            self.language = kwargs.get("language")
        self.is_py = self.language == Language.PYTHON
        if kwargs.get("ref_tracking") is not None:
            ref = kwargs.get("ref_tracking")
        self.ref_tracking = ref
        if self.ref_tracking:
            self.ref_resolver = MapRefResolver()
        else:
            self.ref_resolver = NoRefResolver()
        if kwargs.get("require_type_registration") is not None:
            strict = kwargs.get("require_type_registration")
        self.strict = _ENABLE_TYPE_REGISTRATION_FORCIBLY or strict
        self.policy = policy or DEFAULT_POLICY
        self.compatible = compatible
        self.field_nullable = field_nullable if self.is_py else False
        from pyfory.serialization import MetaStringResolver, SerializationContext
        from pyfory._registry import TypeResolver

        self.metastring_resolver = MetaStringResolver()
        self.type_resolver = TypeResolver(self, meta_share=compatible)
        self.serialization_context = SerializationContext(fory=self, scoped_meta_share_enabled=compatible)
        self.type_resolver.initialize()

        self.buffer = Buffer.allocate(32)
        self._buffer_callback = None
        self._buffers = None
        self._unsupported_callback = None
        self._unsupported_objects = None
        self._peer_language = None
        self.max_depth = max_depth
        self.depth = 0

    def register(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        """
        Register a type for serialization.

        This is an alias for `register_type()`. Type registration enables Fory to
        efficiently serialize and deserialize objects by pre-computing serialization
        metadata.

        For cross-language serialization, types can be matched between languages using:
        1. **type_id** (recommended): Numeric ID matching - faster and more compact
        2. **namespace + typename**: String-based matching - more flexible but larger overhead

        Args:
            cls: The Python type to register
            type_id: Optional unique numeric ID for cross-language type matching.
                Using type_id provides better performance and smaller serialized size
                compared to namespace/typename matching.
            namespace: Optional namespace for cross-language type matching by name.
                Used when type_id is not specified.
            typename: Optional type name for cross-language type matching by name.
                Defaults to class name if not specified. Used with namespace.
            serializer: Optional custom serializer instance for this type

        Example:
            >>> # Register with type_id (recommended for performance)
            >>> fory = Fory(xlang=True)
            >>> fory.register(Person, type_id=100)
            >>>
            >>> # Register with namespace and typename (more flexible)
            >>> fory.register(Person, namespace="com.example", typename="Person")
            >>>
            >>> # Python-native mode (no cross-language matching needed)
            >>> fory = Fory()
            >>> fory.register(Person)
        """
        self.register_type(
            cls,
            type_id=type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
        )

    # `Union[type, TypeVar]` is not supported in py3.6
    def register_type(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        """
        Register a type for serialization.

        Type registration enables Fory to efficiently serialize and deserialize objects
        by pre-computing serialization metadata.

        For cross-language serialization, types can be matched between languages using:
        1. **type_id** (recommended): Numeric ID matching - faster and more compact
        2. **namespace + typename**: String-based matching - more flexible but larger overhead

        Args:
            cls: The Python type to register
            type_id: Optional unique numeric ID for cross-language type matching.
                Using type_id provides better performance and smaller serialized size
                compared to namespace/typename matching.
            namespace: Optional namespace for cross-language type matching by name.
                Used when type_id is not specified.
            typename: Optional type name for cross-language type matching by name.
                Defaults to class name if not specified. Used with namespace.
            serializer: Optional custom serializer instance for this type

        Example:
            >>> # Register with type_id (recommended for performance)
            >>> fory = Fory(xlang=True)
            >>> fory.register_type(Person, type_id=100)
            >>>
            >>> # Register with namespace and typename (more flexible)
            >>> fory.register_type(Person, namespace="com.example", typename="Person")
            >>>
            >>> # Python-native mode (no cross-language matching needed)
            >>> fory = Fory()
            >>> fory.register_type(Person)
        """
        return self.type_resolver.register_type(
            cls,
            type_id=type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
        )

    def register_serializer(self, cls: type, serializer):
        """
        Register a custom serializer for a type.

        Allows you to provide a custom serializer implementation for a specific type,
        overriding Fory's default serialization behavior.

        Args:
            cls: The Python type to associate with the serializer
            serializer: Custom serializer instance implementing the Serializer protocol

        Example:
            >>> fory = Fory()
            >>> fory.register_serializer(MyClass, MyCustomSerializer())
        """
        self.type_resolver.register_serializer(cls, serializer)

    def dumps(
        self,
        obj,
        buffer: Buffer = None,
        buffer_callback=None,
        unsupported_callback=None,
    ) -> Union[Buffer, bytes]:
        """
        Serialize an object to bytes, alias for `serialize` method.
        """
        return self.serialize(obj, buffer, buffer_callback, unsupported_callback)

    def loads(
        self,
        buffer: Union[Buffer, bytes],
        buffers: Iterable = None,
        unsupported_objects: Iterable = None,
    ):
        """
        Deserialize bytes to an object, alias for `deserialize` method.
        """
        return self.deserialize(buffer, buffers, unsupported_objects)

    def serialize(
        self,
        obj,
        buffer: Buffer = None,
        buffer_callback=None,
        unsupported_callback=None,
    ) -> Union[Buffer, bytes]:
        """
        Serialize a Python object to bytes.

        Converts the object into Fory's binary format. The serialization process
        automatically handles reference tracking (if enabled), type information,
        and nested objects.

        Args:
            obj: The object to serialize
            buffer: Optional pre-allocated buffer to write to. If None, uses internal buffer
            buffer_callback: Optional callback for out-of-band buffer serialization
            unsupported_callback: Optional callback for handling unsupported types

        Returns:
            Serialized bytes if buffer is None, otherwise returns the provided buffer

        Example:
            >>> fory = Fory()
            >>> data = fory.serialize({"key": "value", "num": 42})
            >>> print(type(data))
            <class 'bytes'>
        """
        try:
            return self._serialize(
                obj,
                buffer,
                buffer_callback=buffer_callback,
                unsupported_callback=unsupported_callback,
            )
        finally:
            self.reset_write()

    def _serialize(
        self,
        obj,
        buffer: Buffer = None,
        buffer_callback=None,
        unsupported_callback=None,
    ) -> Union[Buffer, bytes]:
        assert self.depth == 0, "Nested serialization should use write_ref/write_no_ref/xwrite_ref/xwrite_no_ref."
        self.depth += 1
        self._buffer_callback = buffer_callback
        self._unsupported_callback = unsupported_callback
        if buffer is None:
            self.buffer.writer_index = 0
            buffer = self.buffer
        if self.language == Language.XLANG:
            buffer.write_int16(MAGIC_NUMBER)
        mask_index = buffer.writer_index
        # 1byte used for bit mask
        buffer.grow(1)
        buffer.writer_index = mask_index + 1
        if obj is None:
            set_bit(buffer, mask_index, 0)
        else:
            clear_bit(buffer, mask_index, 0)
        # set endian
        if is_little_endian:
            set_bit(buffer, mask_index, 1)
        else:
            clear_bit(buffer, mask_index, 1)

        if self.language == Language.XLANG:
            # set reader as x_lang.
            set_bit(buffer, mask_index, 2)
            # set writer language.
            buffer.write_int8(Language.PYTHON.value)
        else:
            # set reader as native.
            clear_bit(buffer, mask_index, 2)
        if self._buffer_callback is not None:
            set_bit(buffer, mask_index, 3)
        else:
            clear_bit(buffer, mask_index, 3)
        # Reserve space for type definitions offset, similar to Java implementation
        type_defs_offset_pos = None
        if self.serialization_context.scoped_meta_share_enabled:
            type_defs_offset_pos = buffer.writer_index
            buffer.write_int32(-1)  # Reserve 4 bytes for type definitions offset

        if self.language == Language.PYTHON:
            self.write_ref(buffer, obj)
        else:
            self.xwrite_ref(buffer, obj)

        # Write type definitions at the end, similar to Java implementation
        if self.serialization_context.scoped_meta_share_enabled:
            meta_context = self.serialization_context.meta_context
            if meta_context is not None and len(meta_context.get_writing_type_defs()) > 0:
                # Update the offset to point to current position
                current_pos = buffer.writer_index
                buffer.put_int32(type_defs_offset_pos, current_pos - type_defs_offset_pos - 4)
                self.type_resolver.write_type_defs(buffer)
        if buffer is not self.buffer:
            return buffer
        else:
            return buffer.to_bytes(0, buffer.writer_index)

    def write_ref(self, buffer, obj, typeinfo=None):
        cls = type(obj)
        if cls is str:
            buffer.write_int16(NOT_NULL_STRING_FLAG)
            buffer.write_string(obj)
            return
        elif cls is int:
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(obj)
            return
        elif cls is bool:
            buffer.write_int16(NOT_NULL_BOOL_FLAG)
            buffer.write_bool(obj)
            return
        if self.ref_resolver.write_ref_or_null(buffer, obj):
            return
        if typeinfo is None:
            typeinfo = self.type_resolver.get_typeinfo(cls)
        self.type_resolver.write_typeinfo(buffer, typeinfo)
        typeinfo.serializer.write(buffer, obj)

    def write_no_ref(self, buffer, obj):
        cls = type(obj)
        if cls is str:
            buffer.write_varuint32(STRING_TYPE_ID)
            buffer.write_string(obj)
            return
        elif cls is int:
            buffer.write_varuint32(INT64_TYPE_ID)
            buffer.write_varint64(obj)
            return
        elif cls is bool:
            buffer.write_varuint32(BOOL_TYPE_ID)
            buffer.write_bool(obj)
            return
        else:
            typeinfo = self.type_resolver.get_typeinfo(cls)
            self.type_resolver.write_typeinfo(buffer, typeinfo)
            typeinfo.serializer.write(buffer, obj)

    def xwrite_ref(self, buffer, obj, serializer=None):
        if serializer is None or serializer.need_to_write_ref:
            if not self.ref_resolver.write_ref_or_null(buffer, obj):
                self.xwrite_no_ref(buffer, obj, serializer=serializer)
        else:
            if obj is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.xwrite_no_ref(buffer, obj, serializer=serializer)

    def xwrite_no_ref(self, buffer, obj, serializer=None):
        if serializer is not None:
            serializer.xwrite(buffer, obj)
            return
        cls = type(obj)
        typeinfo = self.type_resolver.get_typeinfo(cls)
        self.type_resolver.write_typeinfo(buffer, typeinfo)
        typeinfo.serializer.xwrite(buffer, obj)

    def deserialize(
        self,
        buffer: Union[Buffer, bytes],
        buffers: Iterable = None,
        unsupported_objects: Iterable = None,
    ):
        """
        Deserialize bytes back to a Python object.

        Reconstructs an object from Fory's binary format. The deserialization process
        automatically handles reference resolution (if enabled), type instantiation,
        and nested objects.

        Args:
            buffer: Serialized bytes or Buffer to deserialize from
            buffers: Optional iterable of buffers for out-of-band deserialization
            unsupported_objects: Optional iterable of objects for unsupported type handling

        Returns:
            The deserialized Python object

        Example:
            >>> fory = Fory()
            >>> data = fory.serialize({"key": "value"})
            >>> obj = fory.deserialize(data)
            >>> print(obj)
            {'key': 'value'}
        """
        try:
            return self._deserialize(buffer, buffers, unsupported_objects)
        finally:
            self.reset_read()

    def _deserialize(
        self,
        buffer: Union[Buffer, bytes],
        buffers: Iterable = None,
        unsupported_objects: Iterable = None,
    ):
        assert self.depth == 0, "Nested deserialization should use read_ref/read_no_ref/xread_ref/xread_no_ref."
        self.depth += 1
        if isinstance(buffer, bytes):
            buffer = Buffer(buffer)
        if unsupported_objects is not None:
            self._unsupported_objects = iter(unsupported_objects)
        if self.language == Language.XLANG:
            magic_numer = buffer.read_int16()
            assert magic_numer == MAGIC_NUMBER, (
                f"The fory xlang serialization must start with magic number {hex(MAGIC_NUMBER)}. "
                "Please check whether the serialization is based on the xlang protocol and the data didn't corrupt."
            )
        reader_index = buffer.reader_index
        buffer.reader_index = reader_index + 1
        if get_bit(buffer, reader_index, 0):
            return None
        is_little_endian_ = get_bit(buffer, reader_index, 1)
        assert is_little_endian_, "Big endian is not supported for now, please ensure peer machine is little endian."
        is_target_x_lang = get_bit(buffer, reader_index, 2)
        if is_target_x_lang:
            self._peer_language = Language(buffer.read_int8())
        else:
            self._peer_language = Language.PYTHON
        is_out_of_band_serialization_enabled = get_bit(buffer, reader_index, 3)
        if is_out_of_band_serialization_enabled:
            assert buffers is not None, "buffers shouldn't be null when the serialized stream is produced with buffer_callback not null."
            self._buffers = iter(buffers)
        else:
            assert buffers is None, "buffers should be null when the serialized stream is produced with buffer_callback null."

        # Read type definitions at the start, similar to Java implementation
        if self.serialization_context.scoped_meta_share_enabled:
            relative_type_defs_offset = buffer.read_int32()
            if relative_type_defs_offset != -1:
                # Save current reader position
                current_reader_index = buffer.reader_index
                # Jump to type definitions
                buffer.reader_index = current_reader_index + relative_type_defs_offset
                # Read type definitions
                self.type_resolver.read_type_defs(buffer)
                # Jump back to continue with object deserialization
                buffer.reader_index = current_reader_index

        if is_target_x_lang:
            obj = self.xread_ref(buffer)
        else:
            obj = self.read_ref(buffer)
        return obj

    def read_ref(self, buffer):
        ref_resolver = self.ref_resolver
        ref_id = ref_resolver.try_preserve_ref_id(buffer)
        # indicates that the object is first read.
        if ref_id >= NOT_NULL_VALUE_FLAG:
            typeinfo = self.type_resolver.read_typeinfo(buffer)
            self.inc_depth()
            o = typeinfo.serializer.read(buffer)
            self.dec_depth()
            ref_resolver.set_read_object(ref_id, o)
            return o
        else:
            return ref_resolver.get_read_object()

    def read_no_ref(self, buffer):
        """Deserialize not-null and non-reference object from buffer."""
        typeinfo = self.type_resolver.read_typeinfo(buffer)
        self.inc_depth()
        o = typeinfo.serializer.read(buffer)
        self.dec_depth()
        return o

    def xread_ref(self, buffer, serializer=None):
        if serializer is None or serializer.need_to_write_ref:
            ref_resolver = self.ref_resolver
            ref_id = ref_resolver.try_preserve_ref_id(buffer)
            # indicates that the object is first read.
            if ref_id >= NOT_NULL_VALUE_FLAG:
                o = self.xread_no_ref(buffer, serializer=serializer)
                ref_resolver.set_read_object(ref_id, o)
                return o
            else:
                return ref_resolver.get_read_object()
        head_flag = buffer.read_int8()
        if head_flag == NULL_FLAG:
            return None
        return self.xread_no_ref(buffer, serializer=serializer)

    def xread_no_ref(self, buffer, serializer=None):
        if serializer is None:
            serializer = self.type_resolver.read_typeinfo(buffer).serializer
        self.inc_depth()
        o = serializer.xread(buffer)
        self.dec_depth()
        return o

    def write_buffer_object(self, buffer, buffer_object: BufferObject):
        if self._buffer_callback is None or self._buffer_callback(buffer_object):
            buffer.write_bool(True)
            size = buffer_object.total_bytes()
            # writer length.
            buffer.write_varuint32(size)
            writer_index = buffer.writer_index
            buffer.ensure(writer_index + size)
            buf = buffer.slice(buffer.writer_index, size)
            buffer_object.write_to(buf)
            buffer.writer_index += size
        else:
            buffer.write_bool(False)

    def read_buffer_object(self, buffer) -> Buffer:
        in_band = buffer.read_bool()
        if in_band:
            size = buffer.read_varuint32()
            buf = buffer.slice(buffer.reader_index, size)
            buffer.reader_index += size
            return buf
        else:
            assert self._buffers is not None
            return next(self._buffers)

    def handle_unsupported_write(self, buffer, obj):
        if self._unsupported_callback is None or self._unsupported_callback(obj):
            raise NotImplementedError(f"{type(obj)} is not supported for write")

    def handle_unsupported_read(self, buffer):
        assert self._unsupported_objects is not None
        return next(self._unsupported_objects)

    def write_ref_pyobject(self, buffer, value, typeinfo=None):
        if self.ref_resolver.write_ref_or_null(buffer, value):
            return
        if typeinfo is None:
            typeinfo = self.type_resolver.get_typeinfo(type(value))
        self.type_resolver.write_typeinfo(buffer, typeinfo)
        typeinfo.serializer.write(buffer, value)

    def read_ref_pyobject(self, buffer):
        return self.read_ref(buffer)

    def reset_write(self):
        """
        Reset write state after serialization.

        Clears internal write buffers, reference tracking state, and type resolution
        caches. This method is automatically called after each serialization.
        """
        self.depth = 0
        self.ref_resolver.reset_write()
        self.type_resolver.reset_write()
        self.serialization_context.reset_write()
        self.metastring_resolver.reset_write()
        self._buffer_callback = None
        self._unsupported_callback = None

    def reset_read(self):
        """
        Reset read state after deserialization.

        Clears internal read buffers, reference tracking state, and type resolution
        caches. This method is automatically called after each deserialization.
        """
        self.depth = 0
        self.ref_resolver.reset_read()
        self.type_resolver.reset_read()
        self.serialization_context.reset_read()
        self.metastring_resolver.reset_write()
        self._buffers = None
        self._unsupported_objects = None

    def reset(self):
        """
        Reset both write and read state.

        Clears all internal state including buffers, reference tracking, and type
        resolution caches. Use this to ensure a clean state before reusing a Fory
        instance.
        """
        self.reset_write()
        self.reset_read()

    def inc_depth(self):
        self.depth += 1
        if self.depth > self.max_depth:
            self.throw_depth_limit_exceeded_exception()

    def dec_depth(self):
        self.depth -= 1

    def throw_depth_limit_exceeded_exception(self):
        raise Exception(
            f"Read depth exceed max depth: {self.depth}, the deserialization data may be malicious. If it's not malicious, "
            "please increase max read depth by Fory(..., max_depth=...)"
        )


_ENABLE_TYPE_REGISTRATION_FORCIBLY = os.getenv("ENABLE_TYPE_REGISTRATION_FORCIBLY", "0") in {
    "1",
    "true",
}


class ThreadSafeFory:
    """
    Thread-safe wrapper for Fory using instance pooling.

    ThreadSafeFory maintains a pool of Fory instances protected by a lock to enable
    safe concurrent serialization/deserialization across multiple threads. When a thread
    needs to serialize or deserialize data, it acquires an instance from the pool, uses it,
    and returns it for reuse by other threads.

    All type registrations must be performed before any serialization operations to ensure
    consistency across all pooled instances. Attempting to register types after the first
    serialization will raise a RuntimeError.

    Args:
        xlang (bool): Whether to enable cross-language serialization. Defaults to False.
        ref (bool): Whether to enable reference tracking. Defaults to False.
        strict (bool): Whether to require type registration. Defaults to True.
        compatible (bool): Whether to enable compatible mode. Defaults to False.
        max_depth (int): Maximum depth for deserialization. Defaults to 50.

    Example:
        >>> import pyfury
        >>> import threading
        >>> from dataclasses import dataclass
        >>>
        >>> @dataclass
        >>> class Person:
        ...     name: str
        ...     age: int
        >>>
        >>> # Create thread-safe instance
        >>> fory = pyfury.ThreadSafeFory()
        >>> fory.register(Person)
        >>>
        >>> # Use safely from multiple threads
        >>> def worker(thread_id):
        ...     person = Person(f"User{thread_id}", 25)
        ...     data = fory.serialize(person)
        ...     result = fory.deserialize(data)
        ...     print(f"Thread {thread_id}: {result}")
        >>>
        >>> threads = [threading.Thread(target=worker, args=(i,)) for i in range(5)]
        >>> for t in threads: t.start()
        >>> for t in threads: t.join()

    Note:
        - Register all types before calling serialize/deserialize
        - The pool grows dynamically as needed based on thread contention
        - Instances are automatically returned to the pool after use
        - Both Python and Cython modes are supported automatically
    """

    def __init__(self, **kwargs):
        import threading

        self._config = kwargs
        self._callbacks = []
        self._lock = threading.Lock()
        self._pool = []
        self._fory_class = self._get_fory_class()
        self._instances_created = False

    def _get_fory_class(self):
        try:
            from pyfory.serialization import ENABLE_FORY_CYTHON_SERIALIZATION

            if ENABLE_FORY_CYTHON_SERIALIZATION:
                from pyfory.serialization import Fory as CythonFory

                return CythonFory
        except ImportError:
            pass
        return Fory

    def _get_fory(self):
        with self._lock:
            if self._pool:
                return self._pool.pop()
            self._instances_created = True
            fory = self._fory_class(**self._config)
            for callback in self._callbacks:
                callback(fory)
            return fory

    def _return_fory(self, fory):
        with self._lock:
            self._pool.append(fory)

    def _register_callback(self, callback):
        with self._lock:
            if self._instances_created:
                raise RuntimeError(
                    "Cannot register types after Fory instances have been created. Please register all types before calling serialize/deserialize."
                )
            self._callbacks.append(callback)

    def register(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        self._register_callback(lambda f: f.register(cls, type_id=type_id, namespace=namespace, typename=typename, serializer=serializer))

    def register_type(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        self._register_callback(lambda f: f.register_type(cls, type_id=type_id, namespace=namespace, typename=typename, serializer=serializer))

    def register_serializer(self, cls: type, serializer):
        self._register_callback(lambda f: f.register_serializer(cls, serializer))

    def serialize(
        self,
        obj,
        buffer: Buffer = None,
        buffer_callback=None,
        unsupported_callback=None,
    ) -> Union[Buffer, bytes]:
        fory = self._get_fory()
        try:
            return fory.serialize(obj, buffer, buffer_callback, unsupported_callback)
        finally:
            self._return_fory(fory)

    def deserialize(
        self,
        buffer: Union[Buffer, bytes],
        buffers: Iterable = None,
        unsupported_objects: Iterable = None,
    ):
        fory = self._get_fory()
        try:
            return fory.deserialize(buffer, buffers, unsupported_objects)
        finally:
            self._return_fory(fory)

    def dumps(
        self,
        obj,
        buffer: Buffer = None,
        buffer_callback=None,
        unsupported_callback=None,
    ) -> Union[Buffer, bytes]:
        return self.serialize(obj, buffer, buffer_callback, unsupported_callback)

    def loads(
        self,
        buffer: Union[Buffer, bytes],
        buffers: Iterable = None,
        unsupported_objects: Iterable = None,
    ):
        return self.deserialize(buffer, buffers, unsupported_objects)
