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

import static org.apache.fory.json.JsonTestSupport.nullCodec;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import java.beans.Expression;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.data.Kind;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Float16;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonTypeCheckerTest extends ForyJsonTestModels {
  private static final JsonCodec<String> STRING_NULL_CODEC = nullCodec();

  @Factory(dataProvider = "enableCodegen")
  public JsonTypeCheckerTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void defaultRejectsDisallowedWrite() {
    ForyJson json = newJson();
    assertThrows(
        InsecureException.class,
        () -> json.toJson(new Expression(System.class, "exit", new Object[] {0})));
  }

  @Test
  public void defaultRejectsDisallowedRead() {
    ForyJson json = newJson();
    assertThrows(InsecureException.class, () -> json.fromJson("{}", Expression.class));
  }

  @Test
  public void checkerRejectsRootWrite() {
    ForyJson json = rejectingJson(CheckedBean.class);
    assertThrows(InsecureException.class, () -> json.toJson(new CheckedBean()));
  }

  @Test
  public void checkerRejectsRootRead() {
    ForyJson json = rejectingJson(CheckedBean.class);
    assertThrows(InsecureException.class, () -> json.fromJson("{}", CheckedBean.class));
  }

  @Test
  public void defaultExactSkipsChecker() {
    AtomicInteger calls = new AtomicInteger();
    ForyJson json =
        newJsonBuilder()
            .withTypeChecker(
                (className, context) -> {
                  calls.incrementAndGet();
                  return false;
                })
            .build();
    assertEquals(json.toJson("value"), "\"value\"");
    assertEquals(calls.get(), 0);
  }

  @Test
  public void customExactUsesChecker() {
    ForyJson json =
        newJsonBuilder()
            .registerCodec(String.class, STRING_NULL_CODEC)
            .withTypeChecker((className, context) -> !className.equals(String.class.getName()))
            .build();
    assertThrows(InsecureException.class, () -> json.toJson("value"));
  }

  @Test
  public void customPrimitiveUsesChecker() {
    ForyJson json =
        newJsonBuilder()
            .registerCodec(int.class, nullCodec())
            .withTypeChecker((className, context) -> !className.equals(int.class.getName()))
            .build();
    assertThrows(InsecureException.class, () -> json.fromJson("1", int.class));
  }

  @Test
  public void shadowedDefaultUsesChecker() throws Exception {
    String className = Float16.class.getName();
    byte[] classBytes = classBytes(Float16.class);
    ClassLoader loader =
        new ClassLoader(null) {
          @Override
          protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals(className)) {
              return defineClass(name, classBytes, 0, classBytes.length);
            }
            throw new ClassNotFoundException(name);
          }
        };
    Class<?> shadowType = loader.loadClass(className);
    Object value = shadowType.getMethod("fromBits", short.class).invoke(null, (short) 0);
    ForyJson json =
        newJsonBuilder().withTypeChecker((name, context) -> !name.equals(className)).build();
    assertThrows(InsecureException.class, () -> json.toJson(value));
  }

  @Test
  public void duplicateChecksCached() {
    AtomicInteger calls = new AtomicInteger();
    ForyJson json =
        newJsonBuilder()
            .withTypeChecker(
                (className, context) -> {
                  calls.incrementAndGet();
                  return true;
                })
            .build();
    json.toJson(new CheckedBean());
    json.toJson(new CheckedBean());
    assertEquals(calls.get(), 1);
  }

  @Test
  public void deniedChecksCached() {
    AtomicInteger calls = new AtomicInteger();
    ForyJson json =
        newJsonBuilder()
            .withTypeChecker(
                (className, context) -> {
                  calls.incrementAndGet();
                  return !className.equals(CheckedBean.class.getName());
                })
            .build();
    assertThrows(InsecureException.class, () -> json.toJson(new CheckedBean()));
    assertThrows(InsecureException.class, () -> json.toJson(new CheckedBean()));
    assertEquals(calls.get(), 1);
  }

  @Test
  public void nestedFieldRejected() {
    ForyJson json = rejectingJson(RejectedValue.class);
    assertThrows(
        InsecureException.class, () -> json.fromJson("{\"value\":{}}", RejectedHolder.class));
  }

  @Test
  public void collectionScalarChecked() {
    ForyJson json =
        newJsonBuilder()
            .registerCodec(Integer.class, nullCodec())
            .withTypeChecker((className, context) -> !className.equals(Integer.class.getName()))
            .build();
    assertThrows(
        InsecureException.class, () -> json.fromJson("[1]", new TypeRef<List<Integer>>() {}));
  }

  @Test
  public void mapScalarChecked() {
    ForyJson json =
        newJsonBuilder()
            .registerCodec(Integer.class, nullCodec())
            .withTypeChecker((className, context) -> !className.equals(Integer.class.getName()))
            .build();
    assertThrows(
        InsecureException.class,
        () -> json.fromJson("{\"one\":1}", new TypeRef<Map<String, Integer>>() {}));
  }

  @Test
  public void enumMapKeyChecked() {
    ForyJson json = rejectingJson(Kind.class);
    assertThrows(
        InsecureException.class,
        () -> json.fromJson("{\"FAST\":\"ok\"}", new TypeRef<Map<Kind, String>>() {}));
  }

  @Test
  public void classMembersSkipped() {
    ForyJson json = rejectingJson(Class.class);
    ClassMembers value = json.fromJson("{\"type\":\"java.lang.String\"}", ClassMembers.class);
    assertEquals(json.toJson(value), "{}");
  }

  private ForyJson rejectingJson(Class<?> rejectedType) {
    String rejectedName = rejectedType.getName();
    return newJsonBuilder()
        .withTypeChecker((className, context) -> !className.equals(rejectedName))
        .build();
  }

  private static byte[] classBytes(Class<?> type) throws IOException {
    String resource = type.getSimpleName() + ".class";
    try (InputStream input = type.getResourceAsStream(resource);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      if (input == null) {
        throw new IOException("Missing class resource " + resource);
      }
      byte[] buffer = new byte[4096];
      int length;
      while ((length = input.read(buffer)) != -1) {
        output.write(buffer, 0, length);
      }
      return output.toByteArray();
    }
  }

  public static final class CheckedBean {}

  public static final class RejectedValue {}

  public static final class RejectedHolder {
    public RejectedValue value;
  }

  public static final class ClassMembers {
    public Class<?> type = String.class;

    public Class<?> getModelClass() {
      return Integer.class;
    }

    public void setModelClass(Class<?> modelClass) {
      throw new AssertionError("Class setter should be ignored");
    }
  }
}
