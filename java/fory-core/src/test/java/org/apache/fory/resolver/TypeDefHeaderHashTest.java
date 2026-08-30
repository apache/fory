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

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.Fory;
import org.apache.fory.TestUtils;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.serializer.UnknownClass;
import org.testng.Assert;
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
    assertSame(resolver.extRegistry.typeInfoByHeaderHash.get(headerHash), typeInfo);
    assertSame(sharedRegistry.remoteTypeDefByHeaderHash.get(headerHash), wireTypeDef);
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
  public void testTargetLocalBeatsHint() throws Exception {
    Fory writer = newFory(null);
    writer.register(HeaderHashType.class, TYPE_NAME);
    TypeResolver writerResolver = writer.getTypeResolver();
    TypeDef wireTypeDef = writerResolver.getTypeDef(HeaderHashType.class, true);
    int typeId = writerResolver.getTypeInfo(HeaderHashType.class).getTypeId();

    Fory reader = newFory(new SharedRegistry());
    reader.register(HeaderHashType.class, TYPE_NAME);
    TypeResolver resolver = reader.getTypeResolver();
    TypeDef localTypeDef = resolver.getTypeDef(HeaderHashType.class, true);
    Method getTargetTypeInfo =
        TypeResolver.class.getDeclaredMethod("getTargetTypeInfo", TypeInfo.class, Class.class);
    getTargetTypeInfo.setAccessible(true);
    TypeInfo transformedHint =
        (TypeInfo)
            getTargetTypeInfo.invoke(
                resolver, new TypeInfo(Object.class, localTypeDef), HeaderHashType.class);
    TypeInfo[] typeInfoCache = TestUtils.getFieldValue(resolver, "typeInfoCache");
    typeInfoCache[0] = transformedHint;
    MemoryBuffer frame = opaqueTypeFrame(typeId, wireTypeDef, 6, 6);

    TypeInfo typeInfo = resolver.readTypeInfo(prepare(reader, frame), HeaderHashType.class);

    assertNotSame(typeInfo, transformedHint);
    assertSame(typeInfo.getTypeDef(), localTypeDef);
    assertEquals(frame.readerIndex(), frame.size());
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
    readContext.setMetaReadContext(new MetaReadContext());
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
