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

import dataclasses
import importlib
import inspect
import types

import typing
from typing import TypeVar
from abc import ABC, abstractmethod

from pyfory.annotation import ArrayMeta, RefMeta

try:
    from typing import Annotated as _Annotated
except ImportError:
    try:
        from typing_extensions import Annotated as _Annotated
    except ImportError:
        _Annotated = None

try:
    from typing_extensions import get_type_hints as _typing_extensions_get_type_hints
except ImportError:
    _typing_extensions_get_type_hints = None

try:
    from typing_extensions import get_origin as _typing_extensions_get_origin
    from typing_extensions import get_args as _typing_extensions_get_args
except ImportError:
    _typing_extensions_get_origin = None
    _typing_extensions_get_args = None


def _get_origin(type_):
    origin = None
    if _typing_extensions_get_origin is not None:
        origin = _typing_extensions_get_origin(type_)
    elif hasattr(typing, "get_origin"):
        origin = typing.get_origin(type_)
    return origin or getattr(type_, "__origin__", None)


def _get_args(type_):
    args = ()
    if _typing_extensions_get_args is not None:
        args = _typing_extensions_get_args(type_)
    elif hasattr(typing, "get_args"):
        args = typing.get_args(type_)
    return args or getattr(type_, "__args__", ())


def get_type_hints(type_):
    try:
        return typing.get_type_hints(type_, include_extras=True)
    except TypeError:
        if _typing_extensions_get_type_hints is not None:
            return _typing_extensions_get_type_hints(type_, include_extras=True)
        return typing.get_type_hints(type_)


def unwrap_ref(type_):
    origin = _get_origin(type_)
    if _Annotated is not None and origin is _Annotated:
        args = _get_args(type_)
        if args:
            base = args[0]
            other_metadata = []
            for meta in args[1:]:
                if isinstance(meta, RefMeta):
                    if other_metadata:
                        return _Annotated[(base, *other_metadata)], meta.enable
                    return base, meta.enable
                other_metadata.append(meta)
            return type_, None
    if origin is typing.Union:
        args = _get_args(type_)
        new_args = list(args)
        ref_override = None
        for i, arg in enumerate(args):
            base, override = unwrap_ref(arg)
            if override is not None:
                new_args[i] = base
                ref_override = override
        if ref_override is not None:
            return typing.Union[tuple(new_args)], ref_override
    return type_, None


def unwrap_array(type_):
    origin = _get_origin(type_)
    if _Annotated is not None and origin is _Annotated:
        args = _get_args(type_)
        for meta in args[1:]:
            if isinstance(meta, ArrayMeta):
                return meta
    return getattr(type_, "__fory_array_meta__", None)


# modified from `fluent python`
def record_class_factory(cls_name, field_names):
    """
    record_factory: create simple classes just for holding data fields

    >>> Dog = record_class_factory('Dog', 'name weight owner')
    >>> rex = Dog('Rex', 30, 'Bob')
    >>> rex
    Dog(name='Rex', weight=30, owner='Bob')
    >>> name, weight, _ = rex
    >>> name, weight
    ('Rex', 30)
    >>> "{2}'s dog weighs {1}kg".format(*rex)
    "Bob's dog weighs 30kg"
    >>> rex.weight = 32
    >>> rex
    Dog(name='Rex', weight=32, owner='Bob')
    >>> Dog.__mro__
    (<class 'utils.Dog'>, <class 'object'>)

    The factory also accepts a list or tuple of identifiers:

    >>> Dog = record_class_factory('Dog', ['name', 'weight', 'owner'])
    >>> Dog.__slots__
    ('name', 'weight', 'owner')

    """
    try:
        field_names = field_names.replace(",", " ").split()
    except AttributeError:  # no .replace or .split
        pass  # assume it's already a sequence of identifiers
    field_names = tuple(field_names)

    def __init__(self, *args, **kwargs):
        attrs = dict(zip(self.__slots__, args))
        attrs.update(kwargs)
        for name, value in attrs.items():
            setattr(self, name, value)

    def __iter__(self):
        for name in self.__slots__:
            yield getattr(self, name)

    def __eq__(self, other):
        if not isinstance(other, self.__class__):
            return False
        if not self.__slots__ == other.__slots__:
            return False
        else:
            for name in self.__slots__:
                if not getattr(self, name, None) == getattr(other, name, None):
                    return False
        return True

    def __hash__(self):
        return hash([getattr(self, name, None) for name in self.__slots__])

    def __str__(self):
        values = ", ".join("{}={!r}".format(*i) for i in zip(self.__slots__, self))
        return values

    def __repr__(self):
        values = ", ".join("{}={!r}".format(*i) for i in zip(self.__slots__, self))
        return "{}({})".format(self.__class__.__name__, values)

    def __reduce__(self):
        return self.__class__, tuple(self)

    def as_dict(self):
        """Convert record to a dictionary."""
        result = {}
        for name in self.__slots__:
            value = getattr(self, name, None)
            # Recursively convert nested records
            if hasattr(value, "as_dict"):
                value = value.as_dict()
            result[name] = value
        return result

    cls_attrs = dict(
        __slots__=field_names,
        __init__=__init__,
        __iter__=__iter__,
        __eq__=__eq__,
        __hash__=__hash__,
        __str__=__str__,
        __repr__=__repr__,
        __reduce__=__reduce__,
        as_dict=as_dict,
    )

    cls_ = type(cls_name, (object,), cls_attrs)
    # combined with __reduce__ to make it pickable
    globals()[cls_name] = cls_
    return cls_


