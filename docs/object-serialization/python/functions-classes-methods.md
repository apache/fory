---
title: Functions, Classes, and Methods
sidebar_position: 9
id: functions-classes-methods
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

Python native mode serializes Python-specific callable and type values that are outside the xlang
type system. Use `strict=False` only for trusted payloads and apply a deserialization policy when
the accepted dynamic surface must be restricted.

## Serialize Global Functions

Capture and serialize functions defined at module level. Fory deserializes and returns the same
function object:

```python
import pyfory

fory = pyfory.Fory(xlang=False, ref=True, strict=False)

def my_global_function(x):
    return 10 * x

data = fory.dumps(my_global_function)
print(fory.loads(data)(10))  # 100
```

## Serialize Local Functions/Lambdas

Serialize functions with closures and lambda expressions. Fory captures the closure variables
automatically:

```python
import pyfory

fory = pyfory.Fory(xlang=False, ref=True, strict=False)

# Local functions with closures
def my_function():
    local_var = 10
    def local_func(x):
        return x * local_var
    return local_func

data = fory.dumps(my_function())
print(fory.loads(data)(10))  # 100

# Lambdas
data = fory.dumps(lambda x: 10 * x)
print(fory.loads(data)(10))  # 100
```

## Serialize Global Classes/Methods

Serialize class objects, instance methods, class methods, and static methods:

```python
from dataclasses import dataclass
import pyfory
fory = pyfory.Fory(xlang=False, ref=True, strict=False)

@dataclass
class Person:
    name: str
    age: int

    def f(self, x):
        return self.age * x

    @classmethod
    def g(cls, x):
        return 10 * x

    @staticmethod
    def h(x):
        return 10 * x

# Serialize global class
print(fory.loads(fory.dumps(Person))("Bob", 25))  # Person(name='Bob', age=25)

# Serialize instance method
print(fory.loads(fory.dumps(Person("Bob", 20).f))(10))  # 200

# Serialize class method
print(fory.loads(fory.dumps(Person.g))(10))  # 100

# Serialize static method
print(fory.loads(fory.dumps(Person.h))(10))  # 100
```

## Serialize Local Classes/Methods

Serialize classes defined inside functions along with their methods:

```python
from dataclasses import dataclass
import pyfory
fory = pyfory.Fory(xlang=False, ref=True, strict=False)

def create_local_class():
    class LocalClass:
        def f(self, x):
            return 10 * x

        @classmethod
        def g(cls, x):
            return 10 * x

        @staticmethod
        def h(x):
            return 10 * x
    return LocalClass

# Serialize local class
data = fory.dumps(create_local_class())
print(fory.loads(data)().f(10))  # 100

# Serialize local class instance method
data = fory.dumps(create_local_class()().f)
print(fory.loads(data)(10))  # 100

# Serialize local class method
data = fory.dumps(create_local_class().g)
print(fory.loads(data)(10))  # 100

# Serialize local class static method
data = fory.dumps(create_local_class().h)
print(fory.loads(data)(10))  # 100
```
