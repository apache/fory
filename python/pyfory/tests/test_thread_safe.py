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

import threading
from dataclasses import dataclass


from pyfory import ThreadSafeFory


@dataclass
class Person:
    name: str
    age: int


@dataclass
class Address:
    city: str
    country: str


def test_thread_safe_fory_basic_serialization():
    fory = ThreadSafeFory()
    fory.register(Person)

    person = Person(name="Alice", age=30)
    data = fory.serialize(person)
    result = fory.deserialize(data)

    assert result.name == person.name
    assert result.age == person.age


def test_thread_safe_fory_multiple_threads():
    fory = ThreadSafeFory()
    fory.register(Person)

    results = []
    errors = []

    def serialize_deserialize(thread_id):
        try:
            person = Person(name=f"Person{thread_id}", age=20 + thread_id)
            data = fory.serialize(person)
            result = fory.deserialize(data)
            results.append((thread_id, result))
        except Exception as e:
            errors.append((thread_id, e))

    threads = []
    for i in range(10):
        t = threading.Thread(target=serialize_deserialize, args=(i,))
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    assert len(errors) == 0, f"Errors occurred: {errors}"
    assert len(results) == 10

    for thread_id, result in results:
        assert result.name == f"Person{thread_id}"
        assert result.age == 20 + thread_id


def test_thread_safe_fory_registration():
    fory = ThreadSafeFory()
    fory.register(Person, type_id=100)
    fory.register(Address, namespace="test", typename="Address")

    person = Person(name="Bob", age=25)
    data = fory.serialize(person)
    result = fory.deserialize(data)
    assert result.name == person.name

    address = Address(city="NYC", country="USA")
    data = fory.serialize(address)
    result = fory.deserialize(data)
    assert result.city == address.city


def test_thread_safe_fory_xlang_mode():
    fory = ThreadSafeFory(xlang=True, ref=True)
    fory.register(Person)

    person = Person(name="Charlie", age=35)
    data = fory.serialize(person)
    result = fory.deserialize(data)

    assert result.name == person.name
    assert result.age == person.age


def test_thread_safe_fory_dumps_loads():
    fory = ThreadSafeFory()
    fory.register(Person)

    person = Person(name="Dave", age=40)
    data = fory.dumps(person)
    result = fory.loads(data)

    assert result.name == person.name
    assert result.age == person.age


def test_thread_safe_fory_ref_tracking():
    fory = ThreadSafeFory(ref=True)
    fory.register(Person)

    person = Person(name="Eve", age=28)
    data = [person, person]
    serialized = fory.serialize(data)
    result = fory.deserialize(serialized)

    assert len(result) == 2
    assert result[0].name == person.name
    assert result[1].name == person.name


def test_thread_safe_fory_cross_thread_registration():
    fory = ThreadSafeFory()
    fory.register(Person)
    fory.register(Address)

    results = []
    errors = []

    def serialize_data(thread_id):
        try:
            person = Person(name=f"User{thread_id}", age=25)
            data = fory.serialize(person)
            result = fory.deserialize(data)
            results.append(result)
        except Exception as e:
            errors.append((thread_id, e))

    threads = []
    for i in range(5):
        t = threading.Thread(target=serialize_data, args=(i,))
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    assert len(errors) == 0
    assert len(results) == 5


def test_thread_safe_fory_register_after_use():
    fory = ThreadSafeFory()
    fory.register(Person)

    person = Person(name="Alice", age=30)
    fory.serialize(person)

    try:
        fory.register(Address)
        assert False, "Should raise RuntimeError"
    except RuntimeError as e:
        assert "Cannot register types after Fory instances have been created" in str(e)
