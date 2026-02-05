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

package org.apache.fory.serializer;

import org.apache.fory.ForyTestBase;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.config.CompatibleMode;
import org.testng.annotations.Test;

public class MetaSharedObjectSerializerTest extends ForyTestBase {

  @Test
  public void testIgnoreTypeInconsistentSerializer()
      throws InstantiationException, IllegalAccessException {
    String codeA =
        "public class TestA {"
            + "    private int a = 1;"
            + "    private Long b = 2L;"
            + "    private String c = \"test\";"
            + "    private int d;"
            + "}";

    String codeB =
        "public class TestA {"
            + "    private Integer a ;"
            + "    private int b = 30;"
            + "    private String c = \"test\";"
            + "    private String d;"
            + "}";

    Class<?> cls1 = JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestA", codeA);
    Class<?> cls2 = JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestA", codeB);
    ThreadSafeFory fory1 =
        builder()
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withDeserializeUnknownClass(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .deserializeUnknownEnumValueAsNull(true)
            .withScopedMetaShare(true)
            .withCodegen(false)
            .withClassLoader(cls1.getClassLoader())
            .buildThreadSafeFory();
    ThreadSafeFory fory2 =
        builder()
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withDeserializeUnknownClass(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .deserializeUnknownEnumValueAsNull(true)
            .withScopedMetaShare(true)
            .withCodegen(false)
            .withClassLoader(cls2.getClassLoader())
            .buildThreadSafeFory();
    Object data = cls1.newInstance();
    System.out.println(fory2.deserialize(fory1.serialize(data)));
  }
}
