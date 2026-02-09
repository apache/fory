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

"""Fory IDL compiler for Apache Fory."""

__version__ = "0.16.0.dev0"

from fory_compiler.ir.ast import Schema, Message, Enum, Field, EnumValue, Import
from fory_compiler.frontend.fdl import FDLFrontend
from fory_compiler.frontend.fbs import FBSFrontend
from fory_compiler.frontend.proto import ProtoFrontend

__all__ = [
    "Schema",
    "Message",
    "Enum",
    "Field",
    "EnumValue",
    "Import",
    "FDLFrontend",
    "FBSFrontend",
    "ProtoFrontend",
]
