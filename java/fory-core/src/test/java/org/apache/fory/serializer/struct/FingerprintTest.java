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

package org.apache.fory.serializer.struct;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.annotation.Ref;
import org.apache.fory.config.Language;
import org.apache.fory.data.AllUnsignedFields;
import org.apache.fory.data.UnsignedArrayFields;
import org.apache.fory.data.UnsignedScalarFields;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.Types;
import org.testng.annotations.Test;

/** Tests for {@link Fingerprint} with unsigned integer types and unsigned integer array types. */
public class FingerprintTest extends ForyTestBase {

  @ForyStruct
  public static class RefWithoutForyFieldStruct {
    @Ref public RefWithoutForyFieldStruct peer;
  }

  @Test
  public void testUnsignedScalarFieldsFingerprint() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    List<Descriptor> descriptors = Descriptor.getDescriptors(UnsignedScalarFields.class);

    String fingerprint = Fingerprint.computeStructFingerprint(fory, descriptors);

    // Fields are sorted by snake_case name:
    // u16 (UINT16=10), u32 (UINT32=11), u32_var (VAR_UINT32=12),
    // u64 (UINT64=13), u64_tagged (TAGGED_UINT64=15), u64_var (VAR_UINT64=14),
    // u8 (UINT8=9)
    // Format: <name>,<type_id>,<ref>,<nullable>;
    String expected =
        "u16,"
            + Types.UINT16
            + ",0,0;"
            + "u32,"
            + Types.UINT32
            + ",0,0;"
            + "u32_var,"
            + Types.VAR_UINT32
            + ",0,0;"
            + "u64,"
            + Types.UINT64
            + ",0,0;"
            + "u64_tagged,"
            + Types.TAGGED_UINT64
            + ",0,0;"
            + "u64_var,"
            + Types.VAR_UINT64
            + ",0,0;"
            + "u8,"
            + Types.UINT8
            + ",0,0;";
    assertEquals(fingerprint, expected);
  }

  @Test
  public void testUnsignedArrayFieldsFingerprint() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    List<Descriptor> descriptors = Descriptor.getDescriptors(UnsignedArrayFields.class);

    String fingerprint = Fingerprint.computeStructFingerprint(fory, descriptors);

    // Fields are sorted by snake_case name:
    // u16_array (UINT16_ARRAY=45), u32_array (UINT32_ARRAY=46),
    // u64_array (UINT64_ARRAY=47), u8_array (UINT8_ARRAY=44)
    // Format: <name>,<type_id>,<ref>,<nullable>;
    String expected =
        "u16_array,"
            + Types.UINT16_ARRAY
            + ",0,1;"
            + "u32_array,"
            + Types.UINT32_ARRAY
            + ",0,1;"
            + "u64_array,"
            + Types.UINT64_ARRAY
            + ",0,1;"
            + "u8_array,"
            + Types.UINT8_ARRAY
            + ",0,1;";
    assertEquals(fingerprint, expected);
  }

  @Test
  public void testAllUnsignedFieldsFingerprint() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    List<Descriptor> descriptors = Descriptor.getDescriptors(AllUnsignedFields.class);

    String fingerprint = Fingerprint.computeStructFingerprint(fory, descriptors);

    // Fields are sorted by snake_case name alphabetically:
    // u16 (UINT16=10), u16_array (UINT16_ARRAY=45), u32 (UINT32=11), u32_array (UINT32_ARRAY=46),
    // u64 (UINT64=13), u64_array (UINT64_ARRAY=47), u8 (UINT8=9), u8_array (UINT8_ARRAY=44)
    // Format: <name>,<type_id>,<ref>,<nullable>;
    // Scalar primitives are non-nullable (0), arrays are nullable by default (1)
    String expected =
        "u16,"
            + Types.UINT16
            + ",0,0;"
            + "u16_array,"
            + Types.UINT16_ARRAY
            + ",0,1;"
            + "u32,"
            + Types.UINT32
            + ",0,0;"
            + "u32_array,"
            + Types.UINT32_ARRAY
            + ",0,1;"
            + "u64,"
            + Types.UINT64
            + ",0,0;"
            + "u64_array,"
            + Types.UINT64_ARRAY
            + ",0,1;"
            + "u8,"
            + Types.UINT8
            + ",0,0;"
            + "u8_array,"
            + Types.UINT8_ARRAY
            + ",0,1;";
    assertEquals(fingerprint, expected);
  }

  @Test
  public void testRefWithoutForyFieldAffectsFingerprintAndTypeDef() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .withCompatible(true)
            .build();
    fory.register(RefWithoutForyFieldStruct.class, 701);
    List<Descriptor> descriptors = Descriptor.getDescriptors(RefWithoutForyFieldStruct.class);

    String fingerprint = Fingerprint.computeStructFingerprint(fory, descriptors);

    assertEquals(fingerprint, "peer," + Types.UNKNOWN + ",1,0;");
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), RefWithoutForyFieldStruct.class);
    assertEquals(typeDef.getFieldCount(), 1);
    assertTrue(typeDef.getFieldsInfo().get(0).getFieldType().trackingRef());
  }
}
