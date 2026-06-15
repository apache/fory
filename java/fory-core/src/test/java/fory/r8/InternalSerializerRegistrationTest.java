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

package fory.r8;

import java.util.AbstractList;
import java.util.Collection;
import org.apache.fory.Fory;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.collection.CollectionLikeSerializer;
import org.testng.Assert;
import org.testng.annotations.Test;

public class InternalSerializerRegistrationTest {
  @Test
  public void internalSerializerRegistrationDoesNotUsePackageNameAsOwnershipSignal() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    TypeResolver resolver = fory.getTypeResolver();
    InternalListSerializer serializer = new InternalListSerializer(resolver);

    resolver.registerInternalSerializer(InternalList.class, serializer);

    Assert.assertSame(resolver.getSerializer(InternalList.class), serializer);
  }

  public static final class InternalList extends AbstractList<String> {
    @Override
    public String get(int index) {
      throw new IndexOutOfBoundsException(String.valueOf(index));
    }

    @Override
    public int size() {
      return 0;
    }
  }

  public static final class InternalListSerializer extends CollectionLikeSerializer<InternalList> {
    public InternalListSerializer(TypeResolver typeResolver) {
      super(typeResolver, InternalList.class, false);
    }

    @Override
    public Collection<?> onCollectionWrite(WriteContext writeContext, InternalList value) {
      throw new AssertionError("registration test should not serialize");
    }

    @Override
    public InternalList onCollectionRead(Collection collection) {
      throw new AssertionError("registration test should not deserialize");
    }

    @Override
    public InternalList copy(CopyContext copyContext, InternalList value) {
      throw new AssertionError("registration test should not copy");
    }
  }
}
