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

import static org.apache.fory.json.JsonTestSupport.newLatin1Reader;
import static org.apache.fory.json.JsonTestSupport.newUtf16Reader;
import static org.apache.fory.json.JsonTestSupport.newUtf8Reader;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.meta.JsonSubtypeScanInfo;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonSubTypesTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonSubTypesTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void inlineTypedRoot() {
    ForyJson json = newJson();
    Shape value = new Circle(2);
    String text = json.toJson(value, Shape.class);
    assertEquals(text, "{\"kind\":\"circle\",\"radius\":2}");
    Shape decoded = json.fromJson(text, Shape.class);
    assertEquals(((Circle) decoded).radius, 2);
    assertEquals(new String(json.toJsonBytes(value, Shape.class), StandardCharsets.UTF_8), text);
    assertInlineWriterCapabilities(json);
    Shape utf16 = json.fromJson("{\"说明\":\"值\",\"radius\":3,\"kind\":\"circle\"}", Shape.class);
    assertEquals(((Circle) utf16).radius, 3);
    Shape utf8 = json.fromJson(text.getBytes(StandardCharsets.UTF_8), Shape.class);
    assertEquals(((Circle) utf8).radius, 2);
  }

  private void assertInlineWriterCapabilities(ForyJson json) {
    JsonTypeResolver resolver = JsonTestSupport.primaryTypeResolver(json);
    resolver.lockJIT();
    try {
      JsonTypeInfo info = resolver.getTypeInfo(Circle.class, Circle.class);
      ObjectCodec<Circle> owner = resolver.getObjectCodec(Circle.class);
      if (codegenEnabled()) {
        assertNotSame(info.stringWriter(), owner);
        assertNotSame(info.utf8Writer(), owner);
      } else {
        assertSame(info.stringWriter(), owner);
        assertSame(info.utf8Writer(), owner);
      }
    } finally {
      resolver.unlockJIT();
    }
  }

  @Test
  public void classNameSubtype() {
    ForyJson json = newJson();
    Shape value = new Rectangle(3, 4);
    String text = json.toJson(value, Shape.class);
    assertEquals(text, "{\"kind\":\"rectangle\",\"height\":4,\"width\":3}");
    Shape decoded = json.fromJson(text, Shape.class);
    assertEquals(((Rectangle) decoded).width, 3);
    assertEquals(((Rectangle) decoded).height, 4);
  }

  @Test
  public void wrapperObject() {
    ForyJson json = newJson();
    Wrapped value = new WrappedValue("x");
    String text = json.toJson(value, Wrapped.class);
    assertEquals(text, "{\"value\":{\"text\":\"x\"}}");
    Wrapped decoded = json.fromJson(text, Wrapped.class);
    assertEquals(((WrappedValue) decoded).text, "x");
  }

  @Test
  public void genericTypedRoot() {
    ForyJson json = newJson();
    List<Shape> values = Arrays.asList(new Circle(2), new Rectangle(3, 4));
    TypeRef<List<Shape>> type = new TypeRef<List<Shape>>() {};
    String expected =
        "[{\"kind\":\"circle\",\"radius\":2},{\"kind\":\"rectangle\",\"height\":4,\"width\":3}]";
    assertEquals(json.toJson(values, type), expected);
    assertEquals(new String(json.toJsonBytes(values, type), StandardCharsets.UTF_8), expected);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    json.writeJsonTo(values, type, output);
    assertEquals(new String(output.toByteArray(), StandardCharsets.UTF_8), expected);
    assertEquals(json.toJson(null, Shape.class), "null");
    assertThrows(
        IllegalArgumentException.class,
        () -> json.toJson(values, new TypeRef<List<? extends Shape>>() {}));
    assertThrows(ForyJsonException.class, () -> json.toJson(new Triangle(), Shape.class));
  }

  @Test
  public void scannerValidation() {
    ForyJson json = newJson();
    Shape escaped =
        json.fromJson(
            "{\"nested\":{\"kind\":\"rectangle\"},\"k\\u0069nd\":\"cir\\u0063le\",\"radius\":5}",
            Shape.class);
    assertEquals(((Circle) escaped).radius, 5);
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"radius\":1}", Shape.class));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"kind\":1,\"radius\":1}", Shape.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"kind\":\"circle\",\"kind\":\"circle\",\"radius\":1}", Shape.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"kind\":\"circle\",\"radius\":1,}", Shape.class));
  }

  @Test
  public void scannerRestoresCursor() {
    JsonSubtypeScanInfo info = new JsonSubtypeScanInfo("kind", new String[] {"circle"});
    assertScannerRestored(newLatin1Reader("{\"kind\":\"circle\",\"radius\":1}"), info);
    assertScannerRestored(newUtf16Reader("{\"kind\":\"circle\",\"radius\":1}"), info);
    assertScannerRestored(
        newUtf8Reader("{\"kind\":\"circle\",\"radius\":1}".getBytes(StandardCharsets.UTF_8)), info);

    JsonReader missing = newUtf8Reader("{\"radius\":1}".getBytes(StandardCharsets.UTF_8));
    assertThrows(ForyJsonException.class, () -> missing.scanObjectStringField(info));
    missing.expect('{');
  }

  @Test
  public void scannerUnicodeAndDepth() {
    JsonSubtypeScanInfo unicode = new JsonSubtypeScanInfo("类型", new String[] {"圆😀"});
    assertScannerRestored(newUtf16Reader("{\"类型\":\"圆😀\",\"值\":1}"), unicode);
    assertScannerRestored(
        newUtf8Reader("{\"类型\":\"圆😀\",\"值\":1}".getBytes(StandardCharsets.UTF_8)), unicode);
    JsonSubtypeScanInfo latin = new JsonSubtypeScanInfo("type", new String[] {"café"});
    assertScannerRestored(newLatin1Reader("{\"type\":\"café\"}"), latin);

    byte[] invalidUtf8 = "{\"kind\":\"x\"}".getBytes(StandardCharsets.UTF_8);
    invalidUtf8[9] = (byte) 0xc0;
    JsonReader reader = newUtf8Reader(invalidUtf8);
    assertThrows(
        ForyJsonException.class,
        () -> reader.scanObjectStringField(new JsonSubtypeScanInfo("kind", new String[] {"x"})));
    reader.expect('{');

    ForyJson shallow = newJsonBuilder().maxDepth(2).build();
    assertThrows(
        ForyJsonException.class,
        () ->
            shallow.fromJson(
                "{\"kind\":\"circle\",\"nested\":{\"values\":[1]},\"radius\":1}", Shape.class));
  }

  @Test
  public void rejectInvalidDefinitions() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.fromJson("{}", ConcreteBase.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{}", DuplicateNames.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{}", BothReferences.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{}", MissingReference.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"kind\":\"value\",\"x\":1}", CollidingBase.class));
  }

  private static void assertScannerRestored(JsonReader reader, JsonSubtypeScanInfo info) {
    assertEquals(reader.scanObjectStringField(info), 0);
    reader.expect('{');
  }

  @JsonSubTypes(
      property = "kind",
      value = {
        @JsonSubTypes.Type(value = Circle.class, name = "circle"),
        @JsonSubTypes.Type(
            className = "org.apache.fory.json.JsonSubTypesTest$Rectangle",
            name = "rectangle")
      })
  public interface Shape {}

  @JsonSubTypes(
      wrapperObject = true,
      value = {@JsonSubTypes.Type(value = WrappedValue.class, name = "value")})
  public interface Wrapped {}

  public static final class Circle implements Shape {
    public int radius;

    public Circle() {}

    Circle(int radius) {
      this.radius = radius;
    }
  }

  public static final class Rectangle implements Shape {
    public int height;
    public int width;

    public Rectangle() {}

    Rectangle(int width, int height) {
      this.width = width;
      this.height = height;
    }
  }

  public static final class WrappedValue implements Wrapped {
    public String text;

    public WrappedValue() {}

    WrappedValue(String text) {
      this.text = text;
    }
  }

  public static final class Triangle implements Shape {
    public int side;
  }

  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(value = ConcreteChild.class, name = "child")})
  public static class ConcreteBase {}

  public static final class ConcreteChild extends ConcreteBase {}

  @JsonSubTypes(
      property = "kind",
      value = {
        @JsonSubTypes.Type(value = DuplicateOne.class, name = "same"),
        @JsonSubTypes.Type(value = DuplicateTwo.class, name = "same")
      })
  public interface DuplicateNames {}

  public static final class DuplicateOne implements DuplicateNames {}

  public static final class DuplicateTwo implements DuplicateNames {}

  @JsonSubTypes(
      property = "kind",
      value = {
        @JsonSubTypes.Type(
            value = BothValue.class,
            className = "org.apache.fory.json.JsonSubTypesTest$BothValue",
            name = "both")
      })
  public interface BothReferences {}

  public static final class BothValue implements BothReferences {}

  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(name = "missing")})
  public interface MissingReference {}

  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(value = CollidingValue.class, name = "value")})
  public interface CollidingBase {}

  public static final class CollidingValue implements CollidingBase {
    public String kind;
    public int x;
  }
}
