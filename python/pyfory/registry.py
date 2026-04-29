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

import array
import dataclasses
import datetime
import decimal
import enum
import functools
import inspect
import logging
import pickle
import types
import typing
from typing import TypeVar, Union
from enum import Enum

from pyfory import ENABLE_FORY_CYTHON_SERIALIZATION
from pyfory.error import TypeUnregisteredError
from pyfory.field import extract_object_meta

from pyfory.serializer import (
    Serializer,
    Numpy1DArraySerializer,
    NDArraySerializer,
    PythonNDArraySerializer,
    PyArraySerializer,
    DynamicPyArraySerializer,
    NoneSerializer,
    BooleanSerializer,
    ByteSerializer,
    Int16Serializer,
    Int32Serializer,
    Int64Serializer,
    FixedInt32Serializer,
    FixedInt64Serializer,
    TaggedInt64Serializer,
    Uint8Serializer,
    Uint16Serializer,
    Uint32Serializer,
    VarUint32Serializer,
    Uint64Serializer,
    VarUint64Serializer,
    TaggedUint64Serializer,
    Float16Serializer,
    Float16ArraySerializer,
    Float32Serializer,
    Float64Serializer,
    BFloat16Serializer,
    BFloat16ArraySerializer,
    StringSerializer,
    DecimalSerializer,
    DateSerializer,
    TimestampSerializer,
    BytesSerializer,
    ListSerializer,
    TupleSerializer,
    MapSerializer,
    SetSerializer,
    NonExistEnum,
    EnumSerializer,
    SliceSerializer,
    StatefulSerializer,
    _DefaultPolicyStatefulSerializer,
    ReduceSerializer,
    FunctionSerializer,
    ObjectSerializer,
    _DefaultPolicyObjectSerializer,
    TypeSerializer,
    ModuleSerializer,
    MappingProxySerializer,
    MethodSerializer,
    UnsupportedSerializer,
    NativeFuncMethodSerializer,
    PickleBufferSerializer,
    UnionSerializer,
)
from pyfory.policy import DEFAULT_POLICY
from pyfory.serialization import (
    Serializer as CythonSerializer,
    bfloat16,
    bfloat16array,
    float16,
    float16array,
)
from pyfory.meta.metastring import MetaStringEncoder, MetaStringDecoder
from pyfory.meta.meta_compressor import DeflaterMetaCompressor
from pyfory.context import EncodedMetaString
from pyfory.types import (
    TypeId,
    int8,
    int16,
    int32,
    int64,
    fixed_int32,
    fixed_int64,
    tagged_int64,
    uint8,
    uint16,
    uint32,
    fixed_uint32,
    uint64,
    fixed_uint64,
    tagged_uint64,
    float32,
    float64,
    is_struct_type,
    needs_user_type_id,
)
from pyfory.type_util import (
    load_class,
    record_class_factory,
)
from pyfory._fory import (
    DYNAMIC_TYPE_ID,
    # preserve 0 as flag for type id not set in TypeInfo`
    NO_TYPE_ID,
    NO_USER_TYPE_ID,
)
from pyfory.meta.typedef import TypeDef
from pyfory.meta.typedef_decoder import decode_typedef, skip_typedef
from pyfory.meta.typedef_encoder import encode_typedef

try:
    import numpy as np
except ImportError:
    np = None

logger = logging.getLogger(__name__)
namespace_decoder = MetaStringDecoder(".", "_")
typename_decoder = MetaStringDecoder("$", "_")

_NO_REF_NUMERIC_TYPE_IDS = frozenset(
    {
        TypeId.INT8,
        TypeId.INT16,
        TypeId.INT32,
        TypeId.VARINT32,
        TypeId.INT64,
        TypeId.VARINT64,
        TypeId.TAGGED_INT64,
        TypeId.UINT8,
        TypeId.UINT16,
        TypeId.UINT32,
        TypeId.VAR_UINT32,
        TypeId.UINT64,
        TypeId.VAR_UINT64,
        TypeId.TAGGED_UINT64,
        TypeId.FLOAT16,
        TypeId.FLOAT32,
        TypeId.FLOAT64,
        TypeId.BFLOAT16,
    }
)


def _accepts_n_positional_args(factory, nargs: int) -> bool:
    try:
        signature = inspect.signature(factory)
        parameters = tuple(signature.parameters.values())
    except (TypeError, ValueError):
        try:
            signature = inspect.signature(factory.__init__)
            parameters = tuple(signature.parameters.values())[1:]
        except (AttributeError, TypeError, ValueError):
            if inspect.isclass(factory) and issubclass(factory, (Serializer, CythonSerializer)):
                return nargs == 2
            raise TypeError(f"Unable to inspect serializer constructor for {factory!r}")
    min_args = 0
    max_args = 0
    has_varargs = False
    for parameter in parameters:
        if parameter.kind in (inspect.Parameter.POSITIONAL_ONLY, inspect.Parameter.POSITIONAL_OR_KEYWORD):
            max_args += 1
            if parameter.default is inspect._empty:
                min_args += 1
        elif parameter.kind == inspect.Parameter.VAR_POSITIONAL:
            has_varargs = True
    if nargs < min_args:
        return False
    if has_varargs:
        return True
    return nargs <= max_args


def _construct_serializer(serializer_factory, type_resolver, cls):
    for nargs, args in (
        (2, (type_resolver, cls)),
        (1, (type_resolver,)),
        (0, ()),
    ):
        if _accepts_n_positional_args(serializer_factory, nargs):
            return serializer_factory(*args)
    raise TypeError(f"Unsupported serializer constructor for {serializer_factory!r}; expected `(type_resolver, cls)`, `(type_resolver)`, or `()`.")


