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

import typing
from typing import TypeVar
from abc import ABC, abstractmethod

from pyfory.types import RefMeta


def get_type_hints(type_):
    try:
        return typing.get_type_hints(type_, include_extras=True)
    except TypeError:
        return typing.get_type_hints(type_)


def unwrap_ref(type_):
    origin = typing.get_origin(type_) if hasattr(typing, "get_origin") else getattr(type_, "__origin__", None)
    if origin is getattr(typing, "Annotated", None):
        args = typing.get_args(type_) if hasattr(typing, "get_args") else getattr(type_, "__args__", ())
        if args:
            base = args[0]
            for meta in args[1:]:
                if isinstance(meta, RefMeta):
                    return base, meta.enable
            return base, None
    if origin is typing.Union:
        args = typing.get_args(type_) if hasattr(typing, "get_args") else getattr(type_, "__args__", ())
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
    @abstractmethod
    def visit_list(self, field_name, elem_type, types_path=None):
        pass

    @abstractmethod
    def visit_set(self, field_name, elem_type, types_path=None):
        pass

    @abstractmethod
    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        pass

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
    origin = typing.get_origin(type_) if hasattr(typing, "get_origin") else getattr(type_, "__origin__", None)
    if origin is typing.Union:
        args = typing.get_args(type_) if hasattr(typing, "get_args") else getattr(type_, "__args__", ())
        return type(None) in args
    return False


def unwrap_optional(type_, field_nullable=False):
    if not is_optional_type(type_):
        return type_, False or field_nullable
    args = typing.get_args(type_) if hasattr(typing, "get_args") else getattr(type_, "__args__", ())
    non_none_types = [arg for arg in args if arg is not type(None)]
    if len(non_none_types) == 1:
        return non_none_types[0], True
    return typing.Union[tuple(non_none_types)], True


def infer_field(field_name, type_, visitor: TypeVisitor, types_path=None):
    types_path = list(types_path or [])
    type_, _ = unwrap_ref(type_)
    types_path.append(type_)
    origin = typing.get_origin(type_) if hasattr(typing, "get_origin") else getattr(type_, "__origin__", type_)
    origin = origin or type_
    args = typing.get_args(type_) if hasattr(typing, "get_args") else getattr(type_, "__args__", ())
    if args:
        if origin is list or origin == typing.List:
            elem_type = args[0]
            return visitor.visit_list(field_name, elem_type, types_path=types_path)
        elif origin is set or origin == typing.Set:
            elem_type = args[0]
            return visitor.visit_set(field_name, elem_type, types_path=types_path)
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
        if is_function(origin):
            return visitor.visit_other(field_name, type_, types_path=types_path)

        if origin in (list, dict, set):
            return visitor.visit_other(field_name, type_, types_path=types_path)
        
        if (
            inspect.isclass(origin)
            and origin.__module__ not in ("builtins", "datetime")
        ):
            return visitor.visit_customized(field_name, type_, types_path=types_path)

        return visitor.visit_other(field_name, type_, types_path=types_path)


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


def load_class(classname: str):
    mod_name, cls_name = classname.rsplit("#", 1)
    try:
        mod = importlib.import_module(mod_name)
    except ImportError as ex:
        raise Exception(f"Can't import module {mod_name}") from ex
    try:
        classes = cls_name.split(".")
        cls = getattr(mod, classes.pop(0))
        while classes:
            cls = getattr(cls, classes.pop(0))
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
