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

import pytest
import threading
from pyfory.meta.meta_compressor import MetaCompressor, DeflaterMetaCompressor, TypeEqualMetaCompressor, check_meta_compressor


@pytest.fixture
def compressor():
    """Fixture providing a DeflaterMetaCompressor instance."""
    return DeflaterMetaCompressor()


@pytest.fixture
def test_data():
    """Fixture providing test data for compression tests."""
    return b"This is some test data that should be compressed and decompressed correctly"


class TestDeflaterMetaCompressor:
    def test_compress_decompress(self, compressor, test_data):
        """Test that compression and decompression work correctly."""
        compressed = compressor.compress(test_data)
        decompressed = compressor.decompress(compressed)

        assert decompressed == test_data
        assert len(compressed) < len(test_data)

    def test_compress_decompress_with_offset(self, compressor, test_data):
        """Test compression and decompression with offset."""
        offset = 5
        size = 20
        compressed = compressor.compress(test_data, offset, size)
        decompressed = compressor.decompress(compressed)

        assert decompressed == test_data[offset : offset + size]

    def test_compress_decompress_empty_data(self, compressor):
        """Test compression and decompression with empty data."""
        empty_data = b""
        compressed = compressor.compress(empty_data)
        decompressed = compressor.decompress(compressed)

        assert decompressed == empty_data

    def test_compress_decompress_small_data(self, compressor):
        """Test compression and decompression with small data."""
        small_data = b"abc"
        compressed = compressor.compress(small_data)
        decompressed = compressor.decompress(compressed)

        assert decompressed == small_data

    def test_equality_and_hash(self, compressor):
        """Test equality and hash methods."""
        compressor1 = DeflaterMetaCompressor()
        compressor2 = DeflaterMetaCompressor()

        # Test equality
        assert compressor1 == compressor2
        assert compressor1 == compressor1  # Same instance

        # Test hash
        assert hash(compressor1) == hash(compressor2)
        assert hash(compressor1) == hash(DeflaterMetaCompressor)

    def test_thread_safety(self):
        """Test that the compressor is thread safe (basic test)."""
        results = []
        errors = []

        def compress_worker():
            try:
                compressor = DeflaterMetaCompressor()
                for i in range(100):
                    data = f"Test data {i}".encode("utf-8")
                    compressed = compressor.compress(data)
                    decompressed = compressor.decompress(compressed)
                    if decompressed != data:
                        errors.append(f"Mismatch at iteration {i}")
                results.append("success")
            except Exception as e:
                errors.append(str(e))

        threads = []
        for _ in range(4):
            thread = threading.Thread(target=compress_worker)
            threads.append(thread)
            thread.start()

        for thread in threads:
            thread.join()

        assert len(results) == 4
        assert len(errors) == 0


class TestTypeEqualMetaCompressor:
    def test_wrapper_functionality(self):
        """Test that TypeEqualMetaCompressor correctly wraps another compressor."""
        original_compressor = DeflaterMetaCompressor()
        wrapped_compressor = TypeEqualMetaCompressor(original_compressor)

        test_data = b"Test data for wrapper"
        compressed = wrapped_compressor.compress(test_data)
        decompressed = wrapped_compressor.decompress(compressed)

        assert decompressed == test_data

    def test_equality_by_type(self):
        """Test that TypeEqualMetaCompressor compares equality by type."""
        compressor1 = DeflaterMetaCompressor()
        wrapped1 = TypeEqualMetaCompressor(compressor1)
        wrapped2 = TypeEqualMetaCompressor(compressor1)

        # Should be equal because they wrap the same type
        assert wrapped1 == wrapped2
        assert hash(wrapped1) == hash(wrapped2)


class TestCheckMetaCompressor:
    def test_check_meta_compressor_with_proper_implementation(self):
        """Test check_meta_compressor with a compressor that has proper __eq__/__hash__."""
        compressor = DeflaterMetaCompressor()
        result = check_meta_compressor(compressor)

        # Should return the original compressor since it has proper __eq__/__hash__
        assert result is compressor

    def test_check_meta_compressor_without_proper_implementation(self):
        """Test check_meta_compressor with a compressor that lacks proper __eq__/__hash__."""

        class SimpleCompressor(MetaCompressor):
            def compress(self, data, offset=0, size=None):
                return data

            def decompress(self, data, offset=0, size=None):
                return data

        compressor = SimpleCompressor()
        result = check_meta_compressor(compressor)

        # Should return a TypeEqualMetaCompressor wrapper
        assert isinstance(result, TypeEqualMetaCompressor)
        assert result.compressor is compressor
