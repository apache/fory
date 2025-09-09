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
import enum
import functools
import logging
import types
from typing import TypeVar, Union
from enum import Enum

from pyfory._serialization import ENABLE_FORY_CYTHON_SERIALIZATION
from pyfory import Language
from pyfory.error import TypeUnregisteredError

from pyfory.serializer import (
    Serializer,
    Numpy1DArraySerializer,
    NDArraySerializer,
    PyArraySerializer,
    DynamicPyArraySerializer,
    _PickleStub,
    PickleStrongCacheStub,
    PickleCacheStub,
    NoneSerializer,
    BooleanSerializer,
    ByteSerializer,
    Int16Serializer,
    Int32Serializer,
    Int64Serializer,
    Float32Serializer,
    Float64Serializer,
    StringSerializer,
    DateSerializer,
    TimestampSerializer,
    BytesSerializer,
    ListSerializer,
    TupleSerializer,
    MapSerializer,
    SetSerializer,
    EnumSerializer,
    SliceSerializer,
    PickleCacheSerializer,
    PickleStrongCacheSerializer,
    PickleSerializer,
    DataClassSerializer,
    StatefulSerializer,
    ReduceSerializer,
    FunctionSerializer,
    ObjectSerializer,
)
from pyfory.meta.metastring import MetaStringEncoder, MetaStringDecoder
from pyfory.meta.meta_compressor import DeflaterMetaCompressor
from pyfory.type import (
    TypeId,
    Int8Type,
    Int16Type,
    Int32Type,
    Int64Type,
    Float32Type,
    Float64Type,
    load_class,
)
from pyfory._fory import (
    DYNAMIC_TYPE_ID,
    # preserve 0 as flag for type id not set in TypeInfo`
    NO_TYPE_ID,
)

try:
    import numpy as np
except ImportError:
    np = None

logger = logging.getLogger(__name__)
namespace_decoder = MetaStringDecoder(".", "_")
typename_decoder = MetaStringDecoder("$", "_")

if ENABLE_FORY_CYTHON_SERIALIZATION:
    from pyfory._serialization import TypeInfo
else:

    class TypeInfo:
        __slots__ = (
            "cls",
            "type_id",
            "serializer",
            "namespace_bytes",
            "typename_bytes",
            "dynamic_type",
        )

        def __init__(
            self,
            cls: type = None,
            type_id: int = NO_TYPE_ID,
            serializer: Serializer = None,
            namespace_bytes=None,
            typename_bytes=None,
            dynamic_type: bool = False,
        ):
            self.cls = cls
            self.type_id = type_id
            self.serializer = serializer
            self.namespace_bytes = namespace_bytes
            self.typename_bytes = typename_bytes
            self.dynamic_type = dynamic_type

        def __repr__(self):
            return f"TypeInfo(cls={self.cls}, type_id={self.type_id}, serializer={self.serializer})"

        def decode_namespace(self) -> str:
            if self.namespace_bytes is None:
                return ""
            return self.namespace_bytes.decode(namespace_decoder)

        def decode_typename(self) -> str:
            if self.typename_bytes is None:
                return ""
            return self.typename_bytes.decode(typename_decoder)


