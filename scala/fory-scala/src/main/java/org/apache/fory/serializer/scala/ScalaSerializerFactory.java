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

package org.apache.fory.serializer.scala;

import java.lang.reflect.Method;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.JavaSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.SerializerFactory;
import org.apache.fory.util.Preconditions;
import scala.collection.generic.DefaultSerializable;

/** Serializer factory for Scala runtime types. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ScalaSerializerFactory implements SerializerFactory {
  /**
   * Get Serializer for scala type.
   *
   * @see DefaultSerializable
   * @see scala.collection.generic.DefaultSerializationProxy
   */
  @Override
  public Serializer createSerializer(TypeResolver typeResolver, Class<?> clz) {
    if (ScalaEnumSerializer.canSerialize(clz)) {
      return new ScalaEnumSerializer(typeResolver, clz);
    }
    if (scala.Option.class.isAssignableFrom(clz)) {
      return new ScalaOptionSerializer(typeResolver, clz);
    }
    if (typeResolver.isCrossLanguage()) {
      if (scala.collection.Map.class.isAssignableFrom(clz)) {
        return new ScalaXlangMapSerializer(typeResolver, clz);
      } else if (scala.collection.Set.class.isAssignableFrom(clz)) {
        return new ScalaXlangSetSerializer(typeResolver, clz);
      } else if (scala.collection.Seq.class.isAssignableFrom(clz)) {
        return new ScalaXlangSeqSerializer(typeResolver, clz);
      } else if (scala.collection.Iterable.class.isAssignableFrom(clz)) {
        return new ScalaXlangCollectionSerializer(typeResolver, clz);
      }
    }
    // Many map/seq/set types doesn't extends DefaultSerializable.
    if (scala.collection.SortedMap.class.isAssignableFrom(clz)) {
      return new ScalaSortedMapSerializer(typeResolver, clz);
    } else if (scala.collection.Map.class.isAssignableFrom(clz)) {
      return new ScalaMapSerializer(typeResolver, clz);
    } else if (scala.collection.SortedSet.class.isAssignableFrom(clz)) {
      return new ScalaSortedSetSerializer(typeResolver, clz);
    } else if (scala.collection.Seq.class.isAssignableFrom(clz)) {
      return new ScalaSeqSerializer(typeResolver, clz);
    } else if (scala.collection.Iterable.class.isAssignableFrom(clz)) {
      return new ScalaCollectionSerializer(typeResolver, clz);
    }
    if (DefaultSerializable.class.isAssignableFrom(clz)) {
      Method replaceMethod = JavaSerializer.getWriteReplaceMethod(clz);
      Preconditions.checkNotNull(replaceMethod);
      return new ScalaCollectionSerializer(typeResolver, clz);
    }
    return null;
  }
}