if ENABLE_FORY_CYTHON_SERIALIZATION:
    from pyfory.serialization import TypeInfo
else:

    class TypeInfo:
        __slots__ = (
            "cls",
            "type_id",
            "user_type_id",
            "serializer",
            "namespace_bytes",
            "typename_bytes",
            "dynamic_type",
            "type_def",
        )

        def __init__(
            self,
            cls: type = None,
            type_id: int = NO_TYPE_ID,
            user_type_id: int = NO_USER_TYPE_ID,
            serializer: Serializer = None,
            namespace_bytes=None,
            typename_bytes=None,
            dynamic_type: bool = False,
            type_def: TypeDef = None,
        ):
            self.cls = cls
            self.type_id = type_id
            self.user_type_id = user_type_id
            self.serializer = serializer
            self.namespace_bytes = namespace_bytes
            self.typename_bytes = typename_bytes
            self.dynamic_type = dynamic_type
            self.type_def = type_def

        def __repr__(self):
            return f"TypeInfo(cls={self.cls}, type_id={self.type_id}, user_type_id={self.user_type_id}, serializer={self.serializer})"

        def decode_namespace(self) -> str:
            if self.namespace_bytes is None:
                return ""
            return self.namespace_bytes.decode(namespace_decoder)

        def decode_typename(self) -> str:
            if self.typename_bytes is None:
                return ""
            return self.typename_bytes.decode(typename_decoder)


class SharedRegistry:
    __slots__ = ("_metastr_to_bytes", "_encoded_metastrings")

    def __init__(self):
        self._metastr_to_bytes = {}
        self._encoded_metastrings = {}

    def get_encoded_meta_string(self, metastr) -> EncodedMetaString:
        encoded_meta_string = self._metastr_to_bytes.get(metastr)
        if encoded_meta_string is not None:
            return encoded_meta_string
        data = metastr.encoded_data
        length = len(data)
        if length == 0:
            encoded_meta_string = EncodedMetaString(b"", 0)
        elif length <= 16:
            if length <= 8:
                v1 = int.from_bytes(data, "little", signed=False)
                v2 = 0
            else:
                v1 = int.from_bytes(data[:8], "little", signed=False)
                v2 = int.from_bytes(data[8:], "little", signed=False)
            from pyfory.context import hash_small_metastring

            hashcode = hash_small_metastring(v1, v2, length, metastr.encoding.value)
            encoded_meta_string = self.get_or_create_encoded_meta_string(data, hashcode)
        else:
            from pyfory.lib.mmh3 import hash_buffer

            hashcode = hash_buffer(data, seed=47)[0]
            hashcode = (hashcode >> 8 << 8) | (metastr.encoding.value & 0xFF)
            encoded_meta_string = self.get_or_create_encoded_meta_string(data, hashcode)
        self._metastr_to_bytes[metastr] = encoded_meta_string
        return encoded_meta_string

    def get_or_create_encoded_meta_string(self, data: bytes, hashcode: int) -> EncodedMetaString:
        if len(data) == 0:
            return EncodedMetaString(b"", 0)
        key = (hashcode, data)
        encoded_meta_string = self._encoded_metastrings.get(key)
        if encoded_meta_string is None:
            encoded_meta_string = EncodedMetaString(data, hashcode)
            self._encoded_metastrings[key] = encoded_meta_string
        return encoded_meta_string


