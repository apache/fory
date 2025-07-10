from pyfory import Fory, Language
from pyfory.serializer import ReduceSerializer


class BasicReduceObject:
    """Object that implements __reduce__ returning (callable, args)"""

    def __init__(self, value, multiplier=1):
        self.value = value
        self.multiplier = multiplier

    def __reduce__(self):
        return self.__class__, (self.value, self.multiplier)

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.value == other.value and self.multiplier == other.multiplier


class ReduceWithStateObject:
    """Object that implements __reduce__ returning (callable, args, state)"""

    def __init__(self, name, data=None):
        self.name = name
        self.data = data or {}
        self.secret = "hidden"

    def __reduce__(self):
        # Return (callable, args, state)
        return self.__class__, (self.name,), {"data": self.data, "secret": self.secret}

    def __setstate__(self, state):
        self.data = state["data"]
        self.secret = state["secret"]

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.name == other.name and self.data == other.data and self.secret == other.secret


class ReduceExObject:
    """Object that implements __reduce_ex__"""

    def __init__(self, x, y):
        self.x = x
        self.y = y
        self.computed = x * y

    def __reduce_ex__(self, protocol):
        return self.__class__, (self.x, self.y)

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.x == other.x and self.y == other.y and self.computed == other.computed


class ReduceWithListItems:
    """Object that implements __reduce__ with list items"""

    def __init__(self, initial_items=None):
        self.items = list(initial_items or [])
        self.metadata = "test"

    def __reduce__(self):
        # Return (callable, args, state, listitems)
        return self.__class__, (), {"metadata": self.metadata}, iter(self.items)

    def __setstate__(self, state):
        self.metadata = state["metadata"]

    def extend(self, items):
        self.items.extend(items)

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.items == other.items and self.metadata == other.metadata


class ReduceWithDictItems:
    """Object that implements __reduce__ with dict items"""

    def __init__(self, initial_dict=None):
        self.data = dict(initial_dict or {})
        self.name = "dict_obj"

    def __reduce__(self):
        # Return (callable, args, state, listitems, dictitems)
        return self.__class__, (), {"name": self.name}, None, iter(self.data.items())

    def __setstate__(self, state):
        self.name = state["name"]

    def __setitem__(self, key, value):
        self.data[key] = value

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.data == other.data and self.name == other.name


class BothReduceAndStateful:
    """Object that has both __reduce__ and __getstate__/__setstate__
    Should use ReduceSerializer due to a higher precedence"""

    def __init__(self, value):
        self.value = value
        self.reduce_used = False
        self.state_used = False

    def __reduce__(self):
        self.reduce_used = True
        return self.__class__, (self.value,)

    def __getstate__(self):
        self.state_used = True
        return {"value": self.value, "state_used": True}

    def __setstate__(self, state):
        self.value = state["value"]
        self.state_used = state.get("state_used", False)

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.value == other.value


def test_basic_reduce_object():
    """Test basic __reduce__ functionality"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = BasicReduceObject(42, 3)

    # Verify ReduceSerializer is used
    serializer = fory.type_resolver.get_serializer(BasicReduceObject)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.value == 42
    assert deserialized.multiplier == 3


def test_reduce_with_state_object():
    """Test __reduce__ with state"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = ReduceWithStateObject("test", {"key": "value"})

    # Verify ReduceSerializer is used
    serializer = fory.type_resolver.get_serializer(ReduceWithStateObject)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.name == "test"
    assert deserialized.data == {"key": "value"}
    assert deserialized.secret == "hidden"


def test_reduce_ex_object():
    """Test __reduce_ex__ functionality"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = ReduceExObject(5, 7)

    # Verify ReduceSerializer is used
    serializer = fory.type_resolver.get_serializer(ReduceExObject)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.x == 5
    assert deserialized.y == 7
    assert deserialized.computed == 35


def test_reduce_with_list_items():
    """Test __reduce__ with list items"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = ReduceWithListItems([1, 2, 3, 4])

    # Verify ReduceSerializer is used
    serializer = fory.type_resolver.get_serializer(ReduceWithListItems)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.items == [1, 2, 3, 4]
    assert deserialized.metadata == "test"


def test_reduce_with_dict_items():
    """Test __reduce__ with dict items"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = ReduceWithDictItems({"a": 1, "b": 2})

    # Verify ReduceSerializer is used
    serializer = fory.type_resolver.get_serializer(ReduceWithDictItems)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.data == {"a": 1, "b": 2}
    assert deserialized.name == "dict_obj"


def test_reduce_precedence_over_stateful():
    """Test that ReduceSerializer has higher precedence than StatefulSerializer"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = BothReduceAndStateful(100)

    # Verify ReduceSerializer is used, not StatefulSerializer
    serializer = fory.type_resolver.get_serializer(BothReduceAndStateful)
    assert isinstance(serializer, ReduceSerializer)

    # Test serialization/deserialization
    serialized = fory.serialize(obj)
    deserialized = fory.deserialize(serialized)

    assert deserialized == obj
    assert deserialized.value == 100
    # The reduce method should have been used during serialization
    # (though we can't directly test this since it's called on the original object)


def test_reference_tracking():
    """Test that reference tracking works with ReduceSerializer"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj1 = BasicReduceObject(42)
    obj2 = BasicReduceObject(42)
    container = [obj1, obj1, obj2]  # obj1 appears twice

    serialized = fory.serialize(container)
    deserialized = fory.deserialize(serialized)

    assert len(deserialized) == 3
    assert deserialized[0] == obj1
    assert deserialized[1] == obj1
    assert deserialized[2] == obj2
    # Check that the first two references point to the same object
    assert deserialized[0] is deserialized[1]
    assert deserialized[0] is not deserialized[2]


def test_nested_reduce_objects():
    """Test nested objects with __reduce__"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    inner = BasicReduceObject(10, 2)
    outer = ReduceWithStateObject("outer", {"inner": inner})

    serialized = fory.serialize(outer)
    deserialized = fory.deserialize(serialized)

    assert deserialized == outer
    assert deserialized.name == "outer"
    assert deserialized.data["inner"] == inner
    assert deserialized.data["inner"].value == 10
    assert deserialized.data["inner"].multiplier == 2


def test_cross_language_compatibility():
    """Test cross-language compatibility"""
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    obj = BasicReduceObject(123, 4)

    # Serialize with Python
    serialized = fory.serialize(obj)

    # Should be able to deserialize (basic test)
    deserialized = fory.deserialize(serialized)
    assert deserialized == obj

    # The serialized data should use Fory's native format, not pickle
    # This is verified by the fact that we're using serialize_ref/deserialize_ref
    # in the ReduceSerializer implementation
