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

import pyfory as fory

from pyfory.tests.core import require_pyarrow
from pyfory.util import lazy_import
from pyfory.format import (
    schema,
    field,
    int8,
    int16,
    int32,
    int64,
    utf8,
    map_,
    to_arrow_schema,
)

pa = lazy_import("pyarrow")


@require_pyarrow
def test_vectorized():
    field_names = ["f" + str(i) for i in range(1, 6)]
    cls = fory.record_class_factory("TEST_VECTORIZED", field_names)
    # Create Fory schema for the encoder
    fory_schema = schema(
        [
            field("f1", int64()),
            field("f2", int32()),
            field("f3", int16()),
            field("f4", int8()),
            field("f5", utf8()),
        ]
    )
    # Convert to Arrow schema for ArrowWriter
    arrow_schema = to_arrow_schema(fory_schema)
    # Add metadata for class resolution
    arrow_schema = arrow_schema.with_metadata({"cls": fory.get_qualified_classname(cls)})

    writer = fory.format.ArrowWriter(arrow_schema)
    encoder = fory.create_row_encoder(fory_schema)
    num_rows = 10
    data = [[] for _ in range(len(field_names))]
    for i in range(num_rows):
        obj = cls(f1=2**63 - 1, f2=2**31 - 1, f3=2**15 - 1, f4=2**7 - 1, f5=f"str{i}")
        fields_data = list(obj)
        for j in range(len(fields_data)):
            data[j].append(fields_data[j])
        row = encoder.to_row(obj)
        writer.write(row)
    record_batch = writer.finish()
    writer.reset()
    print(f"record_batch {record_batch}")
    print(f"record_batch.num_rows {record_batch.num_rows}")
    print(f"record_batch.num_columns {record_batch.num_columns}")
    assert record_batch.num_rows == num_rows
    assert record_batch.num_columns == 5

    data = [pa.array(data[i], type=arrow_schema[i].type) for i in range(len(field_names))]
    batch1 = pa.RecordBatch.from_arrays(data, field_names)
    assert batch1 == record_batch

    batches = [record_batch] * 3
    table = pa.Table.from_batches(batches)
    print(f"table {table}")


@require_pyarrow
def test_vectorized_map():
    cls = fory.record_class_factory("TEST_VECTORIZED_MAP", ["f0"])
    # Create Fory schema for the encoder
    fory_schema = schema(
        [
            field("f0", map_(utf8(), int32())),
        ]
    )
    # Convert to Arrow schema for ArrowWriter
    arrow_schema = to_arrow_schema(fory_schema)
    # Add metadata for class resolution
    arrow_schema = arrow_schema.with_metadata({"cls": fory.get_qualified_classname(cls)})
    print(arrow_schema)

    writer = fory.format.ArrowWriter(arrow_schema)
    encoder = fory.create_row_encoder(fory_schema)
    num_rows = 5
    data = []
    for i in range(num_rows):
        map_data = {"k1": 1, "k2": 2}
        data.append(list(map_data.items()))
        obj = cls(f0=map_data)
        row = encoder.to_row(obj)
        writer.write(row)
    record_batch = writer.finish()
    print(f"record_batch {record_batch}")
    data = [pa.array(data, type=arrow_schema[0].type)]
    batch1 = pa.RecordBatch.from_arrays(data, ["f0"])
    assert batch1 == record_batch


if __name__ == "__main__":
    test_vectorized()