class TypeResolver:
    __slots__ = (
        "xlang",
        "track_ref",
        "strict",
        "compatible",
        "field_nullable",
        "policy",
        "max_collection_size",
        "max_binary_size",
        "shared_registry",
        "_type_id_counter",
        "_types_info",
        "_metastr_to_type",
        "_hash_to_type_info",
        "_ns_type_to_type_info",
        "_named_type_to_type_info",
        "namespace_encoder",
        "namespace_decoder",
        "typename_encoder",
        "typename_decoder",
        "meta_compressor",
        "require_registration",
        "_type_id_to_type_info",
        "_user_type_id_to_type_info",
        "_used_user_type_ids",
        "_meta_shared_type_info",
        "meta_share",
        "_internal_py_serializer_map",
        "_actual_type_resolver",
        "_allow_unregistered_typedef",
    )

    def __init__(self, config, *, shared_registry):
        self.xlang = config.xlang
        self.track_ref = config.track_ref
        self.strict = config.strict
        self.compatible = config.compatible
        self.field_nullable = config.field_nullable
        self.policy = config.policy
        self.max_collection_size = config.max_collection_size
        self.max_binary_size = config.max_binary_size
        self.shared_registry = shared_registry
        self.require_registration = self.strict
        self._metastr_to_type = dict()
        self._hash_to_type_info = dict()
        self._type_id_to_type_info = dict()
        self._user_type_id_to_type_info = dict()
        self._used_user_type_ids = set()
        self._type_id_counter = 64
        # hold objects to avoid gc, since `flat_hash_map/vector` doesn't
        # hold python reference.
        self._types_info = dict()
        self._ns_type_to_type_info = dict()
        self._named_type_to_type_info = dict()
        self.namespace_encoder = MetaStringEncoder(".", "_")
        self.namespace_decoder = MetaStringDecoder(".", "_")
        # Cache for TypeDef and TypeInfo tuples (similar to Java's classIdToDef)
        self._meta_shared_type_info = {}
        self.typename_encoder = MetaStringEncoder("$", "_")
        self.typename_decoder = MetaStringDecoder("$", "_")
        self.meta_compressor = config.meta_compressor if config.meta_compressor is not None else DeflaterMetaCompressor()
        self.meta_share = config.meta_share
        self._internal_py_serializer_map = {}
        self._actual_type_resolver = self
        self._allow_unregistered_typedef = False

    def _set_actual_resolver(self, type_resolver):
        # Cython mode injects the compiled companion before initialize() so all
        # serializers created during registration bind to the single active
        # Cython resolver instead of manufacturing per-serializer wrappers.
        self._actual_type_resolver = type_resolver

    def initialize(self):
        self._initialize_common()
        if not self.xlang:
            self._initialize_py()
        else:
            self._initialize_xlang()

    def _initialize_py(self):
        register = functools.partial(self._register_type, internal=True)
        register(tuple, serializer=TupleSerializer)
        register(slice, serializer=SliceSerializer)
        if np is not None:
            register(np.ndarray, serializer=PythonNDArraySerializer)
        register(array.array, serializer=DynamicPyArraySerializer)
        register(types.MappingProxyType, serializer=MappingProxySerializer)
        register(pickle.PickleBuffer, serializer=PickleBufferSerializer)
        if not self.require_registration:
            register(types.ModuleType, serializer=ModuleSerializer)
            self._internal_py_serializer_map = {
                ReduceSerializer: (self._stub_cls("__Reduce__"), self._next_type_id()),
                TypeSerializer: (self._stub_cls("__Type__"), self._next_type_id()),
                MethodSerializer: (self._stub_cls("__Method__"), self._next_type_id()),
                FunctionSerializer: (
                    self._stub_cls("__Function__"),
                    self._next_type_id(),
                ),
                NativeFuncMethodSerializer: (
                    self._stub_cls("__NativeFunction__"),
                    self._next_type_id(),
                ),
            }
            for serializer, (
                stub_cls,
                type_id,
            ) in self._internal_py_serializer_map.items():
                register(stub_cls, serializer=serializer, type_id=type_id)

    @staticmethod
    def _stub_cls(name: str):
        return record_class_factory(name, [])

    def _initialize_xlang(self):
        register = functools.partial(self._register_type, internal=True)
        register(array.array, type_id=DYNAMIC_TYPE_ID, serializer=DynamicPyArraySerializer)
        if np is not None:
            register(np.ndarray, type_id=DYNAMIC_TYPE_ID, serializer=NDArraySerializer)

    def _initialize_common(self):
        register = functools.partial(self._register_type, internal=True)
        register(type(None), type_id=TypeId.NONE, serializer=NoneSerializer)
        # Also register None value to map to type(None) for get_type_info(None) calls
        self._types_info[None] = self._types_info[type(None)]
        register(bool, type_id=TypeId.BOOL, serializer=BooleanSerializer)
        # Signed integers
        # Note: int32/int64 use VARINT32/VARINT64 for xlang compatibility (matches Java/Rust)
        # fixed_int32/fixed_int64 use INT32/INT64 for fixed-width encoding
        register(int8, type_id=TypeId.INT8, serializer=ByteSerializer)
        register(int16, type_id=TypeId.INT16, serializer=Int16Serializer)
        register(int32, type_id=TypeId.VARINT32, serializer=Int32Serializer)
        register(fixed_int32, type_id=TypeId.INT32, serializer=FixedInt32Serializer)
        register(int64, type_id=TypeId.VARINT64, serializer=Int64Serializer)
        register(int, type_id=TypeId.VARINT64, serializer=Int64Serializer)
        register(fixed_int64, type_id=TypeId.INT64, serializer=FixedInt64Serializer)
        register(tagged_int64, type_id=TypeId.TAGGED_INT64, serializer=TaggedInt64Serializer)
        # Unsigned integers
        register(uint8, type_id=TypeId.UINT8, serializer=Uint8Serializer)
        register(uint16, type_id=TypeId.UINT16, serializer=Uint16Serializer)
        register(uint32, type_id=TypeId.VAR_UINT32, serializer=VarUint32Serializer)
        register(fixed_uint32, type_id=TypeId.UINT32, serializer=Uint32Serializer)
        register(uint64, type_id=TypeId.VAR_UINT64, serializer=VarUint64Serializer)
        register(fixed_uint64, type_id=TypeId.UINT64, serializer=Uint64Serializer)
        register(tagged_uint64, type_id=TypeId.TAGGED_UINT64, serializer=TaggedUint64Serializer)
        # Floats
        register(
            float32,
            type_id=TypeId.FLOAT32,
            serializer=Float32Serializer,
        )
        register(
            float64,
            type_id=TypeId.FLOAT64,
            serializer=Float64Serializer,
        )
        register(float, type_id=TypeId.FLOAT64, serializer=Float64Serializer)
        register(
            float16,
            type_id=TypeId.FLOAT16,
            serializer=Float16Serializer,
        )
        register(bfloat16, type_id=TypeId.BFLOAT16, serializer=BFloat16Serializer)
        register(str, type_id=TypeId.STRING, serializer=StringSerializer)
        register(decimal.Decimal, type_id=TypeId.DECIMAL, serializer=DecimalSerializer)
        register(datetime.datetime, type_id=TypeId.TIMESTAMP, serializer=TimestampSerializer)
        register(datetime.date, type_id=TypeId.DATE, serializer=DateSerializer)
        register(bytes, type_id=TypeId.BINARY, serializer=BytesSerializer)
        register(
            float16array,
            type_id=TypeId.FLOAT16_ARRAY,
            serializer=Float16ArraySerializer,
        )
        register(
            bfloat16array,
            type_id=TypeId.BFLOAT16_ARRAY,
            serializer=BFloat16ArraySerializer,
        )
        for itemsize, ftype, typeid in PyArraySerializer.typecode_dict.values():
            register(
                ftype,
                type_id=typeid,
                serializer=PyArraySerializer(self._actual_type_resolver, ftype, typeid),
            )
        if np:
            # overwrite pyarray  with same type id.
            # if pyarray are needed, one must annotate that value with XXXArrayType
            # as a field of a struct.
            for dtype, (
                itemsize,
                format,
                ftype,
                typeid,
            ) in Numpy1DArraySerializer.dtypes_dict.items():
                typeinfo = register(
                    ftype,
                    type_id=typeid,
                    serializer=Numpy1DArraySerializer(self._actual_type_resolver, ftype, dtype),
                )
                self._type_id_to_type_info[typeid] = typeinfo
        register(list, type_id=TypeId.LIST, serializer=ListSerializer)
        register(set, type_id=TypeId.SET, serializer=SetSerializer)
        register(dict, type_id=TypeId.MAP, serializer=MapSerializer)

    def register_type(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        return self._register_type(
            cls,
            type_id=type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
        )

    def register_union(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        if serializer is None:
            raise TypeError("register_union requires a serializer")
        if serializer is not None and not isinstance(serializer, Serializer):
            serializer = _construct_serializer(
                serializer,
                self._actual_type_resolver,
                cls,
            )
        if typename is not None and type_id is not None:
            raise TypeError(f"type name {typename} and id {type_id} should not be set at the same time")
        if typename is None and type_id is None:
            type_id = self._next_type_id()
        if type_id not in {0, None}:
            user_type_id = type_id
            type_id = TypeId.TYPED_UNION
        else:
            user_type_id = NO_USER_TYPE_ID
            type_id = TypeId.NAMED_UNION
        return self.__register_type(
            cls,
            type_id=type_id,
            user_type_id=user_type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
            internal=False,
        )

    def _register_type(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        user_type_id: int = NO_USER_TYPE_ID,
        namespace: str = None,
        typename: str = None,
        serializer=None,
        internal=False,
    ):
        """Register type with given type id or typename. If typename is not None, it will be used for
        cross-language serialization."""
        if internal:
            if type_id is not None and type_id >= 0 and type_id > 0xFF:
                raise ValueError(f"Internal type id overflow: {type_id}")
        else:
            if user_type_id not in {None, NO_USER_TYPE_ID} and (user_type_id < 0 or user_type_id > 0xFFFFFFFE):
                raise ValueError(f"user_type_id must be in range [0, 0xfffffffe], got {user_type_id}")
        if serializer is not None and not isinstance(serializer, Serializer):
            serializer = _construct_serializer(
                serializer,
                self._actual_type_resolver,
                cls,
            )
        if (
            cls in self._types_info
            and type_id is None
            and typename is None
            and namespace is None
            and serializer is None
            and user_type_id in {None, NO_USER_TYPE_ID}
        ):
            return self._types_info[cls]
        n_params = len({typename, type_id, None}) - 1
        if n_params == 0 and typename is None:
            type_id = self._next_type_id()
        if n_params == 2:
            raise TypeError(f"type name {typename} and id {type_id} should not be set at the same time")
        if cls in self._types_info:
            raise TypeError(f"{cls} registered already")
        return self._register_xtype(
            cls,
            type_id=type_id,
            user_type_id=user_type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
            internal=internal,
        )

    def _register_xtype(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        user_type_id: int = NO_USER_TYPE_ID,
        namespace: str = None,
        typename: str = None,
        serializer=None,
        internal=False,
    ):
        object_meta = extract_object_meta(cls)
        evolving = True
        if object_meta is not None:
            evolving = object_meta.evolving
        if serializer is None:
            if issubclass(cls, enum.Enum):
                serializer = EnumSerializer(self._actual_type_resolver, cls)
                if type_id is None:
                    type_id = TypeId.NAMED_ENUM
                    user_type_id = NO_USER_TYPE_ID
                else:
                    user_type_id = type_id
                    type_id = TypeId.ENUM
            else:
                serializer = None
                if self.meta_share and evolving:
                    if type_id is None:
                        type_id = TypeId.NAMED_COMPATIBLE_STRUCT
                        user_type_id = NO_USER_TYPE_ID
                    else:
                        user_type_id = type_id
                        type_id = TypeId.COMPATIBLE_STRUCT
                else:
                    if type_id is None:
                        type_id = TypeId.NAMED_STRUCT
                        user_type_id = NO_USER_TYPE_ID
                    else:
                        user_type_id = type_id
                        type_id = TypeId.STRUCT
        elif not internal:
            if type_id is None:
                type_id = TypeId.NAMED_EXT
                user_type_id = NO_USER_TYPE_ID
            else:
                user_type_id = type_id
                type_id = TypeId.EXT

        return self.__register_type(
            cls,
            type_id=type_id,
            user_type_id=user_type_id,
            serializer=serializer,
            namespace=namespace,
            typename=typename,
            internal=internal,
        )

    def __register_type(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        user_type_id: int = NO_USER_TYPE_ID,
        namespace: str = None,
        typename: str = None,
        serializer: Serializer = None,
        internal: bool = False,
    ):
        dynamic_type = type_id is not None and type_id < 0
        # In metashare mode, for struct types, we want to keep serializer=None
        # so that _set_type_info will be called to create the TypeDef-based serializer
        # This applies to both types registered by name and by ID
        should_create_serializer = not internal and serializer is None and not (self.meta_share and type_id is not None and is_struct_type(type_id))

        if should_create_serializer:
            serializer = self._create_serializer(cls)
        if serializer is not None and type_id in _NO_REF_NUMERIC_TYPE_IDS:
            serializer.need_to_write_ref = False

        if typename is None:
            typeinfo = TypeInfo(cls, type_id, user_type_id, serializer, None, None, dynamic_type)
        else:
            if namespace is None:
                splits = typename.rsplit(".", 1)
                if len(splits) == 2:
                    namespace, typename = splits
                else:
                    namespace = ""  # Use empty string for consistency with lookup
            ns_metastr = self.namespace_encoder.encode(namespace or "")
            ns_meta_bytes = self.shared_registry.get_encoded_meta_string(ns_metastr)
            type_metastr = self.typename_encoder.encode(typename)
            type_meta_bytes = self.shared_registry.get_encoded_meta_string(type_metastr)
            typeinfo = TypeInfo(cls, type_id, user_type_id, serializer, ns_meta_bytes, type_meta_bytes, dynamic_type)
            self._named_type_to_type_info[(namespace, typename)] = typeinfo
            self._ns_type_to_type_info[(ns_meta_bytes, type_meta_bytes)] = typeinfo
        self._types_info[cls] = typeinfo
        if type_id is not None and type_id != 0:
            if needs_user_type_id(type_id) and user_type_id not in {None, NO_USER_TYPE_ID}:
                existing = self._user_type_id_to_type_info.get(user_type_id)
                if existing is not None and existing.cls is not cls:
                    raise TypeError(f"user_type_id {user_type_id} already registered for {existing.cls}")
            if needs_user_type_id(type_id) and user_type_id not in {None, NO_USER_TYPE_ID}:
                if user_type_id not in self._user_type_id_to_type_info or not internal:
                    self._user_type_id_to_type_info[user_type_id] = typeinfo
                self._used_user_type_ids.add(user_type_id)
            elif not TypeId.is_namespaced_type(type_id):
                if type_id not in self._type_id_to_type_info or not internal:
                    self._type_id_to_type_info[type_id] = typeinfo
        self._types_info[cls] = typeinfo
        # Create TypeDef for named non-struct types when meta_share is enabled
        if self.meta_share and type_id is not None:
            if type_id in (TypeId.NAMED_ENUM, TypeId.NAMED_EXT, TypeId.NAMED_UNION):
                type_def = encode_typedef(
                    self._actual_type_resolver,
                    cls,
                    include_fields=is_struct_type(type_id),
                )
                if type_def is not None:
                    typeinfo.type_def = type_def
        return typeinfo

    def _next_type_id(self):
        type_id = self._type_id_counter = self._type_id_counter + 1
        while type_id in self._used_user_type_ids:
            type_id = self._type_id_counter = self._type_id_counter + 1
        return type_id

    def register_serializer(self, cls: Union[type, TypeVar], serializer):
        assert isinstance(cls, (type, TypeVar)), cls
        if cls not in self._types_info:
            raise TypeUnregisteredError(f"{cls} not registered")
        typeinfo = self._types_info[cls]
        prev_type_id = typeinfo.type_id
        prev_user_type_id = typeinfo.user_type_id
        if needs_user_type_id(prev_type_id) and prev_user_type_id not in {None, NO_USER_TYPE_ID}:
            self._user_type_id_to_type_info.pop(prev_user_type_id, None)
        else:
            self._type_id_to_type_info.pop(prev_type_id, None)
        if typeinfo.serializer is not serializer:
            if typeinfo.typename_bytes is not None:
                typeinfo.type_id = TypeId.NAMED_EXT
                typeinfo.user_type_id = NO_USER_TYPE_ID
            else:
                typeinfo.type_id = TypeId.EXT
        if needs_user_type_id(typeinfo.type_id) and typeinfo.user_type_id not in {None, NO_USER_TYPE_ID}:
            self._user_type_id_to_type_info[typeinfo.user_type_id] = typeinfo
        else:
            self._type_id_to_type_info[typeinfo.type_id] = typeinfo

    def get_serializer(self, cls: type):
        """
        Returns
        -------
            Returns or create serializer for the provided type
        """
        return self.get_type_info(cls).serializer

    def get_type_info(self, cls, create=True):
        if cls is tuple and self.xlang:
            return self.get_type_info(list, create=create)
        type_info = self._types_info.get(cls)
        if type_info is not None:
            if type_info.serializer is None:
                self._set_type_info(type_info)
            return type_info
        elif not create:
            return None
        if cls is NonExistEnum:
            return self._get_nonexist_enum_type_info()
        if self.require_registration and not issubclass(cls, Enum):
            raise TypeUnregisteredError(f"{cls} not registered")
        logger.info("Type %s not registered", cls)
        serializer = self._create_serializer(cls)
        type_id = None
        if not self.xlang:
            if isinstance(serializer, EnumSerializer):
                type_id = TypeId.NAMED_ENUM
            elif isinstance(serializer, (ObjectSerializer, StatefulSerializer)):
                type_id = TypeId.NAMED_EXT
            elif self._internal_py_serializer_map.get(type(serializer)) is not None:
                type_id = self._internal_py_serializer_map.get(type(serializer))[1]
            if not self.require_registration:
                from pyfory import struct as struct_module

                data_class_types = tuple(
                    cls
                    for cls in (
                        getattr(struct_module, "DataClassSerializer", None),
                        getattr(struct_module, "DataClassStubSerializer", None),
                    )
                    if cls is not None
                )
                if data_class_types and isinstance(serializer, data_class_types):
                    type_id = TypeId.NAMED_STRUCT
        if type_id is None:
            raise TypeUnregisteredError(f"{cls} must be registered using `fory.register_type` API")
        return self.__register_type(
            cls,
            type_id=type_id,
            namespace=cls.__module__,
            typename=cls.__qualname__,
            serializer=serializer,
        )

    def _set_type_info(self, typeinfo):
        serializer_type_resolver = self._actual_type_resolver
        type_id = typeinfo.type_id
        if is_struct_type(type_id):
            from pyfory.struct import DataClassSerializer, DataClassStubSerializer

            # Set a stub serializer FIRST to break recursion for self-referencing types.
            # get_type_info() only calls _set_type_info when serializer is None,
            # so setting stub first prevents re-entry for circular type references.
            typeinfo.serializer = DataClassStubSerializer(serializer_type_resolver, typeinfo.cls)

            if self.meta_share:
                type_def = encode_typedef(serializer_type_resolver, typeinfo.cls)
                if type_def is not None:
                    typeinfo.serializer = type_def.create_serializer(serializer_type_resolver)
                    typeinfo.type_def = type_def
                else:
                    typeinfo.serializer = DataClassSerializer(serializer_type_resolver, typeinfo.cls)
            else:
                typeinfo.serializer = DataClassSerializer(serializer_type_resolver, typeinfo.cls)
        else:
            typeinfo.serializer = self._create_serializer(typeinfo.cls)

        return typeinfo

    def _create_serializer(self, cls):
        serializer_type_resolver = self._actual_type_resolver
        use_default_policy = serializer_type_resolver.policy is DEFAULT_POLICY
        # Check if it's a Union type first
        origin = typing.get_origin(cls) if hasattr(typing, "get_origin") else getattr(cls, "__origin__", None)
        if origin is typing.Union:
            # Extract alternative types from Union
            args = typing.get_args(cls) if hasattr(typing, "get_args") else getattr(cls, "__args__", ())
            # Filter out NoneType as it's handled separately via ref tracking
            alternative_types = [arg for arg in args if arg is not type(None)]
            if len(alternative_types) == 0:
                # Union with only None is equivalent to NoneType
                return NoneSerializer(serializer_type_resolver)
            elif len(alternative_types) == 1:
                # Optional[T] should use the serializer for T
                return self.get_serializer(alternative_types[0])
            else:
                # Real union with multiple alternatives
                return UnionSerializer(serializer_type_resolver, cls, alternative_types)

        for clz in cls.__mro__:
            type_info = self._types_info.get(clz)
            if type_info and type_info.serializer and type_info.serializer.support_subclass():
                serializer = type(type_info.serializer)(serializer_type_resolver, cls)
                break
        else:
            if cls is types.FunctionType:
                # Use FunctionSerializer for function types (including lambdas)
                serializer = FunctionSerializer(serializer_type_resolver, cls)
            elif dataclasses.is_dataclass(cls):
                # lazy create serializer to handle nested struct fields.
                from pyfory.struct import DataClassStubSerializer

                serializer = DataClassStubSerializer(serializer_type_resolver, cls)
            elif issubclass(cls, enum.Enum):
                serializer = EnumSerializer(serializer_type_resolver, cls)
            elif ("builtin_function_or_method" in str(cls) or "cython_function_or_method" in str(cls)) and "<locals>" not in str(cls):
                serializer = NativeFuncMethodSerializer(serializer_type_resolver, cls)
            elif cls is type(self.initialize):
                # Handle bound method objects
                serializer = MethodSerializer(serializer_type_resolver, cls)
            elif issubclass(cls, type):
                # Handle Python type objects and metaclass such as numpy._DTypeMeta(i.e. np.dtype)
                serializer = TypeSerializer(serializer_type_resolver, cls)
            elif cls is array.array:
                # Handle array.array objects with DynamicPyArraySerializer
                # Note: This will use DynamicPyArraySerializer for all array.array objects
                serializer = DynamicPyArraySerializer(serializer_type_resolver, cls)
            elif (hasattr(cls, "__reduce__") and cls.__reduce__ is not object.__reduce__) or (
                hasattr(cls, "__reduce_ex__") and cls.__reduce_ex__ is not object.__reduce_ex__
            ):
                # Use ReduceSerializer for objects that have custom __reduce__ or __reduce_ex__ methods
                # This has higher precedence than StatefulSerializer and ObjectSerializer
                # Only use it for objects with custom reduce methods, not default ones from the object
                serializer = ReduceSerializer(serializer_type_resolver, cls)
            elif hasattr(cls, "__getstate__") and hasattr(cls, "__setstate__"):
                # Use StatefulSerializer for objects that support __getstate__ and __setstate__
                serializer_cls = _DefaultPolicyStatefulSerializer if use_default_policy else StatefulSerializer
                serializer = serializer_cls(serializer_type_resolver, cls)
            elif hasattr(cls, "__dict__") or hasattr(cls, "__slots__"):
                serializer_cls = _DefaultPolicyObjectSerializer if use_default_policy else ObjectSerializer
                serializer = serializer_cls(serializer_type_resolver, cls)
            else:
                # c-extension types will go to here
                serializer = UnsupportedSerializer(serializer_type_resolver, cls)
        return serializer

    def is_registered_by_name(self, cls):
        typeinfo = self._types_info.get(cls)
        if typeinfo is None:
            return False
        return TypeId.is_namespaced_type(typeinfo.type_id)

    def is_registered_by_id(self, cls=None, type_id=None, user_type_id=NO_USER_TYPE_ID):
        if cls is not None:
            typeinfo = self._types_info.get(cls)
            if typeinfo is None:
                return False
            return not TypeId.is_namespaced_type(typeinfo.type_id)
        else:
            if type_id is None:
                return False
            if needs_user_type_id(type_id):
                if user_type_id in {None, NO_USER_TYPE_ID}:
                    return False
                return user_type_id in self._user_type_id_to_type_info
            return type_id in self._type_id_to_type_info

    def get_registered_name(self, cls):
        typeinfo = self._types_info.get(cls)
        assert typeinfo is not None, f"{cls} not registered"
        return typeinfo.decode_namespace(), typeinfo.decode_typename()

    def get_registered_id(self, cls):
        typeinfo = self._types_info.get(cls)
        assert typeinfo is not None, f"{cls} not registered"
        return typeinfo.type_id

    def get_registered_user_type_id(self, cls):
        typeinfo = self._types_info.get(cls)
        assert typeinfo is not None, f"{cls} not registered"
        return typeinfo.user_type_id

    def get_registered_type_ids(self, cls):
        typeinfo = self._types_info.get(cls)
        assert typeinfo is not None, f"{cls} not registered"
        return typeinfo.type_id, typeinfo.user_type_id

    def _load_metabytes_to_type_info(self, ns_metabytes, type_metabytes):
        typeinfo = self._ns_type_to_type_info.get((ns_metabytes, type_metabytes))
        if typeinfo is not None:
            return typeinfo
        ns = ns_metabytes.decode(self.namespace_decoder)
        typename = type_metabytes.decode(self.typename_decoder)
        # the hash computed between languages may be different.
        typeinfo = self._named_type_to_type_info.get((ns, typename))
        if typeinfo is None and typename:
            alt_typename = typename[0].upper() + typename[1:]
            typeinfo = self._named_type_to_type_info.get((ns, alt_typename))
        if typeinfo is not None:
            self._ns_type_to_type_info[(ns_metabytes, type_metabytes)] = typeinfo
            return typeinfo
        cls = load_class(ns + "#" + typename, policy=self.policy)
        typeinfo = self.get_type_info(cls)
        self._ns_type_to_type_info[(ns_metabytes, type_metabytes)] = typeinfo
        return typeinfo

    def write_type_info(self, write_context, typeinfo):
        buffer = write_context.buffer
        if typeinfo.dynamic_type:
            return
        type_id = typeinfo.type_id
        buffer.write_uint8(type_id)
        if type_id in {TypeId.ENUM, TypeId.STRUCT, TypeId.EXT, TypeId.TYPED_UNION}:
            if typeinfo.user_type_id in {None, NO_USER_TYPE_ID}:
                raise TypeError(f"user_type_id required for type_id {type_id}")
            buffer.write_var_uint32(typeinfo.user_type_id)
            return
        if type_id in {TypeId.COMPATIBLE_STRUCT, TypeId.NAMED_COMPATIBLE_STRUCT}:
            self.write_shared_type_meta(write_context, typeinfo)
            return
        if TypeId.is_namespaced_type(type_id):
            if self.meta_share:
                self.write_shared_type_meta(write_context, typeinfo)
            else:
                write_context.meta_string_writer.write_encoded_meta_string(buffer, typeinfo.namespace_bytes)
                write_context.meta_string_writer.write_encoded_meta_string(buffer, typeinfo.typename_bytes)

    def read_type_info(self, read_context):
        buffer = read_context.buffer
        type_id = buffer.read_uint8()
        if type_id in {TypeId.COMPATIBLE_STRUCT, TypeId.NAMED_COMPATIBLE_STRUCT}:
            return self.read_shared_type_meta(read_context, type_id=type_id)
        if TypeId.is_namespaced_type(type_id):
            if self.meta_share:
                return self.read_shared_type_meta(read_context, type_id=type_id)
            ns_metabytes = read_context.meta_string_reader.read_encoded_meta_string(buffer)
            type_metabytes = read_context.meta_string_reader.read_encoded_meta_string(buffer)
            typeinfo = self._ns_type_to_type_info.get((ns_metabytes, type_metabytes))
            if typeinfo is None:
                ns = ns_metabytes.decode(self.namespace_decoder)
                typename = type_metabytes.decode(self.typename_decoder)
                typeinfo = self._named_type_to_type_info.get((ns, typename))
                if typeinfo is None and typename:
                    alt_typename = typename[0].upper() + typename[1:]
                    typeinfo = self._named_type_to_type_info.get((ns, alt_typename))
                if typeinfo is not None:
                    self._ns_type_to_type_info[(ns_metabytes, type_metabytes)] = typeinfo
                    return typeinfo
                if not ns and "." in typename:
                    split_ns, split_typename = typename.rsplit(".", 1)
                    typeinfo = self._named_type_to_type_info.get((split_ns, split_typename))
                    if typeinfo is not None:
                        self._ns_type_to_type_info[(ns_metabytes, type_metabytes)] = typeinfo
                        return typeinfo
                    typename = split_typename
                    ns = split_ns
                if typename and not self.strict:
                    matches = [info for (reg_ns, reg_typename), info in self._named_type_to_type_info.items() if reg_typename == typename]
                    if len(matches) == 1:
                        typeinfo = matches[0]
                        self._ns_type_to_type_info[(ns_metabytes, type_metabytes)] = typeinfo
                        return typeinfo
                name = ns + "." + typename if ns else typename
                raise TypeUnregisteredError(f"{name} not registered")
            return typeinfo
        if type_id in {TypeId.ENUM, TypeId.STRUCT, TypeId.EXT, TypeId.TYPED_UNION}:
            user_type_id = buffer.read_var_uint32()
            return self.get_type_info_by_id(type_id, user_type_id=user_type_id)
        return self.get_type_info_by_id(type_id)

    def get_type_info_by_id(self, type_id, user_type_id=NO_USER_TYPE_ID):
        """Get typeinfo by type_id. Never returns None.

        For unknown ENUM types, returns NonExistEnum typeinfo.
        For other unknown types, raises TypeUnregisteredError.
        """
        if needs_user_type_id(type_id):
            if user_type_id in {None, NO_USER_TYPE_ID}:
                raise TypeUnregisteredError(f"type id {type_id} missing user_type_id")
            typeinfo = self._user_type_id_to_type_info.get(user_type_id)
        else:
            typeinfo = self._type_id_to_type_info.get(type_id)
        if typeinfo is not None:
            return typeinfo
        if type_id == TypeId.ENUM:
            return self._get_nonexist_enum_type_info()
        raise TypeUnregisteredError(f"type id {type_id} (user {user_type_id}) not registered")

    def _get_nonexist_enum_type_info(self):
        """Get or create TypeInfo for NonExistEnum to handle unknown enum types."""
        from pyfory.serializer import NonExistEnum, NonExistEnumSerializer

        typeinfo = self._types_info.get(NonExistEnum)
        if typeinfo is None:
            serializer = NonExistEnumSerializer(self._actual_type_resolver)
            typeinfo = TypeInfo(NonExistEnum, TypeId.ENUM, NO_USER_TYPE_ID, serializer, None, None, False)
            self._types_info[NonExistEnum] = typeinfo
        return typeinfo

    def get_type_info_by_name(self, namespace, typename):
        """Get typeinfo by namespace and typename."""
        return self._named_type_to_type_info.get((namespace, typename))

    def get_meta_compressor(self):
        return self.meta_compressor

    def write_shared_type_meta(self, write_context, typeinfo):
        """Write shared type meta information."""
        buffer = write_context.buffer
        meta_context = write_context.meta_share_context
        assert meta_context is not None, "Meta share write context must be set when compatible mode is enabled"
        type_cls = typeinfo.cls
        index = meta_context.class_map.get(type_cls)
        if index is not None:
            buffer.write_var_uint32((index << 1) | 1)
            return
        index = len(meta_context.class_map)
        meta_context.class_map[type_cls] = index
        buffer.write_var_uint32(index << 1)
        type_def = typeinfo.type_def
        if type_def is None:
            self._set_type_info(typeinfo)
            type_def = typeinfo.type_def
        buffer.write_bytes(type_def.encoded)

    def read_shared_type_meta(self, read_context, type_id=None):
        """Read shared type meta information."""
        buffer = read_context.buffer
        meta_context = read_context.meta_share_context
        assert meta_context is not None, "Meta share read context must be set when compatible mode is enabled"
        if type_id is None:
            type_id = buffer.read_uint8()
        index_marker = buffer.read_var_uint32()
        is_ref = (index_marker & 1) == 1
        index = index_marker >> 1
        if is_ref:
            return meta_context.read_type_infos[index]
        typeinfo = self._read_and_build_type_info(buffer)
        meta_context.read_type_infos.append(typeinfo)
        return typeinfo

    def _build_type_info_from_typedef(self, type_def):
        """Build TypeInfo from TypeDef using TypeDef's create_serializer method."""
        # Create serializer using TypeDef's create_serializer method
        serializer = type_def.create_serializer(self._actual_type_resolver)
        ns_metastr = self.namespace_encoder.encode(type_def.namespace or "")
        ns_meta_bytes = self.shared_registry.get_encoded_meta_string(ns_metastr)
        type_metastr = self.typename_encoder.encode(type_def.typename)
        type_meta_bytes = self.shared_registry.get_encoded_meta_string(type_metastr)
        typeinfo = TypeInfo(
            type_def.cls,
            type_def.type_id,
            type_def.user_type_id,
            serializer,
            ns_meta_bytes,
            type_meta_bytes,
            False,
            type_def,
        )
        return typeinfo

    def _read_and_build_type_info(self, buffer):
        """Read TypeDef inline from buffer and build TypeInfo.

        Used for streaming meta share where TypeDef is written inline.
        """
        # Read the header (first 8 bytes) to get the type ID
        header = buffer.read_int64()
        # Check if we already have this TypeDef cached
        type_info = self._meta_shared_type_info.get(header)
        if type_info is not None:
            skip_typedef(buffer, header)
        else:
            type_def = decode_typedef(buffer, self, header=header)
            type_info = self._build_type_info_from_typedef(type_def)
            self._meta_shared_type_info[header] = type_info
        return type_info
