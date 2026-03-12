/* Copyright (c) 2008-2023, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package org.apache.fory.benchmark.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.util.StringUtils;

public class Data {
  // array
  public int[] ints = {
    0,
    1,
    2,
    3,
    4,
    5,
    63,
    64,
    65,
    127,
    128,
    129,
    4000,
    5000,
    6000,
    16000,
    32000,
    256000,
    1024000,
    -1,
    -2,
    -3,
    -4,
    Integer.MIN_VALUE,
    Integer.MAX_VALUE
  };
  public long[] longs = {
    0,
    1,
    2,
    3,
    4,
    5,
    63,
    64,
    65,
    127,
    128,
    129,
    4000,
    5000,
    6000,
    16000,
    32000,
    256000,
    1024000,
    -1,
    -2,
    -3,
    -4,
    Integer.MIN_VALUE,
    Integer.MAX_VALUE,
    Long.MIN_VALUE,
    Long.MAX_VALUE,
    9999999999L
  };

  // string
  public String str = "abc0123456789";
  public String longStr = newLongStr();

  public static String newLongStr() {
    String strLengthStr = System.getenv("LONG_STR_LENGTH");
    int strLength = 1024;
    if (StringUtils.isNotBlank(strLengthStr)) {
      strLength = Integer.parseInt(strLengthStr);
    }
    return StringUtils.random(strLength);
  }

  public List<String> stringList = new ArrayList<>();
  public List<Integer> intList = new ArrayList<>();
  public Object[] objectArray = new Integer[20];
  public Map<String, String> stringMap = new HashMap<>();
  public Map<Integer, Integer> intMap = new HashMap<>();

  {
    for (int i = 0; i < 20; i++) {
      stringList.add("hello, " + i);
      objectArray[i] = i;
      intList.add(i);
      stringMap.put("key" + i, "value" + i);
      intMap.put(i, i * 2);
    }
  }
}
