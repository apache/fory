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

package org.apache.fory.json.resolver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.meta.JsonBuiltInTypeCatalog;
import org.apache.fory.json.meta.JsonCodecFactory;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonMetadataFormat;
import org.apache.fory.json.meta.JsonTypeMetadata;
import org.apache.fory.json.meta.JsonTypeMetadataData;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.ObjectInstantiator;
import org.testng.Assert;
import org.testng.annotations.Test;

public class JsonAndroidMetadataTest {
  @Test
  public void builtInCatalogMatchesRuntime() throws Exception {
    for (String name : JsonBuiltInTypeCatalog.assignableBinaryNames()) {
      Assert.assertTrue(JsonSharedRegistry.isBuiltInFamily(Class.forName(name)), name);
    }
  }

  @Test
  public void generatedDeclarationsAndSubtypes() throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(
            Arrays.asList(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-cp",
                forkClassPath(),
                AndroidProbe.class.getName()));
    builder.environment().put("FORY_ANDROID_ENABLED", "1");
    Process process = builder.redirectErrorStream(true).start();
    String output = readFully(process.getInputStream());
    Assert.assertEquals(process.waitFor(), 0, output);
  }

  private static String readFully(InputStream inputStream) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = inputStream.read(buffer)) != -1) {
      outputStream.write(buffer, 0, read);
    }
    return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
  }

  private static String forkClassPath() {
    String classPath = System.getProperty("surefire.real.class.path");
    return classPath == null || classPath.isEmpty()
        ? System.getProperty("java.class.path")
        : classPath;
  }

  public static final class AndroidProbe {
    public static void main(String[] args) {
      ForyJson json = ForyJson.builder().withCodegen(true).build();
      assertEquals(json.toJson(new DirectModel("direct")), "\"direct\"");
      assertEquals(json.fromJson("\"read\"", DirectModel.class).text, "read");

      assertEquals(json.toJson(new InheritedModel("inherited")), "\"inherited\"");
      assertEquals(json.fromJson("\"parent\"", InheritedModel.class).text, "parent");
      check(
          JsonAndroidMetadataTest_InheritedModel_ForyJsonMetadata.dominatedCodecTypeCalls == 0,
          "dominated declaration codec must not resolve its type token");

      BaseModel value = new SubtypeModel("subtype");
      assertEquals(json.toJson(value, BaseModel.class), "[\"model\",\"subtype\"]");
      BaseModel decoded = json.fromJson("[\"model\",\"read-subtype\"]", BaseModel.class);
      check(decoded instanceof SubtypeModel, "closed subtype class");
      assertEquals(((SubtypeModel) decoded).text, "read-subtype");

      try {
        json.toJson(new ConflictModel());
        throw new AssertionError("conflicting generated declarations should fail");
      } catch (ForyJsonException expected) {
        check(expected.getMessage().contains("Conflicting inherited"), expected.getMessage());
      }
      check(
          JsonAndroidMetadataTest_ConflictModel_ForyJsonMetadata.operationCalls == 0,
          "conflicting declarations must not initialize codec factories");

      assertEquals(json.toJson("intrinsic"), "\"intrinsic\"");
      List<String> list = new ArrayList<>();
      list.add("value");
      assertEquals(json.toJson(list), "[\"value\"]");
      Map<String, String> map = new HashMap<>();
      map.put("key", "value");
      assertEquals(json.toJson(map), "{\"key\":\"value\"}");
      try {
        json.toJson(new MissingModel());
        throw new AssertionError("missing generated metadata should fail");
      } catch (ForyJsonException expected) {
        check(
            expected.getMessage().contains("Missing generated JSON metadata"),
            expected.getMessage());
      }
      try {
        json.toJson(new ApplicationDate());
        throw new AssertionError("application built-in subclasses require generated metadata");
      } catch (ForyJsonException expected) {
        check(
            expected.getMessage().contains("Missing generated JSON metadata"),
            expected.getMessage());
      }

      SelectiveModel selective = new SelectiveModel();
      selective.ignored = "ignored";
      selective.setValue("selected");
      for (int i = 0; i < 100; i++) {
        String text = json.toJson(selective);
        assertEquals(text, "{\"value\":\"selected\"}");
        SelectiveModel copy = json.fromJson(text, SelectiveModel.class);
        assertEquals(copy.getValue(), "selected");
        check(copy.ignored == null, "ignored field must stay outside the read schema");
      }
      check(
          JsonAndroidMetadataTest_SelectiveModel_ForyJsonMetadata.unselectedTypeCalls == 0,
          "ignored and dominated field types must not resolve their type tokens");
      check(
          JsonAndroidMetadataTest_SelectiveModel_ForyJsonMetadata.unselectedOperationCalls == 0,
          "ignored and dominated accessors/codecs must not initialize operations");
      check(
          JsonAndroidMetadataTest_SelectiveModel_ForyJsonMetadata.getterCalls == 1,
          "selected getter operation must initialize once");
      check(
          JsonAndroidMetadataTest_SelectiveModel_ForyJsonMetadata.setterCalls == 1,
          "selected setter operation must initialize once");
      check(
          JsonAndroidMetadataTest_SelectiveModel_ForyJsonMetadata.instantiatorCalls == 1,
          "selected instantiator operation must initialize once");
    }
  }

  public static final class DirectModel {
    final String text;

    DirectModel(String text) {
      this.text = text;
    }
  }

  public interface ParentModel {}

  public interface MiddleModel extends ParentModel {}

  public static final class InheritedModel implements MiddleModel {
    final String text;

    InheritedModel(String text) {
      this.text = text;
    }
  }

  public abstract static class BaseModel {}

  public static final class SubtypeModel extends BaseModel {
    final String text;

    SubtypeModel(String text) {
      this.text = text;
    }
  }

  public interface LeftModel {}

  public interface RightModel {}

  public static final class ConflictModel implements LeftModel, RightModel {}

  public static final class MissingModel {}

  public static final class ApplicationDate extends Date {}

  public static final class SelectiveModel {
    public Object ignored;
    private String value;

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }

  public static final class DirectCodec extends StringCodec<DirectModel> {
    @Override
    DirectModel create(String text) {
      return new DirectModel(text);
    }

    @Override
    String text(DirectModel value) {
      return value.text;
    }
  }

  public static final class ParentCodec extends StringCodec<ParentModel> {
    @Override
    ParentModel create(String text) {
      return new InheritedModel(text);
    }

    @Override
    String text(ParentModel value) {
      return ((InheritedModel) value).text;
    }
  }

  public static final class SubtypeCodec extends StringCodec<SubtypeModel> {
    @Override
    SubtypeModel create(String text) {
      return new SubtypeModel(text);
    }

    @Override
    String text(SubtypeModel value) {
      return value.text;
    }
  }

  public static final class LeftCodec extends StringCodec<LeftModel> {
    @Override
    LeftModel create(String text) {
      return null;
    }

    @Override
    String text(LeftModel value) {
      return "left";
    }
  }

  public static final class RightCodec extends StringCodec<RightModel> {
    @Override
    RightModel create(String text) {
      return null;
    }

    @Override
    String text(RightModel value) {
      return "right";
    }
  }

  public abstract static class StringCodec<T> implements JsonValueCodec<T> {
    abstract T create(String text);

    abstract String text(T value);

    @Override
    public final void writeString(StringJsonWriter writer, T value) {
      writer.writeString(text(value));
    }

    @Override
    public final void writeUtf8(Utf8JsonWriter writer, T value) {
      writer.writeString(text(value));
    }

    @Override
    public final T readLatin1(Latin1JsonReader reader) {
      return create(reader.readString());
    }

    @Override
    public final T readUtf16(Utf16JsonReader reader) {
      return create(reader.readString());
    }

    @Override
    public final T readUtf8(Utf8JsonReader reader) {
      return create(reader.readString());
    }
  }

  private static void assertEquals(Object actual, Object expected) {
    if (!expected.equals(actual)) {
      throw new AssertionError("expected " + expected + " but got " + actual);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}

abstract class AndroidMetadataCompanion extends JsonTypeMetadata {
  AndroidMetadataCompanion(Class<?> requested, Class<?> target) {
    super(requested, target.getName(), ABI_VERSION);
  }

  static JsonTypeMetadataData empty(int section) {
    return AndroidMetadataFacts.data(section);
  }

  static JsonTypeMetadataData directDeclaration() {
    return AndroidMetadataFacts.directDeclaration();
  }
}

final class JsonAndroidMetadataTest_DirectModel_ForyJsonMetadata extends AndroidMetadataCompanion {
  private static final JsonCodecFactory FACTORY =
      new JsonCodecFactory() {
        @Override
        public JsonValueCodec<?> create() {
          return new JsonAndroidMetadataTest.DirectCodec();
        }
      };

  public JsonAndroidMetadataTest_DirectModel_ForyJsonMetadata(Class<?> requested) {
    super(requested, JsonAndroidMetadataTest.DirectModel.class);
  }

  @Override
  public Object metadata(int section) {
    return section == DECLARATIONS ? directDeclaration() : empty(section);
  }

  @Override
  public Class<?> metadataType(int section, int index) {
    return index == 0
        ? JsonAndroidMetadataTest.DirectModel.class
        : JsonAndroidMetadataTest.DirectCodec.class;
  }

  @Override
  public Object metadataOperation(int section, int index) {
    return FACTORY;
  }
}

final class JsonAndroidMetadataTest_InheritedModel_ForyJsonMetadata
    extends AndroidMetadataCompanion {
  static int dominatedCodecTypeCalls;
  private static final JsonCodecFactory FACTORY =
      new JsonCodecFactory() {
        @Override
        public JsonValueCodec<?> create() {
          return new JsonAndroidMetadataTest.ParentCodec();
        }
      };

  public JsonAndroidMetadataTest_InheritedModel_ForyJsonMetadata(Class<?> requested) {
    super(requested, JsonAndroidMetadataTest.InheritedModel.class);
  }

  @Override
  public Object metadata(int section) {
    return section == DECLARATIONS ? AndroidMetadataFacts.inheritedDeclaration() : empty(section);
  }

  @Override
  public Class<?> metadataType(int section, int index) {
    if (index == 0) {
      return JsonAndroidMetadataTest.InheritedModel.class;
    }
    if (index == 1) {
      return JsonAndroidMetadataTest.MiddleModel.class;
    }
    if (index == 2) {
      return JsonAndroidMetadataTest.ParentModel.class;
    }
    if (index == 3) {
      return JsonAndroidMetadataTest.ParentCodec.class;
    }
    dominatedCodecTypeCalls++;
    return JsonAndroidMetadataTest.LeftCodec.class;
  }

  @Override
  public Object metadataOperation(int section, int index) {
    return FACTORY;
  }
}

final class JsonAndroidMetadataTest_BaseModel_ForyJsonMetadata extends AndroidMetadataCompanion {
  public JsonAndroidMetadataTest_BaseModel_ForyJsonMetadata(Class<?> requested) {
    super(requested, JsonAndroidMetadataTest.BaseModel.class);
  }

  @Override
  public Object metadata(int section) {
    if (section == DECLARATIONS) {
      return AndroidMetadataFacts.declarationWithoutCodec();
    }
    return section == SUBTYPES ? AndroidMetadataFacts.subtypes() : empty(section);
  }

  @Override
  public Class<?> metadataType(int section, int index) {
    return section == DECLARATIONS
        ? JsonAndroidMetadataTest.BaseModel.class
        : JsonAndroidMetadataTest.SubtypeModel.class;
  }

  @Override
  public Object metadataOperation(int section, int index) {
    throw new AssertionError("base metadata has no operations");
  }
}

final class JsonAndroidMetadataTest_SubtypeModel_ForyJsonMetadata extends AndroidMetadataCompanion {
  private static final JsonCodecFactory FACTORY =
      new JsonCodecFactory() {
        @Override
        public JsonValueCodec<?> create() {
          return new JsonAndroidMetadataTest.SubtypeCodec();
        }
      };

  public JsonAndroidMetadataTest_SubtypeModel_ForyJsonMetadata(Class<?> requested) {
    super(requested, JsonAndroidMetadataTest.SubtypeModel.class);
  }

  @Override
  public Object metadata(int section) {
    return section == DECLARATIONS ? directDeclaration() : empty(section);
  }

  @Override
  public Class<?> metadataType(int section, int index) {
    return index == 0
        ? JsonAndroidMetadataTest.SubtypeModel.class
        : JsonAndroidMetadataTest.SubtypeCodec.class;
  }

  @Override
  public Object metadataOperation(int section, int index) {
    return FACTORY;
  }
}

final class JsonAndroidMetadataTest_ConflictModel_ForyJsonMetadata
    extends AndroidMetadataCompanion {
  static int operationCalls;

  public JsonAndroidMetadataTest_ConflictModel_ForyJsonMetadata(Class<?> requested) {
    super(requested, JsonAndroidMetadataTest.ConflictModel.class);
  }

  @Override
  public Object metadata(int section) {
    return section == DECLARATIONS
        ? AndroidMetadataFacts.conflictingDeclarations()
        : empty(section);
  }

  @Override
  public Class<?> metadataType(int section, int index) {
    switch (index) {
      case 0:
        return JsonAndroidMetadataTest.ConflictModel.class;
      case 1:
        return JsonAndroidMetadataTest.LeftModel.class;
      case 2:
        return JsonAndroidMetadataTest.RightModel.class;
      case 3:
        return JsonAndroidMetadataTest.LeftCodec.class;
      default:
        return JsonAndroidMetadataTest.RightCodec.class;
    }
  }

  @Override
  public Object metadataOperation(int section, int index) {
    operationCalls++;
    throw new AssertionError("conflicting declarations must not request operations");
  }
}

final class JsonAndroidMetadataTest_SelectiveModel_ForyJsonMetadata
    extends AndroidMetadataCompanion {
  static int unselectedTypeCalls;
  static int unselectedOperationCalls;
  static int getterCalls;
  static int setterCalls;
  static int instantiatorCalls;

  private static final JsonFieldAccessor GETTER =
      new JsonFieldAccessor() {
        @Override
        public Object getObject(Object target) {
          return ((JsonAndroidMetadataTest.SelectiveModel) target).getValue();
        }
      };
  private static final JsonFieldAccessor SETTER =
      new JsonFieldAccessor() {
        @Override
        public void putObject(Object target, Object value) {
          ((JsonAndroidMetadataTest.SelectiveModel) target).setValue((String) value);
        }
      };
  private static final ObjectInstantiator<JsonAndroidMetadataTest.SelectiveModel> INSTANTIATOR =
      new ObjectInstantiator<JsonAndroidMetadataTest.SelectiveModel>(
          JsonAndroidMetadataTest.SelectiveModel.class) {
        @Override
        public JsonAndroidMetadataTest.SelectiveModel newInstance() {
          return new JsonAndroidMetadataTest.SelectiveModel();
        }

        @Override
        public JsonAndroidMetadataTest.SelectiveModel newInstanceWithArguments(
            Object... arguments) {
          throw new UnsupportedOperationException();
        }
      };

  public JsonAndroidMetadataTest_SelectiveModel_ForyJsonMetadata(Class<?> requested) {
    super(requested, JsonAndroidMetadataTest.SelectiveModel.class);
  }

  @Override
  public Object metadata(int section) {
    if (section == DECLARATIONS) {
      return AndroidMetadataFacts.declarationWithoutCodec();
    }
    return section == OBJECT ? AndroidMetadataFacts.selectiveObject() : empty(section);
  }

  @Override
  public Class<?> metadataType(int section, int index) {
    if (section == DECLARATIONS || index == 0) {
      return JsonAndroidMetadataTest.SelectiveModel.class;
    }
    if (index == 1) {
      return String.class;
    }
    unselectedTypeCalls++;
    throw new AssertionError("unselected field type token " + index + " was requested");
  }

  @Override
  public Object metadataOperation(int section, int index) {
    if (index == 4) {
      getterCalls++;
      return GETTER;
    }
    if (index == 5) {
      setterCalls++;
      return SETTER;
    }
    if (index == 6) {
      instantiatorCalls++;
      return INSTANTIATOR;
    }
    unselectedOperationCalls++;
    throw new AssertionError("unselected field operation " + index + " was requested");
  }
}

final class AndroidMetadataFacts {
  private AndroidMetadataFacts() {}

  static JsonTypeMetadataData directDeclaration() {
    return data(
        JsonTypeMetadata.DECLARATIONS,
        token(0, 0),
        token(1, 1),
        operation(0, 0),
        declaration(0, new int[0], 1, 0));
  }

  static JsonTypeMetadataData declarationWithoutCodec() {
    return data(JsonTypeMetadata.DECLARATIONS, token(0, 0), declaration(0, new int[0], -1, -1));
  }

  static JsonTypeMetadataData inheritedDeclaration() {
    return data(
        JsonTypeMetadata.DECLARATIONS,
        token(0, 0),
        token(1, 1),
        token(2, 2),
        token(3, 3),
        token(4, 4),
        operation(0, 0),
        operation(1, 1),
        declaration(0, new int[] {1}, -1, -1),
        declaration(1, new int[] {2}, 3, 0),
        declaration(2, new int[0], 4, 1));
  }

  static JsonTypeMetadataData conflictingDeclarations() {
    return data(
        JsonTypeMetadata.DECLARATIONS,
        token(0, 0),
        token(1, 1),
        token(2, 2),
        token(3, 3),
        token(4, 4),
        operation(0, 0),
        operation(1, 1),
        declaration(0, new int[] {1, 2}, -1, -1),
        declaration(1, new int[0], 3, 0),
        declaration(2, new int[0], 4, 1));
  }

  static JsonTypeMetadataData selectiveObject() {
    return data(
        JsonTypeMetadata.OBJECT,
        token(0, 0),
        token(1, 1),
        token(2, 2),
        token(3, 3),
        token(4, 4),
        token(5, 5),
        declaredType(0, 1, -1, -1),
        declaredType(1, 2, 3, 0),
        declaredType(2, 4, 5, 1),
        primitiveType(3, JsonMetadataFormat.VOID),
        operation(
            0,
            JsonMetadataFormat.CODEC_FACTORY,
            JsonMetadataFormat.READ | JsonMetadataFormat.WRITE,
            0),
        operation(
            1,
            JsonMetadataFormat.CODEC_FACTORY,
            JsonMetadataFormat.READ | JsonMetadataFormat.WRITE,
            1),
        operation(
            2,
            JsonMetadataFormat.FIELD_ACCESS,
            JsonMetadataFormat.READ | JsonMetadataFormat.WRITE,
            2),
        operation(
            3,
            JsonMetadataFormat.FIELD_ACCESS,
            JsonMetadataFormat.READ | JsonMetadataFormat.WRITE,
            3),
        operation(4, JsonMetadataFormat.GETTER, JsonMetadataFormat.WRITE, 4),
        operation(5, JsonMetadataFormat.SETTER, JsonMetadataFormat.READ, 5),
        operation(6, JsonMetadataFormat.NO_ARG_CONSTRUCTOR, JsonMetadataFormat.READ, 6),
        field(
            "ignored",
            "Ljava/lang/Object;",
            java.lang.reflect.Modifier.PUBLIC,
            1,
            JsonMetadataFormat.HAS_JSON_IGNORE
                | JsonMetadataFormat.IGNORE_READ
                | JsonMetadataFormat.IGNORE_WRITE,
            2),
        field(
            "value",
            "Ljava/lang/String;",
            java.lang.reflect.Modifier.PRIVATE,
            2,
            JsonMetadataFormat.READ_ELIGIBLE | JsonMetadataFormat.WRITE_ELIGIBLE,
            3),
        method(
            "getValue",
            "()Ljava/lang/String;",
            0,
            new int[0],
            JsonMetadataFormat.WRITE_ELIGIBLE,
            4),
        method(
            "setValue",
            "(Ljava/lang/String;)V",
            3,
            new int[] {0},
            JsonMetadataFormat.READ_ELIGIBLE,
            5),
        instantiator(6),
        hierarchy());
  }

  static JsonTypeMetadataData subtypes() {
    Bytes body = new Bytes();
    body.byteValue(2).string("").index(1).signed(0).string("").string("model");
    return data(
        JsonTypeMetadata.SUBTYPES, token(0, 0), fact(JsonMetadataFormat.SUBTYPE_TABLE, body));
  }

  static JsonTypeMetadataData data(int section, byte[]... facts) {
    Bytes stream = new Bytes();
    stream
        .byteValue('F')
        .byteValue('J')
        .byteValue('M')
        .byteValue('D')
        .index(JsonMetadataFormat.VERSION)
        .byteValue(section)
        .index(facts.length);
    for (byte[] fact : facts) {
      stream.raw(fact);
    }
    return new JsonTypeMetadataData(new byte[][] {stream.bytes()}, null);
  }

  private static byte[] token(int id, int directIndex) {
    return fact(
        JsonMetadataFormat.TOKEN,
        new Bytes().index(id).byteValue(JsonMetadataFormat.TOKEN_DIRECT).index(directIndex));
  }

  private static byte[] operation(int id, int directIndex) {
    return operation(
        id,
        JsonMetadataFormat.CODEC_FACTORY,
        JsonMetadataFormat.READ | JsonMetadataFormat.WRITE,
        directIndex);
  }

  private static byte[] operation(int id, int shape, int direction, int directIndex) {
    return fact(
        JsonMetadataFormat.OPERATION,
        new Bytes()
            .index(id)
            .byteValue(shape)
            .byteValue(JsonMetadataFormat.OP_DIRECT)
            .byteValue(direction)
            .index(directIndex));
  }

  private static byte[] declaredType(int id, int token, int codecToken, int codecOperation) {
    return fact(
        JsonMetadataFormat.TYPE_NODE,
        new Bytes()
            .index(id)
            .byteValue(JsonMetadataFormat.TYPE_DECLARED)
            .signed(codecToken)
            .signed(codecOperation)
            .string(codecToken < 0 ? "" : "unselected codec")
            .index(token)
            .signed(-1)
            .index(0));
  }

  private static byte[] primitiveType(int id, int kind) {
    return fact(
        JsonMetadataFormat.TYPE_NODE,
        new Bytes()
            .index(id)
            .byteValue(JsonMetadataFormat.TYPE_PRIMITIVE)
            .signed(-1)
            .signed(-1)
            .string("")
            .byteValue(kind));
  }

  private static byte[] field(
      String name, String descriptor, int modifiers, int typeNode, int flags, int operation) {
    return fact(
        JsonMetadataFormat.FIELD,
        new Bytes()
            .index(0)
            .string(name)
            .string(descriptor)
            .index(modifiers)
            .index(typeNode)
            .index(flags)
            .signed(operation));
  }

  private static byte[] method(
      String name,
      String descriptor,
      int returnNode,
      int[] parameterNodes,
      int flags,
      int operation) {
    Bytes body =
        new Bytes()
            .index(0)
            .string(name)
            .string(descriptor)
            .index(java.lang.reflect.Modifier.PUBLIC)
            .index(returnNode)
            .index(parameterNodes.length);
    for (int parameterNode : parameterNodes) {
      body.index(parameterNode);
    }
    return fact(JsonMetadataFormat.METHOD, body.index(flags).signed(operation));
  }

  private static byte[] instantiator(int operation) {
    return fact(
        JsonMetadataFormat.INSTANTIATOR,
        new Bytes().byteValue(JsonMetadataFormat.INSTANTIATOR_NO_ARG).index(operation));
  }

  private static byte[] hierarchy() {
    return fact(JsonMetadataFormat.HIERARCHY, new Bytes().index(0).signed(-1).index(0));
  }

  private static byte[] declaration(
      int typeToken, int[] interfaces, int codecToken, int codecOperation) {
    Bytes body =
        new Bytes()
            .index(typeToken)
            .byteValue(JsonMetadataFormat.DECL_CLASS)
            .index(0)
            .signed(-1)
            .index(interfaces.length);
    for (int value : interfaces) {
      body.index(value);
    }
    body.signed(codecToken).signed(codecOperation);
    return fact(JsonMetadataFormat.DECLARATION, body);
  }

  private static byte[] fact(int tag, Bytes body) {
    byte[] bytes = body.bytes();
    return new Bytes().byteValue(tag).index(bytes.length).raw(bytes).bytes();
  }

  private static final class Bytes {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    Bytes byteValue(int value) {
      output.write(value);
      return this;
    }

    Bytes index(int value) {
      int remaining = value;
      do {
        int next = remaining & 0x7f;
        remaining >>>= 7;
        output.write(remaining == 0 ? next : next | 0x80);
      } while (remaining != 0);
      return this;
    }

    Bytes signed(int value) {
      return index((value << 1) ^ (value >> 31));
    }

    Bytes string(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      return index(bytes.length).raw(bytes);
    }

    Bytes raw(byte[] bytes) {
      output.write(bytes, 0, bytes.length);
      return this;
    }

    byte[] bytes() {
      return output.toByteArray();
    }
  }
}
