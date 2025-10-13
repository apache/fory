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

import pyarrow as pa
from pyfory.serializer import CrossLanguageCompatibleSerializer, BufferObject
from pyfory.buffer import Buffer


class ArrowRecordBatchSerializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value: pa.RecordBatch):
        self.fory.write_buffer_object(buffer, ArrowRecordBatchBufferObject(value))

    def read(self, buffer: Buffer) -> pa.Table:
        fory_buf = self.fory.read_buffer_object(buffer)
        # If the input source supports zero-copy reads (e.g. like a memory
        # map, or pa.BufferReader), then the returned batches are also
        # zero-copy and do not allocate any new memory on read.
        # So here the read is zero copy.
        reader = pa.ipc.open_stream(pa.py_buffer(fory_buf))
        [batch] = [batch for batch in reader]
        return batch


class ArrowRecordBatchBufferObject(BufferObject):
    def __init__(self, batch: pa.RecordBatch):
        self.batch = batch
        mock_sink = pa.MockOutputStream()
        ArrowRecordBatchBufferObject._write(batch, mock_sink)
        self.nbytes = mock_sink.size()

    def total_bytes(self) -> int:
        return self.nbytes

    def write_to(self, stream):
        if isinstance(stream, Buffer):
            sink = pa.FixedSizeBufferWriter(pa.py_buffer(stream))
        else:
            sink = pa.BufferOutputStream()
        self._write(self.batch, sink)
        if not isinstance(stream, Buffer):
            data = sink.getvalue()
            if hasattr(stream, "write"):
                stream.write(data.to_pybytes())

    def getbuffer(self) -> memoryview:
        sink = pa.BufferOutputStream()
        ArrowRecordBatchBufferObject._write(self.batch, sink)
        arrow_buffer = sink.getvalue()
        return memoryview(arrow_buffer)

    @staticmethod
    def _write(batch, sink):
        stream_writer = pa.RecordBatchStreamWriter(sink, batch.schema)
        stream_writer.write_batch(batch)
        stream_writer.close()


class ArrowTableSerializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value: pa.Table):
        self.fory.write_buffer_object(buffer, ArrowTableBufferObject(value))

    def read(self, buffer: Buffer) -> pa.Table:
        fory_buf = self.fory.read_buffer_object(buffer)
        # If the input source supports zero-copy reads (e.g. like a memory
        # map, or pa.BufferReader), then the returned batches are also
        # zero-copy and do not allocate any new memory on read.
        # So here the read is zero copy.
        reader = pa.ipc.open_stream(pa.py_buffer(fory_buf))
        batches = [batch for batch in reader]
        return pa.Table.from_batches(batches)


class ArrowTableBufferObject(BufferObject):
    def __init__(self, table: pa.Table):
        self.table = table
        mock_sink = pa.MockOutputStream()
        ArrowTableBufferObject._write(table, mock_sink)
        self.nbytes = mock_sink.size()

    def total_bytes(self) -> int:
        return self.nbytes

    def write_to(self, stream):
        if isinstance(stream, Buffer):
            sink = pa.FixedSizeBufferWriter(pa.py_buffer(stream))
        else:
            sink = pa.BufferOutputStream()
        ArrowTableBufferObject._write(self.table, sink)
        if not isinstance(stream, Buffer):
            data = sink.getvalue()
            if hasattr(stream, "write"):
                stream.write(data.to_pybytes())

    def getbuffer(self) -> memoryview:
        sink = pa.BufferOutputStream()
        self._write(self.table, sink)
        arrow_buffer = sink.getvalue()
        return memoryview(arrow_buffer)

    @staticmethod
    def _write(table, sink):
        stream_writer = pa.RecordBatchStreamWriter(sink, table.schema)
        for batch in table.to_batches():
            stream_writer.write_batch(batch)
        stream_writer.close()
