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

package org.apache.fory.json.data;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class UnicodeMatrix {
  public Character boxedChar = Character.valueOf('\u20ac');
  public char charThreeByte = '\u4f60';
  public char charTwoByte = '\u0100';
  public char[] chars = {'\u0100', '\u07ff', '\u0800', '\u20ac', '\u4f60'};
  public String combining = JsonTestData.COMBINING_TEXT;
  public String eu = JsonTestData.EU_TEXT;
  public String mixedScripts = JsonTestData.MIXED_SCRIPT_TEXT;
  public String supplementary = JsonTestData.SUPPLEMENTARY_TEXT;
  public String threeByte = JsonTestData.THREE_BYTE_TEXT;
  public String twoByte = JsonTestData.TWO_BYTE_TEXT;
  public Map<String, String> valueMap = JsonTestData.unicodeMap();
  public List<String> values =
      Arrays.asList(
          JsonTestData.TWO_BYTE_TEXT,
          JsonTestData.THREE_BYTE_TEXT,
          JsonTestData.SUPPLEMENTARY_TEXT,
          JsonTestData.MIXED_SCRIPT_TEXT,
          JsonTestData.COMBINING_TEXT,
          JsonTestData.ZH_TEXT,
          JsonTestData.EU_TEXT);
  public String zh = JsonTestData.ZH_TEXT;
}
