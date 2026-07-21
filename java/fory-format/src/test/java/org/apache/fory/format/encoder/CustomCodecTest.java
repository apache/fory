/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.format.encoder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import lombok.Data;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.reflect.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CustomCodecTest {

  static {
    Encoders.registerCustomCodec(CustomType.class, ZoneId.class, new ZoneIdEncoder());
    Encoders.registerCustomCodec(OptionalCodecType.class, ZoneId.class, new ZoneIdEncoder());
    Encoders.registerCustomCodec(InstantHolder.class, Optional.class, new InstantOptionalCodec());
    Encoders.registerCustomCodec(
        InstantHolderIface.class, Optional.class, new InstantOptionalCodec());
    Encoders.registerCustomCodec(
        InstantCollections.class, Optional.class, new InstantOptionalCodec());
    Encoders.registerCustomCodec(
        InstantNoteHolder.class, Optional.class, new InstantOptionalCodec());
    Encoders.registerCustomCodec(CustomByteBuf.class, new CustomByteBufEncoder());
    Encoders.registerCustomCodec(CustomByteBuf2.class, new CustomByteBuf2Encoder());
    Encoders.registerCustomCodec(CustomByteBuf3.class, new CustomByteBuf3Encoder());
    Encoders.registerCustomCodec(UUID.class, new UuidEncoder());
    Encoders.registerCustomCodec(InterceptedType.class, new InterceptedTypeEncoder());
    // Scoped only to its enclosing types, never to Object, so a top-level List<ScopedElement> must
    // not pick it up while a ScopedElement bean field does.
    Encoders.registerCustomCodec(
        ScopedElement.class, ScopedElement.class, new ScopedElementEncoder());
    Encoders.registerCustomCodec(
        ScopedElementHolder.class, ScopedElement.class, new ScopedElementEncoder());
    Encoders.registerCustomCollectionFactory(
        SortedSet.class, UUID.class, new SortedSetOfUuidDecoder());
    Encoders.registerCustomCollectionFactory(
        SortedSet.class, Long.class, new SortedSetOfLongDecoder());
  }

  @Data
  public static class CustomType {
    public ZoneId f1;
    public CustomByteBuf f2;
    public CustomByteBuf2 f3;
    public CustomByteBuf3 f4;

    public CustomType() {}
  }

  @Data
  public static class CustomByteBuf {
    final byte[] buf;

    CustomByteBuf(final byte[] buf) {
      this.buf = buf;
    }
  }

  @Data
  public static class CustomByteBuf2 {
    final byte[] buf;

    CustomByteBuf2(final byte[] buf) {
      this.buf = buf;
    }
  }

  @Data
  public static class CustomByteBuf3 {
    final byte[] buf;

    CustomByteBuf3(final byte[] buf) {
      this.buf = buf;
    }
  }

  @Data
  public static class UuidType {
    public UUID f1;
    public UUID[] f2;
    public SortedSet<UUID> f3;

    public UuidType() {}
  }

  @Data
  public static class TwoSortedSetsType {
    public SortedSet<UUID> uuids;
    public SortedSet<Long> longs;

    public TwoSortedSetsType() {}
  }

  /** A custom-codec'd element type wrapped in Optional, the untested intersection. */
  @Data
  public static class OptionalCodecType {
    public Optional<ZoneId> zone;

    public OptionalCodecType() {}
  }

  /** An Optional field whose codec is keyed on Optional itself, owning the absence encoding. */
  @Data
  public static class InstantHolder {
    public Optional<Instant> at;

    public InstantHolder() {}
  }

  /** Interface form of {@link InstantHolder}, decoded through the lazy interface-impl path. */
  public interface InstantHolderIface {
    Optional<Instant> getAt();
  }

  /**
   * An Optional-keyed non-nullable column ({@code at}) beside a genuinely nullable column ({@code
   * note}). Because not every field is non-nullable, the compact layout's {@code
   * allFieldsNotNullable} shortcut is off and {@code setNullAt} no longer throws on any null: a
   * null {@code at} reference must be caught by serialize-side normalization to Optional.empty(),
   * not by the writer's all-not-nullable guard.
   */
  @Data
  public static class InstantNoteHolder {
    public Optional<Instant> at;
    public String note;

    public InstantNoteHolder() {}
  }

  /**
   * A bean-scoped Optional codec applied to collection elements: a {@code List} (lazy decode), a
   * {@code Set} (eager {@code ArrayDataForEach} decode), a nested list, and a {@code Map} whose
   * keys and values are both codec-owned (key/value arrays decode through the same path).
   */
  @Data
  public static class InstantCollections {
    public List<Optional<Instant>> list;
    public Set<Optional<Instant>> set;
    public List<List<Optional<Instant>>> nested;
    public Map<Optional<Instant>, Optional<Instant>> map;

    public InstantCollections() {}
  }

  // Element-codec-inside-Optional (codec keyed on ZoneId, not Optional). This already worked
  // before Optional-keyed support; these two guard that the inferField reorder keeps it working.
  // The Optional-keyed behavior itself is covered by the optionalKeyedCodec* tests below.
  @Test
  public void optionalCustomCodecPresent() {
    final OptionalCodecType bean = new OptionalCodecType();
    bean.zone = Optional.of(ZoneId.of("America/Los_Angeles"));
    final RowEncoder<OptionalCodecType> encoder = Encoders.bean(OptionalCodecType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalCodecType out = encoder.fromRow(row);
    Assert.assertEquals(out.zone, Optional.of(ZoneId.of("America/Los_Angeles")));
  }

  @Test
  public void optionalCustomCodecEmpty() {
    final OptionalCodecType bean = new OptionalCodecType();
    bean.zone = Optional.empty();
    final RowEncoder<OptionalCodecType> encoder = Encoders.bean(OptionalCodecType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalCodecType out = encoder.fromRow(row);
    Assert.assertEquals(out.zone, Optional.empty());
  }

  // A codec keyed on Optional itself owns the whole field, including absence, and encodes
  // optionality in-band into a single non-nullable column. Here Optional<Instant> maps to one
  // int64: empty -> Long.MIN_VALUE, present -> epoch millis. An element codec on Instant could
  // not express this, because it never sees the empty case nor controls the absence encoding.
  @Test
  public void optionalKeyedCodecPresent() {
    final InstantHolder bean = new InstantHolder();
    bean.at = Optional.of(Instant.ofEpochMilli(1_700_000_000_000L));
    final RowEncoder<InstantHolder> encoder = Encoders.bean(InstantHolder.class);
    final InstantHolder out = roundTrip(encoder, bean);
    Assert.assertEquals(out.at, Optional.of(Instant.ofEpochMilli(1_700_000_000_000L)));
  }

  @Test
  public void optionalKeyedCodecEmpty() {
    final InstantHolder bean = new InstantHolder();
    bean.at = Optional.empty();
    final RowEncoder<InstantHolder> encoder = Encoders.bean(InstantHolder.class);
    final InstantHolder out = roundTrip(encoder, bean);
    Assert.assertEquals(out.at, Optional.empty());
  }

  /**
   * A null Optional reference (not Optional.empty()) is normalized to empty before the codec runs,
   * so it round-trips as empty: the codec cannot distinguish the two.
   */
  @Test
  public void optionalKeyedCodecNullReference() {
    final InstantHolder bean = new InstantHolder();
    bean.at = null;
    final RowEncoder<InstantHolder> encoder = Encoders.bean(InstantHolder.class);
    final InstantHolder out = roundTrip(encoder, bean);
    Assert.assertEquals(out.at, Optional.empty());
  }

  /** The in-band optionality column is non-nullable: the codec, not the format, owns absence. */
  @Test
  public void optionalKeyedCodecColumnNullability() {
    final Field at = TypeInference.inferSchema(InstantHolder.class).getFieldByName("at");
    Assert.assertNotNull(at);
    Assert.assertFalse(
        at.nullable(), "Optional-keyed in-band codec must produce a non-nullable column");
  }

  // The Optional-keyed codec owns a non-nullable column, so a null reference cannot be encoded as a
  // column null bit. The compact writer enforces this by rejecting setNullAt on a non-nullable
  // column; these cases also exercise the lazy interface-impl decode path alongside the default
  // eager-row class path.

  @Test
  public void optionalKeyedCodecCompactPresent() {
    final InstantHolder bean = new InstantHolder();
    bean.at = Optional.of(Instant.ofEpochMilli(1_700_000_000_000L));
    final RowEncoder<InstantHolder> encoder =
        Encoders.buildBeanCodec(InstantHolder.class).compactEncoding().build().get();
    final InstantHolder out = roundTrip(encoder, bean);
    Assert.assertEquals(out.at, Optional.of(Instant.ofEpochMilli(1_700_000_000_000L)));
  }

  @Test
  public void optionalKeyedCodecCompactEmpty() {
    final InstantHolder bean = new InstantHolder();
    bean.at = Optional.empty();
    final RowEncoder<InstantHolder> encoder =
        Encoders.buildBeanCodec(InstantHolder.class).compactEncoding().build().get();
    final InstantHolder out = roundTrip(encoder, bean);
    Assert.assertEquals(out.at, Optional.empty());
  }

  @Test
  public void optionalKeyedCodecCompactNullReference() {
    final InstantHolder bean = new InstantHolder();
    bean.at = null;
    final RowEncoder<InstantHolder> encoder =
        Encoders.buildBeanCodec(InstantHolder.class).compactEncoding().build().get();
    final InstantHolder out = roundTrip(encoder, bean);
    Assert.assertEquals(out.at, Optional.empty());
  }

  /**
   * A null Optional-keyed reference in a bean that also has a nullable column. The nullable {@code
   * note} field turns off the compact writer's all-not-nullable guard, so {@code setNullAt} no
   * longer throws on the non-nullable {@code at} column. The assertion is on the encoded row's null
   * bits, not the decoded value: routing absence through the column null bit also decodes back to
   * Optional.empty(), so only the wire state distinguishes correct in-band normalization (the
   * {@code at} column is not null and carries the codec's empty sentinel) from the regression
   * ({@code at} set to the column null bit). {@code note} is genuinely null, confirming the guard
   * is off.
   */
  @Test
  public void optionalKeyedCodecCompactMixedNullability() {
    final InstantNoteHolder bean = new InstantNoteHolder();
    bean.at = null;
    bean.note = null;
    final RowEncoder<InstantNoteHolder> encoder =
        Encoders.buildBeanCodec(InstantNoteHolder.class).compactEncoding().build().get();
    final BinaryRow row = encoder.toRow(bean);
    final int atOrdinal = encoder.schema().getFieldIndex("at");
    final int noteOrdinal = encoder.schema().getFieldIndex("note");
    Assert.assertFalse(
        row.isNullAt(atOrdinal),
        "Optional-keyed absence must be encoded in-band, not via the column null bit");
    Assert.assertTrue(row.isNullAt(noteOrdinal), "nullable note must use the column null bit");
    final InstantNoteHolder out = roundTrip(encoder, bean);
    Assert.assertEquals(out.at, Optional.empty());
    Assert.assertNull(out.note);
  }

  @Test
  public void optionalKeyedCodecInterfacePresent() {
    final InstantHolder bean = new InstantHolder();
    bean.at = Optional.of(Instant.ofEpochMilli(1_700_000_000_000L));
    final InstantHolderIface out = encodeAsIface(bean);
    Assert.assertEquals(out.getAt(), Optional.of(Instant.ofEpochMilli(1_700_000_000_000L)));
  }

  @Test
  public void optionalKeyedCodecInterfaceEmpty() {
    final InstantHolder bean = new InstantHolder();
    bean.at = Optional.empty();
    final InstantHolderIface out = encodeAsIface(bean);
    Assert.assertEquals(out.getAt(), Optional.empty());
  }

  @Test
  public void optionalKeyedCodecInterfaceNullReference() {
    final InstantHolder bean = new InstantHolder();
    bean.at = null;
    final InstantHolderIface out = encodeAsIface(bean);
    Assert.assertEquals(out.getAt(), Optional.empty());
  }

  // A bean-scoped codec applies to the bean's collection elements, not only its direct fields.
  // The List path decodes lazily (LazyArrayData); the Set path decodes eagerly (ArrayDataForEach).

  @Test
  public void optionalKeyedCodecInList() {
    final InstantCollections bean = new InstantCollections();
    bean.list =
        Arrays.asList(
            Optional.of(Instant.ofEpochMilli(7)), Optional.empty(), Optional.of(Instant.EPOCH));
    final RowEncoder<InstantCollections> encoder = Encoders.bean(InstantCollections.class);
    final InstantCollections out = roundTrip(encoder, bean);
    Assert.assertEquals(out.list, bean.list);
  }

  @Test
  public void optionalKeyedCodecInSet() {
    final InstantCollections bean = new InstantCollections();
    bean.set = new HashSet<>(Arrays.asList(Optional.of(Instant.ofEpochMilli(9)), Optional.empty()));
    final RowEncoder<InstantCollections> encoder = Encoders.bean(InstantCollections.class);
    final InstantCollections out = roundTrip(encoder, bean);
    Assert.assertEquals(out.set, bean.set);
  }

  @Test
  public void optionalKeyedCodecInNestedList() {
    final InstantCollections bean = new InstantCollections();
    bean.nested =
        Arrays.asList(
            Arrays.asList(Optional.of(Instant.ofEpochMilli(1)), Optional.empty()),
            Collections.singletonList(Optional.empty()));
    final RowEncoder<InstantCollections> encoder = Encoders.bean(InstantCollections.class);
    final InstantCollections out = roundTrip(encoder, bean);
    Assert.assertEquals(out.nested, bean.nested);
  }

  @Test
  public void optionalKeyedCodecInMap() {
    final InstantCollections bean = new InstantCollections();
    // A map key cannot be empty (keys are non-nullable), but a value can.
    bean.map = new LinkedHashMap<>();
    bean.map.put(Optional.of(Instant.ofEpochMilli(3)), Optional.of(Instant.ofEpochMilli(4)));
    bean.map.put(Optional.of(Instant.ofEpochMilli(5)), Optional.empty());
    final RowEncoder<InstantCollections> encoder = Encoders.bean(InstantCollections.class);
    final InstantCollections out = roundTrip(encoder, bean);
    Assert.assertEquals(out.map, bean.map);
  }

  // Codec-resolution scope for collection elements. A codec scoped to an enclosing bean reaches
  // that bean's collection/field elements, but a top-level collection encoder has no enclosing
  // bean,
  // so it resolves elements against Object (matching the inferred schema) and a narrowly-scoped
  // codec must not bind. Otherwise codegen would apply a codec the schema never did, corrupting the
  // row.

  @Test
  public void scopedCodecAppliesToBeanField() {
    final ScopedElementHolder bean = new ScopedElementHolder();
    bean.e = new ScopedElement(1);
    final RowEncoder<ScopedElementHolder> encoder = Encoders.bean(ScopedElementHolder.class);
    final ScopedElementHolder out = roundTrip(encoder, bean);
    // encode adds 100, decode is identity: the codec ran.
    Assert.assertEquals(out.e.v, 101);
  }

  @Test
  public void scopedCodecSkipsTopLevelCollection() {
    final ArrayEncoder<List<ScopedElement>> encoder =
        Encoders.arrayEncoder(new TypeRef<List<ScopedElement>>() {});
    final List<ScopedElement> in = Arrays.asList(new ScopedElement(1), new ScopedElement(2));
    final BinaryArray array = encoder.toArray(in);
    final List<ScopedElement> out = encoder.fromArray(array);
    // The narrowly-scoped codec is not enclosed by any bean here, so elements round-trip unchanged.
    Assert.assertEquals(out, in);
  }

  /** Encode a concrete {@link InstantHolder} and decode it through the interface impl path. */
  private static InstantHolderIface encodeAsIface(InstantHolder bean) {
    final BinaryRow row = Encoders.bean(InstantHolder.class).toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    return Encoders.bean(InstantHolderIface.class).fromRow(row);
  }

  private static <T> T roundTrip(RowEncoder<T> encoder, T bean) {
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    return encoder.fromRow(row);
  }

  @Test
  public void testCustomTypes() {
    final CustomType bean = new CustomType();
    bean.f1 = ZoneId.of("America/Los_Angeles");
    bean.f2 = new CustomByteBuf("f2 value".getBytes(StandardCharsets.UTF_8));
    bean.f3 = new CustomByteBuf2("f3 value".getBytes(StandardCharsets.UTF_8));
    bean.f4 = new CustomByteBuf3("f4 value".getBytes(StandardCharsets.UTF_8));
    final RowEncoder<CustomType> encoder = Encoders.bean(CustomType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final CustomType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Test
  public void testNullFields() {
    final CustomType bean = new CustomType();
    final RowEncoder<CustomType> encoder = Encoders.bean(CustomType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final CustomType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Test
  public void testUuidFields() {
    final UuidType bean = new UuidType();
    bean.f1 = new UUID(1, 2);
    bean.f2 = new UUID[] {new UUID(2, 3), new UUID(3, 4), new UUID(5, 6)};
    bean.f3 = new TreeSet<>(Arrays.asList(new UUID(7, 8), new UUID(9, 10)));
    final RowEncoder<UuidType> encoder = Encoders.bean(UuidType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final UuidType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
    Assert.assertEquals(deserializedBean.f3.comparator(), UnsignedUuidComparator.INSTANCE);
  }

  // Sibling SortedSet fields with different element types must each dispatch
  // to their own factory; the codegen cache must key on (container, element).
  @Test
  public void testSiblingSortedSetsDifferentElementTypes() {
    final TwoSortedSetsType bean = new TwoSortedSetsType();
    bean.uuids = new TreeSet<>(Arrays.asList(new UUID(7, 8), new UUID(9, 10)));
    bean.longs = new TreeSet<>(Arrays.asList(1L, 2L, 3L));
    final RowEncoder<TwoSortedSetsType> encoder = Encoders.bean(TwoSortedSetsType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final TwoSortedSetsType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
    Assert.assertEquals(deserializedBean.uuids.comparator(), UnsignedUuidComparator.INSTANCE);
    Assert.assertEquals(deserializedBean.longs.comparator(), Comparator.reverseOrder());
  }

  static class ZoneIdEncoder implements CustomCodec<ZoneId, String> {
    @Override
    public Field getForyField(final String fieldName) {
      return DataTypes.field(fieldName, DataTypes.utf8());
    }

    @Override
    public String encode(final ZoneId value) {
      return Objects.toString(value, null);
    }

    @Override
    public ZoneId decode(final String value) {
      return ZoneId.of(value);
    }

    @Override
    public TypeRef<String> encodedType() {
      return TypeRef.of(String.class);
    }
  }

  /**
   * Encodes {@code Optional<Instant>} in-band: empty becomes {@code Long.MIN_VALUE}, present
   * becomes epoch millis, in a single non-nullable int64 column. Keyed on Optional so it owns
   * absence; an element codec on Instant could not, as it never sees the empty case.
   */
  @SuppressWarnings("rawtypes") // keyed on Optional.class, whose Class<Optional> is raw
  static class InstantOptionalCodec implements CustomCodec<Optional, Long> {
    private static final long ABSENT = Long.MIN_VALUE;

    @Override
    public Field getForyField(final String fieldName) {
      return DataTypes.notNullField(fieldName, DataTypes.int64());
    }

    @Override
    public Long encode(final Optional value) {
      return value.isPresent() ? ((Instant) value.get()).toEpochMilli() : ABSENT;
    }

    @Override
    public Optional<Instant> decode(final Long value) {
      return value == ABSENT ? Optional.empty() : Optional.of(Instant.ofEpochMilli(value));
    }

    @Override
    public TypeRef<Long> encodedType() {
      return TypeRef.of(Long.class);
    }
  }

  static class CustomByteBufEncoder implements CustomCodec.MemoryBufferCodec<CustomByteBuf> {
    @Override
    public MemoryBuffer encode(final CustomByteBuf value) {
      return MemoryBuffer.fromByteArray(value.buf);
    }

    @Override
    public CustomByteBuf decode(final MemoryBuffer value) {
      return new CustomByteBuf(value.getRemainingBytes());
    }
  }

  static class CustomByteBuf2Encoder implements CustomCodec.ByteArrayCodec<CustomByteBuf2> {
    @Override
    public byte[] encode(final CustomByteBuf2 value) {
      return value.buf;
    }

    @Override
    public CustomByteBuf2 decode(final byte[] value) {
      return new CustomByteBuf2(value);
    }
  }

  static class CustomByteBuf3Encoder implements CustomCodec.BinaryArrayCodec<CustomByteBuf3> {
    @Override
    public BinaryArray encode(final CustomByteBuf3 value) {
      return BinaryArray.fromPrimitiveArray(value.buf);
    }

    @Override
    public CustomByteBuf3 decode(final BinaryArray value) {
      return new CustomByteBuf3(value.toByteArray());
    }
  }

  public static class UuidEncoder implements CustomCodec.MemoryBufferCodec<UUID> {
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

    @Override
    public Field getForyField(final String fieldName) {
      return DataTypes.field(fieldName, DataTypes.fixedWidthBinary(16));
    }
  }

  static class SortedSetOfUuidDecoder implements CustomCollectionFactory<UUID, SortedSet<UUID>> {
    @Override
    public SortedSet<UUID> newCollection(final int size) {
      return new TreeSet<>(UnsignedUuidComparator.INSTANCE);
    }
  }

  static class SortedSetOfLongDecoder implements CustomCollectionFactory<Long, SortedSet<Long>> {
    @Override
    public SortedSet<Long> newCollection(final int size) {
      return new TreeSet<>(Comparator.reverseOrder());
    }
  }

  private enum UnsignedUuidComparator implements Comparator<UUID> {
    INSTANCE;

    @Override
    public int compare(final UUID o1, final UUID o2) {
      final int cmpMsb =
          Long.compareUnsigned(o1.getMostSignificantBits(), o2.getMostSignificantBits());
      if (cmpMsb != 0) {
        return cmpMsb;
      }
      return Long.compareUnsigned(o1.getLeastSignificantBits(), o2.getLeastSignificantBits());
    }
  }

  /**
   * A bean-scoped intercepting codec used to prove codec-resolution scope. Registered only on
   * enclosing type {@link ScopedElement} itself, never on {@code Object}, so it must reach a {@code
   * ScopedElement} field of that bean but must NOT reach the elements of a top-level {@code
   * List<ScopedElement>} encoder, which has no enclosing bean.
   */
  @Data
  public static class ScopedElement {
    public int v;

    public ScopedElement() {}

    public ScopedElement(final int v) {
      this.v = v;
    }
  }

  /** A bean carrying a {@link ScopedElement} field, so the codec's enclosing type is itself. */
  @Data
  public static class ScopedElementHolder {
    public ScopedElement e;

    public ScopedElementHolder() {}
  }

  // Encodes to a terminal Integer (not back to the bean, which would make schema inference
  // recurse).
  // encode adds 100 and decode does not subtract, so a round trip nets +100 IFF the codec was
  // applied; when it is not applied ScopedElement round-trips structurally with v unchanged. This
  // makes codec-resolution scope observable from behavior.
  static class ScopedElementEncoder implements CustomCodec<ScopedElement, Integer> {
    @Override
    public Field getForyField(final String fieldName) {
      return DataTypes.field(fieldName, DataTypes.int32());
    }

    @Override
    public TypeRef<Integer> encodedType() {
      return TypeRef.of(Integer.class);
    }

    @Override
    public Integer encode(final ScopedElement value) {
      return value.v + 100;
    }

    @Override
    public ScopedElement decode(final Integer value) {
      return new ScopedElement(value);
    }
  }

  public interface InterceptedType {
    int f1();
  }

  public static class InterceptedTypeImpl implements InterceptedType {
    private final int f1;

    public InterceptedTypeImpl(final int f1) {
      this.f1 = f1;
    }

    @Override
    public int f1() {
      return f1;
    }
  }

  static class InterceptedTypeEncoder implements CustomCodec.InterceptingCodec<InterceptedType> {
    @Override
    public TypeRef<InterceptedType> encodedType() {
      return TypeRef.of(InterceptedType.class);
    }

    @Override
    public InterceptedType encode(final InterceptedType value) {
      return new InterceptedTypeImpl(value.f1() + 2);
    }

    @Override
    public InterceptedType decode(final InterceptedType value) {
      return new InterceptedTypeImpl(value.f1() + 3);
    }
  }

  @Test
  public void testCodecTypeInterception() {
    final InterceptedType bean = new InterceptedTypeImpl(42);
    final RowEncoder<InterceptedType> encoder = Encoders.bean(InterceptedType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final InterceptedType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f1(), bean.f1() + 5);
    Assert.assertEquals(deserializedBean.getClass(), InterceptedTypeImpl.class);
  }

  public interface WrapInterceptedType {
    InterceptedType f1();
  }

  public static class WrapInterceptedTypeImpl implements WrapInterceptedType {
    private final InterceptedType f1;

    public WrapInterceptedTypeImpl(final InterceptedType f1) {
      this.f1 = f1;
    }

    @Override
    public InterceptedType f1() {
      return f1;
    }
  }

  @Test
  public void testNestedCodecTypeInterception() {
    final WrapInterceptedType bean = new WrapInterceptedTypeImpl(new InterceptedTypeImpl(42));
    final RowEncoder<WrapInterceptedType> encoder = Encoders.bean(WrapInterceptedType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final WrapInterceptedType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f1().f1(), bean.f1().f1() + 5);
    Assert.assertEquals(deserializedBean.f1().getClass(), InterceptedTypeImpl.class);
  }
}
