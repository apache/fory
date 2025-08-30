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

import zlib
from abc import ABC, abstractmethod
from typing import Optional


class MetaCompressor(ABC):
    """
    An interface used to compress class metadata such as field names and types.
    The implementation of this interface should be thread safe.
    """

    @abstractmethod
    def compress(self, data: bytes, offset: int = 0, size: Optional[int] = None) -> bytes:
        """
        Compress the given data.

        Args:
            data: The data to compress
            offset: Starting offset in the data
            size: Size of data to compress (if None, uses len(data) - offset)

        Returns:
            Compressed data as bytes
        """
        pass

    @abstractmethod
    def decompress(self, data: bytes, offset: int = 0, size: Optional[int] = None) -> bytes:
        """
        Decompress the given data.

        Args:
            data: The compressed data to decompress
            offset: Starting offset in the data
            size: Size of data to decompress (if None, uses len(data) - offset)

        Returns:
            Decompressed data as bytes
        """
        pass


class DeflaterMetaCompressor(MetaCompressor):
    """
    A meta compressor based on zlib compression algorithm (equivalent to Java's Deflater).
    This implementation is thread safe.
    """

    def compress(self, data: bytes, offset: int = 0, size: Optional[int] = None) -> bytes:
        """
        Compress the given data using zlib.

        Args:
            data: The data to compress
            offset: Starting offset in the data
            size: Size of data to compress (if None, uses len(data) - offset)

        Returns:
            Compressed data as bytes
        """
        if size is None:
            size = len(data) - offset

        if size <= 0:
            return b""

        # Use zlib.compress which is equivalent to Java's Deflater
        return zlib.compress(data[offset : offset + size])

    def decompress(self, data: bytes, offset: int = 0, size: Optional[int] = None) -> bytes:
        """
        Decompress the given data using zlib.

        Args:
            data: The compressed data to decompress
            offset: Starting offset in the data
            size: Size of data to decompress (if None, uses len(data) - offset)

        Returns:
            Decompressed data as bytes
        """
        if size is None:
            size = len(data) - offset

        if size <= 0:
            return b""

        # Use zlib.decompress which is equivalent to Java's Inflater
        return zlib.decompress(data[offset : offset + size])

    def __hash__(self) -> int:
        """Return hash code based on class type."""
        return hash(DeflaterMetaCompressor)

    def __eq__(self, other) -> bool:
        """Check equality based on class type."""
        if self is other:
            return True
        return other is not None and isinstance(other, DeflaterMetaCompressor)


def check_meta_compressor(compressor: MetaCompressor) -> MetaCompressor:
    """
    Check whether MetaCompressor implements `__eq__/__hash__` method. If not implemented,
    return TypeEqualMetaCompressor instead which compare equality by the compressor type
    for better serializer compile cache.

    Args:
        compressor: The compressor to check

    Returns:
        The compressor or a TypeEqualMetaCompressor wrapper
    """
    # Check if the compressor has custom __eq__ and __hash__ methods
    # by comparing with the default object methods
    if compressor.__class__.__eq__ == object.__eq__ or compressor.__class__.__hash__ == object.__hash__:
        return TypeEqualMetaCompressor(compressor)
    return compressor


class TypeEqualMetaCompressor(MetaCompressor):
    """
    A MetaCompressor wrapper which compare equality by the compressor type for better
    serializer compile cache.
    """

    def __init__(self, compressor: MetaCompressor):
        """
        Initialize with the wrapped compressor.

        Args:
            compressor: The compressor to wrap
        """
        self.compressor = compressor

    def compress(self, data: bytes, offset: int = 0, size: Optional[int] = None) -> bytes:
        """Delegate compression to the wrapped compressor."""
        return self.compressor.compress(data, offset, size)

    def decompress(self, data: bytes, offset: int = 0, size: Optional[int] = None) -> bytes:
        """Delegate decompression to the wrapped compressor."""
        return self.compressor.decompress(data, offset, size)

    def __eq__(self, other) -> bool:
        """Check equality based on compressor class type."""
        if other is None or not isinstance(other, TypeEqualMetaCompressor):
            return False
        return self.compressor.__class__ == other.compressor.__class__

    def __hash__(self) -> int:
        """Return hash code based on compressor class type."""
        return hash(self.compressor.__class__)
