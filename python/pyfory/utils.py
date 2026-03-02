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

import importlib
import inspect
import sys
from typing import Dict, Callable


# This method is derived from https://github.com/mars-project/mars/blob/c36c53fa22e10ef9477d9c454401a2f281375f31/mars/utils.py.
def lazy_import(
    name: str,
    package: str = None,
    globals_: Dict = None,  # pylint: disable=redefined-builtin
    locals_: Dict = None,  # pylint: disable=redefined-builtin
    rename: str = None,
    placeholder: bool = False,
):
    rename = rename or name
    prefix_name = name.split(".", 1)[0]
    globals_ = globals_ or inspect.currentframe().f_back.f_globals

    class LazyModule(object):
        def __init__(self):
            self._on_loads = []

        def __getattr__(self, item):
            if item.startswith("_pytest") or item in ("__bases__", "__test__"):
                raise AttributeError(item)

            real_mod = importlib.import_module(name, package=package)
            if rename in globals_:
                globals_[rename] = real_mod
            elif locals_ is not None:
                locals_[rename] = real_mod
            ret = getattr(real_mod, item)
            for on_load_func in self._on_loads:
                on_load_func()
            # make sure on_load hooks only executed once
            self._on_loads = []
            return ret

        def add_load_handler(self, func: Callable):
            self._on_loads.append(func)
            return func

    if importlib.util.find_spec(prefix_name) is not None:
        return LazyModule()
    elif placeholder:
        return ModulePlaceholder(prefix_name)
    else:
        return None


class ModulePlaceholder:
    def __init__(self, mod_name: str):
        self._mod_name = mod_name

    def _raises(self):
        raise AttributeError(f"{self._mod_name} is required but not installed.")

    def __getattr__(self, key):
        self._raises()

    def __call__(self, *_args, **_kwargs):
        self._raises()


is_little_endian = sys.byteorder == "little"


def get_bit(buffer, base_offset, index):
    from pyfory.serialization import get_bit as _get_bit

    return _get_bit(buffer, base_offset, index)


def set_bit(buffer, base_offset, index):
    from pyfory.serialization import set_bit as _set_bit

    return _set_bit(buffer, base_offset, index)


def clear_bit(buffer, base_offset, index):
    from pyfory.serialization import clear_bit as _clear_bit

    return _clear_bit(buffer, base_offset, index)


def set_bit_to(buffer, base_offset, index, bit_is_set):
    from pyfory.serialization import set_bit_to as _set_bit_to

    return _set_bit_to(buffer, base_offset, index, bit_is_set)


__all__ = [
    "get_bit",
    "set_bit",
    "clear_bit",
    "set_bit_to",
    "lazy_import",
    "is_little_endian",
]