class TypeResolver:
    __slots__ = (
        "fory",
        "_metastr_to_str",
        "_type_id_counter",
        "_types_info",
        "_hash_to_metastring",
        "_metastr_to_type",
        "_hash_to_typeinfo",
        "_dynamic_id_to_typeinfo_list",
        "_dynamic_id_to_metastr_list",
        "_dynamic_write_string_id",
        "_dynamic_written_metastr",
        "_ns_type_to_typeinfo",
        "_named_type_to_typeinfo",
        "namespace_encoder",
        "namespace_decoder",
        "typename_encoder",
        "typename_decoder",
        "meta_compressor",
        "require_registration",
        "metastring_resolver",
        "language",
        "_type_id_to_typeinfo",
    )

    def __init__(self, fory):
        self.fory = fory
        self.metastring_resolver = fory.metastring_resolver
        self.language = fory.language
        self.require_registration = fory.require_type_registration
        self._metastr_to_str = dict()
        self._metastr_to_type = dict()
        self._hash_to_metastring = dict()
        self._hash_to_typeinfo = dict()
        self._dynamic_written_metastr = []
        self._type_id_to_typeinfo = dict()
        self._type_id_counter = 64
        self._dynamic_write_string_id = 0
        # hold objects to avoid gc, since `flat_hash_map/vector` doesn't
        # hold python reference.
        self._types_info = dict()
        self._ns_type_to_typeinfo = dict()
        self._named_type_to_typeinfo = dict()
        self.namespace_encoder = MetaStringEncoder(".", "_")
        self.namespace_decoder = MetaStringDecoder(".", "_")
        self.typename_encoder = MetaStringEncoder("$", "_")
        self.typename_decoder = MetaStringDecoder("$", "_")
        self.meta_compressor = DeflaterMetaCompressor()

    def initialize(self):
        self._initialize_xlang()
        if self.fory.language == Language.PYTHON:
            self._initialize_py()

    def _initialize_py(self):
        register = functools.partial(self._register_type, internal=True)
        register(
            _PickleStub,
            type_id=PickleSerializer.PICKLE_TYPE_ID,
            serializer=PickleSerializer,
        )
        register(
            PickleStrongCacheStub,
            type_id=97,
            serializer=PickleStrongCacheSerializer(self.fory),
        )
        register(
            PickleCacheStub,
            type_id=98,
            serializer=PickleCacheSerializer(self.fory),
        )
        register(type(None), serializer=NoneSerializer)
        register(tuple, serializer=TupleSerializer)
        register(slice, serializer=SliceSerializer)

    def _initialize_xlang(self):
        register = functools.partial(self._register_type, internal=True)
        register(None, type_id=TypeId.NA, serializer=NoneSerializer)
        register(bool, type_id=TypeId.BOOL, serializer=BooleanSerializer)
        register(Int8Type, type_id=TypeId.INT8, serializer=ByteSerializer)
        register(Int16Type, type_id=TypeId.INT16, serializer=Int16Serializer)
        register(Int32Type, type_id=TypeId.INT32, serializer=Int32Serializer)
        register(Int64Type, type_id=TypeId.INT64, serializer=Int64Serializer)
        register(int, type_id=TypeId.INT64, serializer=Int64Serializer)
        register(
            Float32Type,
            type_id=TypeId.FLOAT32,
            serializer=Float32Serializer,
        )
        register(
            Float64Type,
            type_id=TypeId.FLOAT64,
            serializer=Float64Serializer,
        )
        register(float, type_id=TypeId.FLOAT64, serializer=Float64Serializer)
        register(str, type_id=TypeId.STRING, serializer=StringSerializer)
        # TODO(chaokunyang) DURATION DECIMAL
        register(datetime.datetime, type_id=TypeId.TIMESTAMP, serializer=TimestampSerializer)
        register(datetime.date, type_id=TypeId.LOCAL_DATE, serializer=DateSerializer)
        register(bytes, type_id=TypeId.BINARY, serializer=BytesSerializer)
        for itemsize, ftype, typeid in PyArraySerializer.typecode_dict.values():
            register(
                ftype,
                type_id=typeid,
                serializer=PyArraySerializer(self.fory, ftype, typeid),
            )
        register(array.array, type_id=DYNAMIC_TYPE_ID, serializer=DynamicPyArraySerializer)
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
                register(
                    ftype,
                    type_id=typeid,
                    serializer=Numpy1DArraySerializer(self.fory, ftype, dtype),
                )
            register(np.ndarray, type_id=DYNAMIC_TYPE_ID, serializer=NDArraySerializer)
        register(list, type_id=TypeId.LIST, serializer=ListSerializer)
        register(set, type_id=TypeId.SET, serializer=SetSerializer)
        register(dict, type_id=TypeId.MAP, serializer=MapSerializer)
        try:
            import pyarrow as pa
            from pyfory.format.serializer import (
                ArrowRecordBatchSerializer,
                ArrowTableSerializer,
            )

            register(
                pa.RecordBatch,
                type_id=TypeId.ARROW_RECORD_BATCH,
                serializer=ArrowRecordBatchSerializer,
            )
            register(pa.Table, type_id=TypeId.ARROW_TABLE, serializer=ArrowTableSerializer)
        except Exception:
            pass

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

    def _register_type(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
        internal=False,
    ):
        """Register type with given type id or typename. If typename is not None, it will be used for
        cross-language serialization."""
        if serializer is not None and not isinstance(serializer, Serializer):
            try:
                serializer = serializer(self.fory, cls)
            except BaseException:
                try:
                    serializer = serializer(self.fory)
                except BaseException:
                    serializer = serializer()
        n_params = len({typename, type_id, None}) - 1
        if n_params == 0 and typename is None:
            type_id = self._next_type_id()
        if n_params == 2:
            raise TypeError(f"type name {typename} and id {type_id} should not be set at the same time")
        if type_id not in {0, None}:
            # multiple type can have same tpe id
            if type_id in self._type_id_to_typeinfo and cls in self._types_info:
                raise TypeError(f"{cls} registered already")
        elif cls in self._types_info:
            raise TypeError(f"{cls} registered already")
        register_type = self._register_xtype if self.fory.language == Language.XLANG else self._register_pytype
        return register_type(
            cls,
            type_id=type_id,
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
        namespace: str = None,
        typename: str = None,
        serializer=None,
        internal=False,
    ):
        if serializer is None:
            if issubclass(cls, enum.Enum):
                serializer = EnumSerializer(self.fory, cls)
                type_id = TypeId.NAMED_ENUM if type_id is None else ((type_id << 8) + TypeId.ENUM)
            elif cls is types.FunctionType:
                # Use FunctionSerializer for function types (including lambdas)
                serializer = FunctionSerializer(self.fory, cls)
                type_id = TypeId.NAMED_EXT if type_id is None else ((type_id << 8) + TypeId.EXT)
            else:
                serializer = DataClassSerializer(self.fory, cls, xlang=True)
                type_id = TypeId.NAMED_STRUCT if type_id is None else ((type_id << 8) + TypeId.STRUCT)
        elif not internal:
            type_id = TypeId.NAMED_EXT if type_id is None else ((type_id << 8) + TypeId.EXT)
        return self.__register_type(
            cls,
            type_id=type_id,
            serializer=serializer,
            namespace=namespace,
            typename=typename,
            internal=internal,
        )

    def _register_pytype(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer: Serializer = None,
        internal: bool = False,
    ):
        # Set default type_id when None, similar to _register_xtype
        if type_id is None and typename is not None:
            type_id = self._next_type_id()
        return self.__register_type(
            cls,
            type_id=type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
            internal=internal,
        )

    def __register_type(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer: Serializer = None,
        internal: bool = False,
    ):
        dynamic_type = type_id is not None and type_id < 0
        if not internal and serializer is None:
            serializer = self._create_serializer(cls)
        if typename is None:
            typeinfo = TypeInfo(cls, type_id, serializer, None, None, dynamic_type)
        else:
            if namespace is None:
                splits = typename.rsplit(".", 1)
                if len(splits) == 2:
                    namespace, typename = splits
            ns_metastr = self.namespace_encoder.encode(namespace or "")
            ns_meta_bytes = self.metastring_resolver.get_metastr_bytes(ns_metastr)
            type_metastr = self.typename_encoder.encode(typename)
            type_meta_bytes = self.metastring_resolver.get_metastr_bytes(type_metastr)
            typeinfo = TypeInfo(cls, type_id, serializer, ns_meta_bytes, type_meta_bytes, dynamic_type)
            self._named_type_to_typeinfo[(namespace, typename)] = typeinfo
            self._ns_type_to_typeinfo[(ns_meta_bytes, type_meta_bytes)] = typeinfo
        self._types_info[cls] = typeinfo
        if type_id > 0 and (self.language == Language.PYTHON or not TypeId.is_namespaced_type(type_id)):
            if type_id not in self._type_id_to_typeinfo or not internal:
                self._type_id_to_typeinfo[type_id] = typeinfo
        self._types_info[cls] = typeinfo
        return typeinfo

    def _next_type_id(self):
        type_id = self._type_id_counter = self._type_id_counter + 1
        while type_id in self._type_id_to_typeinfo:
            type_id = self._type_id_counter = self._type_id_counter + 1
        return type_id

    def register_serializer(self, cls: Union[type, TypeVar], serializer):
        assert isinstance(cls, (type, TypeVar)), cls
        if cls not in self._types_info:
            raise TypeUnregisteredError(f"{cls} not registered")
        typeinfo = self._types_info[cls]
        if self.fory.language == Language.PYTHON:
            typeinfo.serializer = serializer
            return
        type_id = prev_type_id = typeinfo.type_id
        self._type_id_to_typeinfo.pop(prev_type_id)
        if typeinfo.serializer is not serializer:
            if typeinfo.typename_bytes is not None:
                type_id = typeinfo.type_id & 0xFFFFFF00 | TypeId.NAMED_EXT
            else:
                type_id = typeinfo.type_id & 0xFFFFFF00 | TypeId.EXT
        self._type_id_to_typeinfo[type_id] = typeinfo

    def get_serializer(self, cls: type):
        """
        Returns
        -------
            Returns or create serializer for the provided type
        """
        return self.get_typeinfo(cls).serializer

    def get_typeinfo(self, cls, create=True):
        type_info = self._types_info.get(cls)
        if type_info is not None:
            if type_info.serializer is None:
                type_info.serializer = self._create_serializer(cls)
            return type_info
        elif not create:
            return None
        if self.language != Language.PYTHON or (self.require_registration and not issubclass(cls, Enum)):
            raise TypeUnregisteredError(f"{cls} not registered")
        logger.info("Type %s not registered", cls)
        serializer = self._create_serializer(cls)
        type_id = None
        if self.language == Language.PYTHON:
            if isinstance(serializer, EnumSerializer):
                type_id = TypeId.NAMED_ENUM
            elif type(serializer) is PickleSerializer:
                type_id = PickleSerializer.PICKLE_TYPE_ID
            elif isinstance(serializer, FunctionSerializer):
                type_id = TypeId.NAMED_EXT
            elif isinstance(serializer, (ObjectSerializer, StatefulSerializer, ReduceSerializer)):
                type_id = TypeId.NAMED_EXT
            if not self.require_registration:
                if isinstance(serializer, DataClassSerializer):
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

    def _create_serializer(self, cls):
        for clz in cls.__mro__:
            type_info = self._types_info.get(clz)
            if type_info and type_info.serializer and type_info.serializer.support_subclass():
                serializer = type(type_info.serializer)(self.fory, cls)
                break
        else:
            if cls is types.FunctionType:
                # Use FunctionSerializer for function types (including lambdas)
                serializer = FunctionSerializer(self.fory, cls)
            elif dataclasses.is_dataclass(cls):
                serializer = DataClassSerializer(self.fory, cls)
            elif issubclass(cls, enum.Enum):
                serializer = EnumSerializer(self.fory, cls)
            elif (hasattr(cls, "__reduce__") and cls.__reduce__ is not object.__reduce__) or (
                hasattr(cls, "__reduce_ex__") and cls.__reduce_ex__ is not object.__reduce_ex__
            ):
                # Use ReduceSerializer for objects that have custom __reduce__ or __reduce_ex__ methods
                # This has higher precedence than StatefulSerializer and ObjectSerializer
                # Only use it for objects with custom reduce methods, not default ones from the object
                module_name = getattr(cls, "__module__", "")
                if module_name.startswith("pandas.") or module_name == "builtins" or cls.__name__ in ("type", "function", "method"):
                    # Exclude pandas, built-ins, and certain system types
                    serializer = PickleSerializer(self.fory, cls)
                else:
                    serializer = ReduceSerializer(self.fory, cls)
            elif hasattr(cls, "__getstate__") and hasattr(cls, "__setstate__"):
                # Use StatefulSerializer for objects that support __getstate__ and __setstate__
                # But exclude certain types that have incompatible state methods
                module_name = getattr(cls, "__module__", "")
                if module_name.startswith("pandas."):
                    # Pandas objects have __getstate__/__setstate__ but use incompatible pickle formats
                    serializer = PickleSerializer(self.fory, cls)
                else:
                    serializer = StatefulSerializer(self.fory, cls)
            elif (
                cls is not type
                and (hasattr(cls, "__dict__") or hasattr(cls, "__slots__"))
                and not (np and (issubclass(cls, np.dtype) or cls is type(np.dtype)))
            ):
                serializer = ObjectSerializer(self.fory, cls)
            else:
                serializer = PickleSerializer(self.fory, cls)
        return serializer

    def _load_metabytes_to_typeinfo(self, ns_metabytes, type_metabytes):
        typeinfo = self._ns_type_to_typeinfo.get((ns_metabytes, type_metabytes))
        if typeinfo is not None:
            return typeinfo
        ns = ns_metabytes.decode(self.namespace_decoder)
        typename = type_metabytes.decode(self.typename_decoder)
        # the hash computed between languages may be different.
        typeinfo = self._named_type_to_typeinfo.get((ns, typename))
        if typeinfo is not None:
            self._ns_type_to_typeinfo[(ns_metabytes, type_metabytes)] = typeinfo
            return typeinfo
        cls = load_class(ns + "#" + typename)
        typeinfo = self.get_typeinfo(cls)
        self._ns_type_to_typeinfo[(ns_metabytes, type_metabytes)] = typeinfo
        return typeinfo

    def write_typeinfo(self, buffer, typeinfo):
        if typeinfo.dynamic_type:
            return
        type_id = typeinfo.type_id
        internal_type_id = type_id & 0xFF
        buffer.write_varuint32(type_id)
        if TypeId.is_namespaced_type(internal_type_id):
            self.metastring_resolver.write_meta_string_bytes(buffer, typeinfo.namespace_bytes)
            self.metastring_resolver.write_meta_string_bytes(buffer, typeinfo.typename_bytes)

    def read_typeinfo(self, buffer):
        type_id = buffer.read_varuint32()
        internal_type_id = type_id & 0xFF
        if TypeId.is_namespaced_type(internal_type_id):
            ns_metabytes = self.metastring_resolver.read_meta_string_bytes(buffer)
            type_metabytes = self.metastring_resolver.read_meta_string_bytes(buffer)
            typeinfo = self._ns_type_to_typeinfo.get((ns_metabytes, type_metabytes))
            if typeinfo is None:
                ns = ns_metabytes.decode(self.namespace_decoder)
                typename = type_metabytes.decode(self.typename_decoder)
                typeinfo = self._named_type_to_typeinfo.get((ns, typename))
                if typeinfo is not None:
                    self._ns_type_to_typeinfo[(ns_metabytes, type_metabytes)] = typeinfo
                    return typeinfo
                # TODO(chaokunyang) generate a dynamic type and serializer
                #  when meta share is enabled.
                name = ns + "." + typename if ns else typename
                raise TypeUnregisteredError(f"{name} not registered")
            return typeinfo
        else:
            return self._type_id_to_typeinfo[type_id]

    def get_typeinfo_by_id(self, type_id):
        """Get typeinfo by type_id."""
        return self._type_id_to_typeinfo[type_id]

    def get_typeinfo_by_name(self, namespace, typename):
        """Get typeinfo by namespace and typename."""
        return self._named_type_to_typeinfo.get((namespace, typename))

    def get_meta_compressor(self):
        return self.meta_compressor

    def reset(self):
        pass

    def reset_read(self):
        pass

    def reset_write(self):
        pass
