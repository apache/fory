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

// @Skip()
import 'dart:math' as math;
import 'package:test/test.dart';
import 'package:fory/src/datatype/float16.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/memory/byte_reader.dart';

void main() {
  group('Float16 Conversion', () {
    test('Zero', () {
      expect(Float16.positiveZero.toDouble(), 0.0);
      expect(Float16.negativeZero.toDouble(), -0.0);
      expect(Float16.fromDouble(0.0).toBits(), 0x0000);
      expect(Float16.fromDouble(-0.0).toBits(), 0x8000);
      
      // Verify -0.0 vs 0.0 distinction
      expect(Float16.fromDouble(-0.0).isNegative, true);
      expect(Float16.fromDouble(0.0).isNegative, false);
    });

    test('Infinity', () {
      expect(Float16.positiveInfinity.toDouble(), double.infinity);
      expect(Float16.negativeInfinity.toDouble(), double.negativeInfinity);
      expect(Float16.fromDouble(double.infinity).toBits(), 0x7C00);
      expect(Float16.fromDouble(double.negativeInfinity).toBits(), 0xFC00);
    });

    test('NaN', () {
      expect(Float16.nan.toDouble().isNaN, true);
      expect(Float16.fromDouble(double.nan).isNaN, true);
      // Canonical NaN
      expect(Float16.fromDouble(double.nan).toBits(), 0x7E00);
    });

    test('Exact values', () {
      expect(Float16(1.0).toDouble(), 1.0);
      expect(Float16(-1.0).toDouble(), -1.0);
      expect(Float16(1.5).toDouble(), 1.5);
      expect(Float16(0.5).toDouble(), 0.5);
      expect(Float16(65504.0).toDouble(), 65504.0); // Max Value
    });

    test('Rounding', () {
      // 1.0 is 0x3C00. Next is 1 + 2^-10 = 1.0009765625 (0x3C01)
      // Halfway is 1.00048828125
      
      // Round down
      expect(Float16.fromDouble(1.0004).toDouble(), 1.0);
      
      // Round up
      expect(Float16.fromDouble(1.0006).toDouble(), 1.0009765625);
      
      // Tie to even (1.0 is even (last bit 0))
      // 1.0 + half_epsilon = 1.00048828125
      // 3C00 is even. 3C01 is odd. 
      // If result is exactly halfway, pick even.
      // 1.0 corresponds to stored bits ...00
      
      double one = 1.0;
      double next = 1.0009765625;
      double mid = (one + next) / 2;
      
      // 1.0 (0x3C00) is even. 
      expect(Float16.fromDouble(mid).toBits(), 0x3C00); // 1.0
      
      // 1.0009765625 (0x3C01) is odd.
      // Next is 1.001953125 (0x3C02) - even.
      // Mid between 3C01 and 3C02
      double val1 = 1.0009765625;
      double val2 = 1.001953125;
      double mid2 = (val1 + val2) / 2;
      expect(Float16.fromDouble(mid2).toBits(), 0x3C02); // Round to even (up)
    });

    test('Subnormal', () {
      // Min subnormal: 2^-24 approx 5.96e-8
      double minSub = math.pow(2, -24).toDouble();
      expect(Float16.fromDouble(minSub).toDouble(), minSub);
      expect(Float16.fromDouble(minSub).toBits(), 0x0001);
      
      // Below min subnormal -> 0
      expect(Float16.fromDouble(minSub / 2.1).toBits(), 0x0000);
      // Round up to min subnormal
      expect(Float16.fromDouble(minSub * 0.6).toBits(), 0x0001);
      
      // Max subnormal: 0x03FF = (1 - 2^-10) * 2^-14
      int maxSubBits = 0x03FF;
      Float16 maxSub = Float16.fromBits(maxSubBits);
      expect(maxSub.isSubnormal, true);
      expect(Float16.fromDouble(maxSub.toDouble()).toBits(), maxSubBits);
    });
  });

  group('Float16 Arithmetic', () {
    test('Add', () {
      expect((Float16(1.0) + Float16(2.0)).toDouble(), 3.0);
      expect((Float16(1.0) + 2.0).toDouble(), 3.0);
    });
    test('Sub', () {
      expect((Float16(3.0) - Float16(1.0)).toDouble(), 2.0);
    });
    test('Mul', () {
      expect((Float16(2.0) * Float16(3.0)).toDouble(), 6.0);
    });
    test('Div', () {
      expect((Float16(1.0) / Float16(2.0)), 0.5);
    });
    test('Neg', () {
      expect((-Float16(1.0)).toDouble(), -1.0);
    });
    test('Abs', () {
      expect(Float16(-1.0).abs().toDouble(), 1.0);
    });
  });
  
  group('Float16 Comparision', () {
    test('Equality', () {
       expect(Float16(1.0) == Float16(1.0), true);
       expect(Float16(1.0) == Float16(2.0), false);
       expect(Float16.nan == Float16.nan, true); // Bitwise equality
    });
    
    test('Less/Greater', () {
       expect(Float16(1.0) < Float16(2.0), true);
       expect(Float16(2.0) > Float16(1.0), true);
    });
  });

  group('Serialization', () {
    test('RoundTrip', () {
      var bw = ByteWriter();
      var f1 = Float16(1.234375); // 0x3D3C approx
      var f2 = Float16(-100.0);
      var f3 = Float16.nan;
      var f4 = Float16.positiveInfinity;
      
      bw.writeFloat16(f1);
      bw.writeFloat16(f2);
      bw.writeFloat16(f3);
      bw.writeFloat16(f4);
      
      var bytes = bw.toBytes();
      var br = ByteReader.forBytes(bytes);
      
      var r1 = br.readFloat16();
      var r2 = br.readFloat16();
      var r3 = br.readFloat16();
      var r4 = br.readFloat16();
      
      expect(r1.toDouble(), f1.toDouble());
      expect(r2.toDouble(), f2.toDouble());
      expect(r3.isNaN, true);
      expect(r4.isInfinite, true);
      expect(r4.sign, 1);
    });
  });
}
