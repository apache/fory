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

import warnings

try:
    from pyfory.format._format import (  # noqa: F401
        create_row_encoder,
        RowData,
        # Schema types
        DataType,
        ListType,
        MapType,
        StructType,
        Field,
        Schema,
        TypeId,
        # Factory functions
        boolean,
        int8,
        int16,
        int32,
        int64,
        float16,
        float32,
        float64,
        utf8,
        string,
        binary,
        duration,
        timestamp,
        date32,
        decimal,
        list_,
        map_,
        struct,
        field,
        schema,
        get_byte_width,
    )
    from pyfory.format.infer import (  # noqa: F401
        get_cls_by_schema,
        remove_schema,
        reset,
        infer_schema,
        infer_data_type,
        get_type_id,
        compute_schema_hash,
        from_arrow_schema,
        to_arrow_schema,
    )
    from pyfory.format.encoder import (  # noqa: F401
        encoder,
        Encoder,
    )
except (ImportError, AttributeError) as e:
    warnings.warn(
        f"Fory format initialization failed: {e}",
        RuntimeWarning,
        stacklevel=2,
    )

# Optional: Arrow columnar format support (requires pyarrow)
try:
    from pyfory.format.columnar import (  # noqa: F401
        ArrowWriter,
    )
except (ImportError, AttributeError):
    pass
