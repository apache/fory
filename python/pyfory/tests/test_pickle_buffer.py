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

import pickle
import pytest
from pyfory import Fory


try:
    import numpy as np
except ImportError:
    np = None

try:
    import pandas as pd
except ImportError:
    pd = None


def test_pickle_buffer_serialization():
    fory = Fory(xlang=False, ref=False, strict=False)

    data = b"Hello, PickleBuffer!"
    pickle_buffer = pickle.PickleBuffer(data)

    serialized = fory.serialize(pickle_buffer)
    deserialized = fory.deserialize(serialized)

    assert isinstance(deserialized, pickle.PickleBuffer)
    assert bytes(deserialized.raw()) == data


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_numpy_out_of_band_serialization():
    fory = Fory(xlang=False, ref=False, strict=False)

    arr = np.arange(10000, dtype=np.float64)

    buffer_objects = []
    serialized = fory.serialize(arr, buffer_callback=buffer_objects.append)

    buffers = [o.getbuffer() for o in buffer_objects]

    deserialized = fory.deserialize(serialized, buffers=buffers)

    np.testing.assert_array_equal(arr, deserialized)


@pytest.mark.skipif(pd is None, reason="Requires pandas")
def test_pandas_out_of_band_serialization():
    fory = Fory(xlang=False, ref=False, strict=False)

    df = pd.DataFrame(
        {
            "a": np.arange(1000, dtype=np.float64),
            "b": np.arange(1000, dtype=np.int64),
            "c": ["text"] * 1000,
        }
    )

    buffer_objects = []
    serialized = fory.serialize(df, buffer_callback=buffer_objects.append)

    buffers = [o.getbuffer() for o in buffer_objects]

    deserialized = fory.deserialize(serialized, buffers=buffers)

    pd.testing.assert_frame_equal(df, deserialized)


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_numpy_multiple_arrays_out_of_band():
    fory = Fory(xlang=False, ref=True, strict=False)

    arr1 = np.arange(5000, dtype=np.float32)
    arr2 = np.arange(3000, dtype=np.int32)
    arr3 = np.arange(2000, dtype=np.float64)

    data = [arr1, arr2, arr3]

    buffer_objects = []
    serialized = fory.serialize(data, buffer_callback=buffer_objects.append)

    buffers = [o.getbuffer() for o in buffer_objects]

    deserialized = fory.deserialize(serialized, buffers=buffers)

    assert len(deserialized) == 3
    np.testing.assert_array_equal(arr1, deserialized[0])
    np.testing.assert_array_equal(arr2, deserialized[1])
    np.testing.assert_array_equal(arr3, deserialized[2])


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_numpy_with_mixed_types():
    fory = Fory(xlang=False, ref=True, strict=False)

    arr = np.arange(1000, dtype=np.float64)
    text = "some text"
    number = 42

    data = {"array": arr, "text": text, "number": number}

    buffer_objects = []
    serialized = fory.serialize(data, buffer_callback=buffer_objects.append)

    buffers = [o.getbuffer() for o in buffer_objects]

    deserialized = fory.deserialize(serialized, buffers=buffers)

    assert deserialized["text"] == text
    assert deserialized["number"] == number
    np.testing.assert_array_equal(arr, deserialized["array"])


