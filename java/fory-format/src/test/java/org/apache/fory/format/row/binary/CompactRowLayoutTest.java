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

package org.apache.fory.format.row.binary;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.List;
import lombok.Data;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.testng.annotations.Test;

public class CompactRowLayoutTest {

  @Data
  public static class Nested1 {
    public short f1;
  }

  @Data
  public static class Nested2 {
    public int f1;
  }

  @Data
  public static class InlineNestedType {
    public Nested1 f1;
    public Nested2 f2;
  }

  @Test
  public void nestedStructCopyRoundTrip() {
    final InlineNestedType bean = new InlineNestedType();
    bean.f1 = new Nested1();
    bean.f1.f1 = 42;
    bean.f2 = new Nested2();
    bean.f2.f1 = 75;
    final RowEncoder<InlineNestedType> encoder =
        Encoders.buildBeanCodec(InlineNestedType.class).compactEncoding().build().get();
    final CompactBinaryRow row = (CompactBinaryRow) encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final CompactBinaryRow copy = (CompactBinaryRow) row.copy();
    assertNotSame(copy, row);
    assertSame(copy.getLayout(), row.getLayout());
    assertEquals(encoder.fromRow(copy), bean);
  }

  @Test
  public void nestedStructLayoutPrebuilt() {
    final InlineNestedType bean = new InlineNestedType();
    bean.f1 = new Nested1();
    bean.f1.f1 = 42;
    bean.f2 = new Nested2();
    bean.f2.f1 = 75;
    final RowEncoder<InlineNestedType> encoder =
        Encoders.buildBeanCodec(InlineNestedType.class).compactEncoding().build().get();
    final CompactBinaryRow row = (CompactBinaryRow) encoder.toRow(bean);
    final CompactRowLayout layout = row.getLayout();
    final CompactRowLayout child0 = layout.childLayouts[0];
    final CompactRowLayout child1 = layout.childLayouts[1];
    assertNotNull(child0);
    assertNotNull(child1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    assertSame(((CompactBinaryRow) row.getStruct(0)).getLayout(), child0);
    assertSame(((CompactBinaryRow) row.getStruct(1)).getLayout(), child1);
    final CompactBinaryRow row2 = (CompactBinaryRow) encoder.toRow(bean);
    assertSame(row2.getLayout().childLayouts[0], child0);
    assertSame(row2.getLayout().childLayouts[1], child1);
  }

  @Data
  public static class VarWidthInner {
    public int i;
    public String s;
  }

  @Data
  public static class VarWidthOuter {
    public VarWidthInner f1;
  }

  @Test
  public void varWidthNestedStructRead() {
    final VarWidthOuter bean = new VarWidthOuter();
    bean.f1 = new VarWidthInner();
    bean.f1.i = 42;
    bean.f1.s = "luna";
    final RowEncoder<VarWidthOuter> encoder =
        Encoders.buildBeanCodec(VarWidthOuter.class).compactEncoding().build().get();
    final CompactBinaryRow row = (CompactBinaryRow) encoder.toRow(bean);
    assertEquals(row.getLayout().fixedWidths[0], -1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final CompactBinaryRow inner = (CompactBinaryRow) row.getStruct(0);
    assertSame(inner.getLayout(), row.getLayout().childLayouts[0]);
    assertEquals(encoder.fromRow(row), bean);
  }

  /**
   * Pins {@code getBaseOffset() +} in the var-width getStruct branch by giving the parent a
   * non-zero offset.
   */
  @Test
  public void varWidthNestedStructReadWithNonZeroParentOffset() {
    final VarWidthOuter inner = new VarWidthOuter();
    inner.f1 = new VarWidthInner();
    inner.f1.i = 0x12345678;
    inner.f1.s = "luna";
    final OuterHolder bean = new OuterHolder();
    bean.f1 = inner;
    final RowEncoder<OuterHolder> encoder =
        Encoders.buildBeanCodec(OuterHolder.class).compactEncoding().build().get();
    final CompactBinaryRow root = (CompactBinaryRow) encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(root.toBytes());
    root.pointTo(buffer, 0, buffer.size());

    final CompactBinaryRow middle = (CompactBinaryRow) root.getStruct(0);
    assertTrue(middle.getBaseOffset() > 0);
    assertEquals(middle.getLayout().fixedWidths[0], -1);
    assertNotNull(middle.getStruct(0));
    assertEquals(encoder.fromRow(root), bean);
  }

  @Data
  public static class OuterHolder {
    public VarWidthOuter f1;
  }

  @Data
  public static class ListOfStructHolder {
    public List<VarWidthInner> elements;
  }

  @Test
  public void listOfStructElementLayoutPrebuilt() {
    final ListOfStructHolder bean = new ListOfStructHolder();
    final VarWidthInner inner = new VarWidthInner();
    inner.i = 7;
    inner.s = "luna";
    bean.elements = List.of(inner);
    final RowEncoder<ListOfStructHolder> encoder =
        Encoders.buildBeanCodec(ListOfStructHolder.class).compactEncoding().build().get();
    final CompactBinaryRow row = (CompactBinaryRow) encoder.toRow(bean);
    final CompactRowLayout elementLayout = row.getLayout().childLayouts[0];
    assertNotNull(elementLayout);

    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final CompactBinaryRow element = (CompactBinaryRow) row.getArray(0).getStruct(0);
    assertSame(element.getLayout(), elementLayout);
    assertEquals(encoder.fromRow(row), bean);
  }

  @Test
  public void listOfStructMultipleElements() {
    final ListOfStructHolder bean = new ListOfStructHolder();
    final VarWidthInner a = new VarWidthInner();
    a.i = 1;
    a.s = "a";
    final VarWidthInner b = new VarWidthInner();
    b.i = 2;
    b.s = "bbb";
    final VarWidthInner c = new VarWidthInner();
    c.i = 3;
    c.s = "cccccc";
    bean.elements = List.of(a, b, c);
    final RowEncoder<ListOfStructHolder> encoder =
        Encoders.buildBeanCodec(ListOfStructHolder.class).compactEncoding().build().get();
    final CompactBinaryRow row = (CompactBinaryRow) encoder.toRow(bean);
    final CompactRowLayout elementLayout = row.getLayout().childLayouts[0];
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    for (int i = 0; i < 3; i++) {
      final CompactBinaryRow element = (CompactBinaryRow) row.getArray(0).getStruct(i);
      assertSame(element.getLayout(), elementLayout);
    }
    assertEquals(encoder.fromRow(row), bean);
  }

  @Data
  public static class AllPrimitive {
    public int i;
    public long l;
    public boolean b;
  }

  @Test
  public void allFieldsNotNullableSkipsBitmap() {
    final AllPrimitive bean = new AllPrimitive();
    bean.i = 42;
    bean.l = 0x1122334455667788L;
    bean.b = true;
    final RowEncoder<AllPrimitive> encoder =
        Encoders.buildBeanCodec(AllPrimitive.class).compactEncoding().build().get();
    final CompactBinaryRow row = (CompactBinaryRow) encoder.toRow(bean);
    final CompactRowLayout layout = row.getLayout();
    assertTrue(layout.allFieldsNotNullable);
    assertEquals(layout.bitmapWidthInBytes, 0);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    assertFalse(row.isNullAt(0));
    assertFalse(row.isNullAt(1));
    assertFalse(row.isNullAt(2));
    assertEquals(encoder.fromRow(row), bean);
  }
}
