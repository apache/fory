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

import static org.apache.fory.type.TypeUtils.getSizeOfPrimitiveType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.record.RecordUtils;

/**
 * A utility class to group class fields into groups.
 * <li>primitive fields
 * <li>boxed primitive fields
 * <li>final fields
 * <li>collection fields
 * <li>map fields
 * <li>other fields
 */
public class DescriptorGrouper {
  static final Comparator<Descriptor> COMPARATOR_BY_PRIMITIVE_TYPE_ID =
      (d1, d2) -> {
        int c =
            Types.getPrimitiveTypeId(TypeUtils.unwrap(d2.getRawType()))
                - Types.getPrimitiveTypeId(TypeUtils.unwrap(d1.getRawType()));
        if (c == 0) {
          c = d1.getSnakeCaseName().compareTo(d2.getSnakeCaseName());
          if (c == 0) {
            // Field name duplicate in super/child classes.
            c = d1.getDeclaringClass().compareTo(d2.getDeclaringClass());
          }
        }
        return c;
      };
  private final Collection<Descriptor> descriptors;
  private final Predicate<Class<?>> isMonomorphic;
  private final Function<Descriptor, Descriptor> descriptorUpdater;
  private final boolean descriptorsGroupedOrdered;
  private boolean sorted = false;

  /**
   * When compress disabled, sort primitive descriptors from largest to smallest, if size is the
   * same, sort by field name to fix order.
   *
   * <p>When compress enabled, sort primitive descriptors from largest to smallest but let compress
   * fields ends in tail. if size is the same, sort by field name to fix order.
   */
  public static Comparator<Descriptor> getPrimitiveComparator(
      boolean compressInt, boolean compressLong) {
    if (!compressInt && !compressLong) {
      // sort primitive descriptors from largest to smallest, if size is the same,
      // sort by field name to fix order.
      return (d1, d2) -> {
        int c =
            getSizeOfPrimitiveType(TypeUtils.unwrap(d2.getRawType()))
                - getSizeOfPrimitiveType(TypeUtils.unwrap(d1.getRawType()));
        if (c == 0) {
          c = COMPARATOR_BY_PRIMITIVE_TYPE_ID.compare(d1, d2);
        }
        return c;
      };
    }
    return (d1, d2) -> {
      Class<?> t1 = TypeUtils.unwrap(d1.getRawType());
      Class<?> t2 = TypeUtils.unwrap(d2.getRawType());
      boolean t1Compress = isCompressedType(t1, compressInt, compressLong);
      boolean t2Compress = isCompressedType(t2, compressInt, compressLong);
      if ((t1Compress && t2Compress) || (!t1Compress && !t2Compress)) {
        int c = getSizeOfPrimitiveType(t2) - getSizeOfPrimitiveType(t1);
        if (c == 0) {
          c = COMPARATOR_BY_PRIMITIVE_TYPE_ID.compare(d1, d2);
        }
        return c;
      }
      if (t1Compress) {
        return 1;
      }
      // t2 compress
      return -1;
    };
  }

  private static boolean isCompressedType(Class<?> cls, boolean compressInt, boolean compressLong) {
    cls = TypeUtils.unwrap(cls);
    if (cls == int.class) {
      return compressInt;
    }
    if (cls == long.class) {
      return compressLong;
    }
    return false;
  }

  /** Comparator based on field type, name and declaring class. */
  public static final Comparator<Descriptor> COMPARATOR_BY_TYPE_AND_NAME =
      (d1, d2) -> {
        // sort by type so that we can hit class info cache more possibly.
        // sort by field name to fix order if type is same.
        int c =
            d1
                // Use type name instead of generic type so that fields with type ref
                // constructed in ClassDef which take pojo as non-final Object type
                // will have consistent order between processes if the fields doesn't exist in peer.
                .getTypeName()
                .compareTo(d2.getTypeName());
        if (c == 0) {
          c = d1.getName().compareTo(d2.getName());
          if (c == 0) {
            // Field name duplicate in super/child classes.
            c = d1.getDeclaringClass().compareTo(d2.getDeclaringClass());
          }
        }
        return c;
      };

  private final Collection<Descriptor> primitiveDescriptors;
  private final Collection<Descriptor> boxedDescriptors;
  // The element type should be final.
  private final Collection<Descriptor> collectionDescriptors;
  // The key/value type should be final.
  private final Collection<Descriptor> mapDescriptors;
  private final Collection<Descriptor> finalDescriptors;
  private Collection<Descriptor> otherDescriptors;

