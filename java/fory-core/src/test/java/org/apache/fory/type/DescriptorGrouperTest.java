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

package org.apache.fory.type;

import static org.testng.Assert.*;

import com.google.common.primitives.Primitives;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassResolver;
import org.testng.annotations.Test;

public class DescriptorGrouperTest extends ForyTestBase {

  private Descriptor createDescriptor(
      TypeRef<?> typeRef, String name, int modifier, String declaringClass, boolean trackingRef) {
    return new Descriptor(
        typeRef,
        typeRef.getType().getTypeName(),
        name,
        modifier,
        declaringClass,
        trackingRef,
        !typeRef.isPrimitive());
  }

  private List<Descriptor> createDescriptors() {
    List<Descriptor> descriptors = new ArrayList<>();
    int index = 0;
    for (Class<?> aClass : Primitives.allPrimitiveTypes()) {
      descriptors.add(createDescriptor(TypeRef.of(aClass), "f" + index++, -1, "TestClass", false));
    }
    for (Class<?> t : Primitives.allWrapperTypes()) {
      descriptors.add(createDescriptor(TypeRef.of(t), "f" + index++, -1, "TestClass", false));
    }
    descriptors.add(
        createDescriptor(TypeRef.of(String.class), "f" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(Object.class), "f" + index++, -1, "TestClass", false));
    Collections.shuffle(descriptors, new Random(17));
    return descriptors;
  }