@pytest.mark.skipif(pd is None or np is None, reason="Requires numpy and pandas")
def test_mixed_numpy_pandas_out_of_band():
    fory = Fory(xlang=False, ref=True, strict=False)

    arr = np.arange(500, dtype=np.float64)
    df = pd.DataFrame({"x": np.arange(500, dtype=np.int64), "y": np.arange(500, dtype=np.float32)})

    data = {"array": arr, "dataframe": df}

    buffer_objects = []
    serialized = fory.serialize(data, buffer_callback=buffer_objects.append)

    buffers = [o.getbuffer() for o in buffer_objects]

    deserialized = fory.deserialize(serialized, buffers=buffers)

    np.testing.assert_array_equal(arr, deserialized["array"])
    pd.testing.assert_frame_equal(df, deserialized["dataframe"])


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_selective_out_of_band_serialization():
    fory = Fory(xlang=False, ref=True, strict=False)

    arr1 = np.arange(1000, dtype=np.float64)
    arr2 = np.arange(1000, dtype=np.float64)

    data = [arr1, arr2]

    buffer_objects = []
    counter = 0

    def selective_buffer_callback(buffer_object):
        nonlocal counter
        counter += 1
        if counter % 2 == 0:
            buffer_objects.append(buffer_object)
            return False
        else:
            return True

    serialized = fory.serialize(data, buffer_callback=selective_buffer_callback)

    buffers = [o.getbuffer() for o in buffer_objects]

    deserialized = fory.deserialize(serialized, buffers=buffers)

    assert len(deserialized) == 2
    np.testing.assert_array_equal(arr1, deserialized[0])
    np.testing.assert_array_equal(arr2, deserialized[1])


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_buffer_object_write_to_stream():
    """Test BufferObject.write_to() with different stream types"""
    import io
    from pyfory.serializer import NDArrayBufferObject

    fory = Fory(xlang=False, ref=False, strict=False)

    arr = np.arange(100).reshape(10, 10).astype(np.float64)

    buffer_objects = []
    serialized = fory.serialize(arr, buffer_callback=buffer_objects.append)

    assert len(buffer_objects) > 0, "Should have collected out-of-band buffers"

    for buffer_obj in buffer_objects:
        assert isinstance(buffer_obj, NDArrayBufferObject), f"Expected NDArrayBufferObject, got {type(buffer_obj)}"

    for buffer_obj in buffer_objects:
        stream = io.BytesIO()
        buffer_obj.write_to(stream)
        stream.seek(0)
        data = stream.read()
        assert len(data) == buffer_obj.total_bytes()

    for buffer_obj in buffer_objects:
        mv = buffer_obj.getbuffer()
        assert isinstance(mv, memoryview)
        assert mv.nbytes == buffer_obj.total_bytes()

    buffers = [obj.getbuffer() for obj in buffer_objects]
    deserialized = fory.deserialize(serialized, buffers=buffers)
    np.testing.assert_array_equal(arr, deserialized)


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_multidimensional_numpy_array_out_of_band():
    """Test out-of-band serialization with multi-dimensional numpy arrays"""
    from pyfory.serializer import NDArrayBufferObject

    fory = Fory(xlang=False, ref=False, strict=False)

    arr_2d = np.arange(100).reshape(10, 10).astype(np.float64)
    arr_3d = np.arange(1000).reshape(10, 10, 10).astype(np.int64)
    arr_4d = np.arange(256).reshape(4, 4, 4, 4).astype(np.float32)

    data = [arr_2d, arr_3d, arr_4d]

    buffer_objects = []
    serialized = fory.serialize(data, buffer_callback=buffer_objects.append)

    assert len(buffer_objects) > 0, "Should have collected out-of-band buffers"

    for buffer_obj in buffer_objects:
        assert isinstance(buffer_obj, NDArrayBufferObject), f"Expected NDArrayBufferObject, got {type(buffer_obj)}"
        mv = buffer_obj.getbuffer()
        assert isinstance(mv, memoryview), "getbuffer() should return memoryview"
        assert len(mv) > 0, "Buffer should contain data"

    buffers = [obj.getbuffer() for obj in buffer_objects]
    deserialized = fory.deserialize(serialized, buffers=buffers)

    assert len(deserialized) == 3
    assert deserialized[0].shape == (10, 10)
    assert deserialized[1].shape == (10, 10, 10)
    assert deserialized[2].shape == (4, 4, 4, 4)

    np.testing.assert_array_equal(arr_2d, deserialized[0])
    np.testing.assert_array_equal(arr_3d, deserialized[1])
    np.testing.assert_array_equal(arr_4d, deserialized[2])


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_numpy_array_different_dtypes_out_of_band():
    """Test out-of-band serialization preserves various numpy dtypes"""
    from pyfory.serializer import NDArrayBufferObject

    fory = Fory(xlang=False, ref=False, strict=False)

    arrays = {
        "float16": np.arange(100).reshape(10, 10).astype(np.float16),
        "float32": np.arange(100).reshape(10, 10).astype(np.float32),
        "float64": np.arange(100).reshape(10, 10).astype(np.float64),
        "int8": np.arange(100).reshape(10, 10).astype(np.int8),
        "int16": np.arange(100).reshape(10, 10).astype(np.int16),
        "int32": np.arange(100).reshape(10, 10).astype(np.int32),
        "int64": np.arange(100).reshape(10, 10).astype(np.int64),
        "uint8": np.arange(100).reshape(10, 10).astype(np.uint8),
        "bool": np.array([True, False] * 50, dtype=np.bool_).reshape(10, 10),
    }

    buffer_objects = []
    serialized = fory.serialize(arrays, buffer_callback=buffer_objects.append)

    assert len(buffer_objects) > 0, "Should have collected out-of-band buffers"

    for buffer_obj in buffer_objects:
        assert isinstance(buffer_obj, NDArrayBufferObject), f"Expected NDArrayBufferObject, got {type(buffer_obj)}"
        mv = buffer_obj.getbuffer()
        assert isinstance(mv, memoryview), "getbuffer() should return memoryview"

    buffers = [obj.getbuffer() for obj in buffer_objects]
    deserialized = fory.deserialize(serialized, buffers=buffers)

    for key, original_array in arrays.items():
        np.testing.assert_array_equal(original_array, deserialized[key])
        assert original_array.dtype == deserialized[key].dtype, f"dtype mismatch for {key}: {original_array.dtype} != {deserialized[key].dtype}"


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_large_numpy_arrays_verify_buffer_collection():
    """Verify that large numpy arrays properly use out-of-band buffers"""
    from pyfory.serializer import NDArrayBufferObject

    fory = Fory(xlang=False, ref=False, strict=False)

    large_2d = np.arange(10000).reshape(100, 100).astype(np.int64)
    large_3d = np.arange(27000).reshape(30, 30, 30).astype(np.float32)
    large_4d = np.arange(10000).reshape(10, 10, 10, 10).astype(np.float64)

    arrays = [large_2d, large_3d, large_4d]

    buffer_objects = []
    serialized = fory.serialize(arrays, buffer_callback=buffer_objects.append)

    assert len(buffer_objects) > 0, "Should have collected out-of-band buffers"

    for i, buffer_obj in enumerate(buffer_objects):
        assert isinstance(buffer_obj, NDArrayBufferObject), f"Buffer {i}: Expected NDArrayBufferObject, got {type(buffer_obj)}"
        mv = buffer_obj.getbuffer()
        assert isinstance(mv, memoryview), "getbuffer() should return memoryview"
        assert len(mv) > 0, f"Buffer {i} should contain data"

    buffers = [obj.getbuffer() for obj in buffer_objects]
    deserialized = fory.deserialize(serialized, buffers=buffers)

    assert len(deserialized) == 3
    np.testing.assert_array_equal(large_2d, deserialized[0])
    np.testing.assert_array_equal(large_3d, deserialized[1])
    np.testing.assert_array_equal(large_4d, deserialized[2])
