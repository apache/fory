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
import org.apache.fory.annotation.ForyField;
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

  /**
   * Gets the sort key for a field descriptor.
   *
   * <p>If the field has a {@link ForyField} annotation with id >= 0, returns the id as a string.
   * Otherwise, returns the snake_case field name. This ensures fields are sorted by tag ID when
   * configured, matching the fingerprint computation order.
   *
   * @param descriptor the field descriptor
   * @return the sort key (tag ID as string or snake_case name)
   */
  public static String getFieldSortKey(Descriptor descriptor) {
    ForyField foryField = descriptor.getForyField();
    if (foryField != null && foryField.id() >= 0) {
      return String.valueOf(foryField.id());
    }
    return descriptor.getSnakeCaseName();
  }

  static final Comparator<Descriptor> COMPARATOR_BY_PRIMITIVE_TYPE_ID =
      (d1, d2) -> {
        int c =
            Types.getPrimitiveTypeId(TypeUtils.unwrap(d2.getRawType()))
                - Types.getPrimitiveTypeId(TypeUtils.unwrap(d1.getRawType()));
        if (c == 0) {
          c = getFieldSortKey(d1).compareTo(getFieldSortKey(d2));
          if (c == 0) {
            // Field name duplicate in super/child classes.
            c = d1.getDeclaringClass().compareTo(d2.getDeclaringClass());
            if (c == 0) {
              // Final tie-breaker: use actual field name to distinguish fields with same tag ID.
              // This ensures TreeSet never treats different fields as duplicates.
              c = d1.getName().compareTo(d2.getName());
            }
          }
        }
        return c;
      };
  private final Collection<Descriptor> descriptors;
  private final Predicate<Descriptor> isBuildIn;
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

  /** Comparator based on field type, name/id and declaring class. */
  public static final Comparator<Descriptor> COMPARATOR_BY_TYPE_AND_NAME =
      (d1, d2) -> {
        // sort by type so that we can hit class info cache more possibly.
        // sort by field id/name to fix order if type is same.
        int c = d1.getTypeName().compareTo(d2.getTypeName());
        if (c == 0) {
          c = getFieldSortKey(d1).compareTo(getFieldSortKey(d2));
          if (c == 0) {
            // Field name duplicate in super/child classes.
            c = d1.getDeclaringClass().compareTo(d2.getDeclaringClass());
            if (c == 0) {
              // Final tie-breaker: use actual field name to distinguish fields with same tag ID.
              // This ensures TreeSet never treats different fields as duplicates.
              c = d1.getName().compareTo(d2.getName());
            }
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
  private final Collection<Descriptor> buildInDescriptors;
  private Collection<Descriptor> otherDescriptors;

  /**
   * Create a descriptor grouper.
   *
   * @param isBuildIn whether the class is build-in types.
   * @param descriptors descriptors may have field with same name.
   * @param descriptorsGroupedOrdered whether the descriptors are grouped and ordered.
   * @param descriptorUpdater create a new descriptor from original one.
   * @param primitiveComparator comparator for primitive/boxed fields.
   * @param comparator comparator for non-primitive fields.
   */
  private DescriptorGrouper(
      Predicate<Descriptor> isBuildIn,
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdater,
      Comparator<Descriptor> primitiveComparator,
      Comparator<Descriptor> comparator) {
    this.descriptors = descriptors;
    this.isBuildIn = isBuildIn;
    this.descriptorUpdater = descriptorUpdater;
    this.descriptorsGroupedOrdered = descriptorsGroupedOrdered;
    this.primitiveDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(primitiveComparator);
    this.boxedDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(primitiveComparator);
    this.collectionDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
    this.mapDescriptors = descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
    this.buildInDescriptors =
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
        if (!descriptor.isNullable()) {
          primitiveDescriptors.add(descriptorUpdater.apply(descriptor));
        } else {
          boxedDescriptors.add(descriptorUpdater.apply(descriptor));
        }
      } else if (TypeUtils.isBoxed(descriptor.getRawType())) {
        if (!descriptor.isNullable()) {
          primitiveDescriptors.add(descriptorUpdater.apply(descriptor));
        } else {
          boxedDescriptors.add(descriptorUpdater.apply(descriptor));
        }
      } else if (TypeUtils.isCollection(descriptor.getRawType())) {
        collectionDescriptors.add(descriptorUpdater.apply(descriptor));
      } else if (TypeUtils.isMap(descriptor.getRawType())) {
        mapDescriptors.add(descriptorUpdater.apply(descriptor));
      } else if (isBuildIn.test(descriptor)) {
        buildInDescriptors.add(descriptorUpdater.apply(descriptor));
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
    descriptors.addAll(getBuildInDescriptors());
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

  public Collection<Descriptor> getBuildInDescriptors() {
    Preconditions.checkArgument(sorted);
    return buildInDescriptors;
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
      Predicate<Descriptor> isBuildIn,
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator,
      boolean compressInt,
      boolean compressLong,
      Comparator<Descriptor> comparator) {
    return new DescriptorGrouper(
        isBuildIn,
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
        + buildInDescriptors.size()
        + otherDescriptors.size();
  }
}
