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

package org.apache.fory.serializer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ArrayType;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.type.Descriptor;
import org.codehaus.janino.SimpleCompiler;
import org.testng.Assert;
import org.testng.annotations.Test;

public class FieldValueTypeCheckTest {
  private static final int HOLDER_ID = 200;
  private static final int FIRST_VALUE_ID = 201;
  private static final int SECOND_VALUE_ID = 202;

  public static class Widget {
    public String name;
  }

  public static class Gadget {
    public String name;
  }

  public static class WidgetHolder {
    public Widget widget;
  }

  public static class RefHolder {
    public Object aValue;
    public Widget zWidget;
  }

  public static class WireList extends ArrayList<String> {}

  public static class WireSet extends HashSet<String> {}

  public static class ListHolder {
    public List<String> values;
  }

  public static class DynamicListHolder {
    @ForyField(dynamic = ForyField.Dynamic.TRUE)
    public List<String> values;
  }

  public static class DenseListHolder {
    @ArrayType public List<Integer> values;
  }

  public static final class FinalWireList extends ArrayList<String> {}

  public static class FinalListHolder {
    public FinalWireList values;
  }

  public static class EvilDate extends Date {
    public String name;
  }

  public static class DateGadget {
    public String name;
  }

  public static class DateRefHolder {
    public Object aValue;
    public Date zDate;
  }

  public static class StreamHolder implements Serializable {
    private static final long serialVersionUID = 1L;

    public Object aValue;
    public Widget zWidget;
  }

  @Test
  public void testFreshDynamicValue() {
    Fory writer = newFory(false, false);
    registerTypes(writer, WidgetHolder.class, false);
    Fory reader = newFory(false, false);
    registerTypes(reader, WidgetHolder.class, true);

    WidgetHolder holder = new WidgetHolder();
    holder.widget = widget("attack");
    Assert.assertTrue(
        hasFrame(assertTypeFailure(reader, writer.serialize(holder)), ObjectSerializer.class));

    Fory validWriter = newFory(false, false);
    registerTypes(validWriter, WidgetHolder.class, true);
    WidgetHolder validHolder = new WidgetHolder();
    validHolder.widget = widget("valid");
    WidgetHolder result = (WidgetHolder) reader.deserialize(validWriter.serialize(validHolder));
    Assert.assertEquals(result.widget.name, "valid");
  }

  @Test
  public void testBackReference() {
    Fory writer = newFory(false, true);
    registerTypes(writer, RefHolder.class, false);
    Fory reader = newFory(false, true);
    registerTypes(reader, RefHolder.class, true);

    Widget value = widget("shared");
    RefHolder holder = new RefHolder();
    holder.aValue = value;
    holder.zWidget = value;
    Assert.assertTrue(
        hasFrame(assertTypeFailure(reader, writer.serialize(holder)), ObjectSerializer.class));
  }

  @Test
  public void testContainerValue() {
    Fory writer = newFory(false, false);
    writer.register(ListHolder.class, HOLDER_ID);
    writer.register(WireList.class, FIRST_VALUE_ID);
    writer.register(WireSet.class, SECOND_VALUE_ID);
    Fory reader = newFory(false, false);
    reader.register(ListHolder.class, HOLDER_ID);
    reader.register(WireSet.class, FIRST_VALUE_ID);
    reader.register(WireList.class, SECOND_VALUE_ID);

    ListHolder holder = new ListHolder();
    holder.values = new WireList();
    holder.values.add("value");
    Assert.assertTrue(
        hasFrame(assertTypeFailure(reader, writer.serialize(holder)), ObjectSerializer.class));
  }

  @Test
  public void testFinalContainerValue() {
    Fory writer = newFory(false, false);
    writer.register(FinalListHolder.class, HOLDER_ID);
    writer.register(FinalWireList.class, FIRST_VALUE_ID);
    writer.register(WireSet.class, SECOND_VALUE_ID);
    Fory reader = newFory(false, false);
    reader.register(FinalListHolder.class, HOLDER_ID);
    reader.register(WireSet.class, FIRST_VALUE_ID);
    reader.register(FinalWireList.class, SECOND_VALUE_ID);

    FinalListHolder holder = new FinalListHolder();
    holder.values = new FinalWireList();
    holder.values.add("value");
    DeserializationException error = assertTypeFailure(reader, writer.serialize(holder));
    Assert.assertTrue(hasFrame(error, ObjectSerializer.class));
    Assert.assertTrue(hasMessage(error, "Cannot store deserialized value of type"));
  }