  /**
   * Create a descriptor grouper.
   *
   * @param isMonomorphic whether the class is monomorphic.
   * @param descriptors descriptors may have field with same name.
   * @param descriptorsGroupedOrdered whether the descriptors are grouped and ordered.
   * @param descriptorUpdater create a new descriptor from original one.
   * @param primitiveComparator comparator for primitive/boxed fields.
   * @param comparator comparator for non-primitive fields.
   */
  private DescriptorGrouper(
      Predicate<Class<?>> isMonomorphic,
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdater,
      Comparator<Descriptor> primitiveComparator,
      Comparator<Descriptor> comparator) {
    this.descriptors = descriptors;
    this.isMonomorphic = isMonomorphic;
    this.descriptorUpdater = descriptorUpdater;
    this.descriptorsGroupedOrdered = descriptorsGroupedOrdered;
    this.primitiveDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(primitiveComparator);
    this.boxedDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(primitiveComparator);
    this.collectionDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
    this.mapDescriptors = descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
    this.finalDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
    this.otherDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
  }

  public DescriptorGrouper setOtherDescriptorComparator(Comparator<Descriptor> comparator) {
    Preconditions.checkArgument(!sorted);
    this.otherDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
    return this;
  }

  public DescriptorGrouper sort() {
    if (sorted) {
      return this;
    }
    for (Descriptor descriptor : descriptors) {
      if (TypeUtils.isPrimitive(descriptor.getRawType())) {
        primitiveDescriptors.add(descriptorUpdater.apply(descriptor));
      } else if (TypeUtils.isBoxed(descriptor.getRawType())) {
        boxedDescriptors.add(descriptorUpdater.apply(descriptor));
      } else if (TypeUtils.isCollection(descriptor.getRawType())) {
        collectionDescriptors.add(descriptorUpdater.apply(descriptor));
      } else if (TypeUtils.isMap(descriptor.getRawType())) {
        mapDescriptors.add(descriptorUpdater.apply(descriptor));
      } else if (isMonomorphic.test(descriptor.getRawType())) {
        finalDescriptors.add(descriptorUpdater.apply(descriptor));
      } else {
        otherDescriptors.add(descriptorUpdater.apply(descriptor));
      }
    }
    sorted = true;
    return this;
  }

  public List<Descriptor> getSortedDescriptors() {
    Preconditions.checkArgument(sorted);
    List<Descriptor> descriptors = new ArrayList<>(getNumDescriptors());
    descriptors.addAll(getPrimitiveDescriptors());
    descriptors.addAll(getBoxedDescriptors());
    descriptors.addAll(getFinalDescriptors());
    descriptors.addAll(getCollectionDescriptors());
    descriptors.addAll(getMapDescriptors());
    descriptors.addAll(getOtherDescriptors());
    return descriptors;
  }

  public Collection<Descriptor> getPrimitiveDescriptors() {
    Preconditions.checkArgument(sorted);
    return primitiveDescriptors;
  }

  public Collection<Descriptor> getBoxedDescriptors() {
    Preconditions.checkArgument(sorted);
    return boxedDescriptors;
  }

  public Collection<Descriptor> getCollectionDescriptors() {
    Preconditions.checkArgument(sorted);
    return collectionDescriptors;
  }

  public Collection<Descriptor> getMapDescriptors() {
    Preconditions.checkArgument(sorted);
    return mapDescriptors;
  }

  public Collection<Descriptor> getFinalDescriptors() {
    Preconditions.checkArgument(sorted);
    return finalDescriptors;
  }

  public Collection<Descriptor> getOtherDescriptors() {
    Preconditions.checkArgument(sorted);
    return otherDescriptors;
  }

  private static Descriptor createDescriptor(Descriptor d) {
    Method readMethod = d.getReadMethod();
    if (readMethod != null && !RecordUtils.isRecord(readMethod.getDeclaringClass())) {
      readMethod = null;
    }
    // getter/setter may lose some inner state of an object, so we set them to null.
    if (readMethod == null && d.getWriteMethod() == null) {
      return d;
    }
    return d.copy(readMethod, null);
  }

  public static DescriptorGrouper createDescriptorGrouper(
      Predicate<Class<?>> isMonomorphic,
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator,
      boolean compressInt,
      boolean compressLong,
      Comparator<Descriptor> comparator) {
    return new DescriptorGrouper(
        isMonomorphic,
        descriptors,
        descriptorsGroupedOrdered,
        descriptorUpdator == null ? DescriptorGrouper::createDescriptor : descriptorUpdator,
        getPrimitiveComparator(compressInt, compressLong),
        comparator);
  }

  public int getNumDescriptors() {
    Preconditions.checkArgument(sorted);
    return primitiveDescriptors.size()
        + boxedDescriptors.size()
        + collectionDescriptors.size()
        + mapDescriptors.size()
        + finalDescriptors.size()
        + otherDescriptors.size();
  }
}
