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

import pyfory


class Encoder:
    def __init__(self, clz=None, schema=None):
        """
        A pojo class whose schema can be inferred by `pyfory.infer_schema`
        """
        self.clz = clz
        self.schema = schema or pyfory.format.infer.infer_schema(clz)
        self.row_encoder = pyfory.create_row_encoder(self.schema)
        self.schema_hash: bytes = pyfory.format.infer.compute_schema_hash(self.schema)

    def encode(self, obj):
        row = self.row_encoder.to_row(obj)
        buffer = pyfory.Buffer.allocate(8 + row.size_bytes())
        buffer.write_int64(self.schema_hash)
        row_bytes = row.to_bytes()
        buffer.write_bytes(row_bytes)
        return buffer.to_bytes(0, buffer.get_writer_index())

    def decode(self, binary: bytes):
        buf = pyfory.Buffer(binary, 0, len(binary))
        peer_hash = buf.read_int64()
        assert self.schema_hash == peer_hash, (
            f"Schema is not consistent, encoder schema is {self.schema}, "
            f"clz is {self.clz}. Self/peer schema hash is "
            f"{self.schema_hash, peer_hash}. "
            f"Please check writer schema."
        )
        buf = pyfory.Buffer(binary, 8, len(binary) - 8)
        row = pyfory.RowData(self.schema, buf)
        return self.row_encoder.from_row(row)

    def to_row(self, obj):
        return self.row_encoder.to_row(obj)

    def from_row(self, binary: bytes):
        buf = pyfory.Buffer(binary, 0, len(binary))
        row = pyfory.RowData(self.schema, buf)
        return self.row_encoder.from_row(row)


def encoder(clz=None, schema=None):
    """A pojo class whose schema can be inferred by `pyfory.infer_schema`"""
    return Encoder(clz=clz, schema=schema)