  @Test
  public void testFixedContainerMetadata() throws Exception {
    Fory xlang = Fory.builder().withXlang(true).withCodegen(false).withRefTracking(false).build();
    SerializationFieldInfo xlangField = fieldInfo(xlang, DynamicListHolder.class, "values");
    Assert.assertFalse(xlangField.useDeclaredTypeInfo);
    Assert.assertFalse(xlangField.requiresFieldValueTypeCheck);

    Fory nativeFory = newFory(false, false);
    SerializationFieldInfo overrideField = fieldInfo(nativeFory, DenseListHolder.class, "values");
    Assert.assertNotNull(overrideField.containerSerializerOverride);
    Assert.assertFalse(overrideField.requiresFieldValueTypeCheck);
  }

  @Test
  public void testBuiltInBackReference() {
    Fory writer = newTimeRefFory();
    writer.register(DateRefHolder.class, HOLDER_ID);
    writer.register(EvilDate.class, FIRST_VALUE_ID);
    writer.register(DateGadget.class, SECOND_VALUE_ID);
    writer.registerSerializer(EvilDate.class, new EvilDateSerializer(writer.getConfig()));
    Fory reader = newTimeRefFory();
    reader.register(DateRefHolder.class, HOLDER_ID);
    reader.register(DateGadget.class, FIRST_VALUE_ID);
    reader.register(EvilDate.class, SECOND_VALUE_ID);

    EvilDate value = new EvilDate();
    value.name = "shared";
    DateRefHolder holder = new DateRefHolder();
    holder.aValue = value;
    holder.zDate = value;
    Assert.assertTrue(
        hasFrame(assertTypeFailure(reader, writer.serialize(holder)), ObjectSerializer.class));
  }

  @Test
  public void testCompatibleBackReference() throws Exception {
    Class<?>[] writerTypes = compileCompatibleHolder(true);
    Class<?>[] readerTypes = compileCompatibleHolder(false);
    Fory writer = newCompatibleFory(writerTypes[0].getClassLoader());
    writer.register(writerTypes[0], HOLDER_ID);
    writer.register(writerTypes[1], FIRST_VALUE_ID);
    writer.register(writerTypes[2], SECOND_VALUE_ID);
    Fory reader = newCompatibleFory(readerTypes[0].getClassLoader());
    reader.register(readerTypes[0], HOLDER_ID);
    reader.register(readerTypes[2], FIRST_VALUE_ID);
    reader.register(readerTypes[1], SECOND_VALUE_ID);

    Object holder = writerTypes[0].newInstance();
    Object value = writerTypes[1].newInstance();
    writerTypes[1].getField("name").set(value, "shared");
    writerTypes[0].getField("aValue").set(holder, value);
    writerTypes[0].getField("zWidget").set(holder, value);
    Assert.assertTrue(
        hasFrame(assertTypeFailure(reader, writer.serialize(holder)), CompatibleSerializer.class));
  }

  @Test
  public void testObjectStreamLayer() {
    Fory writer = newFory(false, true);
    registerTypes(writer, StreamHolder.class, false);
    writer.registerSerializer(
        StreamHolder.class,
        new ObjectStreamSerializer(writer.getTypeResolver(), StreamHolder.class));
    Fory reader = newFory(false, true);
    registerTypes(reader, StreamHolder.class, true);
    reader.registerSerializer(
        StreamHolder.class,
        new ObjectStreamSerializer(reader.getTypeResolver(), StreamHolder.class));

    Widget value = widget("shared");
    StreamHolder holder = new StreamHolder();
    holder.aValue = value;
    holder.zWidget = value;
    Assert.assertTrue(
        hasFrame(
            assertTypeFailure(reader, writer.serialize(holder)),
            CompatibleLayerSerializerBase.class));
  }

  @Test
  public void testStaticFieldSetter() throws Exception {
    Fory fory = newFory(false, false);
    StaticSetter setter = new StaticSetter(fory.getTypeResolver());
    WidgetHolder holder = new WidgetHolder();
    Assert.expectThrows(DeserializationException.class, () -> setter.set(holder, new Gadget()));
    Assert.assertNull(holder.widget);
  }

  private static Fory newFory(boolean compatible, boolean trackingRef) {
    return Fory.builder()
        .withXlang(false)
        .withCodegen(false)
        .withCompatible(compatible)
        .withRefTracking(trackingRef)
        .build();
  }

