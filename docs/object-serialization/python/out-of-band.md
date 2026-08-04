---
title: Out-of-Band Serialization
sidebar_position: 11
id: out-of-band
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Fory supports pickle5-compatible out-of-band buffer serialization for efficient zero-copy handling of large data structures.

## Overview

Out-of-band serialization separates metadata from the actual data buffers, allowing for:

- **Zero-copy transfers** when sending data over networks or IPC using `memoryview`
- **Improved performance** for large datasets
- **Pickle5 compatibility** using `pickle.PickleBuffer`
- **Flexible stream support** - write to any writable object (files, BytesIO, sockets, etc.)

## Basic Out-of-Band Serialization

```python
import pyfory
import numpy as np

fory = pyfory.Fory(xlang=False, ref=False, strict=False)

# Large numpy array
array = np.arange(10000, dtype=np.float64)

# Serialize with out-of-band buffers
buffer_objects = []
serialized_data = fory.serialize(array, buffer_callback=buffer_objects.append)

# Convert buffer objects to memoryview for zero-copy transmission
# For contiguous buffers (bytes, numpy arrays), this is zero-copy
# For non-contiguous data, a copy may be created to ensure contiguity
buffers = [obj.getbuffer() for obj in buffer_objects]

# Deserialize with out-of-band buffers (accepts memoryview, bytes, or Buffer)
deserialized_array = fory.deserialize(serialized_data, buffers=buffers)

assert np.array_equal(array, deserialized_array)
```

## Out-of-Band with Pandas DataFrames

```python
import pyfory
import pandas as pd
import numpy as np

fory = pyfory.Fory(xlang=False, ref=False, strict=False)

# Create a DataFrame with numeric columns
df = pd.DataFrame({
    'a': np.arange(1000, dtype=np.float64),
    'b': np.arange(1000, dtype=np.int64),
    'c': ['text'] * 1000
})

# Serialize with out-of-band buffers
buffer_objects = []
serialized_data = fory.serialize(df, buffer_callback=buffer_objects.append)
buffers = [obj.getbuffer() for obj in buffer_objects]

# Deserialize
deserialized_df = fory.deserialize(serialized_data, buffers=buffers)

assert df.equals(deserialized_df)
```

## Selective Out-of-Band Serialization

Control which buffers go out-of-band by providing a callback that returns `True` to keep data in-band or `False` to send it out-of-band:

```python
import pyfory
import numpy as np

fory = pyfory.Fory(xlang=False, ref=True, strict=False)

arr1 = np.arange(1000, dtype=np.float64)
arr2 = np.arange(2000, dtype=np.float64)
data = [arr1, arr2]

buffer_objects = []
counter = 0

def selective_callback(buffer_object):
    global counter
    counter += 1
    # Only send even-numbered buffers out-of-band
    if counter % 2 == 0:
        buffer_objects.append(buffer_object)
        return False  # Out-of-band
    return True  # In-band

serialized = fory.serialize(data, buffer_callback=selective_callback)
buffers = [obj.getbuffer() for obj in buffer_objects]
deserialized = fory.deserialize(serialized, buffers=buffers)
```

## Pickle5 Compatibility

Fory's out-of-band serialization is fully compatible with pickle protocol 5:

```python
import pyfory
import pickle

fory = pyfory.Fory(xlang=False, ref=False, strict=False)

# PickleBuffer objects are automatically supported
data = b"Large binary data"
pickle_buffer = pickle.PickleBuffer(data)

# Serialize with buffer callback for out-of-band handling
buffer_objects = []
serialized = fory.serialize(pickle_buffer, buffer_callback=buffer_objects.append)
buffers = [obj.getbuffer() for obj in buffer_objects]

# Deserialize with buffers
deserialized = fory.deserialize(serialized, buffers=buffers)
assert bytes(deserialized.raw()) == data
```

## Writing Buffers to Different Streams

The `BufferObject.write_to()` method accepts any writable stream object:

```python
import pyfory
import numpy as np
import io

fory = pyfory.Fory(xlang=False, ref=False, strict=False)

array = np.arange(1000, dtype=np.float64)

# Collect out-of-band buffers
buffer_objects = []
serialized = fory.serialize(array, buffer_callback=buffer_objects.append)

# Write to different stream types
for buffer_obj in buffer_objects:
    # Write to BytesIO (in-memory stream)
    bytes_stream = io.BytesIO()
    buffer_obj.write_to(bytes_stream)

    # Write to file
    with open('/tmp/buffer_data.bin', 'wb') as f:
        buffer_obj.write_to(f)

    # Get zero-copy memoryview (for contiguous buffers)
    mv = buffer_obj.getbuffer()
    assert isinstance(mv, memoryview)
```

**Note**: For contiguous memory buffers (like bytes, numpy arrays), `getbuffer()` returns a zero-copy `memoryview`. For non-contiguous data, a copy may be created to ensure contiguity.

## Related Topics

- [NumPy Integration](numpy-integration.md) - NumPy array serialization
- [Basic Serialization](core-api.md) - Standard serialization
- [Configuration](configuration.md) - Fory parameters
