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

package org.apache.fory.meta;

import java.nio.charset.StandardCharsets;
import org.apache.fory.meta.MetaString.Encoding;
import org.apache.fory.util.StringUtils;

/** Decodes MetaString objects back into their original plain text form. */
public class MetaStringDecoder {
  private final char specialChar1;
  private final char specialChar2;

  /**
   * Creates a MetaStringDecoder with specified special characters used for decoding.
   *
   * @param specialChar1 The first special character used in custom decoding.
   * @param specialChar2 The second special character used in custom decoding.
   */
  public MetaStringDecoder(char specialChar1, char specialChar2) {
    this.specialChar1 = specialChar1;
    this.specialChar2 = specialChar2;
  }

  /**
   * Decode data based on passed <code>encoding</code>. The data must be encoded using passed
   * encoding.
   *
   * @param encodedData encoded data using passed <code>encoding</code>.
   * @param encoding encoding the passed data.
   * @return Decoded string.
   */
  public String decode(byte[] encodedData, Encoding encoding) {
    if (encodedData.length == 0) {
      return "";
    }
    switch (encoding) {
      case LOWER_SPECIAL:
        return decodeLowerSpecial(encodedData);
      case LOWER_UPPER_DIGIT_SPECIAL:
        return decodeLowerUpperDigitSpecial(encodedData);
      case FIRST_TO_LOWER_SPECIAL:
        return decodeRepFirstLowerSpecial(encodedData);
      case ALL_TO_LOWER_SPECIAL:
        return decodeRepAllToLowerSpecial(encodedData);
      case UTF_8:
        return new String(encodedData, StandardCharsets.UTF_8);
      default:
        throw new IllegalStateException("Unexpected encoding flag: " + encoding);
    }
  }

  /** Decoding method for {@link Encoding#LOWER_SPECIAL}. */
  private String decodeLowerSpecial(byte[] data) {
    StringBuilder decoded = new StringBuilder();
    int totalBits = data.length * 8; // Total number of bits in the data
    boolean stripLastChar = (data[0] & 0x80) != 0; // Check the first bit of the first byte
    int bitMask = 0b11111; // 5 bits for the mask
    int bitIndex = 1; // Start from the second bit
    while (bitIndex + 5 <= totalBits && !(stripLastChar && (bitIndex + 2 * 5 > totalBits))) {
      int byteIndex = bitIndex / 8;
      int intraByteIndex = bitIndex % 8;
      // Extract the 5-bit character value across byte boundaries if needed
      int charValue;
      if (intraByteIndex > 3) {
        charValue =
            ((data[byteIndex] & 0xFF) << 8)
                | (byteIndex + 1 < data.length ? (data[byteIndex + 1] & 0xFF) : 0);
        charValue = (byte) ((charValue >> (11 - intraByteIndex)) & bitMask);
      } else {
        charValue = data[byteIndex] >> (3 - intraByteIndex) & bitMask;
      }
      bitIndex += 5;
      decoded.append(decodeLowerSpecialChar(charValue));
    }
    return decoded.toString();
  }

  /** Decoding method for {@link Encoding#LOWER_UPPER_DIGIT_SPECIAL}. */
  private String decodeLowerUpperDigitSpecial(byte[] data) {
    StringBuilder decoded = new StringBuilder();
    int bitIndex = 1;
    boolean stripLastChar = (data[0] & 0x80) != 0; // Check the first bit of the first byte
    int bitMask = 0b111111; // 6 bits for mask
    int numBits = data.length * 8;
    while (bitIndex + 6 <= numBits && !(stripLastChar && (bitIndex + 2 * 6 > numBits))) {
      int byteIndex = bitIndex / 8;
      int intraByteIndex = bitIndex % 8;

      // Extract the 6-bit character value across byte boundaries if needed
      int charValue;
      if (intraByteIndex > 2) {
        charValue =
            ((data[byteIndex] & 0xFF) << 8)
                | (byteIndex + 1 < data.length ? (data[byteIndex + 1] & 0xFF) : 0);
        charValue = ((byte) ((charValue >> (10 - intraByteIndex)) & bitMask));
      } else {
        charValue = data[byteIndex] >> (2 - intraByteIndex) & bitMask;
      }
      bitIndex += 6;
      decoded.append(decodeLowerUpperDigitSpecialChar(charValue));
    }
    return decoded.toString();
  }

  /** Decoding special char for LOWER_SPECIAL based on encoding mapping. */
  private char decodeLowerSpecialChar(int charValue) {
    if (charValue >= 0 && charValue <= 25) {
      return (char) ('a' + charValue);
    } else if (charValue == 26) {
      return '.';
    } else if (charValue == 27) {
      return '_';
    } else if (charValue == 28) {
      return '$';
    } else if (charValue == 29) {
      return '|';
    } else {
      throw new IllegalArgumentException("Invalid character value for LOWER_SPECIAL: " + charValue);
    }
  }

  /** Decoding special char for LOWER_UPPER_DIGIT_SPECIAL based on encoding mapping. */
  private char decodeLowerUpperDigitSpecialChar(int charValue) {
    if (charValue >= 0 && charValue <= 25) {
      return (char) ('a' + charValue);
    } else if (charValue >= 26 && charValue <= 51) {
      return (char) ('A' + (charValue - 26));
    } else if (charValue >= 52 && charValue <= 61) {
      return (char) ('0' + (charValue - 52));
    } else if (charValue == 62) {
      return specialChar1;
    } else if (charValue == 63) {
      return specialChar2;
    } else {
      throw new IllegalArgumentException(
          "Invalid character value for LOWER_UPPER_DIGIT_SPECIAL: " + charValue);
    }
  }

  private String decodeRepFirstLowerSpecial(byte[] data) {
    String str = decodeLowerSpecial(data);
    return StringUtils.capitalize(str);
  }

  private String decodeRepAllToLowerSpecial(byte[] data) {
    String str = decodeLowerSpecial(data);
    StringBuilder builder = new StringBuilder();
    char[] chars = str.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      if (chars[i] == '|') {
        char c = chars[++i];
        builder.append(Character.toUpperCase(c));
      } else {
        builder.append(chars[i]);
      }
    }
    return builder.toString();
  }
}
