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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonFormat;
import org.apache.fory.json.annotation.JsonLenient;
import org.apache.fory.json.annotation.JsonLenient.Feature;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonProperty.NullHandling;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonLenientTest extends ForyJsonTestModels {
  private static final int REPRESENTATIONS = 4;

  @Factory(dataProvider = "enableCodegen")
  public JsonLenientTest(boolean codegen) {
    super(codegen);
  }

  public static class Banner {
    public int w;
  }

  public static class Imp {
    public String id;
    public List<String> cat;
    public String[] tags;
    public int w;
    public Integer h;
    public long l;
    public Long boxedLong;
    public short s;
    public byte b;
    public BigInteger big;
    public double d;
    public Double boxedDouble;
    public BigDecimal decimal;
    public boolean flag;
    public Boolean boxedFlag;
    public Banner banner;
    public List<Banner> banners;
    public Map<String, Integer> ext;
    public List<Integer> nums;
    public int[] sizes;
    public Set<Long> ids;
  }

  @JsonLenient
  public static class LenientImp extends Imp {}

  @JsonLenient
  public static class PartlyStrict {
    public List<String> cat;

    @JsonLenient({})
    public List<String> strictCat;

    @JsonLenient(Feature.SCALAR_AS_STRING)
    public String id;
  }

  public static class PropertyOnly {
    @JsonLenient(Feature.SINGLE_VALUE_AS_ARRAY)
    public List<String> cat;

    public List<String> strictCat;
    private int w;

    public int getW() {
      return w;
    }

    @JsonLenient(Feature.FLOAT_AS_INT)
    public void setW(int w) {
      this.w = w;
    }
  }

  public static final class CreatorImp {
    private final List<String> cat;
    private final int w;

    @JsonCreator
    public CreatorImp(
        @JsonLenient @JsonProperty("cat") List<String> cat,
        @JsonLenient(Feature.FLOAT_AS_INT) @JsonProperty("w") int w) {
      this.cat = cat;
      this.w = w;
    }
  }

  @JsonLenient
  public static class Defaults {
    public Integer h = 5;
    public List<String> cat = Collections.singletonList("default");
  }

  @JsonLenient({})
  public static class StrictImp {
    public int w;
  }

  public static class MixinTarget {
    public List<String> cat;
  }

  public static class WrongFeature {
    @JsonLenient(Feature.SINGLE_VALUE_AS_ARRAY)
    public String id;
  }

  public static class WithFormat {
    @JsonLenient
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public int w;
  }

  public static class ConflictingDeclarations {
    @JsonLenient(Feature.FLOAT_AS_INT)
    private int w;

    public int getW() {
      return w;
    }

    @JsonLenient(Feature.EMPTY_STRING_AS_NULL)
    public void setW(int w) {
      this.w = w;
    }
  }

  private static <T> T read(ForyJson json, String text, Class<T> type, int representation) {
    switch (representation) {
      case 0:
        return json.fromJson(text, type);
      case 1:
        // A trailing unknown member makes the input UTF-16 while known members keep their order.
        return json.fromJson(
            text.substring(0, text.length() - 1) + ",\"ignoredUnicode\":\"中\"}", type);
      case 2:
        return json.fromJson(text.getBytes(StandardCharsets.UTF_8), type);
      default:
        return json.fromJson(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), type);
    }
  }

  private static void assertRejected(ForyJson json, String text, Class<?> type, String message) {
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      int current = representation;
      ForyJsonException error =
          expectThrows(ForyJsonException.class, () -> read(json, text, type, current));
      assertTrue(String.valueOf(error.getMessage()).contains(message), error.getMessage());
    }
  }

  @Test
  public void strictByDefault() {
    ForyJson json = newJson();
    assertRejected(json, "{\"cat\":\"IAB1\"}", Imp.class, "");
    assertRejected(json, "{\"id\":123}", Imp.class, "");
    assertRejected(json, "{\"w\":300.7}", Imp.class, "");
    assertRejected(json, "{\"w\":\" 300 \"}", Imp.class, "");
    assertRejected(json, "{\"h\":\"\"}", Imp.class, "");
    assertRejected(json, "{\"banner\":\"\"}", Imp.class, "");
  }

  @Test
  public void offSpecShapes() {
    ForyJson json = newJson();
    String text =
        "{\"id\":-1.50e3,\"cat\":\"IAB1\",\"tags\":\"t\",\"w\":300.7,\"h\":\" 250 \","
            + "\"l\":\"-9.9e1\",\"boxedLong\":1e3,\"s\":\" 7.9 \",\"b\":-1.5,"
            + "\"big\":12345678901234567890.99,\"d\":\" 1.5 \",\"boxedDouble\":\"\","
            + "\"decimal\":\" 2.50 \",\"flag\":\" true \",\"boxedFlag\":\"\","
            + "\"banner\":\"\",\"banners\":{\"w\":5},\"ext\":\"\",\"nums\":\" 3.5 \","
            + "\"sizes\":[1.5,\" 2 \"],\"ids\":7}";
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      LenientImp imp = read(json, text, LenientImp.class, representation);
      assertEquals(imp.id, "-1.50e3");
      assertEquals(imp.cat, Collections.singletonList("IAB1"));
      assertEquals(imp.tags, new String[] {"t"});
      assertEquals(imp.w, 300);
      assertEquals(imp.h, Integer.valueOf(250));
      assertEquals(imp.l, -99L);
      assertEquals(imp.boxedLong, Long.valueOf(1000L));
      assertEquals(imp.s, (short) 7);
      assertEquals(imp.b, (byte) -1);
      assertEquals(imp.big, new BigInteger("12345678901234567890"));
      assertEquals(imp.d, 1.5d);
      assertNull(imp.boxedDouble);
      assertEquals(imp.decimal, new BigDecimal("2.50"));
      assertTrue(imp.flag);
      assertNull(imp.boxedFlag);
      assertNull(imp.banner);
      assertEquals(imp.banners.size(), 1);
      assertEquals(imp.banners.get(0).w, 5);
      assertNull(imp.ext);
      assertEquals(imp.nums, Collections.singletonList(3));
      assertEquals(imp.sizes, new int[] {1, 2});
      assertEquals(imp.ids, Collections.singleton(7L));
    }
    assertGeneratedWhenSupported(json, LenientImp.class);
  }

  @Test
  public void emptyStrings() {
    ForyJson json = newJson();
    String text =
        "{\"id\":\"\",\"cat\":\"\",\"w\":\"\",\"h\":\" \",\"flag\":\"\",\"d\":\"\","
            + "\"nums\":[\"\",1],\"sizes\":\"\"}";
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      LenientImp imp = read(json, text, LenientImp.class, representation);
      assertEquals(imp.id, "");
      assertNull(imp.cat);
      assertEquals(imp.w, 0);
      assertNull(imp.h);
      assertFalse(imp.flag);
      assertEquals(imp.d, 0d);
      assertEquals(imp.nums, Arrays.asList(null, 1));
      assertNull(imp.sizes);
    }
  }

  @Test
  public void nativeShapesUnchanged() {
    ForyJson lenient = newJson();
    ForyJson strict = newJson();
    String text =
        "{\"id\":\"a\",\"cat\":[\"x\",\"y\"],\"tags\":[],\"w\":3,\"h\":null,\"l\":\"4\","
            + "\"boxedLong\":null,\"s\":5,\"b\":6,\"big\":\"7\",\"d\":1.25,\"boxedDouble\":\"NaN\","
            + "\"decimal\":\"1.10\",\"flag\":\"true\",\"boxedFlag\":false,\"banner\":{\"w\":8},"
            + "\"banners\":[],\"ext\":{\"k\":9},\"nums\":[1,null],\"sizes\":[2],\"ids\":null}";
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      LenientImp imp = read(lenient, text, LenientImp.class, representation);
      Imp expected = read(strict, text, Imp.class, representation);
      assertEquals(lenient.toJson(imp), strict.toJson(expected));
    }
  }

  @Test
  public void truncationLimits() {
    ForyJson json = newJson();
    assertRejected(json, "{\"w\":3e10}", LenientImp.class, "Integer overflow");
    assertRejected(json, "{\"b\":128.5}", LenientImp.class, "Byte overflow");
    assertRejected(json, "{\"l\":1e999999999}", LenientImp.class, "Long overflow");
    assertRejected(json, "{\"big\":1e20000}", LenientImp.class, "Integer part is too long");
    assertRejected(json, "{\"l\":1e9999999999}", LenientImp.class, "exponent is out of range");
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      LenientImp imp =
          read(
              json,
              "{\"l\":1e-999999999,\"big\":-0.5,\"w\":-0.0}",
              LenientImp.class,
              representation);
      assertEquals(imp.l, 0L);
      assertEquals(imp.big, BigInteger.ZERO);
      assertEquals(imp.w, 0);
    }
  }

  @Test
  public void malformedPaddedScalars() {
    ForyJson json = newJson();
    assertRejected(json, "{\"w\":\" 30 0 \"}", LenientImp.class, "");
    assertRejected(json, "{\"h\":\" null \"}", LenientImp.class, "");
    assertRejected(json, "{\"h\":\" \\\"1\\\" \"}", LenientImp.class, "");
  }

  @Test
  public void propertyAndClassSettings() {
    ForyJson json = newJson();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      PartlyStrict partly =
          read(
              json,
              "{\"cat\":\"a\",\"strictCat\":[\"b\"],\"id\":5}",
              PartlyStrict.class,
              representation);
      assertEquals(partly.cat, Collections.singletonList("a"));
      assertEquals(partly.strictCat, Collections.singletonList("b"));
      assertEquals(partly.id, "5");
      PropertyOnly property =
          read(json, "{\"cat\":\"a\",\"w\":2.5}", PropertyOnly.class, representation);
      assertEquals(property.cat, Collections.singletonList("a"));
      assertEquals(property.getW(), 2);
      CreatorImp creator =
          read(json, "{\"cat\":\"a\",\"w\":3.5}", CreatorImp.class, representation);
      assertEquals(creator.cat, Collections.singletonList("a"));
      assertEquals(creator.w, 3);
    }
    assertRejected(json, "{\"strictCat\":\"b\"}", PartlyStrict.class, "");
    assertRejected(json, "{\"strictCat\":\"b\"}", PropertyOnly.class, "");
    assertRejected(json, "{\"w\":\"\"}", PropertyOnly.class, "");
    assertGeneratedWhenSupported(json, PartlyStrict.class);
    assertGeneratedWhenSupported(json, PropertyOnly.class);
  }

  @Test
  public void builderDefault() {
    ForyJson json = newJsonBuilder().lenientRead(Feature.FLOAT_AS_INT).build();
    assertEquals(json.config().lenientRead(), Collections.singleton(Feature.FLOAT_AS_INT));
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      assertEquals(read(json, "{\"w\":2.5}", Imp.class, representation).w, 2);
    }
    assertRejected(json, "{\"w\":\" 2 \"}", Imp.class, "");
    assertRejected(json, "{\"w\":2.5}", StrictImp.class, "");
    // Root values are outside property metadata.
    expectThrows(ForyJsonException.class, () -> json.fromJson("2.5", int.class));
  }

  @Test
  public void mixinSuppliesClassSetting() {
    ForyJson json = newJsonBuilder().registerMixin(Mixin.class).build();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      assertEquals(
          read(json, "{\"cat\":\"a\"}", MixinTarget.class, representation).cat,
          Collections.singletonList("a"));
    }
  }

  @JsonMixin(target = MixinTarget.class)
  @JsonLenient(Feature.SINGLE_VALUE_AS_ARRAY)
  public abstract static class Mixin {}

  @Test
  public void invalidDeclarations() {
    ForyJson json = newJson();
    assertRejected(json, "{\"id\":\"a\"}", WrongFeature.class, "no feature applicable");
    assertRejected(json, "{\"w\":1}", WithFormat.class, "requires the default representation");
    assertRejected(json, "{\"w\":1}", ConflictingDeclarations.class, "Conflicting @JsonLenient");
  }

  @Test
  public void emptyStringIsNotExplicitNull() {
    ForyJson json = newJsonBuilder().onNullRead(NullHandling.SKIP).build();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      Defaults defaults = read(json, "{\"h\":\"\",\"cat\":null}", Defaults.class, representation);
      assertNull(defaults.h);
      assertEquals(defaults.cat, Collections.singletonList("default"));
    }
  }
}
