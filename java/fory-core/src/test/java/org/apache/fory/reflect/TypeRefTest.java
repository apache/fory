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

package org.apache.fory.reflect;

import static org.testng.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.annotation.Int32Type;
import org.apache.fory.annotation.Nullable;
import org.apache.fory.annotation.Ref;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.ScalaTypes;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class TypeRefTest extends ForyTestBase {
  static class MapObject extends LinkedHashMap<String, Object> {}

  @Test
  public void testGetSubtype() {
    // For issue: https://github.com/apache/fory/issues/1604
    TypeRef<? extends Map<String, Object>> typeRef =
        TypeUtils.mapOf(MapObject.class, String.class, Object.class);
    assertEquals(typeRef, TypeRef.of(MapObject.class));
    assertEquals(
        TypeUtils.mapOf(Map.class, String.class, Object.class),
        new TypeRef<Map<String, Object>>() {});
  }

  @Data
  static class MyInternalClass<T> {
    public int c = 9;
    public T t;
  }

  @EqualsAndHashCode(callSuper = true)
  static class MyInternalBaseClass extends MyInternalClass<String> {
    public int d = 19;
  }

  @Data
  static class MyClass {
    protected Map<String, MyInternalClass<?>> fields;
    private transient int r = 13;

    public MyClass() {
      fields = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      fields.put("test", new MyInternalBaseClass());
    }
  }

  @Test
  public void testWildcardType() {
    Tuple2<TypeRef<?>, TypeRef<?>> mapKeyValueType =
        TypeUtils.getMapKeyValueType(new TypeRef<Map<String, MyInternalClass<?>>>() {});
    Assert.assertEquals(mapKeyValueType.f0.getType(), String.class);
    Assert.assertEquals(
        mapKeyValueType.f1.getRawType(), new TypeRef<MyInternalClass<?>>() {}.getRawType());
  }

  @Test(dataProvider = "enableCodegen")
  public void testWildcardTypeSerialization(boolean enableCodegen) {
    // see issue https://github.com/apache/fory/issues/1633
    Fory fory = builder().withCodegen(enableCodegen).build();
    serDeCheck(fory, new MyClass());
  }

  static class MultiParamList<A, E> extends ArrayList<E> {}

  static class MultiParamMap<A, K, V> extends HashMap<K, V> {}

  static class StringKeyMap<V> extends HashMap<String, V> {}

  static class ArrayElementList<A, E> extends ArrayList<E[]> {}

  static class ArrayValueMap<A, K, V> extends HashMap<K, V[]> {}

  static class TypeUseItem {}

  static class FixedElementList<A> extends ArrayList<TypeUseItem> {}

  static class ContainerTypeUseStruct {
    MultiParamList<String, List<TypeUseItem>> nestedItems;
    FixedElementList<@Ref(enable = false) TypeUseItem> fixedItems;
  }

  @Test
  public void testCustomContainerTypeRefNormalization() {
    TypeRef<?> listType = new TypeRef<MultiParamList<String, Integer>>() {};
    Assert.assertEquals(listType.getTypeArguments().size(), 1);
    Assert.assertEquals(listType.getTypeArguments().get(0), TypeRef.of(Integer.class));
    Assert.assertEquals(TypeUtils.getElementType(listType), TypeRef.of(Integer.class));

    GenericType listGenericType = GenericType.build(listType);
    Assert.assertEquals(listGenericType.getTypeParametersCount(), 1);
    Assert.assertEquals(listGenericType.getTypeParameter0().getCls(), Integer.class);

    TypeRef<?> mapType = new TypeRef<MultiParamMap<String, Long, Integer>>() {};
    Assert.assertEquals(mapType.getTypeArguments().size(), 2);
    Assert.assertEquals(mapType.getTypeArguments().get(0), TypeRef.of(Long.class));
    Assert.assertEquals(mapType.getTypeArguments().get(1), TypeRef.of(Integer.class));

    Tuple2<TypeRef<?>, TypeRef<?>> keyValueType = TypeUtils.getMapKeyValueType(mapType);
    Assert.assertEquals(keyValueType.f0, TypeRef.of(Long.class));
    Assert.assertEquals(keyValueType.f1, TypeRef.of(Integer.class));

    GenericType mapGenericType = GenericType.build(mapType);
    Assert.assertEquals(mapGenericType.getTypeParametersCount(), 2);
    Assert.assertEquals(mapGenericType.getTypeParameter0().getCls(), Long.class);
    Assert.assertEquals(mapGenericType.getTypeParameter1().getCls(), Integer.class);

    TypeRef<?> fixedKeyMapType = new TypeRef<StringKeyMap<List<Integer>>>() {};
    Assert.assertEquals(fixedKeyMapType.getTypeArguments().size(), 2);
    Assert.assertEquals(fixedKeyMapType.getTypeArguments().get(0), TypeRef.of(String.class));
    Assert.assertEquals(fixedKeyMapType.getTypeArguments().get(1), new TypeRef<List<Integer>>() {});

    GenericType fixedKeyMapGenericType = GenericType.build(fixedKeyMapType);
    Assert.assertEquals(fixedKeyMapGenericType.getTypeParametersCount(), 2);
    Assert.assertEquals(fixedKeyMapGenericType.getTypeParameter0().getCls(), String.class);
    Assert.assertEquals(fixedKeyMapGenericType.getTypeParameter1().getCls(), List.class);
  }

  @Test
  public void testCustomContainerArrayNormalization() {
    TypeRef<?> elementType =
        TypeUtils.getElementType(new TypeRef<ArrayElementList<String, Integer>>() {});
    Assert.assertEquals(elementType.getRawType(), Integer[].class);
    Assert.assertEquals(elementType.getComponentType(), TypeRef.of(Integer.class));

    Tuple2<TypeRef<?>, TypeRef<?>> keyValueType =
        TypeUtils.getMapKeyValueType(new TypeRef<ArrayValueMap<String, Long, Integer>>() {});
    Assert.assertEquals(keyValueType.f0, TypeRef.of(Long.class));
    Assert.assertEquals(keyValueType.f1.getRawType(), Integer[].class);
    Assert.assertEquals(keyValueType.f1.getComponentType(), TypeRef.of(Integer.class));
  }

  @Test
  public void testCustomContainerTypeUseMetadata() throws Exception {
    Field nestedItemsField = ContainerTypeUseStruct.class.getDeclaredField("nestedItems");
    TypeRef<?> nestedItemsType = TypeRef.ofTypeUse(nestedItemsField.getAnnotatedType());
    Assert.assertEquals(nestedItemsType.getTypeArguments().size(), 1);
    TypeRef<?> nestedListType = nestedItemsType.getTypeArguments().get(0);
    Assert.assertEquals(nestedListType.getRawType(), List.class);
    Assert.assertEquals(nestedListType.getTypeArguments().get(0), TypeRef.of(TypeUseItem.class));

    TypeExtMeta elementMeta = TypeExtMeta.of(Types.UNKNOWN, true, false);
    TypeRef<?> refItemsType =
        TypeRef.of(
            new TypeRef.ParameterizedTypeImpl(
                null, MultiParamList.class, new Type[] {String.class, TypeUseItem.class}),
            null,
            Arrays.asList(TypeRef.of(String.class), TypeRef.of(TypeUseItem.class, elementMeta)),
            null);
    Assert.assertEquals(refItemsType.getTypeArguments().size(), 1);
    Assert.assertEquals(refItemsType.getTypeArguments().get(0).getTypeExtMeta(), elementMeta);

    TypeExtMeta keyMeta = TypeExtMeta.of(Types.UNKNOWN, true, false);
    TypeExtMeta valueMeta = TypeExtMeta.of(Types.UNKNOWN, true, true);
    TypeRef<?> refMapType =
        TypeRef.of(
            new TypeRef.ParameterizedTypeImpl(
                null,
                MultiParamMap.class,
                new Type[] {String.class, TypeUseItem.class, TypeUseItem.class}),
            null,
            Arrays.asList(
                TypeRef.of(String.class),
                TypeRef.of(TypeUseItem.class, keyMeta),
                TypeRef.of(TypeUseItem.class, valueMeta)),
            null);
    Assert.assertEquals(refMapType.getTypeArguments().size(), 2);
    Assert.assertEquals(refMapType.getTypeArguments().get(0).getTypeExtMeta(), keyMeta);
    Assert.assertEquals(refMapType.getTypeArguments().get(1).getTypeExtMeta(), valueMeta);

    Field fixedItemsField = ContainerTypeUseStruct.class.getDeclaredField("fixedItems");
    TypeRef<?> fixedItemsType = TypeRef.ofTypeUse(fixedItemsField.getAnnotatedType());
    TypeRef<?> fixedElementType = TypeUtils.getElementType(fixedItemsType);
    Assert.assertEquals(fixedElementType, TypeRef.of(TypeUseItem.class));
    Assert.assertFalse(fixedElementType.hasTypeExtMeta());
  }

  @Test
  public void testScalaContainerTypeRefNormalization() throws Exception {
    if (!ScalaTypes.SCALA_AVAILABLE) {
      throw new SkipException("Scala is not available on the Java test classpath");
    }
    Class<?> listClass = Class.forName("scala.collection.immutable.List");
    TypeRef<?> listType =
        TypeRef.of(new TypeRef.ParameterizedTypeImpl(null, listClass, new Type[] {String.class}));
    Assert.assertEquals(listType.getTypeArguments().size(), 1);
    Assert.assertEquals(listType.getTypeArguments().get(0), TypeRef.of(String.class));
    Assert.assertEquals(TypeUtils.getElementType(listType), TypeRef.of(String.class));

    Class<?> mapClass = Class.forName("scala.collection.immutable.Map");
    TypeRef<?> mapType =
        TypeRef.of(
            new TypeRef.ParameterizedTypeImpl(
                null, mapClass, new Type[] {String.class, Integer.class}));
    Tuple2<TypeRef<?>, TypeRef<?>> keyValueType = TypeUtils.getMapKeyValueType(mapType);
    Assert.assertEquals(mapType.getTypeArguments().size(), 2);
    Assert.assertEquals(keyValueType.f0, TypeRef.of(String.class));
    Assert.assertEquals(keyValueType.f1, TypeRef.of(Integer.class));
  }

  static class TypeUseMetadataStruct {
    @Nullable String nickname;

    @Int32Type(encoding = Int32Encoding.FIXED)
    Integer code;

    List<@Ref(enable = false) String> names;
    List<@Int32Type(encoding = Int32Encoding.FIXED) Integer> codes;
  }

  @Test
  public void testTypeUseMetadataKeepsNullableOwnership() throws Exception {
    Field nicknameField = TypeUseMetadataStruct.class.getDeclaredField("nickname");
    TypeRef<?> nicknameType = TypeRef.ofTypeUse(nicknameField.getAnnotatedType());
    TypeExtMeta nicknameMeta = nicknameType.getTypeExtMeta();
    Assert.assertEquals(nicknameMeta.typeId(), Types.UNKNOWN);
    Assert.assertTrue(nicknameMeta.nullable());
    Assert.assertFalse(nicknameMeta.trackingRef());

    Field codeField = TypeUseMetadataStruct.class.getDeclaredField("code");
    TypeRef<?> codeType = TypeRef.ofTypeUse(codeField.getAnnotatedType());
    Assert.assertFalse(codeType.hasTypeExtMeta());

    Field namesField = TypeUseMetadataStruct.class.getDeclaredField("names");
    TypeRef<?> namesType = TypeRef.ofTypeUse(namesField.getAnnotatedType());
    TypeExtMeta namesElementMeta = namesType.getTypeArguments().get(0).getTypeExtMeta();
    Assert.assertEquals(namesElementMeta.typeId(), Types.UNKNOWN);
    Assert.assertTrue(namesElementMeta.nullable());
    Assert.assertFalse(namesElementMeta.trackingRef());

    Field codesField = TypeUseMetadataStruct.class.getDeclaredField("codes");
    TypeRef<?> codesType = TypeRef.ofTypeUse(codesField.getAnnotatedType());
    TypeExtMeta codesElementMeta = codesType.getTypeArguments().get(0).getTypeExtMeta();
    Assert.assertEquals(codesElementMeta.typeId(), Types.INT32);
    Assert.assertTrue(codesElementMeta.nullable());
    Assert.assertFalse(codesElementMeta.trackingRef());
  }

  @Test
  public void testIsWildcard() {
    // Test with direct wildcard types extracted from parameterized types
    Type wildcardExtendsNumber =
        ((ParameterizedType) new TypeRef<List<? extends Number>>() {}.getType())
            .getActualTypeArguments()[0];
    assertTrue(wildcardExtendsNumber instanceof WildcardType);
    assertTrue(TypeRef.of(wildcardExtendsNumber).isWildcard());

    Type wildcardSuperInteger =
        ((ParameterizedType) new TypeRef<List<? super Integer>>() {}.getType())
            .getActualTypeArguments()[0];
    assertTrue(TypeRef.of(wildcardSuperInteger).isWildcard());

    Type unboundedWildcard =
        ((ParameterizedType) new TypeRef<List<?>>() {}.getType()).getActualTypeArguments()[0];
    assertTrue(TypeRef.of(unboundedWildcard).isWildcard());

    // Non-wildcard types
    assertFalse(TypeRef.of(String.class).isWildcard());
    assertFalse(new TypeRef<List<String>>() {}.isWildcard());
    assertFalse(new TypeRef<Map<String, Integer>>() {}.isWildcard());
  }

  @Test
  public void testHasWildcard() {
    // Types with wildcards
    assertTrue(new TypeRef<List<?>>() {}.hasWildcard());
    assertTrue(new TypeRef<List<? extends Number>>() {}.hasWildcard());
    assertTrue(new TypeRef<List<? super Integer>>() {}.hasWildcard());
    assertTrue(new TypeRef<Map<String, ? extends Number>>() {}.hasWildcard());
    assertTrue(new TypeRef<Map<? extends String, Integer>>() {}.hasWildcard());
    assertTrue(new TypeRef<Map<? extends String, ? super Integer>>() {}.hasWildcard());

    // Direct wildcard type
    Type wildcardType =
        ((ParameterizedType) new TypeRef<List<? extends Number>>() {}.getType())
            .getActualTypeArguments()[0];
    assertTrue(TypeRef.of(wildcardType).hasWildcard());

    // Types without wildcards
    assertFalse(TypeRef.of(String.class).hasWildcard());
    assertFalse(new TypeRef<List<String>>() {}.hasWildcard());
    assertFalse(new TypeRef<Map<String, Integer>>() {}.hasWildcard());
    assertFalse(TypeRef.of(int.class).hasWildcard());
  }

  @Test
  public void testResolveWildcard() {
    // ? extends Number -> Number
    Type wildcardExtendsNumber =
        ((ParameterizedType) new TypeRef<List<? extends Number>>() {}.getType())
            .getActualTypeArguments()[0];
    TypeRef<?> resolved = TypeRef.of(wildcardExtendsNumber).resolveWildcard();
    assertEquals(resolved.getRawType(), Number.class);

    // ? super Integer -> Object (upper bound is Object)
    Type wildcardSuperInteger =
        ((ParameterizedType) new TypeRef<List<? super Integer>>() {}.getType())
            .getActualTypeArguments()[0];
    resolved = TypeRef.of(wildcardSuperInteger).resolveWildcard();
    assertEquals(resolved.getRawType(), Object.class);

    // ? -> Object
    Type unboundedWildcard =
        ((ParameterizedType) new TypeRef<List<?>>() {}.getType()).getActualTypeArguments()[0];
    resolved = TypeRef.of(unboundedWildcard).resolveWildcard();
    assertEquals(resolved.getRawType(), Object.class);

    // Non-wildcard types return themselves
    TypeRef<String> stringRef = TypeRef.of(String.class);
    assertSame(stringRef.resolveWildcard(), stringRef);

    TypeRef<List<String>> listRef = new TypeRef<List<String>>() {};
    assertSame(listRef.resolveWildcard(), listRef);
  }

  @Test
  public void testResolveAllWildcards() {
    // List<? extends String> -> List<String>
    TypeRef<?> listWithWildcard = new TypeRef<List<? extends String>>() {};
    TypeRef<?> resolved = listWithWildcard.resolveAllWildcards();
    assertFalse(resolved.hasWildcard());
    assertEquals(resolved.getRawType(), List.class);
    Type[] typeArgs = ((ParameterizedType) resolved.getType()).getActualTypeArguments();
    assertEquals(typeArgs[0], String.class);

    // Map<? extends String, ? super Integer> -> Map<String, Object>
    TypeRef<?> mapWithWildcards = new TypeRef<Map<? extends String, ? super Integer>>() {};
    resolved = mapWithWildcards.resolveAllWildcards();
    assertFalse(resolved.hasWildcard());
    assertEquals(resolved.getRawType(), Map.class);
    typeArgs = ((ParameterizedType) resolved.getType()).getActualTypeArguments();
    assertEquals(typeArgs[0], String.class);
    assertEquals(typeArgs[1], Object.class);

    // List<String> -> List<String> (unchanged)
    TypeRef<List<String>> noWildcard = new TypeRef<List<String>>() {};
    resolved = noWildcard.resolveAllWildcards();
    assertEquals(resolved.getType(), noWildcard.getType());

    // String -> String (unchanged)
    TypeRef<String> simpleType = TypeRef.of(String.class);
    assertSame(simpleType.resolveAllWildcards(), simpleType);
  }

  @Test
  public void testCapturedWildcard() {
    // When resolving type arguments, wildcards get captured with their upper bounds.
    // Test that captured wildcards are detected properly
    TypeRef<Map<String, ?>> mapWithWildcard = new TypeRef<Map<String, ?>>() {};
    Tuple2<TypeRef<?>, TypeRef<?>> keyValueTypes = TypeUtils.getMapKeyValueType(mapWithWildcard);

    // The value type should be a captured wildcard after resolution
    TypeRef<?> valueType = keyValueTypes.f1;

    // Test isWildcard() detects captured wildcards
    assertTrue(valueType.isWildcard());

    // Test hasWildcard() detects captured wildcards
    assertTrue(valueType.hasWildcard());

    // Test resolveWildcard() resolves captured wildcards to their bound
    TypeRef<?> resolved = valueType.resolveWildcard();
    assertEquals(resolved.getRawType(), Object.class);

    // Test with bounded wildcard: Map<String, ? extends Number>
    TypeRef<Map<String, ? extends Number>> mapWithBoundedWildcard =
        new TypeRef<Map<String, ? extends Number>>() {};
    keyValueTypes = TypeUtils.getMapKeyValueType(mapWithBoundedWildcard);
    valueType = keyValueTypes.f1;

    assertTrue(valueType.isWildcard());
    resolved = valueType.resolveWildcard();
    assertEquals(resolved.getRawType(), Number.class);
  }
}