def get_qualified_classname(obj):
    import inspect

    t = obj if inspect.isclass(obj) else type(obj)
    return t.__module__ + "." + t.__name__


def is_subclass(from_type, to_type):
    try:
        return issubclass(from_type, to_type)
    except TypeError:
        return False


class TypeVisitor(ABC):
    def visit_array(self, field_name, elem_type, carrier, types_path=None):
        raise TypeError(f"Array type with element {elem_type} is not supported")

    @abstractmethod
    def visit_list(self, field_name, elem_type, types_path=None):
        pass

    @abstractmethod
    def visit_set(self, field_name, elem_type, types_path=None):
        pass

    @abstractmethod
    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        pass

    def visit_tuple(self, field_name, elem_types, types_path=None):
        raise TypeError(f"Tuple type with elements {elem_types} is not supported")

    @abstractmethod
    def visit_customized(self, field_name, type_, types_path=None):
        pass

    @abstractmethod
    def visit_other(self, field_name, type_, types_path=None):
        pass


def infer_field_types(type_, field_nullable=False):
    type_hints = get_type_hints(type_)
    from pyfory.struct import StructTypeVisitor

    visitor = StructTypeVisitor(type_)
    result = {}
    for name, hint in sorted(type_hints.items()):
        unwrapped, _ = unwrap_optional(hint, field_nullable=field_nullable)
        result[name] = infer_field(name, unwrapped, visitor)
    return result


def is_optional_type(type_):
    origin = _get_origin(type_)
    if origin is typing.Union:
        args = _get_args(type_)
        return type(None) in args
    return False


def unwrap_optional(type_, field_nullable=False):
    if not is_optional_type(type_):
        return type_, False or field_nullable
    args = _get_args(type_)
    non_none_types = [arg for arg in args if arg is not type(None)]
    if len(non_none_types) == 1:
        return non_none_types[0], True
    return typing.Union[tuple(non_none_types)], True


def get_homogeneous_tuple_elem_type(type_or_args):
    if isinstance(type_or_args, tuple):
        args = type_or_args
    else:
        origin = _get_origin(type_or_args)
        if origin not in (tuple, typing.Tuple):
            return None
        args = _get_args(type_or_args)
    if not args or args == ((),):
        return None
    if len(args) == 2 and args[1] is Ellipsis:
        return args[0]
    first = args[0]
    if all(arg == first for arg in args[1:]):
        return first
    return None


