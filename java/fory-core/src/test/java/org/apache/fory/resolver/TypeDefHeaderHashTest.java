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

package org.apache.fory.resolver;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.Fory;
import org.apache.fory.TestUtils;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.serializer.UnknownClass;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class TypeDefHeaderHashTest {
  private static final String TYPE_NAME = "test.HeaderHashType";

  public interface HeaderHashTarget {}

  public static class HeaderHashType implements HeaderHashTarget {
    public int value;
  }

  public static class OtherHeaderHashType {
    public long value;
  }

  public static class FirstTarget {
    public int value;
  }

  public static class SecondTarget {
    public int value;
    public int extra;
  }

  @Test
  public void testLocalHashHit() {
    Fory writer = newFory(null);
    writer.register(HeaderHashType.class, TYPE_NAME);
    TypeDef wireTypeDef = writer.getTypeResolver().getTypeDef(HeaderHashType.class, true);

    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory reader = newFory(sharedRegistry);
    reader.register(HeaderHashType.class, TYPE_NAME);
    TypeResolver resolver = reader.getTypeResolver();
    TypeDef localTypeDef = resolver.getTypeDef(HeaderHashType.class, true);
    long headerHash = TypeDef.headerHash(localTypeDef.getId());
    TypeInfo remoteOwner = new TypeInfo(Object.class, wireTypeDef);
    resolver.extRegistry.typeInfoByHeaderHash.put(headerHash, remoteOwner);
    sharedRegistry.remoteTypeDefByHeaderHash.put(headerHash, wireTypeDef);
    MemoryBuffer frame = opaqueMetaFrame(wireTypeDef, 5, 5);

    TypeInfo typeInfo = resolver.readSharedClassMeta(prepare(reader, frame), HeaderHashType.class);

    assertSame(typeInfo.getTypeDef(), localTypeDef);
    assertEquals(frame.readerIndex(), frame.size());
    assertSame(resolver.extRegistry.typeInfoByHeaderHash.get(headerHash), remoteOwner);
    assertSame(sharedRegistry.remoteTypeDefByHeaderHash.get(headerHash), wireTypeDef);
    assertSame(
        resolver.readSharedClassMeta(
            prepare(reader, opaqueMetaFrame(wireTypeDef, 5, 5)), HeaderHashType.class),
        typeInfo);
  }

  @Test
  public void testLocalTypeInfoReuse() {
    Fory writer = newFory(null);
    writer.register(HeaderHashType.class, TYPE_NAME);
    writer.register(OtherHeaderHashType.class, "test.OtherHeaderHashType");
    TypeDef firstTypeDef = writer.getTypeResolver().getTypeDef(HeaderHashType.class, true);
    TypeDef secondTypeDef = writer.getTypeResolver().getTypeDef(OtherHeaderHashType.class, true);

    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory reader = newFory(sharedRegistry);
    reader.register(HeaderHashType.class, TYPE_NAME);
    reader.register(OtherHeaderHashType.class, "test.OtherHeaderHashType");
    TypeResolver resolver = reader.getTypeResolver();

    TypeInfo first =
        resolver.readSharedClassMeta(
            prepare(reader, opaqueMetaFrame(firstTypeDef, 5, 5)), HeaderHashType.class);
    TypeInfo second =
        resolver.readSharedClassMeta(
            prepare(reader, opaqueMetaFrame(secondTypeDef, 5, 5)), OtherHeaderHashType.class);
    TypeInfo firstAgain =
        resolver.readSharedClassMeta(
            prepare(reader, opaqueMetaFrame(firstTypeDef, 5, 5)), HeaderHashType.class);
    TypeInfo secondAgain =
        resolver.readSharedClassMeta(
            prepare(reader, opaqueMetaFrame(secondTypeDef, 5, 5)), OtherHeaderHashType.class);

    assertSame(firstAgain, first);
    assertSame(secondAgain, second);
    Assert.assertTrue(sharedRegistry.remoteTypeDefByHeaderHash.isEmpty());
  }

  @Test
  public void testLocalMissStaysLocal() {
    Fory writer = newFory(null);
    writer.register(HeaderHashType.class, TYPE_NAME);
    TypeResolver writerResolver = writer.getTypeResolver();
    TypeDef wireTypeDef = writerResolver.getTypeDef(HeaderHashType.class, true);
    int typeId = writerResolver.getTypeInfo(HeaderHashType.class).getTypeId();

    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory reader = newFory(sharedRegistry);
    reader.register(HeaderHashType.class, TYPE_NAME);
    TypeResolver resolver = reader.getTypeResolver();
    TypeDef localTypeDef = resolver.getTypeDef(HeaderHashType.class, true);
    long headerHash = TypeDef.headerHash(localTypeDef.getId());

    TypeInfo typeInfo = readTypeInfo(reader, typeFrame(typeId, wireTypeDef));

    assertSame(typeInfo.getTypeDef(), localTypeDef);
    assertSame(resolver.extRegistry.typeInfoByHeaderHash.get(headerHash), typeInfo);
    Assert.assertFalse(sharedRegistry.remoteTypeDefByHeaderHash.containsKey(headerHash));
  }

  @Test
  public void testTargetLocalBeatsHint() {
    Fory writer = newFory(null);
    writer.register(HeaderHashType.class, TYPE_NAME);
    TypeResolver writerResolver = writer.getTypeResolver();
    TypeDef wireTypeDef = writerResolver.getTypeDef(HeaderHashType.class, true);
    int typeId = writerResolver.getTypeInfo(HeaderHashType.class).getTypeId();

    Fory reader = newFory(new SharedRegistry());
    reader.register(HeaderHashType.class, TYPE_NAME);
    TypeResolver resolver = reader.getTypeResolver();
    TypeDef localTypeDef = resolver.getTypeDef(HeaderHashType.class, true);
    TypeInfo sourceHint = new TypeInfo(Object.class, wireTypeDef);
    TypeInfo[] typeInfoCache = TestUtils.getFieldValue(resolver, "typeInfoCache");
    typeInfoCache[0] = sourceHint;
    MemoryBuffer frame = opaqueTypeFrame(typeId, wireTypeDef, 6, 6);

    TypeInfo typeInfo = resolver.readTypeInfo(prepare(reader, frame), HeaderHashType.class);

    assertNotSame(typeInfo, sourceHint);
    assertSame(typeInfo.getTypeDef(), localTypeDef);
    assertSame(typeInfoCache[0], sourceHint);
    assertEquals(frame.readerIndex(), frame.size());
  }

  @DataProvider
  public Object[][] modes() {
    return new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}};
  }

  @Test(dataProvider = "modes")
  public void testTargetTypeInfoReuse(boolean xlang, boolean codegen) throws Exception {
    Fory writer = compatibleFory(xlang, codegen);
    writer.register(HeaderHashType.class, 201);
    HeaderHashType value = new HeaderHashType();
    value.value = 42;
    byte[] bytes = writer.serialize(value);
    TypeDef typeDef = writer.getTypeResolver().getTypeDef(HeaderHashType.class, true);
    int typeId = writer.getTypeResolver().getTypeInfo(HeaderHashType.class).getTypeId();

    Fory reader = compatibleFory(xlang, codegen);
    reader.register(HeaderHashType.class, 201);
    reader.register(FirstTarget.class, 202);
    reader.register(SecondTarget.class, 203);
    TypeResolver resolver = reader.getTypeResolver();
    TypeInfo first =
        resolver.readTypeInfo(prepare(reader, typeFrame(typeId, typeDef)), FirstTarget.class);
    TypeInfo source =
        resolver.extRegistry.typeInfoByHeaderHash.get(TypeDef.headerHash(typeDef.getId()));
    assertSame(source.getType(), HeaderHashType.class);
    Field cacheField = TypeResolver.class.getDeclaredField("typeInfoCache");
    cacheField.setAccessible(true);
    TypeInfo[] hints = (TypeInfo[]) cacheField.get(resolver);
    assertSame(hints[0], source);
    TypeInfo second =
        resolver.readTypeInfo(prepare(reader, typeFrame(typeId, typeDef)), SecondTarget.class);
    assertNotSame(first, second);
    assertSame(first.getType(), FirstTarget.class);
    assertSame(second.getType(), SecondTarget.class);
    MemoryBuffer references = MemoryBuffer.newHeapBuffer(typeDef.getEncoded().length + 16);
    references.writeUInt8(typeId);
    references.writeVarUInt32(0);
    references.writeBytes(typeDef.getEncoded());
    references.writeUInt8(typeId);
    references.writeVarUInt32(1);
    references.writeUInt8(typeId);
    references.writeVarUInt32(1);
    ReadContext context = prepare(reader, readable(references));
    assertSame(resolver.readTypeInfo(context, FirstTarget.class), first);
    assertSame(resolver.readTypeInfo(context, SecondTarget.class), second);
    assertSame(resolver.readTypeInfo(context), source);
    for (int i = 0; i < 3; i++) {
      assertSame(
          resolver.readTypeInfo(prepare(reader, typeFrame(typeId, typeDef)), FirstTarget.class),
          first);
      assertSame(
          resolver.readTypeInfo(prepare(reader, typeFrame(typeId, typeDef)), SecondTarget.class),
          second);
      assertSame(hints[0], source);
      assertSame(reader.getReadContext().getMetaReadContext().readTypeInfos.get(0), source);
      assertSame(
          resolver.extRegistry.typeInfoByHeaderHash.get(TypeDef.headerHash(typeDef.getId())),
          source);
      assertEquals(reader.deserialize(bytes, FirstTarget.class).value, 42);
      SecondTarget converted = reader.deserialize(bytes, SecondTarget.class);
      assertEquals(converted.value, 42);
      assertEquals(converted.extra, 0);
      assertEquals(((HeaderHashType) reader.deserialize(bytes)).value, 42);
    }
  }

  @Test
  public void testCachedLocalMetadata() {
    Fory reader = compatibleFory(false, false);
    TypeResolver template = reader.getTypeResolver();
    AtomicInteger localQueries = new AtomicInteger();
    ClassResolver resolver =
        new ClassResolver(
            template.config,
            template.extRegistry.classLoader,
            template.sharedRegistry,
            template.jitContext) {
          @Override
          public TypeInfo getTypeInfo(Class<?> cls, boolean createIfAbsent) {
            if (!createIfAbsent) {
              localQueries.incrementAndGet();
            }
            return super.getTypeInfo(cls, createIfAbsent);
          }
        };
    resolver.initialize();
    resolver.register(HeaderHashType.class, 201);
    resolver.register(FirstTarget.class, 202);
    TypeDef typeDef = resolver.getTypeDef(HeaderHashType.class, true);
    TypeInfo source =
        resolver.readSharedClassMeta(
            prepare(reader, opaqueMetaFrame(typeDef, 5, 5)), HeaderHashType.class);
    TypeInfo target =
        resolver.readSharedClassMeta(
            prepare(reader, opaqueMetaFrame(typeDef, 5, 5)), FirstTarget.class);
    int initialQueries = localQueries.get();
    Assert.assertTrue(initialQueries > 0);
    for (int i = 0; i < 3; i++) {
      assertSame(
          resolver.readSharedClassMeta(
              prepare(reader, opaqueMetaFrame(typeDef, 5, 5)), HeaderHashType.class),
          source);
      assertSame(
          resolver.readSharedClassMeta(
              prepare(reader, opaqueMetaFrame(typeDef, 5, 5)), FirstTarget.class),
          target);
    }
    assertEquals(localQueries.get(), initialQueries);
  }

  private static Fory compatibleFory(boolean xlang, boolean codegen) {
    return Fory.builder()
        .withXlang(xlang)
        .withCodegen(codegen)
        .withCompatible(true)
        .withScopedMetaShare(true)
        .withAsyncCompilation(false)
        .build();
  }

  @Test
  public void testUnregisteredTarget() {
    Fory writer = newFory(null);
    writer.register(HeaderHashType.class, TYPE_NAME);
    TypeResolver writerResolver = writer.getTypeResolver();
    TypeDef wireTypeDef = writerResolver.getTypeDef(HeaderHashType.class, true);
    int typeId = writerResolver.getTypeInfo(HeaderHashType.class).getTypeId();

    Fory reader = newFory(new SharedRegistry());
    reader.register(HeaderHashType.class, TYPE_NAME);
    MemoryBuffer frame = typeFrame(typeId, wireTypeDef);

    TypeInfo typeInfo =
        reader.getTypeResolver().readTypeInfo(prepare(reader, frame), HeaderHashTarget.class);

    assertSame(typeInfo.getType(), HeaderHashType.class);
    assertEquals(frame.readerIndex(), frame.size());
  }

  @Test
  public void testPersistentHashHit() {
    Fory writer = newFory(null);
    writer.register(HeaderHashType.class, TYPE_NAME);
    TypeResolver writerResolver = writer.getTypeResolver();
    TypeDef wireTypeDef = writerResolver.getTypeDef(HeaderHashType.class, true);
    int typeId = writerResolver.getTypeInfo(HeaderHashType.class).getTypeId();

    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory firstReader = newFory(sharedRegistry);
    TypeInfo first = readTypeInfo(firstReader, typeFrame(typeId, wireTypeDef));
    long headerHash = TypeDef.headerHash(wireTypeDef.getId());
    assertSame(first.getType(), UnknownClass.UnknownStruct.class);
    assertSame(sharedRegistry.remoteTypeDefByHeaderHash.get(headerHash), first.getTypeDef());

    Fory secondReader = newFory(sharedRegistry);
    AtomicInteger policyCalls = new AtomicInteger();
    secondReader
        .getTypeResolver()
        .setTypeChecker(
            (resolver, className) -> {
              policyCalls.incrementAndGet();
              return false;
            });
    MemoryBuffer hitFrame = opaqueTypeFrame(typeId, wireTypeDef, 7, 7);
    TypeInfo second = readTypeInfo(secondReader, hitFrame);

    assertSame(second.getTypeDef(), first.getTypeDef());
    assertEquals(hitFrame.readerIndex(), hitFrame.size());
    assertEquals(policyCalls.get(), 0);
  }

  @Test
  public void testFieldHintHashHit() {
    Fory writer = newFory(null);
    writer.register(HeaderHashType.class, TYPE_NAME);
    TypeResolver writerResolver = writer.getTypeResolver();
    TypeDef wireTypeDef = writerResolver.getTypeDef(HeaderHashType.class, true);
    int typeId = writerResolver.getTypeInfo(HeaderHashType.class).getTypeId();

    Fory reader = newFory(new SharedRegistry());
    TypeResolver resolver = reader.getTypeResolver();
    TypeInfo first = readTypeInfo(reader, typeFrame(typeId, wireTypeDef));
    resolver.extRegistry.typeInfoByHeaderHash.clear();

    MemoryBuffer hitFrame = opaqueTypeFrame(typeId, wireTypeDef, 9, 9);
    TypeInfo second = readTypeInfo(reader, hitFrame);
    assertSame(second, first);
    assertEquals(hitFrame.readerIndex(), hitFrame.size());

    MemoryBuffer truncated = opaqueTypeFrame(typeId, wireTypeDef, 9, 8);
    Assert.assertThrows(IndexOutOfBoundsException.class, () -> readTypeInfo(reader, truncated));
  }

  private static Fory newFory(SharedRegistry sharedRegistry) {
    return Fory.builder()
        .withXlang(true)
        .requireClassRegistration(false)
        .withCompatible(true)
        .withMetaShare(true)
        .withDeserializeUnknownClass(true)
        .withSharedRegistry(sharedRegistry)
        .build();
  }

  private static TypeInfo readTypeInfo(Fory fory, MemoryBuffer buffer) {
    return fory.getTypeResolver().readTypeInfo(prepare(fory, buffer));
  }

  private static ReadContext prepare(Fory fory, MemoryBuffer buffer) {
    ReadContext readContext = fory.getReadContext();
    MetaReadContext metaReadContext = readContext.getMetaReadContext();
    if (metaReadContext == null) {
      readContext.setMetaReadContext(new MetaReadContext());
    } else {
      metaReadContext.readTypeInfos.clear();
    }
    readContext.prepare(buffer, null, false);
    return readContext;
  }

  private static MemoryBuffer typeFrame(int typeId, TypeDef typeDef) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(typeDef.getEncoded().length + 8);
    buffer.writeUInt8(typeId);
    buffer.writeVarUInt32(0);
    buffer.writeBytes(typeDef.getEncoded());
    return readable(buffer);
  }

  private static MemoryBuffer opaqueTypeFrame(
      int typeId, TypeDef typeDef, int declaredBodySize, int writtenBodySize) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(writtenBodySize + 16);
    buffer.writeUInt8(typeId);
    writeOpaqueMeta(buffer, typeDef, declaredBodySize, writtenBodySize);
    return readable(buffer);
  }

  private static MemoryBuffer opaqueMetaFrame(
      TypeDef typeDef, int declaredBodySize, int writtenBodySize) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(writtenBodySize + 16);
    writeOpaqueMeta(buffer, typeDef, declaredBodySize, writtenBodySize);
    return readable(buffer);
  }

  private static void writeOpaqueMeta(
      MemoryBuffer buffer, TypeDef typeDef, int declaredBodySize, int writtenBodySize) {
    long header = (typeDef.getId() & ~0xfffL) | declaredBodySize;
    assertSameHash(typeDef.getId(), header);
    buffer.writeVarUInt32(0);
    buffer.writeInt64(header);
    for (int i = 0; i < writtenBodySize; i++) {
      buffer.writeByte(0);
    }
  }

  private static void assertSameHash(long original, long current) {
    assertNotEquals(current, original);
    assertEquals(TypeDef.headerHash(current), TypeDef.headerHash(original));
  }

  private static MemoryBuffer readable(MemoryBuffer buffer) {
    return MemoryBuffer.fromByteArray(buffer.getBytes(0, buffer.writerIndex()));
  }
}
