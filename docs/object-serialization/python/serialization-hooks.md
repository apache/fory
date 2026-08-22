---
title: Serialization Hooks
sidebar_position: 10
id: serialization-hooks
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

Python native mode honors Python object customization protocols while writing Fory native bytes.
It does not emit Pickle wire data. Use this page when replacing pickle or cloudpickle or when a
class controls its reduction, construction, or state restoration.

## Pickle And Cloudpickle Replacement

Native mode is the Python mode to choose when the existing boundary uses `pickle` or
`cloudpickle`. It supports richer Python values than JSON and xlang mode, including Python
functions, local classes, closures, and reduction hooks.

Use xlang mode instead when the payload crosses language boundaries or the data model should be a
portable schema shared with other Fory implementations.

## Custom Python Object Hooks

Native mode respects common Python customization hooks:

```python
import pyfory

class SessionToken:
    def __init__(self, value):
        self.value = value

    def __getstate__(self):
        return {"value": self.value}

    def __setstate__(self, state):
        self.value = state["value"]

fory = pyfory.Fory(xlang=False, strict=False)
token = fory.loads(fory.dumps(SessionToken("abc")))
print(token.value)  # abc
```

Use these hooks for Python-only payloads. For xlang payloads, model the data as dataclasses with
portable field annotations instead.

## Protocol 5 buffers

Reduction hooks can expose Pickle protocol 5 buffers. The transport and buffer callback procedure
is documented in [Out-of-Band Buffers](out-of-band.md).
