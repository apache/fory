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

import os
from abc import ABC, abstractmethod
from typing import Iterable, Optional, Union

_ENABLE_TYPE_REGISTRATION_FORCIBLY = os.getenv("ENABLE_TYPE_REGISTRATION_FORCIBLY", "0") in {
    "1",
    "true",
}

from pyfory.resolver import NOT_NULL_VALUE_FLAG
from pyfory.types import TypeId
from pyfory.policy import DeserializationPolicy, DEFAULT_POLICY

DYNAMIC_TYPE_ID = -1
# preserve 0 as flag for type id not set in TypeInfo`
NO_TYPE_ID = 0
# 0xffffffff means "unset" for user type id.
NO_USER_TYPE_ID = 0xFFFFFFFF
INT64_TYPE_ID = TypeId.VARINT64
FLOAT64_TYPE_ID = TypeId.FLOAT64
BOOL_TYPE_ID = TypeId.BOOL
STRING_TYPE_ID = TypeId.STRING
# `NOT_NULL_VALUE_FLAG` + `TYPE_ID << 1` in little-endian order
NOT_NULL_INT64_FLAG = NOT_NULL_VALUE_FLAG & 0b11111111 | (INT64_TYPE_ID << 8)

from pyfory.serialization import Buffer, Config, ENABLE_FORY_CYTHON_SERIALIZATION
from pyfory.context import WriteContext, ReadContext


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
    both Python native mode and xlang mode. Xlang mode handles registered schema
    objects and cross-language reference metadata; Python native mode handles the
    broader Python object graph surface, including circular Python objects.

    In Python native mode (xlang=False), Fory can serialize all Python objects
    including dataclasses, classes with custom serialization methods, and local
    functions/classes, making it a drop-in replacement for pickle.

    In xlang mode, the default, Fory serializes objects in a format that can be
    deserialized by other Fory-supported languages (Java, Go, Rust, C++, etc.).

    Examples:
        >>> import pyfory
        >>> from dataclasses import dataclass
        >>>
        >>> @dataclass
        >>> class Person:
        ...     name: str
        ...     age: pyfory.Int32
        >>>
        >>> fory = pyfory.Fory(xlang=True)
        >>> fory.register(Person, name="example.Person")
        >>> data = fory.serialize(Person("Alice", 30))
        >>> person = fory.deserialize(data)

    See Also:
        ThreadSafeFory: Thread-safe wrapper for concurrent usage
    """

    __slots__ = (
        "config",
        "xlang",
        "compatible",
        "track_ref",
        "type_resolver",
        "write_context",
        "read_context",
        "strict",
        "buffer",
        "max_depth",
        "max_container_memory_bytes",
        "field_nullable",
        "policy",
    )

    def __init__(
        self,
        xlang: bool = True,
        ref: bool = False,
        strict: bool = True,
        compatible: Optional[bool] = None,
        max_depth: int = 50,
        max_type_fields: int = 512,
        max_type_meta_bytes: int = 4096,
        max_schema_versions_per_type: int = 10,
        max_average_schema_versions_per_type: int = 3,
        max_container_memory_bytes: int = -1,
        policy: DeserializationPolicy = None,
        field_nullable: bool = False,
        meta_compressor=None,
    ):
        """
        Initialize a Fory serialization instance.

        Args:
            xlang: Enable xlang mode. When False, uses
                Python native mode supporting all Python objects (dataclasses, __reduce__,
                local functions/classes). With ref=True and strict=False, serves as a
                drop-in replacement for pickle. When True, uses the xlang wire format
                compatible with other Fory languages (Java, Go, Rust, etc), but Python-
                specific features like functions and __reduce__ methods are not supported.

            ref: Enable reference tracking for shared references and Python native-mode
                circular references. When enabled, duplicate objects are stored once.
                Disabled by default for better performance.

            strict: Require type registration before serialization (default: True). When
                disabled, unknown types can be deserialized, which may be insecure if
                malicious code exists in __new__/__init__/__eq__/__hash__ methods.
                **WARNING**: Only disable in trusted environments. When disabling strict
                mode, you should provide a custom `policy` parameter to control which types
                are allowed. We are not responsible for security risks when this option
                is disabled without proper policy controls.

            compatible: Enable schema evolution. When omitted, compatible mode is enabled.
                When enabled, supports forward/backward compatibility for dataclass field
                additions and removals.

            max_depth: Maximum nesting depth for deserialization (default: 50). Raises
                an exception if exceeded to prevent malicious deeply-nested data attacks.

            max_type_fields: Maximum accepted field count in one received struct TypeDef.

            max_type_meta_bytes: Maximum accepted body size in one received TypeDef.

            max_schema_versions_per_type: Maximum accepted remote metadata versions for one
                logical type.

            max_average_schema_versions_per_type: Average remote metadata versions allowed
                across accepted remote types.

            max_container_memory_bytes: Maximum estimated container-owned memory per root
                deserialization. `-1` means auto; positive values are explicit byte limits.

            policy: Custom deserialization policy for security checks. When provided,
                it controls which types can be deserialized, overriding the default policy.
                **Strongly recommended** when strict=False to maintain security controls.

            field_nullable: Treat all dataclass fields as nullable regardless of
                Optional annotation.

        Example:
            >>> # Python native mode with reference tracking
            >>> fory = Fory(xlang=False, ref=True)
            >>>
            >>> # Xlang mode with compatible schema evolution
            >>> fory = Fory(xlang=True)
        """
        compatible = True if compatible is None else compatible
        self.xlang = xlang
        self.track_ref = ref
        self.strict = _ENABLE_TYPE_REGISTRATION_FORCIBLY or strict
        self.policy = policy or DEFAULT_POLICY
        self.compatible = compatible
        self.field_nullable = field_nullable
        self.max_depth = max_depth
        if not isinstance(max_type_fields, int) or max_type_fields <= 0:
            raise ValueError("max_type_fields must be a positive integer")
        if not isinstance(max_type_meta_bytes, int) or max_type_meta_bytes <= 0:
            raise ValueError("max_type_meta_bytes must be a positive integer")
        if not isinstance(max_schema_versions_per_type, int) or max_schema_versions_per_type <= 0:
            raise ValueError("max_schema_versions_per_type must be a positive integer")
        if not isinstance(max_average_schema_versions_per_type, int) or max_average_schema_versions_per_type <= 0:
            raise ValueError("max_average_schema_versions_per_type must be a positive integer")
        if (
            not isinstance(max_container_memory_bytes, int)
            or (max_container_memory_bytes != -1 and max_container_memory_bytes <= 0)
            or max_container_memory_bytes > (1 << 63) - 1
        ):
            raise ValueError("max_container_memory_bytes must be -1 or a positive 63-bit integer")
        self.max_container_memory_bytes = max_container_memory_bytes
        self.config = Config(
            xlang=xlang,
            track_ref=ref,
            strict=self.strict,
            compatible=compatible,
            meta_share=compatible,
            scoped_meta_share_enabled=compatible,
            max_depth=max_depth,
            max_type_fields=max_type_fields,
            max_type_meta_bytes=max_type_meta_bytes,
            max_schema_versions_per_type=max_schema_versions_per_type,
            max_average_schema_versions_per_type=max_average_schema_versions_per_type,
            max_container_memory_bytes=max_container_memory_bytes,
            field_nullable=field_nullable,
            policy=self.policy,
            meta_compressor=meta_compressor,
        )
        from pyfory.registry import SharedRegistry, TypeResolver

        shared_registry = SharedRegistry()
        self.type_resolver = TypeResolver(
            self.config,
            shared_registry=shared_registry,
        )
        self.type_resolver.initialize()
        self.write_context = WriteContext(self.config, self.type_resolver)
        self.read_context = ReadContext(self.config, self.type_resolver)
        self.buffer = Buffer.allocate(32)

    def register(
        self,
        cls,
        *,
        type_id: int = None,
        name: str = None,
        serializer=None,
    ):
        """
        Register a type for serialization.

        This is an alias for `register_type()`. Type registration enables Fory to
        efficiently serialize and deserialize objects by pre-computing serialization
        metadata.

        For cross-language serialization, types can be matched between languages using:
        1. **type_id** (recommended): Numeric ID matching - faster and more compact
        2. **name**: String-based matching - more flexible but larger overhead

        Args:
            cls: The Python type to register
            type_id: Optional unique numeric ID for cross-language type matching.
                Using type_id provides better performance and smaller serialized size
                compared to name matching.
            name: Optional name for cross-language type matching. The last `.`
                separates the internal namespace from the type name.
            serializer: Optional custom serializer instance for this type

        Example:
            >>> # Register with type_id (recommended for performance)
            >>> fory = Fory(xlang=True)
            >>> fory.register(Person, type_id=100)
            >>>
            >>> # Register with name (more flexible)
            >>> fory.register(Person, name="com.example.Person")
            >>>
            >>> # Python native mode (no cross-language matching needed)
            >>> fory = Fory(xlang=False)
            >>> fory.register(Person)
        """
        return self.register_type(
            cls,
            type_id=type_id,
            name=name,
            serializer=serializer,
        )

    def register_type(
        self,
        cls,
        *,
        type_id: int = None,
        name: str = None,
        serializer=None,
    ):
        """
        Register a type for serialization.

        Type registration enables Fory to efficiently serialize and deserialize objects
        by pre-computing serialization metadata.

        For cross-language serialization, types can be matched between languages using:
        1. **type_id** (recommended): Numeric ID matching - faster and more compact
        2. **name**: String-based matching - more flexible but larger overhead

        Args:
            cls: The Python type to register
            type_id: Optional unique numeric ID for cross-language type matching.
                Using type_id provides better performance and smaller serialized size
                compared to name matching.
            name: Optional name for cross-language type matching. The last `.`
                separates the internal namespace from the type name.
            serializer: Optional custom serializer instance for this type

        Example:
            >>> # Register with type_id (recommended for performance)
            >>> fory = Fory(xlang=True)
            >>> fory.register_type(Person, type_id=100)
            >>>
            >>> # Register with name (more flexible)
            >>> fory.register_type(Person, name="com.example.Person")
            >>>
            >>> # Python native mode (no cross-language matching needed)
            >>> fory = Fory(xlang=False)
            >>> fory.register_type(Person)
        """
        return self.type_resolver.register_type(
            cls,
            type_id=type_id,
            name=name,
            serializer=serializer,
        )

    def register_union(
        self,
        cls,
        *,
        type_id: int = None,
        name: str = None,
        serializer=None,
    ):
        """
        Register a union type with a generated serializer.
        """
        return self.type_resolver.register_union(
            cls,
            type_id=type_id,
            name=name,
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
            >>> fory = Fory(xlang=False)
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

    def dump(self, obj, stream):
        """
        Serialize an object directly to a writable stream.

        Args:
            obj: The object to serialize
            stream: Writable stream implementing write(...)

        Notes:
            The stream must be a non-retaining sink: ``write(data)`` must
            synchronously consume ``data`` before returning. Fory may reuse or
            modify the underlying buffer after ``write`` returns, so retaining
            the passed object (or a view of it) is unsupported. If your sink
            needs retention, copy bytes inside ``write``.
        """
        try:
            self.buffer.set_writer_index(0)
            output_stream = Buffer.wrap_output_stream(stream)
            self.buffer.bind_output_stream(output_stream)
            self._serialize(
                obj,
                self.buffer,
                buffer_callback=None,
                unsupported_callback=None,
            )
            self.force_flush()
        finally:
            self.buffer.bind_output_stream(None)
            self.reset_write()

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
            >>> fory = Fory(xlang=False)
            >>> data = fory.serialize({"key": "value", "num": 42})
            >>> print(type(data))
            <class 'bytes'>
        """
        try:
            write_buffer = self._serialize(
                obj,
                buffer,
                buffer_callback=buffer_callback,
                unsupported_callback=unsupported_callback,
            )
            if write_buffer is not self.buffer:
                return write_buffer
            if write_buffer.get_output_stream() is not None:
                return write_buffer
            return write_buffer.to_bytes(0, write_buffer.get_writer_index())
        finally:
            self.reset_write()

    def _serialize(
        self,
        obj,
        buffer: Buffer = None,
        buffer_callback=None,
        unsupported_callback=None,
    ) -> Buffer:
        if buffer is None:
            self.buffer.set_writer_index(0)
            buffer = self.buffer
        write_context = self.write_context
        write_context.prepare(buffer, buffer_callback=buffer_callback, unsupported_callback=unsupported_callback)
        mask_index = buffer.get_writer_index()
        buffer.grow(1)
        buffer.set_writer_index(mask_index + 1)
        bitmap = 1 if self.xlang else 0
        if buffer_callback is not None:
            bitmap |= 2
        buffer.put_int8(mask_index, bitmap)
        write_context.write_ref(obj)
        return buffer

    def enter_flush_barrier(self):
        self.write_context.enter_flush_barrier()

    def exit_flush_barrier(self):
        self.write_context.exit_flush_barrier()

    def try_flush(self):
        self.write_context.try_flush()

    def force_flush(self):
        self.write_context.force_flush()

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
            >>> fory = Fory(xlang=False)
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
        if isinstance(buffer, bytes):
            buffer = Buffer(buffer)
        read_context = self.read_context
        reader_index = buffer.get_reader_index()
        buffer.set_reader_index(reader_index + 1)
        bitmap = buffer.get_int8(reader_index) & 0xFF
        if bitmap & ~0b11:
            raise ValueError(f"Unsupported root header bitmap 0x{bitmap:02x}")
        if bool(bitmap & 1) != self.xlang:
            raise ValueError("Header bitmap mismatch at xlang bit")
        peer_out_of_band_enabled = bool(bitmap & 2)
        if peer_out_of_band_enabled and buffers is None:
            raise ValueError("Out-of-band buffers are required by the root header")
        if not peer_out_of_band_enabled and buffers is not None:
            raise ValueError("Out-of-band buffers were provided for an in-band root payload")
        read_context.prepare(
            buffer,
            buffers=buffers,
            unsupported_objects=unsupported_objects,
            peer_out_of_band_enabled=peer_out_of_band_enabled,
            root_input_bytes=buffer.size() - reader_index,
        )
        return read_context.read_ref()

    def reset_write(self):
        """
        Reset write state after serialization.

        Clears internal write buffers and reference tracking state. This method
        is automatically called after each serialization.
        """
        self.write_context.reset()

    def reset_read(self):
        """
        Reset read state after deserialization.

        Clears internal read buffers and reference tracking state. This method
        is automatically called after each deserialization.
        """
        self.read_context.reset()

    def reset(self):
        """
        Reset both write and read state.

        Clears all per-operation state including buffers and reference tracking.
        Use this to ensure a clean state before reusing a Fory instance.
        """
        self.reset_write()
        self.reset_read()


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
        xlang (bool): Whether to enable xlang mode. Defaults to True.
        ref (bool): Whether to enable reference tracking. Defaults to False.
        strict (bool): Whether to require type registration. Defaults to True.
        compatible (bool): Whether to enable compatible mode. Defaults to compatible mode
            in both xlang and Python native mode. Set False only when every reader and
            writer always uses the same Python class schema and smaller payloads matter.
        max_depth (int): Maximum depth for deserialization. Defaults to 50.
    Example:
        >>> import pyfory
        >>> import threading
        >>> from dataclasses import dataclass
        >>>
        >>> @dataclass
        >>> class Person:
        ...     name: str
        ...     age: int
        >>>
        >>> # Create thread-safe instance
        >>> fory = pyfory.ThreadSafeFory(xlang=False)
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

    def __init__(self, fory_factory=None, **kwargs):
        import threading

        self._config = kwargs
        self._fory_factory = fory_factory
        self._callbacks = []
        self._lock = threading.Lock()
        self._pool = []
        if fory_factory is not None:
            self._fory_class = None
        elif ENABLE_FORY_CYTHON_SERIALIZATION:
            from pyfory.serialization import Fory as CythonFory

            self._fory_class = CythonFory
        else:
            self._fory_class = Fory
        self._instances_created = False

    def _get_fory(self):
        with self._lock:
            if self._pool:
                return self._pool.pop()
            self._instances_created = True
            if self._fory_factory is not None:
                fory = self._fory_factory()
            else:
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
        cls,
        *,
        type_id: int = None,
        name: str = None,
        serializer=None,
    ):
        self._register_callback(lambda f: f.register(cls, type_id=type_id, name=name, serializer=serializer))

    def register_type(
        self,
        cls,
        *,
        type_id: int = None,
        name: str = None,
        serializer=None,
    ):
        self._register_callback(lambda f: f.register_type(cls, type_id=type_id, name=name, serializer=serializer))

    def register_union(
        self,
        cls,
        *,
        type_id: int = None,
        name: str = None,
        serializer=None,
    ):
        self._register_callback(lambda f: f.register_union(cls, type_id=type_id, name=name, serializer=serializer))

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

    def dump(self, obj, stream):
        """
        Serialize an object directly to a writable stream.

        Notes:
            The stream must be a non-retaining sink: ``write(data)`` must
            synchronously consume ``data`` before returning. Fory may reuse or
            modify the underlying buffer after ``write`` returns, so retaining
            the passed object (or a view of it) is unsupported. If your sink
            needs retention, copy bytes inside ``write``.
        """
        fory = self._get_fory()
        try:
            return fory.dump(obj, stream)
        finally:
            self._return_fory(fory)
