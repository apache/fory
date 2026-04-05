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

package org.apache.fory.config;

import static org.testng.Assert.*;

import org.apache.fory.Fory;
import org.apache.fory.meta.MetaCompressor;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ForyBuilderTest {

  @Test
  public void testWithMetaCompressor() {
    MetaCompressor metaCompressor =
        new ForyBuilder()
            .withMetaCompressor(
                new MetaCompressor() {
                  @Override
                  public byte[] compress(byte[] data, int offset, int size) {
                    return new byte[0];
                  }

                  @Override
                  public byte[] decompress(byte[] compressedData, int offset, int size) {
                    return new byte[0];
                  }
                })
            .metaCompressor;
    Assert.assertEquals(metaCompressor.getClass().getSimpleName(), "TypeEqualMetaCompressor");
    new ForyBuilder()
        .withMetaCompressor(
            new MetaCompressor() {
              @Override
              public byte[] compress(byte[] data, int offset, int size) {
                return new byte[0];
              }

              @Override
              public byte[] decompress(byte[] compressedData, int offset, int size) {
                return new byte[0];
              }

              @Override
              public boolean equals(Object o) {
                if (this == o) {
                  return true;
                }
                return o != null && getClass() == o.getClass();
              }

              @Override
              public int hashCode() {
                return getClass().hashCode();
              }
            });
  }

  @Test
  public void testCompatibleStateIsBooleanBacked() {
    Fory compatibleFromMode =
        new ForyBuilder().withCompatibleMode(CompatibleMode.COMPATIBLE).build();
    Fory compatibleFromBoolean = new ForyBuilder().withCompatible(true).build();
    Fory schemaConsistent = new ForyBuilder().withCompatible(false).build();

    assertTrue(compatibleFromMode.isCompatible());
    assertTrue(compatibleFromMode.getConfig().isCompatible());
    assertEquals(compatibleFromMode.getCompatibleMode(), CompatibleMode.COMPATIBLE);
    assertEquals(compatibleFromMode.getConfig().getCompatibleMode(), CompatibleMode.COMPATIBLE);
    assertEquals(compatibleFromMode.getConfig(), compatibleFromBoolean.getConfig());

    assertFalse(schemaConsistent.isCompatible());
    assertFalse(schemaConsistent.getConfig().isCompatible());
    assertEquals(schemaConsistent.getCompatibleMode(), CompatibleMode.SCHEMA_CONSISTENT);
    assertEquals(
        schemaConsistent.getConfig().getCompatibleMode(), CompatibleMode.SCHEMA_CONSISTENT);
  }
}
