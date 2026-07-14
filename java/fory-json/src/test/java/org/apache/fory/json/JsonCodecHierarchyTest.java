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

package org.apache.fory.json;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.platform.JdkVersion;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class JsonCodecHierarchyTest extends ForyJsonTestModels {
  @Test
  public void directRoots() {
    ForyJson json = newJson();
    assertMarker(json, new DirectValue(), "direct");
    assertMarker(json, DirectEnum.VALUE, "enum");
    assertEquals(json.toJson(new DirectImplementation(), DirectContract.class), "\"interface\"");
    assertMarker(json, new DirectImplementation(), "interface");
    assertMarker(json, new DirectList(), "collection");
    assertMarker(json, new InheritedList(), "inherited-collection");
  }

  @Test
  public void recordRoot() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonCodecAnnotatedRecord",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonCodec;\n"
                + "import org.apache.fory.json.JsonCodecHierarchyTest.RecordCodec;\n"
                + "@JsonCodec(RecordCodec.class)\n"
                + "public record JsonCodecAnnotatedRecord(int value) {}\n");
    Object value = type.getConstructor(int.class).newInstance(1);
    assertMarker(newJson(), value, "record");
  }

  @Test
  public void inheritsDeclarations() {
    ForyJson json = newJson();
    assertMarker(json, new ChildValue(), "a");
    assertMarker(json, new InheritedImplementation(), "interface-a");
  }

  @Test
  public void sameInterfaces() {
    ForyJson json = newJson();
    assertMarker(json, new SameOrderLeft(), "a");
    assertMarker(json, new SameOrderRight(), "a");
  }

  @Test
  public void interfaceOrder() throws Exception {
    try (LoadedType leftFirst = compileConflictType("LeftConflict, RightConflict");
        LoadedType rightFirst = compileConflictType("RightConflict, LeftConflict")) {
      ForyJson json = newJson();
      String leftMessage = conflictMessage(json, leftFirst.newInstance());
      String rightMessage = conflictMessage(json, rightFirst.newInstance());
      assertEquals(leftMessage, rightMessage);
      assertTrue(
          leftMessage.contains(LeftConflict.class.getName() + " -> " + ACodec.class.getName()));
      assertTrue(
          leftMessage.contains(RightConflict.class.getName() + " -> " + BCodec.class.getName()));
    }
  }

  @Test
  public void specificDeclarations() {
    ForyJson json = newJson();
    assertMarker(json, new DiamondValue(), "a");
    assertMarker(json, new SpecificValue(), "child-interface");
    assertMarker(json, new ClassOverride(), "child-class");
  }

  @Test
  public void classInterface() {
    ForyJson json = newJson();
    assertMarker(json, new RelatedChild(), "related-class");
    assertMarker(json, new SameClassInterface(), "a");
    String message = conflictMessage(json, new UnrelatedClassInterface());
    assertTrue(message.contains(ParentValue.class.getName() + " -> " + ACodec.class.getName()));
    assertTrue(
        message.contains(UnrelatedInterface.class.getName() + " -> " + BCodec.class.getName()));
  }

  @Test
  public void disambiguation() {
    ForyJson json = newJson();
    assertMarker(json, new DirectConflict(), "direct-conflict");

    RuntimeMarkerCodec<BuilderConflict> codec = new RuntimeMarkerCodec<>("builder-conflict");
    json = newJsonBuilder().registerCodec(BuilderConflict.class, codec).build();
    assertMarker(json, new BuilderConflict(), "builder-conflict");

    RuntimeMarkerCodec<DirectValue> directCodec = new RuntimeMarkerCodec<>("builder-direct");
    json = newJsonBuilder().registerCodec(DirectValue.class, directCodec).build();
    assertMarker(json, new DirectValue(), "builder-direct");
  }

  @Test
  public void builderIsExact() {
    RuntimeMarkerCodec<ParentValue> codec = new RuntimeMarkerCodec<>("registered-parent");
    ForyJson json = newJsonBuilder().registerCodec(ParentValue.class, codec).build();
    assertMarker(json, new ParentValue(), "registered-parent");
    assertMarker(json, new ChildValue(), "a");
  }

  @Test
  public void subtypePrecedence() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new SubTypeValue(), SubTypeContract.class), "\"subtypes\"");
    assertMarker(json, new SubTypeValue(), "subtypes");
  }

  @Test
  public void inheritedResults() {
    ForyJson json = newJson();
    assertEquals(json.fromJson("\"latin\"", ReadChild.class).reader, "latin1");
    assertEquals(json.fromJson("\"你好\"", ReadChild.class).reader, "utf16");
    assertEquals(
        json.fromJson("\"utf8\"".getBytes(StandardCharsets.UTF_8), ReadChild.class).reader, "utf8");

    assertNull(json.fromJson("\"latin\"", NullChild.class));
    assertNull(json.fromJson("\"你好\"", NullChild.class));
    assertNull(json.fromJson("null".getBytes(StandardCharsets.UTF_8), NullChild.class));
  }

  @Test
  public void incompatibleResults() {
    ForyJson json = newJson();
    assertIncompatible(() -> json.fromJson("\"latin\"", IncompatibleChild.class));
    assertIncompatible(() -> json.fromJson("\"你好\"", IncompatibleChild.class));
    assertIncompatible(
        () -> json.fromJson("\"utf8\"".getBytes(StandardCharsets.UTF_8), IncompatibleChild.class));
  }

  @Test
  public void invalidCodecs() {
    assertInvalid(PrivateCodecValue.class, PrivateCodec.class, "must be public");
    assertInvalid(AbstractCodecValue.class, AbstractCodec.class, "must be concrete");
    assertInvalid(NoDefaultCodecValue.class, NoDefaultCodec.class, "public no-argument");
    assertInvalid(MemberCodecValue.class, MemberCodec.class, "top-level or a static nested class");
    assertInvalid(ThrowingCodecValue.class, ThrowingCodec.class, "constructor failed");
  }

  @Test
  public void codecReuse() {
    CountingCodec.CONSTRUCTIONS.set(0);
    ForyJson json = newJson();
    assertMarker(json, new CountedValueA(), "counted");
    assertMarker(json, new CountedValueB(), "counted");
    assertMarker(json, new CountedValueA(), "counted");
    assertEquals(CountingCodec.CONSTRUCTIONS.get(), 1);

    assertMarker(newJson(), new CountedValueA(), "counted");
    assertEquals(CountingCodec.CONSTRUCTIONS.get(), 2);
  }

  @Test
  public void concurrentCodecReuse() throws Exception {
    CountingCodec.CONSTRUCTIONS.set(0);
    ForyJson json = newJson();
    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        final boolean first = (i & 1) == 0;
        results.add(
            executor.submit(() -> json.toJson(first ? new CountedValueA() : new CountedValueB())));
      }
      for (Future<String> result : results) {
        assertEquals(result.get(), "\"counted\"");
      }
    } finally {
      executor.shutdownNow();
    }
    assertEquals(CountingCodec.CONSTRUCTIONS.get(), 1);
  }

  @Test
  public void securityPrecedesConstruction() {
    CountingCodec.CONSTRUCTIONS.set(0);
    ForyJson json =
        newJsonBuilder()
            .withTypeChecker(
                (className, context) -> !className.equals(CountedValueA.class.getName()))
            .build();
    try {
      json.toJson(new CountedValueA());
      fail("Expected annotated target rejection");
    } catch (InsecureException expected) {
      assertEquals(CountingCodec.CONSTRUCTIONS.get(), 0);
    }
  }

  private static void assertMarker(ForyJson json, Object value, String marker) {
    String expected = '"' + marker + '"';
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
  }

  private static String conflictMessage(ForyJson json, Object value) {
    try {
      json.toJson(value);
      fail("Expected an inherited @JsonCodec conflict");
      return null;
    } catch (ForyJsonException e) {
      return e.getMessage();
    }
  }

  private static void assertIncompatible(ReadCall call) {
    try {
      call.read();
      fail("Expected an incompatible inherited codec result");
    } catch (ForyJsonException e) {
      String message = e.getMessage();
      assertTrue(message.contains(IncompatibleCodec.class.getName()));
      assertTrue(message.contains(IncompatibleParent.class.getName()));
      assertTrue(message.contains(IncompatibleChild.class.getName()));
      assertTrue(message.contains("expected null or a value assignable"));
    }
  }

  private static void assertInvalid(
      Class<?> valueType, Class<? extends JsonValueCodec<?>> codecType, String reason) {
    try {
      ForyJson.builder().withCodegen(false).build().toJson(newValue(valueType));
      fail("Expected an invalid @JsonCodec class");
    } catch (ForyJsonException e) {
      assertTrue(e.getMessage().contains(codecType.getName()));
      assertTrue(e.getMessage().contains(reason));
    }
  }

  private static Object newValue(Class<?> type) {
    try {
      return type.getConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static LoadedType compileConflictType(String interfaces) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new SkipException("A JDK compiler is required");
    }
    Path root = Files.createTempDirectory("fory-json-codec-order");
    Path output = root.resolve("classes");
    Path source = root.resolve("src/org/apache/fory/json/hierarchy/ConflictValue.java");
    Files.createDirectories(output);
    Files.createDirectories(source.getParent());
    String text =
        "package org.apache.fory.json.hierarchy;\n"
            + "import org.apache.fory.json.JsonCodecHierarchyTest.LeftConflict;\n"
            + "import org.apache.fory.json.JsonCodecHierarchyTest.RightConflict;\n"
            + "public final class ConflictValue implements "
            + interfaces
            + " {}\n";
    Files.write(source, text.getBytes(StandardCharsets.UTF_8));
    int exit =
        compiler.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            output.toString(),
            source.toString());
    assertEquals(exit, 0);
    URLClassLoader loader =
        new URLClassLoader(
            new URL[] {output.toUri().toURL()}, JsonCodecHierarchyTest.class.getClassLoader());
    Class<?> type = Class.forName("org.apache.fory.json.hierarchy.ConflictValue", true, loader);
    return new LoadedType(root, loader, type);
  }

  private static final class LoadedType implements AutoCloseable {
    private final Path root;
    private final URLClassLoader loader;
    private final Class<?> type;

    private LoadedType(Path root, URLClassLoader loader, Class<?> type) {
      this.root = root;
      this.loader = loader;
      this.type = type;
    }

    private Object newInstance() throws ReflectiveOperationException {
      return type.getConstructor().newInstance();
    }

    @Override
    public void close() throws Exception {
      loader.close();
      try (Stream<Path> files = Files.walk(root)) {
        files
            .sorted(Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    Files.deleteIfExists(path);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
      }
    }
  }

  private interface ReadCall {
    Object read();
  }

  public abstract static class MarkerCodec implements JsonValueCodec<Object> {
    protected abstract String marker();

    @Override
    public final void writeString(StringJsonWriter writer, Object value) {
      writeStringValue(writer, value, marker());
    }

    @Override
    public final void writeUtf8(Utf8JsonWriter writer, Object value) {
      writeUtf8Value(writer, value, marker());
    }

    @Override
    public Object readLatin1(Latin1JsonReader reader) {
      reader.skipValue();
      return null;
    }

    @Override
    public Object readUtf16(Utf16JsonReader reader) {
      reader.skipValue();
      return null;
    }

    @Override
    public Object readUtf8(Utf8JsonReader reader) {
      reader.skipValue();
      return null;
    }
  }

  public static class ACodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "a";
    }
  }

  public static class BCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "b";
    }
  }

  public static class DirectCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "direct";
    }
  }

  public static class EnumCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "enum";
    }
  }

  public static class InterfaceCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "interface";
    }
  }

  public static class CollectionMarkerCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "collection";
    }
  }

  public static class InheritedCollectionCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "inherited-collection";
    }
  }

  public static class RecordCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "record";
    }
  }

  public static class InterfaceACodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "interface-a";
    }
  }

  public static class ChildInterfaceCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "child-interface";
    }
  }

  public static class ChildClassCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "child-class";
    }
  }

  public static class RelatedClassCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "related-class";
    }
  }

  public static class DirectConflictCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "direct-conflict";
    }
  }

  public static class SubTypesCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "subtypes";
    }
  }

  public static class CountingCodec extends MarkerCodec {
    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    public CountingCodec() {
      CONSTRUCTIONS.incrementAndGet();
    }

    @Override
    protected String marker() {
      return "counted";
    }
  }

  private static final class RuntimeMarkerCodec<T> implements JsonValueCodec<T> {
    private final String marker;

    private RuntimeMarkerCodec(String marker) {
      this.marker = marker;
    }

    @Override
    public void writeString(StringJsonWriter writer, T value) {
      writeStringValue(writer, value, marker);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, T value) {
      writeUtf8Value(writer, value, marker);
    }

    @Override
    public T readLatin1(Latin1JsonReader reader) {
      reader.skipValue();
      return null;
    }

    @Override
    public T readUtf16(Utf16JsonReader reader) {
      reader.skipValue();
      return null;
    }

    @Override
    public T readUtf8(Utf8JsonReader reader) {
      reader.skipValue();
      return null;
    }
  }

  public static class AssignableCodec implements JsonValueCodec<ReadParent> {
    @Override
    public void writeString(StringJsonWriter writer, ReadParent value) {
      writeStringValue(writer, value, "read");
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, ReadParent value) {
      writeUtf8Value(writer, value, "read");
    }

    @Override
    public ReadParent readLatin1(Latin1JsonReader reader) {
      reader.skipValue();
      return new ReadChild("latin1");
    }

    @Override
    public ReadParent readUtf16(Utf16JsonReader reader) {
      reader.skipValue();
      return new ReadChild("utf16");
    }

    @Override
    public ReadParent readUtf8(Utf8JsonReader reader) {
      reader.skipValue();
      return new ReadChild("utf8");
    }
  }

  public static class NullCodec implements JsonValueCodec<NullParent> {
    @Override
    public void writeString(StringJsonWriter writer, NullParent value) {
      writer.writeNull();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, NullParent value) {
      writer.writeNull();
    }

    @Override
    public NullParent readLatin1(Latin1JsonReader reader) {
      reader.skipValue();
      return null;
    }

    @Override
    public NullParent readUtf16(Utf16JsonReader reader) {
      reader.skipValue();
      return null;
    }

    @Override
    public NullParent readUtf8(Utf8JsonReader reader) {
      reader.skipValue();
      return null;
    }
  }

  public static class IncompatibleCodec implements JsonValueCodec<IncompatibleParent> {
    @Override
    public void writeString(StringJsonWriter writer, IncompatibleParent value) {
      writeStringValue(writer, value, "incompatible");
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, IncompatibleParent value) {
      writeUtf8Value(writer, value, "incompatible");
    }

    @Override
    public IncompatibleParent readLatin1(Latin1JsonReader reader) {
      reader.skipValue();
      return new IncompatibleParent();
    }

    @Override
    public IncompatibleParent readUtf16(Utf16JsonReader reader) {
      reader.skipValue();
      return new IncompatibleParent();
    }

    @Override
    public IncompatibleParent readUtf8(Utf8JsonReader reader) {
      reader.skipValue();
      return new IncompatibleParent();
    }
  }

  private static void writeStringValue(StringJsonWriter writer, Object value, String marker) {
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeString(marker);
    }
  }

  private static void writeUtf8Value(Utf8JsonWriter writer, Object value, String marker) {
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeString(marker);
    }
  }

  @JsonCodec(DirectCodec.class)
  public static class DirectValue {}

  @JsonCodec(EnumCodec.class)
  public enum DirectEnum {
    VALUE
  }

  @JsonCodec(InterfaceCodec.class)
  public interface DirectContract {}

  public static class DirectImplementation implements DirectContract {}

  @JsonCodec(CollectionMarkerCodec.class)
  public static class DirectList extends ArrayList<String> {}

  @JsonCodec(InheritedCollectionCodec.class)
  public interface InheritedCollectionContract {}

  public static class InheritedList extends ArrayList<String>
      implements InheritedCollectionContract {}

  @JsonCodec(ACodec.class)
  public static class ParentValue {}

  public static class ChildValue extends ParentValue {}

  @JsonCodec(InterfaceACodec.class)
  public interface InheritedContract {}

  public static class InheritedImplementation implements InheritedContract {}

  @JsonCodec(ACodec.class)
  public interface SameInterfaceA {}

  @JsonCodec(ACodec.class)
  public interface SameInterfaceB {}

  public static class SameOrderLeft implements SameInterfaceA, SameInterfaceB {}

  public static class SameOrderRight implements SameInterfaceB, SameInterfaceA {}

  @JsonCodec(ACodec.class)
  public interface LeftConflict {}

  @JsonCodec(BCodec.class)
  public interface RightConflict {}

  @JsonCodec(ACodec.class)
  public interface DiamondRoot {}

  public interface DiamondLeft extends DiamondRoot {}

  public interface DiamondRight extends DiamondRoot {}

  public static class DiamondValue implements DiamondLeft, DiamondRight {}

  @JsonCodec(ACodec.class)
  public interface SpecificParent {}

  @JsonCodec(ChildInterfaceCodec.class)
  public interface SpecificChild extends SpecificParent {}

  public static class SpecificValue implements SpecificChild {}

  @JsonCodec(ACodec.class)
  public static class ClassParent {}

  @JsonCodec(ChildClassCodec.class)
  public static class ClassOverride extends ClassParent {}

  @JsonCodec(ACodec.class)
  public interface RelatedInterface {}

  @JsonCodec(RelatedClassCodec.class)
  public static class RelatedParent implements RelatedInterface {}

  public static class RelatedChild extends RelatedParent {}

  @JsonCodec(ACodec.class)
  public interface SameClassInterfaceContract {}

  public static class SameClassInterface extends ParentValue
      implements SameClassInterfaceContract {}

  @JsonCodec(BCodec.class)
  public interface UnrelatedInterface {}

  public static class UnrelatedClassInterface extends ParentValue implements UnrelatedInterface {}

  @JsonCodec(DirectConflictCodec.class)
  public static class DirectConflict extends ParentValue implements UnrelatedInterface {}

  public static class BuilderConflict extends ParentValue implements UnrelatedInterface {}

  @JsonCodec(SubTypesCodec.class)
  @JsonSubTypes(
      value = @JsonSubTypes.Type(name = "value", value = SubTypeValue.class),
      inclusion = JsonSubTypes.Inclusion.WRAPPER_ARRAY)
  public interface SubTypeContract {}

  public static class SubTypeValue implements SubTypeContract {}

  @JsonCodec(AssignableCodec.class)
  public static class ReadParent {}

  public static class ReadChild extends ReadParent {
    private final String reader;

    public ReadChild(String reader) {
      this.reader = reader;
    }
  }

  @JsonCodec(NullCodec.class)
  public static class NullParent {}

  public static class NullChild extends NullParent {}

  @JsonCodec(IncompatibleCodec.class)
  public static class IncompatibleParent {}

  public static class IncompatibleChild extends IncompatibleParent {}

  @JsonCodec(PrivateCodec.class)
  public static class PrivateCodecValue {}

  private static class PrivateCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "private";
    }
  }

  @JsonCodec(AbstractCodec.class)
  public static class AbstractCodecValue {}

  public abstract static class AbstractCodec extends MarkerCodec {}

  @JsonCodec(NoDefaultCodec.class)
  public static class NoDefaultCodecValue {}

  public static class NoDefaultCodec extends MarkerCodec {
    public NoDefaultCodec(String ignored) {}

    @Override
    protected String marker() {
      return "no-default";
    }
  }

  @JsonCodec(MemberCodec.class)
  public static class MemberCodecValue {}

  public class MemberCodec extends MarkerCodec {
    @Override
    protected String marker() {
      return "member";
    }
  }

  @JsonCodec(ThrowingCodec.class)
  public static class ThrowingCodecValue {}

  public static class ThrowingCodec extends MarkerCodec {
    public ThrowingCodec() {
      throw new IllegalStateException("expected");
    }

    @Override
    protected String marker() {
      return "throwing";
    }
  }

  @JsonCodec(CountingCodec.class)
  public static class CountedValueA {}

  @JsonCodec(CountingCodec.class)
  public static class CountedValueB {}
}
