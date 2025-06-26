# Fory Row Format

Fory row format is heavily inspired by spark tungsten row format, but with changes:

- Use arrow schema to describe meta.
- The implementation support java/C++/python/etc..
- String support latin/utf16/utf8 encoding.
- Decimal use arrow decimal format.
- Variable-size field can be inline in fixed-size region if small enough.
- Allow skip padding by generate Row using aot to put offsets in generated code.

The initial Fory java row data structure implementation is modified from spark unsafe row/writer.

See `Encoders.bean` Javadoc for a list built-in supported types.

## Row Format Java

To begin using the row format from Java, start with the `Encoders` class:

```
// Many built-in types and collections are supported
public record MyRecord(int key, String value) {}

// The encoder supplier is relatively expensive to create
// It is thread-safe and should be re-used
Supplier<RowEncoder<MyRecord>> encoderFactory =
    Encoders.buildBeanCodec(MyRecord.class)
            .build();

// Each individual encoder is relatively cheap to create
// It is not thread-safe, but may be reused by the same thread
var encoder = encoderFactory.get();
byte[] encoded = encoder.encode(new MyRecord(42, "Test"));

MyRecord deserialized = encoder.decode(encoded);
```

## Compact Format

The default row format is cross-language compatible and alignment-padded for maximum performance.
When data size is a greater concern, the compact format provides an alternate encoding that uses
significantly less space.

Enable the compact codec on the encoder builder:

```
Supplier<RowEncoder<MyRecord>> encoderFactory =
    Encoders.buildBeanCodec(MyRecord.class)
            .compactEncoding()
            .build();
```

Optimizations include:

- struct stores fixed-size fields (e.g. Int128. FixedSizeBinary) inline in fixed-data area without offset + size
- struct of all fixed-sized fields is itself considered fixed-size to store in other struct or array
- struct skips null bitmap if all fields are non-nullable
- struct sorts fields by fixed-size for best-effort (but not guaranteed) alignment
- struct can use less than 8 bytes for small data (int, short, etc)
- struct null bitmap stored at end of struct to borrow alignment padding if possible
- array stores fixed-size fields inline in fixed-data area without offset+size
- array header uses 4 bytes for size (since Collection and array are only int-sized) and leaves remaining 4 bytes for start of null bitmap

## Custom Type Registration

It is possible to register custom type handling and collection factories for the row format -
see Encoders.registerCustomCodec and Encoders.registerCustomCollectionFactory. For an interface,
Fory can synthesize a simple value implementation, such as the UuidType below.

A short example:

```
public interface UuidType {
  UUID f1();
  UUID[] f2();
  SortedSet<UUID> f3();
}

static class UuidEncoder implements CustomCodec.MemoryBufferCodec<UUID> {
  @Override
  public MemoryBuffer encode(final UUID value) {
    final MemoryBuffer result = MemoryBuffer.newHeapBuffer(16);
    result.putInt64(0, value.getMostSignificantBits());
    result.putInt64(8, value.getLeastSignificantBits());
    return result;
  }

  @Override
  public UUID decode(final MemoryBuffer value) {
    return new UUID(value.readInt64(), value.readInt64());
  }
}

static class SortedSetOfUuidDecoder implements CustomCollectionFactory<UUID, SortedSet<UUID>> {
  @Override
  public SortedSet<UUID> newCollection(final int size) {
    return new TreeSet<>();
  }
}

Encoders.registerCustomCodec(UUID.class, new UuidEncoder());
Encoders.registerCustomCollectionFactory(
    SortedSet.class, UUID.class, new SortedSetOfUuidDecoder());

RowEncoder<UuidType> encoder = Encoders.bean(UuidType.class);
```