  @Test
  public void testComparatorByTypeAndName() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    List<Descriptor> descriptors = new ArrayList<>();
    descriptors.add(
        createDescriptor(TypeRef.of(Date.class), "z_timestamp", -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(Instant.class), "a_timestamp", -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(LocalDateTime.class), "m_timestamp", -1, "TestClass", false));
    descriptors.sort(((ClassResolver) fory.getTypeResolver()).createTypeAndNameComparator());
    assertEquals(
        descriptors.stream().map(Descriptor::getName).collect(Collectors.toList()),
        Arrays.asList("a_timestamp", "m_timestamp", "z_timestamp"));
  }

  @Test
  public void testPrimitiveComparator() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    List<Descriptor> descriptors = new ArrayList<>();
    int index = 0;
    for (Class<?> aClass : Primitives.allPrimitiveTypes()) {
      descriptors.add(createDescriptor(TypeRef.of(aClass), "f" + index++, -1, "TestClass", false));
    }
    Collections.shuffle(descriptors, new Random(7));
    descriptors.sort(fory.getTypeResolver().getPrimitiveComparator());
    List<? extends Class<?>> classes =
        descriptors.stream().map(Descriptor::getRawType).collect(Collectors.toList());
    // With compression enabled (default): int/long are compressed and go to the end
    // Non-compressed sorted by size (desc), then typeId (asc), matching xlang field order.
    List<Class<?>> expected =
        Arrays.asList(
            double.class,
            float.class,
            short.class,
            char.class,
            boolean.class,
            byte.class,
            void.class,
            long.class,
            int.class);
    assertEquals(classes, expected);
  }

  @Test
  public void testPrimitiveCompressedComparator() {
    List<Descriptor> descriptors = new ArrayList<>();
    int index = 0;
    for (Class<?> aClass : Primitives.allPrimitiveTypes()) {
      descriptors.add(createDescriptor(TypeRef.of(aClass), "f" + index++, -1, "TestClass", false));
    }
    Collections.shuffle(descriptors, new Random(7));
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    descriptors.sort(fory.getTypeResolver().getPrimitiveComparator());
    List<? extends Class<?>> classes =
        descriptors.stream().map(Descriptor::getRawType).collect(Collectors.toList());
    // With compression enabled (default): int/long are compressed and go to the end
    // Non-compressed sorted by size (desc), then typeId (asc), matching xlang field order.
    List<Class<?>> expected =
        Arrays.asList(
            double.class,
            float.class,
            short.class,
            char.class,
            boolean.class,
            byte.class,
            void.class,
            long.class,
            int.class);
    assertEquals(classes, expected);
  }

  @Test
  public void testXlangPrimitiveComparatorUsesAscendingTypeIdTieBreaker() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).build();
    List<Descriptor> descriptors = new ArrayList<>();
    descriptors.add(
        createDescriptor(TypeRef.of(Short.class), "shortValue", -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(Float16.class), "float16Value", -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(BFloat16.class), "bfloat16Value", -1, "TestClass", false));

    Collections.shuffle(descriptors, new Random(11));
    descriptors.sort(fory.getTypeResolver().getPrimitiveComparator());

    List<? extends Class<?>> classes =
        descriptors.stream().map(Descriptor::getRawType).collect(Collectors.toList());
    assertEquals(classes, Arrays.asList(Short.class, Float16.class, BFloat16.class));
  }

  @Test
  public void testGrouper() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    List<Descriptor> descriptors = createDescriptors();
    int index = 0;
    descriptors.add(
        createDescriptor(TypeRef.of(Object.class), "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(Date.class), "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(Instant.class), "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(Instant.class), "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(new TypeRef<List<String>>() {}, "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(new TypeRef<List<Integer>>() {}, "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(
            new TypeRef<Map<String, Integer>>() {}, "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(
            new TypeRef<Map<String, String>>() {}, "c" + index++, -1, "TestClass", false));
    DescriptorGrouper grouper =
        DescriptorGrouper.createDescriptorGrouper(
                d -> ReflectionUtils.isMonomorphic(d.getRawType()),
                descriptors,
                false,
                null,
                fory.getTypeResolver().getPrimitiveComparator(),
                ((ClassResolver) fory.getTypeResolver()).createTypeAndNameComparator())
            .sort();
    {
      List<? extends Class<?>> classes =
          grouper.getPrimitiveDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      // With compression enabled: int/long go to end, sorted by size then typeId (asc)
      List<Class<?>> expected =
          Arrays.asList(
              double.class,
              float.class,
              short.class,
              char.class,
              boolean.class,
              byte.class,
              void.class,
              long.class,
              int.class);
      assertEquals(classes, expected);
    }
    {
      List<? extends Class<?>> classes =
          grouper.getBoxedDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      // With compression enabled: Integer/Long go to end, sorted by size then typeId (asc)
      List<Class<?>> expected =
          Arrays.asList(
              Double.class,
              Float.class,
              Short.class,
              Character.class,
              Boolean.class,
              Byte.class,
              Void.class,
              Long.class,
              Integer.class);
      assertEquals(classes, expected);
    }
    {
      List<TypeRef<?>> types =
          grouper.getCollectionDescriptors().stream()
              .map(Descriptor::getTypeRef)
              .collect(Collectors.toList());
      // Normalized type name is the same (Collection), fallback to field name order (c4 then c5)
      List<TypeRef<?>> expected =
          Arrays.asList(new TypeRef<List<String>>() {}, new TypeRef<List<Integer>>() {});
      assertEquals(types, expected);
    }
    {
      List<TypeRef<?>> types =
          grouper.getMapDescriptors().stream()
              .map(Descriptor::getTypeRef)
              .collect(Collectors.toList());
      List<TypeRef<?>> expected =
          Arrays.asList(
              new TypeRef<Map<String, Integer>>() {}, new TypeRef<Map<String, String>>() {});
      assertEquals(types, expected);
    }
    {
      List<? extends Class<?>> classes =
          grouper.getBuildInDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      assertEquals(classes, Arrays.asList(String.class, Instant.class, Instant.class));
    }
    {
      List<? extends Class<?>> classes =
          grouper.getOtherDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      assertEquals(classes, Arrays.asList(Date.class, Object.class, Object.class));
    }
  }

  @Test
  public void testCompressedPrimitiveGrouper() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    DescriptorGrouper grouper =
        DescriptorGrouper.createDescriptorGrouper(
                d -> ReflectionUtils.isMonomorphic(d.getRawType()),
                createDescriptors(),
                false,
                null,
                fory.getTypeResolver().getPrimitiveComparator(),
                ((ClassResolver) fory.getTypeResolver()).createTypeAndNameComparator())
            .sort();
    {
      List<? extends Class<?>> classes =
          grouper.getPrimitiveDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      // With compression enabled: int/long go to end, sorted by size then typeId (asc)
      List<Class<?>> expected =
          Arrays.asList(
              double.class,
              float.class,
              short.class,
              char.class,
              boolean.class,
              byte.class,
              void.class,
              long.class,
              int.class);
      assertEquals(classes, expected);
    }
    {
      List<? extends Class<?>> classes =
          grouper.getBoxedDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      // With compression enabled: Integer/Long go to end, sorted by size then typeId (asc)
      List<Class<?>> expected =
          Arrays.asList(
              Double.class,
              Float.class,
              Short.class,
              Character.class,
              Boolean.class,
              Byte.class,
              Void.class,
              Long.class,
              Integer.class);
      assertEquals(classes, expected);
    }
  }

  /** Test that collection-like descriptors use xlang/spec type-id groups before field names. */
  @Test
  public void testNormalizedTypeNameComparator() {
    Fory fory = builder().build();
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
    Comparator<Descriptor> comparator = classResolver.createTypeAndNameComparator();

    // Create descriptors with different Collection/Map subtypes
    List<Descriptor> descriptors = new ArrayList<>();
    // List type
    descriptors.add(
        createDescriptor(new TypeRef<List<String>>() {}, "listField", -1, "TestClass", false));
    // Set type
    descriptors.add(
        createDescriptor(new TypeRef<Set<String>>() {}, "setField", -1, "TestClass", false));
    // Collection type
    descriptors.add(
        createDescriptor(
            new TypeRef<Collection<String>>() {}, "collField", -1, "TestClass", false));
    // ArrayList type
    descriptors.add(
        createDescriptor(
            new TypeRef<ArrayList<String>>() {}, "arrayListField", -1, "TestClass", false));
    // HashMap type
    descriptors.add(
        createDescriptor(
            new TypeRef<HashMap<String, Integer>>() {}, "hashMapField", -1, "TestClass", false));
    // Map type
    descriptors.add(
        createDescriptor(
            new TypeRef<Map<String, Integer>>() {}, "mapField", -1, "TestClass", false));

    // Sort with the normalized comparator
    descriptors.sort(comparator);

    // Get field names after sorting
    List<String> fieldNames =
        descriptors.stream().map(Descriptor::getName).collect(Collectors.toList());

    // LIST-like fields are ordered by field name, then SET, then MAP.
    List<String> expected =
        Arrays.asList(
            "arrayListField", "collField", "listField", "setField", "hashMapField", "mapField");
    assertEquals(fieldNames, expected);
  }

  @Test
  public void testComparatorUsesFieldIdentifierBeforeRawTypeName() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    Comparator<Descriptor> comparator =
        ((ClassResolver) fory.getTypeResolver()).createTypeAndNameComparator();
    Descriptor date = createDescriptor(TypeRef.of(Date.class), "z_date", -1, "TestClass", false);
    Descriptor instant =
        createDescriptor(TypeRef.of(Instant.class), "a_instant", -1, "TestClass", false);
    assertTrue(comparator.compare(date, instant) > 0);
  }

  @Test
  public void testXlangSortedDescriptorsCollapseNonPrimitiveGroups() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).build();
    List<Descriptor> descriptors = new ArrayList<>();
    descriptors.add(createDescriptor(TypeRef.of(int.class), "m_int", -1, "TestClass", false));
    descriptors.add(createDescriptor(TypeRef.of(Integer.class), "n_int", -1, "TestClass", false));
    descriptors.add(createDescriptor(TypeRef.of(String.class), "z_string", -1, "TestClass", false));
    descriptors.add(
        createDescriptor(new TypeRef<Map<String, Integer>>() {}, "a_map", -1, "TestClass", false));
    descriptors.add(
        createDescriptor(new TypeRef<List<String>>() {}, "b_list", -1, "TestClass", false));
    descriptors.add(createDescriptor(TypeRef.of(Object.class), "c_object", -1, "TestClass", false));

    DescriptorGrouper grouper =
        fory.getTypeResolver().groupDescriptors(descriptors, false, descriptor -> descriptor);

    List<String> sortedNames =
        grouper.getSortedDescriptors().stream()
            .map(Descriptor::getName)
            .collect(Collectors.toList());
    assertEquals(
        sortedNames, Arrays.asList("m_int", "n_int", "a_map", "b_list", "c_object", "z_string"));
  }
}
