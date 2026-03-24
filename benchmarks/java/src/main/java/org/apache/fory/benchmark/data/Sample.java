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

import java.io.Serializable;
import java.util.Arrays;

public class Sample implements Serializable {
  public int intValue;
  public long longValue;
  public float floatValue;
  public double doubleValue;
  public short shortValue;
  public char charValue;
  public boolean booleanValue;

  public Integer intValueBoxed;
  public Long longValueBoxed;
  public Float floatValueBoxed;
  public Double doubleValueBoxed;
  public Short shortValueBoxed;
  public Character charValueBoxed;
  public Boolean booleanValueBoxed;

  public int[] intArray;
  public long[] longArray;
  public float[] floatArray;
  public double[] doubleArray;
  public short[] shortArray;
  public char[] charArray;
  public boolean[] booleanArray;

  public String string; // Can be null.
  public Sample sample; // Can be null.

  public Sample() {}

  public static void main(String[] args) {
    Object obj = new MediaContent().populate(false);
    System.out.println(obj);
    System.out.println(obj.equals(new MediaContent().populate(false)));
  }

  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((booleanValueBoxed == null) ? 0 : booleanValueBoxed.hashCode());
    result = prime * result + ((charValueBoxed == null) ? 0 : charValueBoxed.hashCode());
    result = prime * result + ((doubleValueBoxed == null) ? 0 : doubleValueBoxed.hashCode());
    result = prime * result + ((floatValueBoxed == null) ? 0 : floatValueBoxed.hashCode());
    result = prime * result + ((intValueBoxed == null) ? 0 : intValueBoxed.hashCode());
    result = prime * result + ((longValueBoxed == null) ? 0 : longValueBoxed.hashCode());
    result = prime * result + ((shortValueBoxed == null) ? 0 : shortValueBoxed.hashCode());
    result = prime * result + Arrays.hashCode(booleanArray);
    result = prime * result + (booleanValue ? 1231 : 1237);
    result = prime * result + Arrays.hashCode(charArray);
    result = prime * result + charValue;
    result = prime * result + Arrays.hashCode(doubleArray);
    long temp;
    temp = Double.doubleToLongBits(doubleValue);
    result = prime * result + (int) (temp ^ (temp >>> 32));
    result = prime * result + Arrays.hashCode(floatArray);
    result = prime * result + Float.floatToIntBits(floatValue);
    result = prime * result + Arrays.hashCode(intArray);
    result = prime * result + intValue;
    result = prime * result + Arrays.hashCode(longArray);
    result = prime * result + (int) (longValue ^ (longValue >>> 32));
    result = prime * result + Arrays.hashCode(shortArray);
    result = prime * result + shortValue;
    result = prime * result + ((string == null) ? 0 : string.hashCode());
    return result;
  }

  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null) {
      return false;
    }
    if (getClass() != object.getClass()) {
      return false;
    }
    Sample other = (Sample) object;
    if (booleanValueBoxed == null) {
      if (other.booleanValueBoxed != null) {
        return false;
      }
    } else if (!booleanValueBoxed.equals(other.booleanValueBoxed)) {
      return false;
    }
    if (charValueBoxed == null) {
      if (other.charValueBoxed != null) {
        return false;
      }
    } else if (!charValueBoxed.equals(other.charValueBoxed)) {
      return false;
    }
    if (doubleValueBoxed == null) {
      if (other.doubleValueBoxed != null) {
        return false;
      }
    } else if (!doubleValueBoxed.equals(other.doubleValueBoxed)) {
      return false;
    }
    if (floatValueBoxed == null) {
      if (other.floatValueBoxed != null) {
        return false;
      }
    } else if (!floatValueBoxed.equals(other.floatValueBoxed)) {
      return false;
    }
    if (intValueBoxed == null) {
      if (other.intValueBoxed != null) {
        return false;
      }
    } else if (!intValueBoxed.equals(other.intValueBoxed)) {
      return false;
    }
    if (longValueBoxed == null) {
      if (other.longValueBoxed != null) {
        return false;
      }
    } else if (!longValueBoxed.equals(other.longValueBoxed)) {
      return false;
    }
    if (shortValueBoxed == null) {
      if (other.shortValueBoxed != null) {
        return false;
      }
    } else if (!shortValueBoxed.equals(other.shortValueBoxed)) {
      return false;
    }
    if (!Arrays.equals(booleanArray, other.booleanArray)) {
      return false;
    }
    if (booleanValue != other.booleanValue) {
      return false;
    }
    if (!Arrays.equals(charArray, other.charArray)) {
      return false;
    }
    if (charValue != other.charValue) {
      return false;
    }
    if (!Arrays.equals(doubleArray, other.doubleArray)) {
      return false;
    }
    if (Double.doubleToLongBits(doubleValue) != Double.doubleToLongBits(other.doubleValue)) {
      return false;
    }
    if (!Arrays.equals(floatArray, other.floatArray)) {
      return false;
    }
    if (Float.floatToIntBits(floatValue) != Float.floatToIntBits(other.floatValue)) {
      return false;
    }
    if (!Arrays.equals(intArray, other.intArray)) {
      return false;
    }
    if (intValue != other.intValue) {
      return false;
    }
    if (!Arrays.equals(longArray, other.longArray)) {
      return false;
    }
    if (longValue != other.longValue) {
      return false;
    }
    if (!Arrays.equals(shortArray, other.shortArray)) {
      return false;
    }
    if (shortValue != other.shortValue) {
      return false;
    }
    if (string == null) {
      if (other.string != null) {
        return false;
      }
    } else if (!string.equals(other.string)) {
      return false;
    }
    return true;
  }

  public Sample populate(boolean circularReference) {
    intValue = 123;
    longValue = 1230000;
    floatValue = 12.345f;
    doubleValue = 1.234567;
    shortValue = 12345;
    charValue = '!';
    booleanValue = true;

    intValueBoxed = 321;
    longValueBoxed = 3210000L;
    floatValueBoxed = 54.321f;
    doubleValueBoxed = 7.654321;
    shortValueBoxed = 32100;
    charValueBoxed = '$';
    booleanValueBoxed = Boolean.FALSE;

    intArray = new int[] {-1234, -123, -12, -1, 0, 1, 12, 123, 1234};
    longArray = new long[] {-123400, -12300, -1200, -100, 0, 100, 1200, 12300, 123400};
    floatArray = new float[] {-12.34f, -12.3f, -12, -1, 0, 1, 12, 12.3f, 12.34f};
    doubleArray = new double[] {-1.234, -1.23, -12, -1, 0, 1, 12, 1.23, 1.234};
    shortArray = new short[] {-1234, -123, -12, -1, 0, 1, 12, 123, 1234};
    charArray = "asdfASDF".toCharArray();
    booleanArray = new boolean[] {true, false, false, true};

    string = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    if (circularReference) {
      sample = this;
    }
    return this;
  }
}
