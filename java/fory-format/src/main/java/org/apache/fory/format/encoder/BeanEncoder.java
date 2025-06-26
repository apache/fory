package org.apache.fory.format.encoder;

import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.exception.ClassNotCompatibleException;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

class BeanEncoder<T> implements RowEncoder<T> {
  private final Schema schema;
  private final GeneratedRowEncoder codec;
  private final BaseBinaryRowWriter writer;
  private final long schemaHash;
  private final MemoryBuffer buffer = MemoryUtils.buffer(16);

  BeanEncoder(
      final Schema schema, final GeneratedRowEncoder codec, final BaseBinaryRowWriter writer) {
    this.schema = schema;
    this.codec = codec;
    this.writer = writer;
    this.schemaHash = DataTypes.computeSchemaHash(schema);
  }

  protected void reset() {}

  @Override
  public Schema schema() {
    return schema;
  }

  @SuppressWarnings("unchecked")
  @Override
  public T fromRow(final BinaryRow row) {
    return (T) codec.fromRow(row);
  }

  @Override
  public BinaryRow toRow(final T obj) {
    reset();
    return codec.toRow(obj);
  }

  @Override
  public T decode(final MemoryBuffer buffer) {
    return decode(buffer, buffer.readInt32());
  }

  public T decode(final MemoryBuffer buffer, final int size) {
    final long peerSchemaHash = buffer.readInt64();
    if (peerSchemaHash != schemaHash) {
      throw new ClassNotCompatibleException(
          String.format(
              "Schema is not consistent, encoder schema is %s. "
                  + "self/peer schema hash are %s/%s. "
                  + "Please check writer schema.",
              schema, schemaHash, peerSchemaHash));
    }
    final BinaryRow row = new BinaryRow(schema);
    row.pointTo(buffer, buffer.readerIndex(), size);
    buffer.increaseReaderIndex(size - 8);
    return fromRow(row);
  }

  @Override
  public T decode(final byte[] bytes) {
    return decode(MemoryUtils.wrap(bytes), bytes.length);
  }

  @Override
  public byte[] encode(final T obj) {
    buffer.writerIndex(0);
    buffer.writeInt64(schemaHash);
    writer.setBuffer(buffer);
    writer.reset();
    final BinaryRow row = toRow(obj);
    return buffer.getBytes(0, 8 + row.getSizeInBytes());
  }

  @Override
  public void encode(final MemoryBuffer buffer, final T obj) {
    final int writerIndex = buffer.writerIndex();
    buffer.writeInt32(-1);
    try {
      buffer.writeInt64(schemaHash);
      writer.setBuffer(buffer);
      writer.reset();
      codec.toRow(obj);
      buffer.putInt32(writerIndex, buffer.writerIndex() - writerIndex - 4);
    } finally {
      writer.setBuffer(this.buffer);
    }
  }
}