  private static Fory newCompatibleFory(ClassLoader classLoader) {
    return Fory.builder()
        .withXlang(false)
        .withCodegen(false)
        .withCompatible(true)
        .withRefTracking(true)
        .withClassLoader(classLoader)
        .build();
  }

  private static Fory newTimeRefFory() {
    return Fory.builder()
        .withXlang(false)
        .withCodegen(false)
        .withCompatible(false)
        .withRefTracking(true)
        .ignoreTimeRef(false)
        .build();
  }

  private static Class<?>[] compileCompatibleHolder(boolean senderOnly) throws Exception {
    String className = "org.apache.fory.serializer.dynamic.CompatibleHolder";
    String source =
        "package org.apache.fory.serializer.dynamic;"
            + " public class CompatibleHolder {"
            + " public Object aValue;"
            + " public Widget zWidget;"
            + (senderOnly ? " public int senderOnly;" : "")
            + " public static class Widget { public String name; }"
            + " public static class Gadget { public String name; }"
            + " }";
    SimpleCompiler compiler = new SimpleCompiler();
    compiler.setParentClassLoader(FieldValueTypeCheckTest.class.getClassLoader().getParent());
    compiler.cook(source);
    ClassLoader classLoader = compiler.getClassLoader();
    return new Class<?>[] {
      classLoader.loadClass(className),
      classLoader.loadClass(className + "$Widget"),
      classLoader.loadClass(className + "$Gadget")
    };
  }

  private static void registerTypes(Fory fory, Class<?> holderType, boolean swapValues) {
    fory.register(holderType, HOLDER_ID);
    fory.register(swapValues ? Gadget.class : Widget.class, FIRST_VALUE_ID);
    fory.register(swapValues ? Widget.class : Gadget.class, SECOND_VALUE_ID);
  }

  private static Widget widget(String name) {
    Widget widget = new Widget();
    widget.name = name;
    return widget;
  }

  private static DeserializationException assertTypeFailure(Fory reader, byte[] bytes) {
    return Assert.expectThrows(DeserializationException.class, () -> reader.deserialize(bytes));
  }

  private static boolean hasFrame(Throwable error, Class<?> owner) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      for (StackTraceElement frame : current.getStackTrace()) {
        if (frame.getClassName().equals(owner.getName())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasMessage(Throwable error, String text) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current.getMessage() != null && current.getMessage().contains(text)) {
        return true;
      }
    }
    return false;
  }

  private static SerializationFieldInfo fieldInfo(Fory fory, Class<?> type, String fieldName)
      throws NoSuchFieldException {
    return FieldGroups.buildFieldsInfo(
            fory.getTypeResolver(), Collections.singletonList(type.getDeclaredField(fieldName)))
        .allFields[0];
  }

  private static final class StaticSetter extends StaticGeneratedStructSerializer<WidgetHolder> {
    private final SerializationFieldInfo fieldInfo;

    private StaticSetter(TypeResolver typeResolver) throws NoSuchFieldException {
      super(typeResolver, WidgetHolder.class);
      fieldInfo =
          FieldGroups.buildFieldsInfo(
                  typeResolver,
                  Collections.singletonList(WidgetHolder.class.getDeclaredField("widget")))
              .allFields[0];
    }

    private void set(WidgetHolder holder, Object value) {
      setReadFieldValue(holder, fieldInfo, value);
    }

    @Override
    public void write(WriteContext writeContext, WidgetHolder value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WidgetHolder read(ReadContext readContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WidgetHolder copy(CopyContext copyContext, WidgetHolder value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Descriptor> getGeneratedDescriptors() {
      return Collections.emptyList();
    }

    @Override
    public WidgetHolder readCompatible(ReadContext readContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StaticGeneratedStructSerializer<WidgetHolder> copySerializer(
        TypeResolver typeResolver, Class<?> type, TypeDef typeDef) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class EvilDateSerializer extends Serializer<EvilDate> {
    private EvilDateSerializer(org.apache.fory.config.Config config) {
      super(config, EvilDate.class, true, false);
    }

    @Override
    public void write(WriteContext writeContext, EvilDate value) {
      writeContext.getBuffer().writeByte(Fory.NOT_NULL_VALUE_FLAG);
      writeContext.writeString(value.name);
    }

    @Override
    public EvilDate read(ReadContext readContext) {
      EvilDate value = new EvilDate();
      if (readContext.getBuffer().readByte() != Fory.NULL_FLAG) {
        value.name = readContext.readString();
      }
      return value;
    }
  }
}
