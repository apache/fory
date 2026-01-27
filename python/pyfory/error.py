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


class ForyError(Exception):
    pass


class ForyOutOfMemoryError(ForyError):
    pass


class ForyOutOfBoundError(ForyError):
    pass


class ForyKeyError(ForyError):
    pass


class ForyTypeError(ForyError):
    pass


class ForyInvalidError(ForyError):
    pass


class ForyIOError(ForyError):
    pass


class ForyUnknownError(ForyError):
    pass


class ForyEncodeError(ForyError):
    pass


class ForyInvalidDataError(ForyError):
    pass


class ForyInvalidRefError(ForyError):
    pass


class ForyUnknownEnumError(ForyError):
    pass


class ForyEncodingError(ForyError):
    pass


class ForyDepthExceedError(ForyError):
    pass


class ForyUnsupportedError(ForyError):
    pass


class ForyNotAllowedError(ForyError):
    pass


class ForyStructVersionMismatchError(ForyError):
    pass


class ForyTypeMismatchError(ForyError):
    pass


class ForyBufferOutOfBoundError(ForyError):
    pass


class TypeNotCompatibleError(ForyError):
    pass


class TypeUnregisteredError(ForyError):
    pass


class CompileError(ForyError):
    pass


_ERROR_CODE_TO_EXCEPTION = {
    1: ForyOutOfMemoryError,
    2: ForyOutOfBoundError,
    3: ForyKeyError,
    4: ForyTypeError,
    5: ForyInvalidError,
    6: ForyIOError,
    7: ForyUnknownError,
    8: ForyEncodeError,
    9: ForyInvalidDataError,
    10: ForyInvalidRefError,
    11: ForyUnknownEnumError,
    12: ForyEncodingError,
    13: ForyDepthExceedError,
    14: ForyUnsupportedError,
    15: ForyNotAllowedError,
    16: ForyStructVersionMismatchError,
    17: ForyTypeMismatchError,
    18: ForyBufferOutOfBoundError,
}


def raise_fory_error(code, message):
    if isinstance(message, bytes):
        message = message.decode("utf-8", "replace")
    exc_cls = _ERROR_CODE_TO_EXCEPTION.get(int(code), ForyError)
    raise exc_cls(message)
