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

import logging
from abc import ABC, abstractmethod
from typing import Dict, List, Any, Tuple

try:
    import numpy as np
except ImportError:
    np = None

logger = logging.getLogger(__name__)


NULL_FLAG = -3
# This flag indicates that object is a not-null value.
# We don't use another byte to indicate REF, so that we can save one byte.
REF_FLAG = -2
# this flag indicates that the object is a non-null value.
NOT_NULL_VALUE_FLAG = -1
# this flag indicates that the object is a referencable and first read.
REF_VALUE_FLAG = 0


class RefResolver(ABC):
    @abstractmethod
    def write_ref_or_null(self, buffer, obj):
        """
        Write reference and tag for the obj if the obj has been written
        previously, write null/not-null tag otherwise.

        Returns
        -------
            true if no bytes need to be written for the object.
        """

    @abstractmethod
    def read_ref_or_null(self, buffer):
        """
        Returns
        -------
            `REF_FLAG` if a reference to a previously read object was
            read.
            `NULL_FLAG` if the object is null.
            `REF_VALUE_FLAG` if the object is not null and reference tracking is
             not enabled or the object is first read.
        """

    @abstractmethod
    def preserve_ref_id(self) -> int:
        """
        Preserve a reference id, which is used by `setReadObject` to set up
        reference for object that is first deserialized.

        Returns
        -------
            a reference id or -1 if reference is not enabled.
        """

    @abstractmethod
    def try_preserve_ref_id(self, buffer) -> int:
        """
        Preserve and return a `refId` which is `>=` {@link NOT_NULL_VALUE_FLAG}
        if the value is not null. If the value is referencable value, the `refId`
        will be {@link #preserveReferenceId}.

        Returns
        -------
            a reference id
        """

    @abstractmethod
    def last_preserved_ref_id(self) -> int:
        """
        Returns
        -------
            the last preserved reference id.
        """

    @abstractmethod
    def reference(self, obj):
        """
        Call this method immediately after composited object such as object
        array/map/collection/bean is created, so that circular reference can
        be deserialized correctly.
        """

    @abstractmethod
    def get_read_object(self, id_=None):
        """
        Returns
        -------
            the object for the specified id.
        """

    @abstractmethod
    def set_read_object(self, id_, obj):
        """
        Sets the id for an object that has been read.

        Parameters
        ----------
        id_: int
            The id from {@link #nextReadRefId)}.
        obj:
            the object that has been read
        """

    @abstractmethod
    def reset(self):
        pass

    @abstractmethod
    def reset_write(self):
        pass

    @abstractmethod
    def reset_read(self):
        pass


class MapRefResolver(RefResolver):
    written_objects: Dict[int, Tuple[int, Any]]  # id(obj) -> (ref_id, obj)
    read_objects: List[Any]
    read_ref_ids: List[int]

    def __init__(self):
        self.written_objects = dict()
        self.read_objects = list()
        self.read_ref_ids = list()
        self.read_object = None

    def write_ref_or_null(self, buffer, obj):
        if obj is None:
            buffer.write_int8(NULL_FLAG)
            return True
        else:
            object_id = id(obj)
            written_id = self.written_objects.get(object_id, None)
            # The obj has been written previously.
            if written_id is not None:
                buffer.write_int8(REF_FLAG)
                buffer.write_var_uint32(written_id[0])
                return True
            else:
                written_id = len(self.written_objects)
                # Hold object to avoid tmp object gc when serialize nested
                # fields/objects.
                self.written_objects[object_id] = (written_id, obj)
            buffer.write_int8(REF_VALUE_FLAG)
            return False

    def read_ref_or_null(self, buffer):
        head_flag = buffer.read_int8()
        if head_flag == REF_FLAG:
            # read reference id and get object from reference resolver
            ref_id = buffer.read_var_uint32()
            self.read_object = self.get_read_object(ref_id)
            return REF_FLAG
        else:
            self.read_object = None
            return head_flag

    def preserve_ref_id(self) -> int:
        next_read_ref_id = len(self.read_objects)
        self.read_objects.append(None)
        self.read_ref_ids.append(next_read_ref_id)
        return next_read_ref_id

    def try_preserve_ref_id(self, buffer) -> int:
        head_flag = buffer.read_int8()
        if head_flag == REF_FLAG:
            # read reference id and get object from reference resolver
            ref_id = buffer.read_var_uint32()
            self.read_object = self.get_read_object(id_=ref_id)
        else:
            self.read_object = None
            if head_flag == REF_VALUE_FLAG:
                return self.preserve_ref_id()
            # For NOT_NULL_VALUE_FLAG, push -1 to read_ref_ids so reference() knows
            # this object is not referenceable (it's a value type, not a reference type)
            self.read_ref_ids.append(-1)
        # `head_flag` except `REF_FLAG` can be used as stub reference id because we use
        # `refId >= NOT_NULL_VALUE_FLAG` to read data.
        return head_flag

    def last_preserved_ref_id(self) -> int:
        return self.read_ref_ids[-1]

    def reference(self, obj):
        ref_id = self.read_ref_ids.pop()
        # When NOT_NULL_VALUE_FLAG was read instead of REF_VALUE_FLAG,
        # -1 is pushed to read_ref_ids. This means the object is a value type
        # (not a reference type), so we skip reference tracking.
        if ref_id < 0:
            return
        self.set_read_object(ref_id, obj)

    def get_read_object(self, id_=None):
        if id_ is None:
            return self.read_object
        ref_id = id_
        size = len(self.read_objects)
        if ref_id < 0 or ref_id >= size:
            raise ValueError(f"Invalid ref id {ref_id}, current size {size}")
        obj = self.read_objects[ref_id]
        if obj is None:
            raise ValueError(f"Invalid ref id {ref_id}, current size {size}")
        return obj

    def set_read_object(self, id_, obj):
        if id_ >= 0:
            if id_ >= len(self.read_objects):
                raise RuntimeError(f"Ref id {id_} invalid")
            self.read_objects[id_] = obj

    def reset(self):
        self.reset_write()
        self.reset_read()

    def reset_write(self):
        self.written_objects.clear()

    def reset_read(self):
        self.read_objects.clear()
        self.read_ref_ids.clear()
        self.read_object = None


class NoRefResolver(RefResolver):
    def write_ref_or_null(self, buffer, obj):
        if obj is None:
            buffer.write_int8(NULL_FLAG)
            return True
        else:
            buffer.write_int8(NOT_NULL_VALUE_FLAG)
            return False

    def read_ref_or_null(self, buffer):
        return buffer.read_int8()

    def preserve_ref_id(self) -> int:
        return -1

    def try_preserve_ref_id(self, buffer) -> int:
        # `NOT_NULL_VALUE_FLAG` can be used as stub reference id because we use
        # `refId >= NOT_NULL_VALUE_FLAG` to read data.
        return buffer.read_int8()

    def last_preserved_ref_id(self) -> int:
        return -1

    def reference(self, obj):
        pass

    def get_read_object(self, id_=None):
        return None

    def set_read_object(self, id_, obj):
        pass

    def reset(self):
        pass

    def reset_write(self):
        pass

    def reset_read(self):
        pass
