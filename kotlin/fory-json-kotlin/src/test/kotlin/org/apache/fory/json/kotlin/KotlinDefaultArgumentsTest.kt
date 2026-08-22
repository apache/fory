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

package org.apache.fory.json.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinDefaultArgumentsTest {
  class MaskDefaults(
    val p0: Int = 0,
    val p1: Int = 1,
    val p2: Int = 2,
    val p3: Int = 3,
    val p4: Int = 4,
    val p5: Int = 5,
    val p6: Int = 6,
    val p7: Int = 7,
    val p8: Int = 8,
    val p9: Int = 9,
    val p10: Int = 10,
    val p11: Int = 11,
    val p12: Int = 12,
    val p13: Int = 13,
    val p14: Int = 14,
    val p15: Int = 15,
    val p16: Int = 16,
    val p17: Int = 17,
    val p18: Int = 18,
    val p19: Int = 19,
    val p20: Int = 20,
    val p21: Int = 21,
    val p22: Int = 22,
    val p23: Int = 23,
    val p24: Int = 24,
    val p25: Int = 25,
    val p26: Int = 26,
    val p27: Int = 27,
    val p28: Int = 28,
    val p29: Int = 29,
    val p30: Int = 30,
    val p31: Int = 31,
    val p32: Int = 32,
    val p33: Int = 33,
    val p34: Int = 34,
    val p35: Int = 35,
    val p36: Int = 36,
    val p37: Int = 37,
    val p38: Int = 38,
    val p39: Int = 39,
    val p40: Int = 40,
    val p41: Int = 41,
    val p42: Int = 42,
    val p43: Int = 43,
    val p44: Int = 44,
    val p45: Int = 45,
    val p46: Int = 46,
    val p47: Int = 47,
    val p48: Int = 48,
    val p49: Int = 49,
    val p50: Int = 50,
    val p51: Int = 51,
    val p52: Int = 52,
    val p53: Int = 53,
    val p54: Int = 54,
    val p55: Int = 55,
    val p56: Int = 56,
    val p57: Int = 57,
    val p58: Int = 58,
    val p59: Int = 59,
    val p60: Int = 60,
    val p61: Int = 61,
    val p62: Int = 62,
    val p63: Int = 63,
    val p64: Int = 64,
  )

  @Test
  fun maskWords() {
    val fory = ForyJsonKotlin.builder().withAsyncCompilation(false).build()
    val type = jsonTypeRef<MaskDefaults>()
    val latin1 = "{\"p0\":100,\"p31\":131,\"p32\":132,\"p63\":163,\"p64\":164}"
    assertMaskValues(fory.fromJson(latin1, type))
    assertMaskValues(fory.fromJson(latin1.dropLast(1) + ",\"ignored\":\"漢\"}", type))
    assertMaskValues(fory.fromJson(latin1.toByteArray(), type))
  }

  private fun assertMaskValues(value: MaskDefaults) {
    assertEquals(100, value.p0)
    assertEquals(1, value.p1)
    assertEquals(30, value.p30)
    assertEquals(131, value.p31)
    assertEquals(132, value.p32)
    assertEquals(33, value.p33)
    assertEquals(62, value.p62)
    assertEquals(163, value.p63)
    assertEquals(164, value.p64)
  }
}
