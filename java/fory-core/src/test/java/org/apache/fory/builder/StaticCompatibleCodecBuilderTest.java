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

package org.apache.fory.builder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.apache.fory.Fory;
import org.apache.fory.builder.Generated.GeneratedStaticCompatibleSerializer;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.FieldCodecCategory;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.StaticGeneratedStructSerializer;
import org.apache.fory.serializer.StaticGeneratedStructSerializer.RemoteFieldInfo;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class StaticCompatibleCodecBuilderTest {
  @DataProvider
  public static Object[][] xlangModes() {
    return new Object[][] {{false}, {true}};
  }

  @Test
  public void testForyDebugAnnotationEnablesStaticCompatibleTracing() throws Exception {
    CompilationResult debugResult =
        compile(
            "test.StaticCompatibleDebugPayload",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyDebug;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct\n"
                + "@ForyDebug\n"
                + "public class StaticCompatibleDebugPayload {\n"
                + "  public int id;\n"
                + "  public StaticCompatibleDebugPayload() {}\n"
                + "}\n");
    CompilationResult plainResult =
        compile(
            "test.StaticCompatiblePlainPayload",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "@ForyStruct\n"
                + "public class StaticCompatiblePlainPayload {\n"
                + "  public int id;\n"
                + "  public StaticCompatiblePlainPayload() {}\n"
                + "}\n");
    Assert.assertTrue(debugResult.success, debugResult.diagnostics());
    Assert.assertTrue(plainResult.success, plainResult.diagnostics());
    try (URLClassLoader debugLoader = debugResult.classLoader();
        URLClassLoader plainLoader = plainResult.classLoader()) {
      assertStaticCompatibleDebugTracing(
          debugLoader,
          debugLoader.loadClass("test.StaticCompatibleDebugPayload"),
          true,
          "debug-reader");
      assertStaticCompatibleDebugTracing(
          plainLoader,
          plainLoader.loadClass("test.StaticCompatiblePlainPayload"),
          false,
          "plain-reader");
    }
  }

  @Test(dataProvider = "xlangModes")
  public void testStaticCompatibleSerializerReadsRuntimeRemoteTypeDef(boolean xlang)
      throws Exception {
    CompilationResult writerResult =
        compile(
            "test.StaticCompatiblePayload",
            "package test;\n"
                + "public class StaticCompatiblePayload {\n"
                + "  public int id;\n"
                + "  public int legacy;\n"
                + "  public String name;\n"
                + "  public StaticCompatiblePayload() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.StaticCompatiblePayload",
            "package test;\n"
                + "public class StaticCompatiblePayload {\n"
                + "  public int id;\n"
                + "  public String added = \"default\";\n"
                + "  public String name;\n"
                + "  public StaticCompatiblePayload() {}\n"
                + "}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.StaticCompatiblePayload");
      Class<?> readerType = readerLoader.loadClass("test.StaticCompatiblePayload");
      Fory writer = compatibleFory(writerLoader, writerType, xlang, "writer");
      Fory reader = compatibleFory(readerLoader, readerType, xlang, "reader");
      Object writerValue = writerType.getConstructor().newInstance();
      setField(writerType, writerValue, "id", 42);
      setField(writerType, writerValue, "legacy", 99);
      setField(writerType, writerValue, "name", xlang ? "xlang" : "native");

      Object result =
          roundTripThroughStaticCompatibleSerializer(
              writer, reader, writerType, readerType, writerValue);
      Assert.assertSame(result.getClass(), readerType);
      Assert.assertEquals(getField(readerType, result, "id"), 42);
      Assert.assertEquals(getField(readerType, result, "added"), "default");
      Assert.assertEquals(getField(readerType, result, "name"), xlang ? "xlang" : "native");
    }
  }

  @Test
  public void testStaticCompatibleSerializerPreservesRemoteCodecCategories() throws Exception {
    CompilationResult writerResult =
        compile(
            "test.StaticCompatibleCategoryPayload",
            "package test;\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "public class StaticCompatibleCategoryPayload {\n"
                + "  public int id;\n"
                + "  public String name;\n"
                + "  public List<String> labels;\n"
                + "  public Map<String, Integer> scores;\n"
                + "  public StaticCompatibleCategoryPayload() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.StaticCompatibleCategoryPayload",
            "package test;\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "public class StaticCompatibleCategoryPayload {\n"
                + "  public int id;\n"
                + "  public String name;\n"
                + "  public List<String> labels;\n"
                + "  public Map<String, Integer> scores;\n"
                + "  public StaticCompatibleCategoryPayload() {}\n"
                + "}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.StaticCompatibleCategoryPayload");
      Class<?> readerType = readerLoader.loadClass("test.StaticCompatibleCategoryPayload");
      Fory writer = compatibleFory(writerLoader, writerType, false, "category-writer");
      Fory reader = compatibleFory(readerLoader, readerType, false, "category-reader");
      TypeDef remoteTypeDef = TypeDef.buildTypeDef(writer.getTypeResolver(), writerType);
      Class<Object> readerClass = cast(readerType);
      Class<? extends Serializer> compatibleSerializerClass =
          CodecUtils.loadOrGenStaticCompatibleCodecClass(
              reader.getTypeResolver(), readerClass, remoteTypeDef);
      Serializer<Object> compatibleSerializer =
          compatibleSerializerClass
              .getConstructor(TypeResolver.class, Class.class, TypeDef.class)
              .newInstance(reader.getTypeResolver(), readerClass, remoteTypeDef);

      Map<String, FieldCodecCategory> categories = remoteCodecCategories(compatibleSerializer);
      Assert.assertEquals(categories.get("id"), FieldCodecCategory.BUILD_IN);
      Assert.assertEquals(categories.get("name"), FieldCodecCategory.BUILD_IN);
      Assert.assertEquals(categories.get("labels"), FieldCodecCategory.CONTAINER);
      Assert.assertEquals(categories.get("scores"), FieldCodecCategory.CONTAINER);
    }
  }

  @Test
  public void testStaticCompatibleRecordSerializerConvertsRemoteField() throws Exception {
    assumeRecordSupport();
    CompilationResult writerResult =
        compile(
            "test.StaticCompatibleRecordPayload",
            "package test;\n"
                + "public class StaticCompatibleRecordPayload {\n"
                + "  public String id;\n"
                + "  public StaticCompatibleRecordPayload() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.StaticCompatibleRecordPayload",
            "package test;\n" + "public record StaticCompatibleRecordPayload(int id) {}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.StaticCompatibleRecordPayload");
      Class<?> readerType = readerLoader.loadClass("test.StaticCompatibleRecordPayload");
      Fory writer = compatibleFory(writerLoader, writerType, false, "record-writer");
      Fory reader = compatibleFory(readerLoader, readerType, false, "record-reader");
      TypeDef remoteTypeDef = TypeDef.buildTypeDef(writer.getTypeResolver(), writerType);
      String generatedSource =
          new StaticCompatibleCodecBuilder(TypeRef.of(readerType), reader, remoteTypeDef).genCode();
      Assert.assertTrue(generatedSource.contains("case 0:"));
      Assert.assertTrue(generatedSource.contains("case 1:"));
      Assert.assertTrue(generatedSource.contains("FieldConverters.readIntTarget"));
      Assert.assertTrue(generatedSource.contains("int _f_recordValue0 = 0;"));
      Assert.assertTrue(
          generatedSource.contains(
              "return new test.StaticCompatibleRecordPayload(_f_recordValue0);"));
      Assert.assertFalse(generatedSource.contains("newInstanceWithArguments"));
      Assert.assertFalse(generatedSource.contains("Object[] _f_recordArgs = this._f_recordArgs"));
      Assert.assertFalse(generatedSource.contains("_f_recordValues"));
      Assert.assertFalse(generatedSource.contains("readMatchedRecordField"));
      Object writerValue = writerType.getConstructor().newInstance();
      setField(writerType, writerValue, "id", "73");

      Object result =
          roundTripThroughStaticCompatibleSerializer(
              writer, reader, writerType, readerType, writerValue);
      Assert.assertSame(result.getClass(), readerType);
      Assert.assertEquals(invoke(readerType, result, "id"), 73);
    }
  }

  @Test
  public void testInaccessibleRecordInstantiator() throws Exception {
    assumeRecordSupport();
    CompilationResult writerResult =
        compile(
            "test.StaticCompatibleHiddenRecordPayload",
            "package test;\n"
                + "public class StaticCompatibleHiddenRecordPayload {\n"
                + "  public String id;\n"
                + "  public StaticCompatibleHiddenRecordPayload() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.StaticCompatibleHiddenRecordPayload",
            "package test;\n" + "record StaticCompatibleHiddenRecordPayload(int id) {}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.StaticCompatibleHiddenRecordPayload");
      Class<?> readerType = readerLoader.loadClass("test.StaticCompatibleHiddenRecordPayload");
      Fory writer = compatibleFory(writerLoader, writerType, false, "hidden-record-writer");
      Fory reader = compatibleFory(readerLoader, readerType, false, "hidden-record-reader");
      TypeDef remoteTypeDef = TypeDef.buildTypeDef(writer.getTypeResolver(), writerType);
      String generatedSource =
          new StaticCompatibleCodecBuilder(TypeRef.of(readerType), reader, remoteTypeDef).genCode();
      Assert.assertTrue(generatedSource.contains("newInstanceWithArguments"));
      Assert.assertTrue(generatedSource.contains("Object[] _f_recordArgs = this._f_recordArgs"));
      Assert.assertFalse(
          generatedSource.contains("return new test.StaticCompatibleHiddenRecordPayload"));
    }
  }

  @Test
  public void testCompatibleRecordSerializerConvertsRemoteField() throws Exception {
    assumeRecordSupport();
    CompilationResult writerResult =
        compile(
            "test.CompatibleRecordPayload",
            "package test;\n"
                + "public class CompatibleRecordPayload {\n"
                + "  public String id;\n"
                + "  public CompatibleRecordPayload() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.CompatibleRecordPayload",
            "package test;\n" + "public record CompatibleRecordPayload(int id) {}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.CompatibleRecordPayload");
      Class<?> readerType = readerLoader.loadClass("test.CompatibleRecordPayload");
      Fory writer = compatibleFory(writerLoader, writerType, false, "record-reflect-writer", false);
      Fory reader = compatibleFory(readerLoader, readerType, false, "record-reflect-reader", false);
      Object writerValue = writerType.getConstructor().newInstance();
      setField(writerType, writerValue, "id", "73");

      writer.setMetaWriteContext(new MetaWriteContext());
      byte[] bytes = writer.serialize(writerValue);
      reader.setMetaReadContext(new MetaReadContext());
      Object result = reader.deserialize(bytes);
      Assert.assertSame(result.getClass(), readerType);
      Assert.assertEquals(invoke(readerType, result, "id"), 73);
    }
  }

  @Test
  public void testStaticCompatibleSerializerUsesListArrayAction() throws Exception {
    CompilationResult writerResult =
        compile(
            "test.StaticCompatibleArrayPayload",
            "package test;\n"
                + "import org.apache.fory.annotation.Int32Type;\n"
                + "import org.apache.fory.collection.Int32List;\n"
                + "import org.apache.fory.config.Int32Encoding;\n"
                + "public class StaticCompatibleArrayPayload {\n"
                + "  @Int32Type(encoding = Int32Encoding.FIXED) public Int32List values;\n"
                + "  public StaticCompatibleArrayPayload() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.StaticCompatibleArrayPayload",
            "package test;\n"
                + "public class StaticCompatibleArrayPayload {\n"
                + "  public int[] values;\n"
                + "  public StaticCompatibleArrayPayload() {}\n"
                + "}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.StaticCompatibleArrayPayload");
      Class<?> readerType = readerLoader.loadClass("test.StaticCompatibleArrayPayload");
      Fory writer = compatibleFory(writerLoader, writerType, true, "array-writer");
      Fory reader = compatibleFory(readerLoader, readerType, true, "array-reader");
      Object writerValue = writerType.getConstructor().newInstance();
      setField(
          writerType,
          writerValue,
          "values",
          new org.apache.fory.collection.Int32List(new int[] {4, 5, 6}));

      Object result =
          roundTripThroughStaticCompatibleSerializer(
              writer, reader, writerType, readerType, writerValue);
      Assert.assertSame(result.getClass(), readerType);
      Assert.assertTrue(
          Arrays.equals((int[]) getField(readerType, result, "values"), new int[] {4, 5, 6}));
    }
  }

  @Test
  public void testStaticCompatibleSkipsUnknownBackReferenceField() throws Exception {
    CompilationResult writerResult =
        compile(
            "test.StaticCompatibleRefPayload",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.annotation.Nullable;\n"
                + "import org.apache.fory.annotation.Ref;\n"
                + "public class StaticCompatibleRefPayload {\n"
                + "  @Nullable @Ref public String name;\n"
                + "  @Nullable @Ref public String nameAlias;\n"
                + "  public List<String> after;\n"
                + "  public StaticCompatibleRefPayload() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.StaticCompatibleRefPayload",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.annotation.Nullable;\n"
                + "import org.apache.fory.annotation.Ref;\n"
                + "public class StaticCompatibleRefPayload {\n"
                + "  @Nullable @Ref public String name;\n"
                + "  public List<String> after;\n"
                + "  public StaticCompatibleRefPayload() {}\n"
                + "}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.StaticCompatibleRefPayload");
      Class<?> readerType = readerLoader.loadClass("test.StaticCompatibleRefPayload");
      Fory writer = compatibleFory(writerLoader, writerType, false, "ref-writer");
      Fory reader = compatibleFory(readerLoader, readerType, false, "ref-reader");
      Object writerValue = writerType.getConstructor().newInstance();
      String shared = new String("shared");
      setField(writerType, writerValue, "name", shared);
      setField(writerType, writerValue, "nameAlias", shared);
      setField(writerType, writerValue, "after", Arrays.asList("after"));

      Object result =
          roundTripThroughStaticCompatibleSerializer(
              writer, reader, writerType, readerType, writerValue);
      Assert.assertSame(result.getClass(), readerType);
      Assert.assertEquals(getField(readerType, result, "name"), "shared");
      Assert.assertEquals(getField(readerType, result, "after"), Arrays.asList("after"));
    }
  }

  @Test
  public void testStaticCompatibleArrayPayloadReadsOrdinaryAnnotatedList() throws Exception {
    CompilationResult writerResult =
        compile(
            "test.StaticCompatibleAnnotatedListPayload",
            "package test;\n"
                + "public class StaticCompatibleAnnotatedListPayload {\n"
                + "  public int[] values;\n"
                + "  public StaticCompatibleAnnotatedListPayload() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.StaticCompatibleAnnotatedListPayload",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.annotation.Int32Type;\n"
                + "import org.apache.fory.config.Int32Encoding;\n"
                + "public class StaticCompatibleAnnotatedListPayload {\n"
                + "  public List<@Int32Type(encoding = Int32Encoding.FIXED) Integer> values;\n"
                + "  public StaticCompatibleAnnotatedListPayload() {}\n"
                + "}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.StaticCompatibleAnnotatedListPayload");
      Class<?> readerType = readerLoader.loadClass("test.StaticCompatibleAnnotatedListPayload");
      Fory writer = compatibleFory(writerLoader, writerType, true, "annotated-list-writer");
      Fory reader = compatibleFory(readerLoader, readerType, true, "annotated-list-reader");
      Object writerValue = writerType.getConstructor().newInstance();
      setField(writerType, writerValue, "values", new int[] {7, 8, 9});

      Object result =
          roundTripThroughStaticCompatibleSerializer(
              writer, reader, writerType, readerType, writerValue);
      Assert.assertSame(result.getClass(), readerType);
      Assert.assertEquals(getField(readerType, result, "values"), Arrays.asList(7, 8, 9));
    }
  }

  @Test
  public void testStaticRejectsNestedRefSchema() throws Exception {
    assertStaticSchemaFails(
        "package test;\n"
            + "import java.util.List;\n"
            + "import org.apache.fory.annotation.Ref;\n"
            + "public class StaticCompatibleNestedPayload {\n"
            + "  public List<@Ref String> values;\n"
            + "  public StaticCompatibleNestedPayload() {}\n"
            + "}\n",
        "package test;\n"
            + "import java.util.List;\n"
            + "public class StaticCompatibleNestedPayload {\n"
            + "  public List<String> values;\n"
            + "  public StaticCompatibleNestedPayload() {}\n"
            + "}\n");
  }

  @Test
  public void testStaticRejectsNestedArraySchema() throws Exception {
    assertStaticSchemaFails(
        "package test;\n"
            + "import java.util.List;\n"
            + "public class StaticCompatibleNestedPayload {\n"
            + "  public List<byte[]> values;\n"
            + "  public StaticCompatibleNestedPayload() {}\n"
            + "}\n",
        "package test;\n"
            + "import java.util.List;\n"
            + "import org.apache.fory.annotation.UInt8Type;\n"
            + "public class StaticCompatibleNestedPayload {\n"
            + "  public List<@UInt8Type byte[]> values;\n"
            + "  public StaticCompatibleNestedPayload() {}\n"
            + "}\n");
  }

  @Test
  public void testStaticBinaryUint8ArrayUsesCompatibleCase() throws Exception {
    CompilationResult writerResult =
        compile(
            "test.StaticCompatibleBytePayload",
            "package test;\n"
                + "public class StaticCompatibleBytePayload {\n"
                + "  public byte[] value;\n"
                + "  public StaticCompatibleBytePayload() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.StaticCompatibleBytePayload",
            "package test;\n"
                + "import org.apache.fory.annotation.UInt8Type;\n"
                + "public class StaticCompatibleBytePayload {\n"
                + "  @UInt8Type public byte[] value;\n"
                + "  public StaticCompatibleBytePayload() {}\n"
                + "}\n");
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.StaticCompatibleBytePayload");
      Class<?> readerType = readerLoader.loadClass("test.StaticCompatibleBytePayload");
      Fory writer = compatibleFory(writerLoader, writerType, true, "byte-writer");
      Fory reader = compatibleFory(readerLoader, readerType, true, "uint8-reader");
      TypeDef remoteTypeDef = TypeDef.buildTypeDef(writer.getTypeResolver(), writerType);
      Class<? extends Serializer> compatibleSerializerClass =
          CodecUtils.loadOrGenStaticCompatibleCodecClass(
              reader.getTypeResolver(), cast(readerType), remoteTypeDef);
      Serializer<Object> compatibleSerializer =
          compatibleSerializerClass
              .getConstructor(TypeResolver.class, Class.class, TypeDef.class)
              .newInstance(reader.getTypeResolver(), readerType, remoteTypeDef);
      Assert.assertEquals(remoteFields(compatibleSerializer).get(0).matchedId, 1);

      Object writerValue = writerType.getConstructor().newInstance();
      setField(writerType, writerValue, "value", new byte[] {1, 2});
      Object result =
          roundTripThroughStaticCompatibleSerializer(
              writer, reader, writerType, readerType, writerValue);
      Assert.assertTrue(
          Arrays.equals((byte[]) getField(readerType, result, "value"), new byte[] {1, 2}));
    }
  }

  @Test
  public void testStaticRejectsRootPrimitiveArraySchema() throws Exception {
    assertStaticSchemaFails(
        "package test;\n"
            + "public class StaticCompatibleNestedPayload {\n"
            + "  public byte[] values;\n"
            + "  public StaticCompatibleNestedPayload() {}\n"
            + "}\n",
        "package test;\n"
            + "import org.apache.fory.annotation.Int8Type;\n"
            + "public class StaticCompatibleNestedPayload {\n"
            + "  @Int8Type public byte[] values;\n"
            + "  public StaticCompatibleNestedPayload() {}\n"
            + "}\n");
    assertStaticSchemaFails(
        "package test;\n"
            + "import org.apache.fory.annotation.Int8Type;\n"
            + "public class StaticCompatibleNestedPayload {\n"
            + "  @Int8Type public byte[] values;\n"
            + "  public StaticCompatibleNestedPayload() {}\n"
            + "}\n",
        "package test;\n"
            + "import org.apache.fory.annotation.UInt8Type;\n"
            + "public class StaticCompatibleNestedPayload {\n"
            + "  @UInt8Type public byte[] values;\n"
            + "  public StaticCompatibleNestedPayload() {}\n"
            + "}\n");
    assertStaticSchemaFails(
        "package test;\n"
            + "import org.apache.fory.annotation.Float16Type;\n"
            + "public class StaticCompatibleNestedPayload {\n"
            + "  @Float16Type public short[] values;\n"
            + "  public StaticCompatibleNestedPayload() {}\n"
            + "}\n",
        "package test;\n"
            + "import org.apache.fory.annotation.BFloat16Type;\n"
            + "public class StaticCompatibleNestedPayload {\n"
            + "  @BFloat16Type public short[] values;\n"
            + "  public StaticCompatibleNestedPayload() {}\n"
            + "}\n");
  }

  @Test
  public void testGraalCompatibleSerializerRegistryUsesLocalReaderClass() throws Exception {
    CompilationResult writerAResult =
        compile(
            "test.WriterPayloadA",
            "package test;\n"
                + "public class WriterPayloadA {\n"
                + "  public int id;\n"
                + "  public String oldName;\n"
                + "  public WriterPayloadA() {}\n"
                + "}\n");
    CompilationResult writerBResult =
        compile(
            "test.WriterPayloadB",
            "package test;\n"
                + "public class WriterPayloadB {\n"
                + "  public int id;\n"
                + "  public long oldCount;\n"
                + "  public WriterPayloadB() {}\n"
                + "}\n");
    CompilationResult readerResult =
        compile(
            "test.ReaderPayload",
            "package test;\n"
                + "public class ReaderPayload {\n"
                + "  public int id;\n"
                + "  public ReaderPayload() {}\n"
                + "}\n");
    Assert.assertTrue(writerAResult.success, writerAResult.diagnostics());
    Assert.assertTrue(writerBResult.success, writerBResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerALoader = writerAResult.classLoader();
        URLClassLoader writerBLoader = writerBResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerAType = writerALoader.loadClass("test.WriterPayloadA");
      Class<?> writerBType = writerBLoader.loadClass("test.WriterPayloadB");
      Class<?> readerType = readerLoader.loadClass("test.ReaderPayload");
      Fory writerA = compatibleFory(writerALoader, writerAType, false, "writer-a");
      Fory writerB = compatibleFory(writerBLoader, writerBType, false, "writer-b");
      Fory reader = compatibleFory(readerLoader, readerType, false, "reader");
      TypeDef typeDefA = TypeDef.buildTypeDef(writerA.getTypeResolver(), writerAType);
      TypeDef typeDefB = TypeDef.buildTypeDef(writerB.getTypeResolver(), writerBType);
      Assert.assertNotEquals(typeDefA.getId(), typeDefB.getId());

      Class<Object> readerClass = cast(readerType);
      Class<? extends Serializer> serializerA =
          CodecUtils.loadOrGenStaticCompatibleCodecClass(
              reader.getTypeResolver(), readerClass, typeDefA);
      Class<? extends Serializer> serializerB =
          CodecUtils.loadOrGenStaticCompatibleCodecClass(
              reader.getTypeResolver(), readerClass, typeDefB);
      Assert.assertSame(serializerA, serializerB);

      GraalvmSupport.GraalvmClassRegistry registry = GraalvmSupport.getClassRegistry(0);
      registry.putCompatibleDeserializerClass(readerType, serializerA);
      Assert.assertSame(registry.getCompatibleDeserializerClass(readerType), serializerA);
    }
  }

  private static Object roundTripThroughStaticCompatibleSerializer(
      Fory writer, Fory reader, Class<?> writerType, Class<?> readerType, Object writerValue)
      throws Exception {
    TypeDef remoteTypeDef = TypeDef.buildTypeDef(writer.getTypeResolver(), writerType);
    Assert.assertNotEquals(
        remoteTypeDef.getId(), TypeDef.buildTypeDef(reader.getTypeResolver(), readerType).getId());
    Class<Object> readerClass = cast(readerType);
    Class<? extends Serializer> compatibleSerializerClass =
        CodecUtils.loadOrGenStaticCompatibleCodecClass(
            reader.getTypeResolver(), readerClass, remoteTypeDef);
    Assert.assertTrue(
        GeneratedStaticCompatibleSerializer.class.isAssignableFrom(compatibleSerializerClass));
    Serializer<Object> compatibleSerializer =
        compatibleSerializerClass
            .getConstructor(TypeResolver.class, Class.class, TypeDef.class)
            .newInstance(reader.getTypeResolver(), readerClass, remoteTypeDef);
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> compatibleSerializer.write(reader.getWriteContext(), null));

    writer.setMetaWriteContext(new MetaWriteContext());
    byte[] bytes = writer.serialize(writerValue);
    reader.setMetaReadContext(new MetaReadContext());
    return reader.deserialize(bytes);
  }

  private static void assertStaticSchemaFails(String writerSource, String readerSource)
      throws Exception {
    CompilationResult writerResult = compile("test.StaticCompatibleNestedPayload", writerSource);
    CompilationResult readerResult = compile("test.StaticCompatibleNestedPayload", readerSource);
    Assert.assertTrue(writerResult.success, writerResult.diagnostics());
    Assert.assertTrue(readerResult.success, readerResult.diagnostics());
    try (URLClassLoader writerLoader = writerResult.classLoader();
        URLClassLoader readerLoader = readerResult.classLoader()) {
      Class<?> writerType = writerLoader.loadClass("test.StaticCompatibleNestedPayload");
      Class<?> readerType = readerLoader.loadClass("test.StaticCompatibleNestedPayload");
      Fory writer = compatibleFory(writerLoader, writerType, true, "schema-writer");
      Fory reader = compatibleFory(readerLoader, readerType, true, "schema-reader");
      TypeDef remoteTypeDef = TypeDef.buildTypeDef(writer.getTypeResolver(), writerType);
      Class<? extends Serializer> compatibleSerializerClass =
          CodecUtils.loadOrGenStaticCompatibleCodecClass(
              reader.getTypeResolver(), cast(readerType), remoteTypeDef);
      InvocationTargetException exception =
          Assert.expectThrows(
              InvocationTargetException.class,
              () ->
                  compatibleSerializerClass
                      .getConstructor(TypeResolver.class, Class.class, TypeDef.class)
                      .newInstance(reader.getTypeResolver(), readerType, remoteTypeDef));
      Assert.assertTrue(exception.getCause() instanceof RuntimeException, exception.toString());
    }
  }

  private static Map<String, FieldCodecCategory> remoteCodecCategories(
      Serializer<Object> compatibleSerializer) throws Exception {
    List<RemoteFieldInfo> remoteFields = remoteFields(compatibleSerializer);
    Map<String, FieldCodecCategory> categories = new HashMap<>();
    for (RemoteFieldInfo remoteField : remoteFields) {
      categories.put(
          remoteField.descriptor.getName(), remoteField.serializationFieldInfo.codecCategory);
    }
    return categories;
  }

  @SuppressWarnings("unchecked")
  private static List<RemoteFieldInfo> remoteFields(Serializer<Object> compatibleSerializer)
      throws Exception {
    Field remoteFieldsField =
        StaticGeneratedStructSerializer.class.getDeclaredField("remoteFields");
    remoteFieldsField.setAccessible(true);
    return (List<RemoteFieldInfo>) remoteFieldsField.get(compatibleSerializer);
  }

  private static Fory compatibleFory(
      ClassLoader classLoader, Class<?> type, boolean xlang, String role) {
    return compatibleFory(classLoader, type, xlang, role, true);
  }

  private static Fory compatibleFory(
      ClassLoader classLoader, Class<?> type, boolean xlang, String role, boolean codegen) {
    Fory fory =
        Fory.builder()
            .withName("static-compatible-" + role + "-" + (xlang ? "xlang" : "native"))
            .withClassLoader(classLoader)
            .withXlang(xlang)
            .withCodegen(codegen)
            .withMetaShare(true)
            .withScopedMetaShare(false)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    if (xlang) {
      fory.register(type, "test", type.getSimpleName());
    }
    return fory;
  }

  private static void assertStaticCompatibleDebugTracing(
      ClassLoader classLoader, Class<?> type, boolean expectedDebug, String role) {
    Fory fory = compatibleFory(classLoader, type, false, role);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), type);
    String generatedSource =
        new StaticCompatibleCodecBuilder(TypeRef.of(type), fory, typeDef).genCode();
    Assert.assertEquals(generatedSource.contains("debugRemoteReadField(\""), expectedDebug);
  }

  @SuppressWarnings("unchecked")
  private static Class<Object> cast(Class<?> type) {
    return (Class<Object>) type;
  }

  private static CompilationResult compile(String typeName, String source) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assert.assertNotNull(compiler, "Tests require a JDK compiler");
    Path root = Files.createTempDirectory("fory-static-compatible-test");
    Path sourceRoot = root.resolve("src");
    Path classRoot = root.resolve("classes");
    Files.createDirectories(sourceRoot);
    Files.createDirectories(classRoot);
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
              "-classpath", System.getProperty("java.class.path"), "-d", classRoot.toString());
      JavaCompiler.CompilationTask task =
          compiler.getTask(null, fileManager, diagnostics, options, null, units);
      return new CompilationResult(classRoot, task.call(), diagnostics.getDiagnostics());
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

  private static final class CompilationResult {
    final Path classRoot;
    final boolean success;
    final List<Diagnostic<? extends JavaFileObject>> diagnostics;

    CompilationResult(
        Path classRoot, boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
      this.classRoot = classRoot;
      this.success = success;
      this.diagnostics = new ArrayList<>(diagnostics);
    }

    URLClassLoader classLoader() throws IOException {
      URL[] urls = {classRoot.toUri().toURL()};
      return new URLClassLoader(urls, StaticCompatibleCodecBuilderTest.class.getClassLoader());
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
