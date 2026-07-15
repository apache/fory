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

package org.apache.fory.android.json.record;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.fory.android.json.record.aar.AarRecord;
import org.apache.fory.android.json.record.jar.JarRecord;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.annotation.JsonType;

public final class RecordRuntimeScenarios {
  private RecordRuntimeScenarios() {}

  public static void applicationJarAndAarRecordsRoundTrip() {
    AppRecord value =
        new AppRecord(
            34,
            new JarRecord(7, "jar-record", Arrays.asList(Integer.valueOf(1), Integer.valueOf(2))),
            new AarRecord(8L, "aar-record"));
    ForyJson json = ForyJson.builder().build();
    String text = json.toJson(value);
    AppRecord stringCopy = json.fromJson(text, AppRecord.class);
    AppRecord utf8Copy = json.fromJson(text.getBytes(StandardCharsets.UTF_8), AppRecord.class);
    assertRecord(stringCopy);
    assertRecord(utf8Copy);

    PrivateRecord privateValue = new PrivateRecord(9, "private");
    PrivateRecord privateCopy = json.fromJson(json.toJson(privateValue), PrivateRecord.class);
    if (privateCopy.id() != 9 || !"private".equals(privateCopy.name())) {
      throw new AssertionError("Private record round trip mismatch: " + privateCopy);
    }
  }

  private static void assertRecord(AppRecord value) {
    if (value.appId() != 34
        || value.jarRecord().id() != 7
        || !"jar-record".equals(value.jarRecord().name())
        || value.jarRecord().values().size() != 2
        || value.aarRecord().sequence() != 8L
        || !"aar-record".equals(value.aarRecord().description())) {
      throw new AssertionError("Record round trip mismatch: " + value);
    }
  }

  @JsonType
  private record PrivateRecord(int id, String name) {}
}