def infer_field(field_name, type_, visitor: TypeVisitor, types_path=None):
    types_path = list(types_path or [])
    type_, _ = unwrap_ref(type_)
    types_path.append(type_)
    array_meta = unwrap_array(type_)
    if array_meta is not None:
        return visitor.visit_array(field_name, array_meta.element_type, array_meta.carrier, types_path=types_path)
    origin = _get_origin(type_) or getattr(type_, "__origin__", type_)
    origin = origin or type_
    args = _get_args(type_)
    if args:
        if origin is list or origin == typing.List:
            elem_type = args[0]
            return visitor.visit_list(field_name, elem_type, types_path=types_path)
        elif origin is set or origin == typing.Set:
            elem_type = args[0]
            return visitor.visit_set(field_name, elem_type, types_path=types_path)
        elif origin is tuple or origin == typing.Tuple:
            return visitor.visit_tuple(field_name, args, types_path=types_path)
        elif origin is dict or origin == typing.Dict:
            key_type, value_type = args
            return visitor.visit_dict(field_name, key_type, value_type, types_path=types_path)
        elif origin is typing.Union:
            # For Optional types (Union[X, None]), unwrap to get the inner type
            # This allows proper type inference for element types in collections
            unwrapped, is_optional = unwrap_optional(type_)
            if is_optional and unwrapped is not type_:
                # Recursively infer the unwrapped type
                return infer_field(field_name, unwrapped, visitor, types_path)
            # Non-Optional Union types are treated as "other" types and handled by UnionSerializer
            return visitor.visit_other(field_name, type_, types_path=types_path)
        else:
            raise TypeError(f"Collection types should be {list, dict} instead of {type_}")
    else:
        if is_function(origin) or not hasattr(origin, "__annotations__"):
            return visitor.visit_other(field_name, type_, types_path=types_path)
        else:
            return visitor.visit_customized(field_name, type_, types_path=types_path)


def is_function(func):
    return inspect.isfunction(func) or is_cython_function(func)


def is_cython_function(func):
    return getattr(func, "func_name", None) is not None


def compute_string_hash(string):
    string_bytes = string.encode("utf-8")
    hash_ = 17
    for b in string_bytes:
        hash_ = hash_ * 31 + b
        while hash_ >= 2**31 - 1:
            hash_ = hash_ // 7
    return hash_


def qualified_class_name(cls):
    if isinstance(cls, TypeVar):
        return cls.__module__ + "#" + cls.__name__
    else:
        return cls.__module__ + "#" + cls.__qualname__


def load_class(classname: str, policy=None):
    mod_name, cls_name = classname.rsplit("#", 1)
    if policy is not None:
        result = policy.validate_module(mod_name)
        if result is not None:
            if isinstance(result, str):
                mod_name = result
                mod = None
            else:
                assert isinstance(result, types.ModuleType), f"validate_module must return module, str, or None, got {type(result)}"
                mod = result
        else:
            mod = None
    else:
        mod = None
    if mod is None:
        try:
            mod = importlib.import_module(mod_name)
        except ImportError as ex:
            raise Exception(f"Can't import module {mod_name}") from ex
    try:
        classes = cls_name.split(".")
        cls = getattr(mod, classes.pop(0))
        while classes:
            cls = getattr(cls, classes.pop(0))
        if policy is not None:
            result = policy.validate_class(cls, is_local=False)
            if result is not None:
                cls = result
        return cls
    except AttributeError as ex:
        raise Exception(f"Can't import class {cls_name} from module {mod_name}") from ex


# This method is derived from https://github.com/ericvsmith/dataclasses/blob/5f6568c3468f872e8f447dc20666628387786397/dataclass_tools.py.
def dataslots(cls):
    # Need to create a new class, since we can't set __slots__
    #  after a class has been created.

    # Make sure __slots__ isn't already set.
    if "__slots__" in cls.__dict__:  # pragma: no cover
        raise TypeError(f"{cls.__name__} already specifies __slots__")

    # Create a new dict for our new class.
    cls_dict = dict(cls.__dict__)
    field_names = tuple(f.name for f in dataclasses.fields(cls))
    cls_dict["__slots__"] = field_names
    for field_name in field_names:
        # Remove our attributes, if present. They'll still be
        #  available in _MARKER.
        cls_dict.pop(field_name, None)
    # Remove __dict__ itself.
    cls_dict.pop("__dict__", None)
    # And finally create the class.
    qualname = getattr(cls, "__qualname__", None)
    cls = type(cls)(cls.__name__, cls.__bases__, cls_dict)
    if qualname is not None:
        cls.__qualname__ = qualname
    return cls
