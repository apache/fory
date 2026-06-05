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

package org.apache.fory.annotation.processing;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.exception.SerializationException;
import org.apache.fory.meta.FieldInfo;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.serializer.StaticGeneratedStructSerializer;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.Types;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class ForyStructProcessorTest {
  @Test
  public void testStaticSerializerSelectedWithCodegenDisabled() throws Exception {
    CompilationResult result =
        compile(
            "test.SimpleStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class SimpleStruct {\n"
                + "  public int id;\n"
                + "  public String name;\n"
                + "  public SimpleStruct() {}\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> type = loader.loadClass("test.SimpleStruct");
      Class<?> serializerType = loader.loadClass("test.SimpleStruct_ForyNativeSerializer");
      Assert.assertTrue(StaticGeneratedStructSerializer.class.isAssignableFrom(serializerType));

      Object value = type.getConstructor().newInstance();
      setField(type, value, "id", 7);
      setField(type, value, "name", "fory");

      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withClassLoader(loader)
              .withCodegen(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      Object serializer = fory.getTypeResolver().getTypeInfo(type).getSerializer();
      Assert.assertEquals(serializer.getClass().getName(), serializerType.getName());
      Object roundTrip = fory.deserialize(fory.serialize(value));
      Assert.assertEquals(getField(type, roundTrip, "id"), 7);
      Assert.assertEquals(getField(type, roundTrip, "name"), "fory");
      Object copied = fory.copy(value);
      Assert.assertEquals(getField(type, copied, "id"), 7);
      Assert.assertEquals(getField(type, copied, "name"), "fory");
    }
    String rules =
        result.generatedResource("META-INF/proguard/fory-static-generated-test.SimpleStruct.pro");
    Assert.assertTrue(rules.contains("-keep,allowoptimization class test.SimpleStruct { *; }"));
    Assert.assertTrue(rules.contains("class test.SimpleStruct_ForySerializer"));
    Assert.assertTrue(rules.contains("class test.SimpleStruct_ForyNativeSerializer"));
  }

  @Test
  public void testLegacyBooleanEvolvingAnnotationCompiles() throws Exception {
    CompilationResult result =
        compile(
            "test.LegacyFixedStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct(evolving = false) public class LegacyFixedStruct {\n"
                + "  public int id;\n"
                + "  public LegacyFixedStruct() {}\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      loader.loadClass("test.LegacyFixedStruct_ForySerializer");
      loader.loadClass("test.LegacyFixedStruct_ForyNativeSerializer");
    }
  }

  @Test
  public void testForyDebugAnnotationEmitsGeneratedFieldTracing() throws Exception {
    CompilationResult result =
        compile(
            "test.DebugStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyDebug;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct\n"
                + "@ForyDebug\n"
                + "public class DebugStruct {\n"
                + "  public int id;\n"
                + "  public String name;\n"
                + "  public DebugStruct() {}\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    String generatedSource = result.generatedSource("test/DebugStruct_ForyNativeSerializer.java");
    Assert.assertTrue(
        generatedSource.contains("private static final boolean FORY_STRUCT_DEBUG = true;"));
    Assert.assertTrue(generatedSource.contains("debugWriteField(\""));
    Assert.assertTrue(generatedSource.contains("debugReadField(\""));
    Assert.assertTrue(generatedSource.contains("debugRemoteReadField(\""));
  }

  @Test
  public void testForyStructDebugParameterIsRejected() throws Exception {
    CompilationResult result =
        compile(
            "test.LegacyDebugStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct(debug = true) public class LegacyDebugStruct {\n"
                + "  public int id;\n"
                + "  public LegacyDebugStruct() {}\n"
                + "}\n");
    Assert.assertFalse(result.success);
    Assert.assertTrue(result.diagnostics().contains("debug"), result.diagnostics());
  }

  @Test
  public void testPrivateFieldUsesAccessibleAccessors() throws Exception {
    CompilationResult result =
        compile(
            "test.PrivateStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class PrivateStruct {\n"
                + "  private int id;\n"
                + "  private String name;\n"
                + "  public PrivateStruct() {}\n"
                + "  int getId() { return id; }\n"
                + "  void setId(int id) { this.id = id; }\n"
                + "  protected String getName() { return name; }\n"
                + "  protected void setName(String name) { this.name = name; }\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> type = loader.loadClass("test.PrivateStruct");
      Object value = type.getConstructor().newInstance();
      invoke(type, value, "setId", int.class, 8);
      invoke(type, value, "setName", String.class, "static");
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withClassLoader(loader)
              .withCodegen(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      Object roundTrip = fory.deserialize(fory.serialize(value));
      Assert.assertEquals(invoke(type, roundTrip, "getId"), 8);
      Assert.assertEquals(invoke(type, roundTrip, "getName"), "static");
    }
  }

  @Test
  public void testPrivateFieldWithoutAccessorsFailsCompilation() throws Exception {
    CompilationResult result =
        compile(
            "test.BadStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class BadStruct {\n"
                + "  private int id;\n"
                + "  public BadStruct() {}\n"
                + "}\n");
    Assert.assertFalse(result.success);
    Assert.assertTrue(result.diagnostics().contains("getter/setter"), result.diagnostics());
  }

  @Test
  public void testPrivateStructFailsCompilation() throws Exception {
    CompilationResult result =
        compile(
            "test.PrivateOwner",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "public class PrivateOwner {\n"
                + "  @ForyStruct private static class HiddenStruct {\n"
                + "    int id;\n"
                + "    HiddenStruct() {}\n"
                + "  }\n"
                + "}\n");
    Assert.assertFalse(result.success);
    Assert.assertTrue(result.diagnostics().contains("must not be private"), result.diagnostics());
  }

  @Test
  public void testDuplicateForyFieldIdFailsCompilation() throws Exception {
    CompilationResult result =
        compile(
            "test.DuplicateIdStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyField;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class DuplicateIdStruct {\n"
                + "  @ForyField(id = 1) public int left;\n"
                + "  @ForyField(id = 1) public int right;\n"
                + "  public DuplicateIdStruct() {}\n"
                + "}\n");
    Assert.assertFalse(result.success);
    Assert.assertTrue(
        result.diagnostics().contains("Duplicate @ForyField id 1"), result.diagnostics());
  }

  @Test
  public void testNegativeForyFieldIdFailsCompilation() throws Exception {
    CompilationResult result =
        compile(
            "test.NegativeIdStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyField;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class NegativeIdStruct {\n"
                + "  @ForyField(id = -2) public int value;\n"
                + "  public NegativeIdStruct() {}\n"
                + "}\n");
    Assert.assertFalse(result.success);
    Assert.assertTrue(
        result
            .diagnostics()
            .contains("@ForyField id must be -1 (no tag ID) or a non-negative tag ID"),
        result.diagnostics());
  }

  @Test
  public void testInvalidScalarAnnotationCarrierFailsCompilation() throws Exception {
    CompilationResult result =
        compile(
            "test.BadScalarCarrierStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.annotation.UInt32Type;\n"
                + "@ForyStruct public class BadScalarCarrierStruct {\n"
                + "  @UInt32Type public String id;\n"
                + "  public BadScalarCarrierStruct() {}\n"
                + "}\n");
    Assert.assertFalse(result.success);
    Assert.assertTrue(
        result
            .diagnostics()
            .contains("@UInt32Type is not compatible with field type java.lang.String"),
        result.diagnostics());
  }

  @Test
  public void testInnerTypeGeneratedAsTopLevelBinaryTail() throws Exception {
    CompilationResult result =
        compile(
            "test.Outer",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "public class Outer {\n"
                + "  @ForyStruct public static class Inner {\n"
                + "    public int id;\n"
                + "    public Inner() {}\n"
                + "  }\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> xlangSerializer = loader.loadClass("test.Outer_Inner_ForySerializer");
      Class<?> nativeSerializer = loader.loadClass("test.Outer_Inner_ForyNativeSerializer");
      Assert.assertTrue(StaticGeneratedStructSerializer.class.isAssignableFrom(xlangSerializer));
      Assert.assertTrue(StaticGeneratedStructSerializer.class.isAssignableFrom(nativeSerializer));
    }
  }

  @Test
  public void testGeneratedNameCollisionFailsCompilation() throws Exception {
    CompilationResult result =
        compile(
            "test.Outer",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "public class Outer {\n"
                + "  @ForyStruct public static class Inner {\n"
                + "    public int id;\n"
                + "    public Inner() {}\n"
                + "  }\n"
                + "}\n"
                + "class Outer_Inner_ForySerializer {}\n");
    Assert.assertFalse(result.success);
    Assert.assertTrue(result.diagnostics().contains("collides"), result.diagnostics());
  }

  @Test
  public void testGeneratedDescriptorsCarryNestedTypeMetadata() throws Exception {
    CompilationResult result =
        compile(
            "test.MetadataStruct",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.annotation.Ref;\n"
                + "import org.apache.fory.annotation.Int32Type;\n"
                + "import org.apache.fory.annotation.UInt16Type;\n"
                + "import org.apache.fory.config.Int32Encoding;\n"
                + "@ForyStruct public class MetadataStruct {\n"
                + "  public List<@Ref String> names;\n"
                + "  public List<@Int32Type(encoding = Int32Encoding.FIXED) Integer> codes;\n"
                + "  public @UInt16Type int code;\n"
                + "  public MetadataStruct() {}\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    String generatedSource = result.generatedSource("test/MetadataStruct_ForySerializer.java");
    Assert.assertFalse(generatedSource.contains("TypeRef"), generatedSource);
    Assert.assertTrue(generatedSource.contains("Descriptor.generatedType("), generatedSource);
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> type = loader.loadClass("test.MetadataStruct");
      Class<?> serializerType = loader.loadClass("test.MetadataStruct_ForySerializer");
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withClassLoader(loader)
              .withCodegen(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      StaticGeneratedStructSerializer<?> serializer =
          (StaticGeneratedStructSerializer<?>)
              serializerType
                  .getConstructor(org.apache.fory.resolver.TypeResolver.class, Class.class)
                  .newInstance(fory.getTypeResolver(), type);
      Descriptor names = descriptor(serializer.getDescriptors(), "names");
      Assert.assertTrue(names.getTypeRef().hasExplicitTypeArguments());
      Assert.assertTrue(names.getTypeRef().getTypeArguments().get(0).hasTypeExtMeta());
      Assert.assertTrue(
          names.getTypeRef().getTypeArguments().get(0).getTypeExtMeta().trackingRef());
      Assert.assertTrue(names.getTypeRef().getTypeArguments().get(0).getTypeExtMeta().nullable());
      Descriptor codes = descriptor(serializer.getDescriptors(), "codes");
      Assert.assertEquals(
          codes.getTypeRef().getTypeArguments().get(0).getTypeExtMeta().typeId(), Types.INT32);
      Assert.assertTrue(codes.getTypeRef().getTypeArguments().get(0).getTypeExtMeta().nullable());
      Descriptor code = descriptor(serializer.getDescriptors(), "code");
      Assert.assertTrue(code.getTypeRef().hasTypeExtMeta());
      Assert.assertEquals(code.getTypeRef().getTypeExtMeta().typeId(), Types.UINT16);
      Assert.assertFalse(code.getTypeRef().getTypeExtMeta().nullable());
      Assert.assertFalse(code.getTypeRef().getTypeExtMeta().trackingRef());
    }
  }

  @Test
  public void testGeneratedDescriptorsPreserveRefMetadataPresence() throws Exception {
    CompilationResult result =
        compile(
            "test.RefMetadataStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.annotation.Ref;\n"
                + "@ForyStruct public class RefMetadataStruct {\n"
                + "  public Customer peer;\n"
                + "  @Ref(enable = false) public Customer localOnly;\n"
                + "  public RefMetadataStruct() {}\n"
                + "  public static class Customer { public String id; public Customer() {} }\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> type = loader.loadClass("test.RefMetadataStruct");
      Class<?> serializerType = loader.loadClass("test.RefMetadataStruct_ForyNativeSerializer");
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withClassLoader(loader)
              .withCodegen(false)
              .withRefTracking(true)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      StaticGeneratedStructSerializer<?> serializer =
          (StaticGeneratedStructSerializer<?>)
              serializerType
                  .getConstructor(org.apache.fory.resolver.TypeResolver.class, Class.class)
                  .newInstance(fory.getTypeResolver(), type);
      Descriptor peer = descriptor(serializer.getDescriptors(), "peer");
      Assert.assertFalse(peer.hasTrackingRefMetadata());
      Assert.assertFalse(peer.isTrackingRef());
      Descriptor localOnly = descriptor(serializer.getDescriptors(), "localOnly");
      Assert.assertTrue(localOnly.hasTrackingRefMetadata());
      Assert.assertFalse(localOnly.isTrackingRef());

      TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), type);
      Assert.assertTrue(fieldInfo(typeDef, "peer").getFieldType().trackingRef());
      Assert.assertFalse(fieldInfo(typeDef, "localOnly").getFieldType().trackingRef());
    }
  }

  @Test
  public void testGeneratedUnsignedScalarWritesValidateRange() throws Exception {
    CompilationResult result =
        compile(
            "test.UnsignedStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.annotation.UInt8Type;\n"
                + "import org.apache.fory.annotation.UInt16Type;\n"
                + "import org.apache.fory.annotation.UInt32Type;\n"
                + "@ForyStruct public class UnsignedStruct {\n"
                + "  public @UInt8Type int u8;\n"
                + "  public @UInt16Type int u16;\n"
                + "  public @UInt32Type long u32;\n"
                + "  public UnsignedStruct() {}\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> type = loader.loadClass("test.UnsignedStruct");
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withClassLoader(loader)
              .withCodegen(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      Object value = type.getConstructor().newInstance();
      setField(type, value, "u8", 255);
      setField(type, value, "u16", 65535);
      setField(type, value, "u32", 4294967295L);
      Object roundTrip = fory.deserialize(fory.serialize(value));
      Assert.assertEquals(getField(type, roundTrip, "u8"), 255);
      Assert.assertEquals(getField(type, roundTrip, "u16"), 65535);
      Assert.assertEquals(getField(type, roundTrip, "u32"), 4294967295L);

      setField(type, value, "u8", 256);
      Assert.assertThrows(SerializationException.class, () -> fory.serialize(value));
      setField(type, value, "u8", 255);
      setField(type, value, "u16", 65536);
      Assert.assertThrows(SerializationException.class, () -> fory.serialize(value));
      setField(type, value, "u16", 65535);
      setField(type, value, "u32", 4294967296L);
      Assert.assertThrows(SerializationException.class, () -> fory.serialize(value));
    }
  }

  @Test
  public void testRecordReadAndCopyUseCanonicalConstructor() throws Exception {
    assumeRecordSupport();
    CompilationResult result =
        compile(
            "test.RecordStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.annotation.Ignore;\n"
                + "@ForyStruct public record RecordStruct(int id, String ignored, String name) {\n"
                + "  @Ignore public String ignored() { return ignored; }\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    String generatedSource = result.generatedSource("test/RecordStruct_ForyNativeSerializer.java");
    Assert.assertTrue(generatedSource.contains("private void readCompatibleRecordField0("));
    Assert.assertTrue(generatedSource.contains("switch (remoteField.matchedId)"));
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> type = loader.loadClass("test.RecordStruct");
      Object value =
          type.getConstructor(int.class, String.class, String.class)
              .newInstance(5, "skip", "record");
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withClassLoader(loader)
              .withCodegen(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      Object roundTrip = fory.deserialize(fory.serialize(value));
      Assert.assertEquals(invoke(type, roundTrip, "id"), 5);
      Assert.assertNull(invoke(type, roundTrip, "ignored"));
      Assert.assertEquals(invoke(type, roundTrip, "name"), "record");
      Object copied = fory.copy(value);
      Assert.assertEquals(invoke(type, copied, "id"), 5);
      Assert.assertNull(invoke(type, copied, "ignored"));
      Assert.assertEquals(invoke(type, copied, "name"), "record");
    }
  }

  @Test
  public void testCompatibleReadUsesGeneratedSerializer() throws Exception {
    CompilationResult writerResult =
        compile(
            "test.EvolvingStruct",
            "package test;\n"
                + "import java.math.BigDecimal;\n"
                + "import org.apache.fory.annotation.ForyField;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.annotation.Nullable;\n"
                + "@ForyStruct public class EvolvingStruct {\n"
                + "  @Nullable @ForyField(id = 1) public String id;\n"
                + "  @Nullable @ForyField(id = 2) public String name;\n"
                + "  @Nullable @ForyField(id = 4) public String flag;\n"
                + "  @ForyField(id = 5) public int text;\n"
                + "  @Nullable @ForyField(id = 6) public String decimalText;\n"
                + "  @Nullable @ForyField(id = 7) public BigDecimal decimalValue;\n"
                + "  @ForyField(id = 8) public long narrow;\n"
                + "  public EvolvingStruct() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.EvolvingStruct",
            "package test;\n"
                + "import java.math.BigDecimal;\n"
                + "import org.apache.fory.annotation.ForyField;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.annotation.Nullable;\n"
                + "@ForyStruct public class EvolvingStruct {\n"
                + "  @ForyField(id = 1) public int id;\n"
                + "  @Nullable @ForyField(id = 2) public String name;\n"
                + "  @Nullable @ForyField(id = 3) public String added = \"default\";\n"
                + "  @ForyField(id = 4) public boolean flag;\n"
                + "  @Nullable @ForyField(id = 5) public String text;\n"
                + "  @Nullable @ForyField(id = 6) public BigDecimal decimalText;\n"
                + "  @Nullable @ForyField(id = 7) public String decimalValue;\n"
                + "  @ForyField(id = 8) public int narrow;\n"
                + "  public EvolvingStruct() {}\n"
                + "}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    String generatedSource =
        readerResult.generatedSource("test/EvolvingStruct_ForyNativeSerializer.java");
    Assert.assertTrue(
        generatedSource.contains("readCompatibleField(readContext, value, remoteField)"));
    Assert.assertTrue(generatedSource.contains("private void readCompatibleField0("));
    Assert.assertTrue(generatedSource.contains("switch (remoteField.matchedId)"));
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.EvolvingStruct");
      Object value = writerType.getConstructor().newInstance();
      setField(writerType, value, "id", "42");
      setField(writerType, value, "name", "old");
      setField(writerType, value, "flag", "true");
      setField(writerType, value, "text", 7);
      setField(writerType, value, "decimalText", "12.50");
      setField(writerType, value, "decimalValue", new java.math.BigDecimal("10.500"));
      setField(writerType, value, "narrow", 123L);
      Fory writer =
          Fory.builder()
              .withXlang(false)
              .withClassLoader(writerLoader)
              .withCodegen(false)
              .withMetaShare(true)
              .withScopedMetaShare(false)
              .withCompatible(true)
              .requireClassRegistration(false)
              .build();
      writer.setMetaWriteContext(new MetaWriteContext());
      byte[] bytes = writer.serialize(value);

      Class<?> readerType = readerLoader.loadClass("test.EvolvingStruct");
      Fory reader =
          Fory.builder()
              .withXlang(false)
              .withClassLoader(readerLoader)
              .withCodegen(false)
              .withMetaShare(true)
              .withScopedMetaShare(false)
              .withCompatible(true)
              .requireClassRegistration(false)
              .build();
      reader.setMetaReadContext(new MetaReadContext());
      Object roundTrip = reader.deserialize(bytes);
      Assert.assertSame(roundTrip.getClass(), readerType);
      Assert.assertEquals(getField(readerType, roundTrip, "id"), 42);
      Assert.assertEquals(getField(readerType, roundTrip, "name"), "old");
      Assert.assertEquals(getField(readerType, roundTrip, "added"), "default");
      Assert.assertEquals(getField(readerType, roundTrip, "flag"), true);
      Assert.assertEquals(getField(readerType, roundTrip, "text"), "7");
      Assert.assertEquals(
          ((java.math.BigDecimal) getField(readerType, roundTrip, "decimalText"))
              .compareTo(new java.math.BigDecimal("12.5")),
          0);
      Assert.assertEquals(getField(readerType, roundTrip, "decimalValue"), "10.5");
      Assert.assertEquals(getField(readerType, roundTrip, "narrow"), 123);
      Object serializer = reader.getTypeResolver().getTypeInfo(readerType).getSerializer();
      Assert.assertTrue(serializer instanceof StaticGeneratedStructSerializer);
    }
  }

  @Test
  public void testStaticSerializerRoundTripsWithRuntimeSerializerSchemaConsistent()
      throws Exception {
    assertStaticRuntimeRoundTrip(false);
  }

  @Test
  public void testStaticSerializerRoundTripsWithRuntimeSerializerCompatible() throws Exception {
    assertStaticRuntimeRoundTrip(true);
  }

  @Test
  public void testStaticSerializerHandlesMonomorphicRecursiveField() throws Exception {
    CompilationResult result =
        compile(
            "test.RecursiveStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyField;\n"
                + "import org.apache.fory.annotation.ForyField.Dynamic;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.annotation.Nullable;\n"
                + "import org.apache.fory.annotation.Ref;\n"
                + "@ForyStruct public class RecursiveStruct {\n"
                + "  public int id;\n"
                + "  @Nullable @Ref @ForyField(dynamic = Dynamic.FALSE)\n"
                + "  public RecursiveStruct next;\n"
                + "  public RecursiveStruct() {}\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> type = loader.loadClass("test.RecursiveStruct");
      Object value = type.getConstructor().newInstance();
      setField(type, value, "id", 12);
      setField(type, value, "next", value);

      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withClassLoader(loader)
              .withCodegen(false)
              .withRefTracking(true)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      Object serializer = fory.getTypeResolver().getTypeInfo(type).getSerializer();
      Assert.assertTrue(serializer instanceof StaticGeneratedStructSerializer);
      Object roundTrip = fory.deserialize(fory.serialize(value));
      Assert.assertEquals(getField(type, roundTrip, "id"), 12);
      Assert.assertSame(getField(type, roundTrip, "next"), roundTrip);
    }
  }

  @Test
  public void testGeneratedDescriptorDiscoveryDoesNotSelectStaticSerializerWhenCodegenEnabled()
      throws Exception {
    CompilationResult result =
        compile(
            "test.DescriptorStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class DescriptorStruct {\n"
                + "  public int id;\n"
                + "  public String name;\n"
                + "  public DescriptorStruct() {}\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> type = loader.loadClass("test.DescriptorStruct");
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withClassLoader(loader)
              .withCodegen(true)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      List<Descriptor> descriptors = fory.getTypeResolver().getFieldDescriptors(type, true);
      Assert.assertEquals(descriptors.size(), 2);

      Object serializer = fory.getTypeResolver().getTypeInfo(type).getSerializer();
      Assert.assertFalse(
          serializer instanceof StaticGeneratedStructSerializer, serializer.getClass().getName());
    }
  }

  private static void assertStaticRuntimeRoundTrip(boolean compatible) throws Exception {
    CompilationResult staticResult =
        compile(
            "test.RoundTripStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyField;\n"
                + "import org.apache.fory.annotation.Nullable;\n"
                + "import org.apache.fory.annotation.UInt16Type;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class RoundTripStruct {\n"
                + "  public int id;\n"
                + "  public @UInt16Type int code;\n"
                + "  public String name;\n"
                + "  @Nullable @ForyField(id = 4) public String strictName;\n"
                + "  public RoundTripStruct() {}\n"
                + "}\n");
    CompilationResult runtimeResult =
        compile(
            "test.RoundTripStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyField;\n"
                + "import org.apache.fory.annotation.Nullable;\n"
                + "import org.apache.fory.annotation.UInt16Type;\n"
                + "public class RoundTripStruct {\n"
                + "  public int id;\n"
                + "  public @UInt16Type int code;\n"
                + "  public String name;\n"
                + "  @Nullable @ForyField(id = 4) public String strictName;\n"
                + "  public RoundTripStruct() {}\n"
                + "}\n");
    Assert.assertTrue(staticResult.success, staticResult.diagnostics());
    Assert.assertTrue(runtimeResult.success, runtimeResult.diagnostics());
    try (URLClassLoader staticLoader = staticResult.classLoader();
        URLClassLoader runtimeLoader = runtimeResult.classLoader()) {
      Class<?> staticType = staticLoader.loadClass("test.RoundTripStruct");
      Class<?> runtimeType = runtimeLoader.loadClass("test.RoundTripStruct");
      ThreadSafeFory staticFory = threadSafeFory(staticLoader, false, compatible);
      ThreadSafeFory runtimeFory = threadSafeFory(runtimeLoader, true, compatible);
      staticFory.register(staticType, 9101);
      runtimeFory.register(runtimeType, 9101);

      Object staticSerializer =
          staticFory.execute(
              fory -> fory.getTypeResolver().getTypeInfo(staticType).getSerializer());
      Object runtimeSerializer =
          runtimeFory.execute(
              fory -> fory.getTypeResolver().getTypeInfo(runtimeType).getSerializer());
      Assert.assertTrue(staticSerializer instanceof StaticGeneratedStructSerializer);
      Assert.assertFalse(runtimeSerializer instanceof StaticGeneratedStructSerializer);

      Object staticValue = staticType.getConstructor().newInstance();
      setField(staticType, staticValue, "id", 101);
      setField(staticType, staticValue, "code", 513);
      setField(staticType, staticValue, "name", compatible ? "compatible-static" : "static");
      setField(
          staticType,
          staticValue,
          "strictName",
          compatible ? "compatible-strict-static" : "strict-static");
      Object runtimeRoundTrip = runtimeFory.deserialize(staticFory.serialize(staticValue));
      Assert.assertSame(runtimeRoundTrip.getClass(), runtimeType);
      Assert.assertEquals(getField(runtimeType, runtimeRoundTrip, "id"), 101);
      Assert.assertEquals(getField(runtimeType, runtimeRoundTrip, "code"), 513);
      Assert.assertEquals(
          getField(runtimeType, runtimeRoundTrip, "name"),
          compatible ? "compatible-static" : "static");
      Assert.assertEquals(
          getField(runtimeType, runtimeRoundTrip, "strictName"),
          compatible ? "compatible-strict-static" : "strict-static");

      Object runtimeValue = runtimeType.getConstructor().newInstance();
      setField(runtimeType, runtimeValue, "id", 202);
      setField(runtimeType, runtimeValue, "code", 1024);
      setField(runtimeType, runtimeValue, "name", compatible ? "compatible-runtime" : "runtime");
      setField(
          runtimeType,
          runtimeValue,
          "strictName",
          compatible ? "compatible-strict-runtime" : "strict-runtime");
      Object staticRoundTrip = staticFory.deserialize(runtimeFory.serialize(runtimeValue));
      Assert.assertSame(staticRoundTrip.getClass(), staticType);
      Assert.assertEquals(getField(staticType, staticRoundTrip, "id"), 202);
      Assert.assertEquals(getField(staticType, staticRoundTrip, "code"), 1024);
      Assert.assertEquals(
          getField(staticType, staticRoundTrip, "name"),
          compatible ? "compatible-runtime" : "runtime");
      Assert.assertEquals(
          getField(staticType, staticRoundTrip, "strictName"),
          compatible ? "compatible-strict-runtime" : "strict-runtime");
    }
  }

  private static ThreadSafeFory threadSafeFory(
      ClassLoader classLoader, boolean codegen, boolean compatible) {
    return Fory.builder()
        .withXlang(false)
        .withClassLoader(classLoader)
        .withCodegen(codegen)
        .withCompatible(compatible)
        .requireClassRegistration(true)
        .buildThreadSafeForyPool(1);
  }

  @Test
  public void testStaticCompatibleReadUsesListArrayAction() throws Exception {
    CompilationResult writerResult =
        compile(
            "test.ArrayShapeStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.annotation.Int32Type;\n"
                + "import org.apache.fory.collection.Int32List;\n"
                + "import org.apache.fory.config.Int32Encoding;\n"
                + "@ForyStruct public class ArrayShapeStruct {\n"
                + "  @Int32Type(encoding = Int32Encoding.FIXED) public Int32List values;\n"
                + "  public ArrayShapeStruct() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.ArrayShapeStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class ArrayShapeStruct {\n"
                + "  public int[] values;\n"
                + "  public ArrayShapeStruct() {}\n"
                + "}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.ArrayShapeStruct");
      Class<?> readerType = readerLoader.loadClass("test.ArrayShapeStruct");
      Fory writer = xlangCompatibleFory(writerLoader, writerType, false);
      Fory reader = xlangCompatibleFory(readerLoader, readerType, false);
      Object writerValue = writerType.getConstructor().newInstance();
      setField(
          writerType,
          writerValue,
          "values",
          new org.apache.fory.collection.Int32List(new int[] {1, 2, 3}));

      Object result = reader.deserialize(writer.serialize(writerValue));
      Assert.assertSame(result.getClass(), readerType);
      Assert.assertTrue(
          Arrays.equals((int[]) getField(readerType, result, "values"), new int[] {1, 2, 3}));
    }
  }

  @Test
  public void testStaticArrayTypeListWritesDenseArrayPayload() throws Exception {
    CompilationResult writerResult =
        compile(
            "test.DenseListStruct",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.annotation.ArrayType;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.annotation.UInt32Type;\n"
                + "import org.apache.fory.type.Float16;\n"
                + "@ForyStruct public class DenseListStruct {\n"
                + "  @ArrayType public List<Integer> values;\n"
                + "  @ArrayType public List<@UInt32Type Long> unsignedValues;\n"
                + "  @ArrayType public List<Float16> float16Values;\n"
                + "  public DenseListStruct() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.DenseListStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.Float16Type;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.annotation.UInt32Type;\n"
                + "@ForyStruct public class DenseListStruct {\n"
                + "  public int[] values;\n"
                + "  public @UInt32Type int[] unsignedValues;\n"
                + "  public @Float16Type short[] float16Values;\n"
                + "  public DenseListStruct() {}\n"
                + "}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.DenseListStruct");
      Class<?> readerType = readerLoader.loadClass("test.DenseListStruct");
      Fory writer = xlangCompatibleFory(writerLoader, writerType, false, "DenseListStruct");
      Fory reader = xlangCompatibleFory(readerLoader, readerType, false, "DenseListStruct");
      Object writerValue = writerType.getConstructor().newInstance();
      setField(writerType, writerValue, "values", Arrays.asList(1, 2, 3));
      setField(
          writerType,
          writerValue,
          "unsignedValues",
          Arrays.asList(1L, 2L, Integer.toUnsignedLong(-1)));
      setField(
          writerType,
          writerValue,
          "float16Values",
          Arrays.asList(
              org.apache.fory.type.Float16.fromBits((short) 0x0000),
              org.apache.fory.type.Float16.fromBits((short) 0x3C00)));

      Object result = reader.deserialize(writer.serialize(writerValue));
      Assert.assertSame(result.getClass(), readerType);
      Assert.assertTrue(
          Arrays.equals((int[]) getField(readerType, result, "values"), new int[] {1, 2, 3}));
      Assert.assertTrue(
          Arrays.equals(
              (int[]) getField(readerType, result, "unsignedValues"), new int[] {1, 2, -1}));
      Assert.assertTrue(
          Arrays.equals(
              (short[]) getField(readerType, result, "float16Values"),
              new short[] {(short) 0x0000, (short) 0x3C00}));
    }
  }

  @Test
  public void testStaticListArrayCompatibleReadPayloadValidation() throws Exception {
    CompilationResult nullableListWriter =
        compile(
            "test.ListArrayMismatchStruct",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class ListArrayMismatchStruct {\n"
                + "  public List<Integer> values;\n"
                + "  public ListArrayMismatchStruct() {}\n"
                + "}\n");
    CompilationResult arrayReader =
        compile(
            "test.ListArrayMismatchStruct",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class ListArrayMismatchStruct {\n"
                + "  public int[] values;\n"
                + "  public ListArrayMismatchStruct() {}\n"
                + "}\n");
    Assert.assertTrue(nullableListWriter.success, nullableListWriter.diagnostics());
    Assert.assertTrue(arrayReader.success, arrayReader.diagnostics());
    try (URLClassLoader writerLoader = nullableListWriter.classLoader();
        URLClassLoader readerLoader = arrayReader.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.ListArrayMismatchStruct");
      Class<?> readerType = readerLoader.loadClass("test.ListArrayMismatchStruct");
      Fory writer = xlangCompatibleFory(writerLoader, writerType, false, "ListArrayMismatchStruct");
      Fory reader = xlangCompatibleFory(readerLoader, readerType, false, "ListArrayMismatchStruct");
      Object writerValue = writerType.getConstructor().newInstance();
      setField(writerType, writerValue, "values", Arrays.asList(1, 2, 3));
      Object result = reader.deserialize(writer.serialize(writerValue));
      Assert.assertTrue(
          Arrays.equals((int[]) getField(readerType, result, "values"), new int[] {1, 2, 3}));

      Object nullElementWriterValue = writerType.getConstructor().newInstance();
      setField(writerType, nullElementWriterValue, "values", Arrays.asList(1, null, 3));
      byte[] nullElementPayload = writer.serialize(nullElementWriterValue);
      Assert.expectThrows(
          DeserializationException.class, () -> reader.deserialize(nullElementPayload));
    }

    CompilationResult nestedListWriter =
        compile(
            "test.NestedListArrayMismatchStruct",
            "package test;\n"
                + "import java.util.Arrays;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class NestedListArrayMismatchStruct {\n"
                + "  public List<List<Integer>> values;\n"
                + "  public NestedListArrayMismatchStruct() {}\n"
                + "}\n");
    CompilationResult nestedArrayReader =
        compile(
            "test.NestedListArrayMismatchStruct",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct public class NestedListArrayMismatchStruct {\n"
                + "  public List<int[]> values;\n"
                + "  public NestedListArrayMismatchStruct() {}\n"
                + "}\n");
    Assert.assertTrue(nestedListWriter.success, nestedListWriter.diagnostics());
    Assert.assertTrue(nestedArrayReader.success, nestedArrayReader.diagnostics());
    try (URLClassLoader writerLoader = nestedListWriter.classLoader();
        URLClassLoader readerLoader = nestedArrayReader.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.NestedListArrayMismatchStruct");
      Class<?> readerType = readerLoader.loadClass("test.NestedListArrayMismatchStruct");
      Fory writer =
          xlangCompatibleFory(writerLoader, writerType, false, "NestedListArrayMismatchStruct");
      Fory reader =
          xlangCompatibleFory(readerLoader, readerType, false, "NestedListArrayMismatchStruct");
      Object writerValue = writerType.getConstructor().newInstance();
      setField(writerType, writerValue, "values", Arrays.asList(Arrays.asList(1, 2, 3)));
      byte[] payload = writer.serialize(writerValue);
      Object result = reader.deserialize(payload);
      Assert.assertNull(getField(readerType, result, "values"));
    }
  }

  private static Fory xlangCompatibleFory(ClassLoader classLoader, Class<?> type, boolean codegen) {
    return xlangCompatibleFory(classLoader, type, codegen, "ArrayShapeStruct");
  }

  private static Fory xlangCompatibleFory(
      ClassLoader classLoader, Class<?> type, boolean codegen, String typeName) {
    Fory fory =
        Fory.builder()
            .withClassLoader(classLoader)
            .withXlang(true)
            .withCompatible(true)
            .withCodegen(codegen)
            .requireClassRegistration(false)
            .build();
    fory.register(type, "test", typeName);
    return fory;
  }

  private static CompilationResult compile(String typeName, String source) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assert.assertNotNull(compiler, "Tests require a JDK compiler");
    Path root = Files.createTempDirectory("fory-processor-test");
    Path sourceRoot = root.resolve("src");
    Path classRoot = root.resolve("classes");
    Path generatedRoot = root.resolve("generated");
    Files.createDirectories(sourceRoot);
    Files.createDirectories(classRoot);
    Files.createDirectories(generatedRoot);
    Path sourceFile = sourceRoot.resolve(typeName.replace('.', '/') + ".java");
    Files.createDirectories(sourceFile.getParent());
    Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      Iterable<? extends JavaFileObject> units =
          fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(sourceFile.toFile()));
      List<String> options =
          Arrays.asList(
              "-classpath",
              System.getProperty("java.class.path"),
              "-d",
              classRoot.toString(),
              "-s",
              generatedRoot.toString());
      JavaCompiler.CompilationTask task =
          compiler.getTask(null, fileManager, diagnostics, options, null, units);
      task.setProcessors(Collections.singletonList(new ForyStructProcessor()));
      return new CompilationResult(
          classRoot, generatedRoot, task.call(), diagnostics.getDiagnostics());
    }
  }

  private static void assumeRecordSupport() {
    if (javaSpecificationVersion() < 16) {
      throw new SkipException("Record source tests require JDK 16 or newer");
    }
  }

  private static int javaSpecificationVersion() {
    String version = System.getProperty("java.specification.version");
    if (version.startsWith("1.")) {
      version = version.substring(2);
    }
    int dotIndex = version.indexOf('.');
    if (dotIndex >= 0) {
      version = version.substring(0, dotIndex);
    }
    return Integer.parseInt(version);
  }

  private static void setField(Class<?> type, Object target, String name, Object value)
      throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getField(Class<?> type, Object target, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Object invoke(Class<?> type, Object target, String name) throws Exception {
    java.lang.reflect.Method method = type.getDeclaredMethod(name);
    method.setAccessible(true);
    return method.invoke(target);
  }

  private static void invoke(
      Class<?> type, Object target, String name, Class<?> parameterType, Object value)
      throws Exception {
    java.lang.reflect.Method method = type.getDeclaredMethod(name, parameterType);
    method.setAccessible(true);
    method.invoke(target, value);
  }

  private static Descriptor descriptor(List<Descriptor> descriptors, String name) {
    for (Descriptor descriptor : descriptors) {
      if (descriptor.getName().equals(name)) {
        return descriptor;
      }
    }
    throw new AssertionError("Missing descriptor " + name);
  }

  private static FieldInfo fieldInfo(TypeDef typeDef, String name) {
    for (FieldInfo fieldInfo : typeDef.getFieldsInfo()) {
      if (fieldInfo.getFieldName().equals(name)) {
        return fieldInfo;
      }
    }
    throw new AssertionError("Missing field info " + name);
  }

  private static final class CompilationResult {
    final Path classRoot;
    final Path generatedRoot;
    final boolean success;
    final List<Diagnostic<? extends JavaFileObject>> diagnostics;

    CompilationResult(
        Path classRoot,
        Path generatedRoot,
        boolean success,
        List<Diagnostic<? extends JavaFileObject>> diagnostics) {
      this.classRoot = classRoot;
      this.generatedRoot = generatedRoot;
      this.success = success;
      this.diagnostics = new ArrayList<>(diagnostics);
    }

    URLClassLoader classLoader() throws IOException {
      URL[] urls = {classRoot.toUri().toURL()};
      return new URLClassLoader(urls, ForyStructProcessorTest.class.getClassLoader());
    }

    String generatedSource(String relativePath) throws IOException {
      return new String(
          Files.readAllBytes(generatedRoot.resolve(relativePath)), StandardCharsets.UTF_8);
    }

    String generatedResource(String relativePath) throws IOException {
      return new String(
          Files.readAllBytes(classRoot.resolve(relativePath)), StandardCharsets.UTF_8);
    }

    String diagnostics() {
      StringBuilder builder = new StringBuilder();
      for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
        builder.append(diagnostic).append('\n');
      }
      return builder.toString();
    }
  }
}
