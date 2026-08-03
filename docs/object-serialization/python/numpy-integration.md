---
title: NumPy & Pandas
sidebar_position: 12
id: numpy-integration
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

Fory natively supports numpy arrays and pandas DataFrame with optimized serialization.

## NumPy Array Serialization

Large arrays use zero-copy when possible:

```python
import pyfory
import numpy as np

f = pyfory.Fory(xlang=False)

# Numpy arrays are supported natively
arrays = {
    'matrix': np.random.rand(1000, 1000),
    'vector': np.arange(10000),
    'bool_mask': np.random.choice([True, False], size=5000)
}

data = f.serialize(arrays)
result = f.deserialize(data)

# Zero-copy for compatible array types
assert np.array_equal(arrays['matrix'], result['matrix'])
```

## Pandas DataFrames

Fory can serialize Pandas DataFrames efficiently:

```python
import pyfory
import pandas as pd
import numpy as np

f = pyfory.Fory(xlang=False, ref=False, strict=False)

df = pd.DataFrame({
    'a': np.arange(1000, dtype=np.float64),
    'b': np.arange(1000, dtype=np.int64),
    'c': ['text'] * 1000
})

data = f.serialize(df)
result = f.deserialize(data)

assert df.equals(result)
```

## Zero-Copy with Out-of-Band Buffers

For maximum performance with large arrays, use out-of-band serialization:

```python
import pyfory
import numpy as np

f = pyfory.Fory(xlang=False, ref=False, strict=False)

# Large array
array = np.random.rand(10000, 1000)

# Out-of-band for zero-copy
buffer_objects = []
data = f.serialize(array, buffer_callback=buffer_objects.append)
buffers = [obj.getbuffer() for obj in buffer_objects]

result = f.deserialize(data, buffers=buffers)
assert np.array_equal(array, result)
```

## Supported Array Types

- `np.ndarray` (all dtypes)
- `np.matrix`
- Structured arrays
- Record arrays

## Related Topics

- [Out-of-Band Serialization](out-of-band.md) - Zero-copy buffers
- [Basic Serialization](core-api.md) - Standard usage
