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

package org.apache.fory.json.codegen;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import org.apache.fory.json.codec.ArrayListCodecSupport;
import org.testng.annotations.Test;

public class Utf8CollectionWriterCodegenTest {
  @Test
  public void directArrayListElements() {
    Utf8CollectionWriterCodegen codegen = new Utf8CollectionWriterCodegen();
    String source = codegen.genCode("example", "Strings", true);
    String objectSource = codegen.genCode("example", "Objects", false);
    if (ArrayListCodecSupport.isAvailable()) {
      assertTrue(source.contains("ArrayListCodecSupport.elements(list)"));
      assertTrue(source.contains("String element = (String) elements[index]"));
      assertFalse(source.contains("list.get(index)"));
      assertTrue(objectSource.contains("elementWriter.writeUtf8(writer, elements[index])"));
      assertFalse(objectSource.contains("list.get(index)"));

      ArrayList<String> values = new ArrayList<>();
      values.add("first");
      assertEquals(ArrayListCodecSupport.elements(values)[0], "first");
    } else {
      assertTrue(source.contains("String element = (String) list.get(index)"));
      assertFalse(source.contains("ArrayListCodecSupport.elements(list)"));
      assertTrue(objectSource.contains("elementWriter.writeUtf8(writer, list.get(index))"));
    }
  }
}
