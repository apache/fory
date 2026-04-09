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

from abc import ABC, abstractmethod

NULL_FLAG = -3
# This flag indicates that object is a not-null value.
# We don't use another byte to indicate REF, so that we can save one byte.
REF_FLAG = -2
# This flag indicates that the object is a non-null value.
NOT_NULL_VALUE_FLAG = -1
# This flag indicates that the object is a referencable and first read.
REF_VALUE_FLAG = 0


class RefWriter(ABC):
    @abstractmethod
    def write_ref_or_null(self, buffer, obj):
        """
        Write a ref/null/value header for ``obj``.

        Returns ``True`` when the payload is fully handled by the header.
        """

    @abstractmethod
    def write_ref_value_flag(self, buffer, obj):
        """
        Write a ref/value header for a non-null ``obj``.

        Returns ``True`` when the caller should continue writing the payload.
        """

    @abstractmethod
    def write_null_flag(self, buffer, obj):
        """Write only a null marker when ``obj`` is ``None``."""

    @abstractmethod
    def reset(self):
        """Reset write-side state for a new top-level operation."""


class MapRefWriter(RefWriter):
    __slots__ = ("written_objects",)

    def __init__(self):
        self.written_objects = {}

    def write_ref_or_null(self, buffer, obj):
        if obj is None:
            buffer.write_int8(NULL_FLAG)
            return True
        object_id = id(obj)
        written_id = self.written_objects.get(object_id)
        if written_id is not None:
            buffer.write_int8(REF_FLAG)
            buffer.write_var_uint32(written_id[0])
            return True
        written_id = len(self.written_objects)
        # Hold the object to keep temporary nested values alive until reset.
        self.written_objects[object_id] = (written_id, obj)
        buffer.write_int8(REF_VALUE_FLAG)
        return False

    def write_ref_value_flag(self, buffer, obj):
        assert obj is not None
        object_id = id(obj)
        written_id = self.written_objects.get(object_id)
        if written_id is not None:
            buffer.write_int8(REF_FLAG)
            buffer.write_var_uint32(written_id[0])
            return False
        written_id = len(self.written_objects)
        self.written_objects[object_id] = (written_id, obj)
        buffer.write_int8(REF_VALUE_FLAG)
        return True

    def write_null_flag(self, buffer, obj):
        if obj is None:
            buffer.write_int8(NULL_FLAG)
            return True
        return False

    def reset(self):
        self.written_objects.clear()


class NoRefWriter(RefWriter):
    __slots__ = ()

    def write_ref_or_null(self, buffer, obj):
        if obj is None:
            buffer.write_int8(NULL_FLAG)
            return True
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        return False

    def write_ref_value_flag(self, buffer, obj):
        assert obj is not None
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        return True

    def write_null_flag(self, buffer, obj):
        if obj is None:
            buffer.write_int8(NULL_FLAG)
            return True
        return False

    def reset(self):
        pass


class RefReader(ABC):
    @abstractmethod
    def read_ref_or_null(self, buffer):
        """Read a ref/null/value header and return the raw header byte."""

    @abstractmethod
    def preserve_ref_id(self, ref_id=None) -> int:
        """Preserve the next read ref id, or an explicit ``ref_id`` when provided."""

    @abstractmethod
    def try_preserve_ref_id(self, buffer) -> int:
        """
        Read a ref/value header and return either a preserved ref id or the raw header byte.
        """

    @abstractmethod
    def last_preserved_ref_id(self) -> int:
        """Return the last preserved ref id."""

    @abstractmethod
    def has_preserved_ref_id(self) -> bool:
        """Return whether there is a pending preserved ref id."""

    @abstractmethod
    def reference(self, obj):
        """Bind the last preserved ref id to ``obj``."""

    @abstractmethod
    def get_read_ref(self, id_=None):
        """Return the resolved read reference."""

    @abstractmethod
    def set_read_ref(self, id_, obj):
        """Bind ``obj`` to an already preserved read ref id."""

    @abstractmethod
    def reset(self):
        """Reset read-side state for a new top-level operation."""


class MapRefReader(RefReader):
    __slots__ = ("read_objects", "read_ref_ids", "read_object")

    def __init__(self):
        self.read_objects = []
        self.read_ref_ids = []
        self.read_object = None

    def read_ref_or_null(self, buffer):
        head_flag = buffer.read_int8()
        if head_flag == REF_FLAG:
            ref_id = buffer.read_var_uint32()
            self.read_object = self.get_read_ref(ref_id)
        else:
            self.read_object = None
        return head_flag

    def preserve_ref_id(self, ref_id=None) -> int:
        if ref_id is None:
            ref_id = len(self.read_objects)
            self.read_objects.append(None)
        self.read_ref_ids.append(ref_id)
        return ref_id

    def try_preserve_ref_id(self, buffer) -> int:
        head_flag = buffer.read_int8()
        if head_flag == REF_FLAG:
            ref_id = buffer.read_var_uint32()
            self.read_object = self.get_read_ref(ref_id)
            return head_flag
        self.read_object = None
        if head_flag == REF_VALUE_FLAG:
            return self.preserve_ref_id()
        # ``NOT_NULL_VALUE_FLAG`` means the value is not ref-tracked, but we still push a
        # sentinel so ``reference`` can be called unconditionally by callers that materialize
        # composite objects early.
        self.read_ref_ids.append(-1)
        return head_flag

    def last_preserved_ref_id(self) -> int:
        return self.read_ref_ids[-1]

    def has_preserved_ref_id(self) -> bool:
        return bool(self.read_ref_ids)

    def reference(self, obj):
        ref_id = self.read_ref_ids.pop()
        if ref_id < 0:
            return
        self.set_read_ref(ref_id, obj)

    def get_read_ref(self, id_=None):
        if id_ is None:
            return self.read_object
        if id_ < 0 or id_ >= len(self.read_objects):
            raise ValueError(f"Invalid ref id {id_}, current size {len(self.read_objects)}")
        obj = self.read_objects[id_]
        if obj is None:
            raise ValueError(f"Invalid ref id {id_}, current size {len(self.read_objects)}")
        return obj

    def set_read_ref(self, id_, obj):
        if id_ < 0:
            return
        if id_ >= len(self.read_objects):
            raise RuntimeError(f"Ref id {id_} invalid")
        self.read_objects[id_] = obj

    def reset(self):
        self.read_objects.clear()
        self.read_ref_ids.clear()
        self.read_object = None


class NoRefReader(RefReader):
    __slots__ = ()

    def read_ref_or_null(self, buffer):
        return buffer.read_int8()

    def preserve_ref_id(self, ref_id=None) -> int:
        return -1

    def try_preserve_ref_id(self, buffer) -> int:
        return buffer.read_int8()

    def last_preserved_ref_id(self) -> int:
        return -1

    def has_preserved_ref_id(self) -> bool:
        return False

    def reference(self, obj):
        pass

    def get_read_ref(self, id_=None):
        return None

    def set_read_ref(self, id_, obj):
        pass

    def reset(self):
        pass
