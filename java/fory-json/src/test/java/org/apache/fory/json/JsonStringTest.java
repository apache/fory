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

package org.apache.fory.json;

import static org.apache.fory.json.JsonTestSupport.newLatin1Reader;
import static org.apache.fory.json.JsonTestSupport.newStringWriter;
import static org.apache.fory.json.JsonTestSupport.newUtf16Reader;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.apache.fory.json.data.CharValue;
import org.apache.fory.json.data.Kind;
import org.apache.fory.json.data.Nested;
import org.apache.fory.json.data.PublicFields;
import org.apache.fory.json.data.UnicodeEnumValue;
import org.apache.fory.json.data.UnicodeFieldNames;
import org.apache.fory.json.data.UnicodeKind;
import org.apache.fory.json.data.UnicodeMatrix;
import org.apache.fory.json.data.UnicodeValues;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.serializer.StringSerializer;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonStringTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonStringTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void escapeStrings() {
    ForyJson json = newJson();
    PublicFields fields = new PublicFields();
    fields.name = "a\n\"b\"\\\u1234";
    String stringExpected = "{\"active\":true,\"id\":7,\"name\":\"a\\n\\\"b\\\"\\\\\u1234\"}";
    assertEquals(json.toJson(fields), stringExpected);
    assertEquals(json.fromJson(stringExpected, PublicFields.class).name, fields.name);
  }

  @Test
  public void writeUtf16StringText() {
    ForyJson json = newJson();
    UnicodeValues values = new UnicodeValues();
    String expected =
        "{\"first\":\"\u1234\",\"second\":\"music \uD834\uDD1E\","
            + "\"tags\":[\"latin\",\"\u1234\",\"\uD83D\uDE00\"]}";
    assertEquals(json.toJson(values), expected);
    assertEquals(json.toJson(values), expected);
    assertEquals(new String(json.toJsonBytes(values), StandardCharsets.UTF_8), expected);
    assertEquals(json.fromJson(expected, UnicodeValues.class).second, values.second);
    assertEquals(json.fromJson(json.toJsonBytes(values), UnicodeValues.class).tags, values.tags);
  }

  @Test
  public void readCachedUtf16Bytes() {
    String input = "\"music \uD834\uDD1E\"";
    byte[] bytes = new byte[input.length() << 1];
    StringSerializer.copyStringCharsToBytes(input, bytes);
    Utf16JsonReader reader = newUtf16Reader().reset(input, bytes);
    assertEquals(reader.readString(), "music \uD834\uDD1E");
    reader.finish();
  }

  @Test
  public void readUtf16FieldNameProbe() {
    long hash = JsonFieldNameHash.hash("duration");
    int length = "duration".length();
    long mask = packedNameMask(length);

    Utf16JsonReader direct = utf16Reader("\"duration\":123");
    assertTrue(direct.tryReadNextFieldNameColon(hash, mask, length));
    assertEquals(direct.readNextIntValue(), 123);
    direct.finish();

    Utf16JsonReader mismatch = utf16Reader("\"durable\":7");
    assertFalse(mismatch.tryReadNextFieldNameColon(hash, mask, length));
    assertEquals(mismatch.readFieldNameHash(), JsonFieldNameHash.hash("durable"));
    mismatch.expectNextToken(':');
    assertEquals(mismatch.readNextIntValue(), 7);
    mismatch.finish();

    Utf16JsonReader escaped = utf16Reader("\"dur\\u0061tion\":9");
    assertFalse(escaped.tryReadNextFieldNameColon(hash, mask, length));
    assertEquals(escaped.readFieldNameHash(), hash);
    escaped.expectNextToken(':');
    assertEquals(escaped.readNextIntValue(), 9);
    escaped.finish();
  }

  @Test
  public void readUtf16FieldNameTokenProbe() {
    String shortToken = "\"size\":";
    int shortTail = shortToken.length() - 4;
    Utf16JsonReader shortDirect = utf16Reader("\"size\":7");
    assertTrue(
        shortDirect.tryReadNextFieldNameUtf16Token2(
            utf16Word(shortToken, 0, 4),
            -1L,
            utf16Word(shortToken, 4, shortTail),
            utf16WordMask(shortTail),
            shortToken.length()));
    assertEquals(shortDirect.readIntTokenValue(), 7);
    shortDirect.finish();

    String name = "duration";
    String token = "\"" + name + "\":";
    long firstWord = utf16Word(token, 0, 4);
    long secondWord = utf16Word(token, 4, 4);
    long thirdWord = utf16Word(token, 8, token.length() - 8);

    Utf16JsonReader direct = utf16Reader("\"duration\":123");
    assertTrue(
        direct.tryReadNextFieldNameUtf16Token3(firstWord, secondWord, thirdWord, token.length()));
    assertEquals(direct.readIntTokenValue(), 123);
    direct.finish();

    Utf16JsonReader nullable = utf16Reader("\"duration\":null");
    assertTrue(
        nullable.tryReadNextFieldNameUtf16Token3(firstWord, secondWord, thirdWord, token.length()));
    assertEquals(nullable.readNullableStringToken(), null);
    nullable.finish();

    Utf16JsonReader spaced = utf16Reader("\"duration\" : 123");
    assertFalse(
        spaced.tryReadNextFieldNameUtf16Token3(firstWord, secondWord, thirdWord, token.length()));
    assertTrue(
        spaced.tryReadNextFieldNameUtf16(
            JsonFieldNameHash.hash(name),
            packedNameMask(8),
            utf16Word(name, 0, 4),
            -1L,
            utf16Word(name, 4, 4),
            -1L,
            8));
    assertEquals(spaced.readNextIntValue(), 123);
    spaced.finish();
  }

  @Test
  public void writeUtf16Char() {
    ForyJson json = newJson();
    CharValue value = new CharValue();
    value.value = '\u1234';
    assertEquals(json.toJson(value), "{\"value\":\"\u1234\"}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"value\":\"\u1234\"}");
  }

  @Test
  public void writeNonLatin1Matrix() {
    ForyJson json = newJson();
    UnicodeMatrix value = new UnicodeMatrix();
    String expected = unicodeMatrixJson();
    assertEquals(json.toJson(value), expected);
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertUnicodeMatrix(json.fromJson(expected, UnicodeMatrix.class));
    assertUnicodeMatrix(
        json.fromJson(expected.getBytes(StandardCharsets.UTF_8), UnicodeMatrix.class));
    assertEquals(json.fromJson("\"" + MIXED_SCRIPT_TEXT + "\"", String.class), MIXED_SCRIPT_TEXT);
    assertEquals(
        json.fromJson(
            ("\"" + SUPPLEMENTARY_TEXT + "\"").getBytes(StandardCharsets.UTF_8), String.class),
        SUPPLEMENTARY_TEXT);
  }

  @Test
  public void writeReadZhEuStrings() {
    ForyJson json = newJson();
    assertTextRoundTrip(json, ZH_TEXT);
    assertTextRoundTrip(json, EU_TEXT);
  }

  @Test
  public void stringWriterResetAfterMaterialize() {
    StringJsonWriter writer = newStringWriter(new byte[16]);
    writer.writeString("你好，Fory");
    String utf16Json = writer.toJson();
    writer.reset();
    writer.writeString("ascii");
    String latin1Json = writer.toJson();
    assertEquals(utf16Json, "\"你好，Fory\"");
    assertEquals(latin1Json, "\"ascii\"");
    if (StringSerializer.isBytesBackedString()) {
      assertTrue(StringSerializer.isUtf16Coder(StringSerializer.getStringCoder(utf16Json)));
      assertTrue(StringSerializer.isLatin1Coder(StringSerializer.getStringCoder(latin1Json)));
    }
  }

  @Test
  public void stringWriterLatin1AfterUtf16() {
    StringJsonWriter writer = newStringWriter(new byte[16]);
    writer.writeArrayStart();
    writer.writeStringElement(0, "你好");
    writer.writeStringElement(1, "http://example.com/keynote.jpg");
    writer.writeStringElement(2, "café");
    writer.writeStringElement(3, "line\nbreak");
    writer.writeArrayEnd();
    assertEquals(
        writer.toJson(), "[\"你好\",\"http://example.com/keynote.jpg\",\"café\",\"line\\nbreak\"]");
  }

  @Test
  public void stringWriterUtf16Escapes() {
    StringJsonWriter writer = newStringWriter(new byte[16]);
    writer.writeArrayStart();
    writer.writeStringElement(0, "你好");
    writer.writeStringElement(1, "前缀\"\\\\\n\u1234");
    writer.writeArrayEnd();
    assertEquals(writer.toJson(), "[\"你好\",\"前缀\\\"\\\\\\\\\\n\u1234\"]");
  }

  @Test
  public void stringWriterShrinksOnReset() throws Exception {
    StringJsonWriter writer = newStringWriter(new byte[16]);
    writer.writeString(repeat('a', 40000) + "你好，Fory");
    assertTrue(writerBufferLength(writer) > 65536);
    writer.toJson();
    writer.reset();
    assertEquals(writerBufferLength(writer), 65536);
    writer.writeString("café");
    assertEquals(writer.toJson(), "\"café\"");
  }

  @Test
  public void readStringInputLayouts() {
    ForyJson json = newJson();
    String latin1Json = "{\"active\":true,\"id\":7,\"name\":\"café\"}";
    String utf16Json = "{\"active\":true,\"id\":7,\"name\":\"你好，Fory\"}";
    if (StringSerializer.isBytesBackedString()) {
      assertTrue(StringSerializer.isLatin1Coder(StringSerializer.getStringCoder(latin1Json)));
      assertTrue(StringSerializer.isUtf16Coder(StringSerializer.getStringCoder(utf16Json)));
    }
    assertEquals(json.fromJson(latin1Json, PublicFields.class).name, "café");
    assertEquals(json.fromJson(utf16Json, PublicFields.class).name, ZH_TEXT);
    assertEquals(json.fromJson("\"café\"", String.class), "café");
    assertEquals(json.fromJson("\"你好，Fory\"", String.class), ZH_TEXT);
  }

  @Test
  public void readStringInputEscapes() {
    ForyJson json = newJson();

    String asciiEscaped = json.fromJson("\"line\\n\\r\\t\\b\\f\\\\\\\"\\/end\"", String.class);
    String latin1Escaped = json.fromJson("\"caf\\u00E9\"", String.class);
    String chineseEscaped = json.fromJson("\"\\u4E2D\\u6587\"", String.class);
    String supplementary = json.fromJson("\"\\uD83D\\uDE00\"", String.class);

    assertEquals(asciiEscaped, "line\n\r\t\b\f\\\"/end");
    assertEquals(latin1Escaped, "café");
    assertEquals(chineseEscaped, "中文");
    assertEquals(supplementary, "\uD83D\uDE00");
    assertLatin1String(asciiEscaped);
    assertLatin1String(latin1Escaped);
    assertUtf16String(chineseEscaped);
    assertUtf16String(supplementary);

    String utf16Escaped = "\"中文\\n\\u00E9\\uD83D\\uDE00\"";
    if (StringSerializer.isBytesBackedString()) {
      assertTrue(StringSerializer.isUtf16Coder(StringSerializer.getStringCoder(utf16Escaped)));
    }
    assertEquals(json.fromJson(utf16Escaped, String.class), "中文\né\uD83D\uDE00");

    String utf16Object = "{\"active\":true,\"id\":7,\"name\":\"line\\n\",\"ignored\":\"中文\"}";
    PublicFields fields = json.fromJson(utf16Object, PublicFields.class);
    assertEquals(fields.name, "line\n");
    assertLatin1String(fields.name);

    assertThrows(ForyJsonException.class, () -> json.fromJson("\"bad\\x\"", String.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"中文\\x\"", String.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"bad\u001f\"", String.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"中文\u001f\"", String.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"\\uD83D\"", String.class));
  }

  @Test
  public void readerDecodeBufferShrinks() throws Exception {
    String latin1Input = "\"" + repeat('a', 9000) + "\\n\"";
    if (StringSerializer.isBytesBackedString()
        && StringSerializer.isLatin1Coder(StringSerializer.getStringCoder(latin1Input))) {
      Latin1JsonReader latin1Reader = newLatin1Reader(latin1Input);
      assertEquals(latin1Reader.readString(), repeat('a', 9000) + "\n");
      assertTrue(readerBufferLength(latin1Reader) > 8192);
      latin1Reader.clear();
      assertEquals(readerBufferLength(latin1Reader), 8192);
    }

    String utf16Input = "\"中文" + repeat('b', 9000) + "\\n\"";
    Utf16JsonReader utf16Reader = newUtf16Reader(utf16Input);
    assertEquals(utf16Reader.readString(), "中文" + repeat('b', 9000) + "\n");
    assertTrue(readerBufferLength(utf16Reader) > 8192);
    utf16Reader.clear();
    assertEquals(readerBufferLength(utf16Reader), 8192);
  }

  @Test
  public void readUnicodeFieldNames() {
    ForyJson json = newJson();
    String direct = "{\"café\":\"" + EU_TEXT + "\",\"你好\":\"" + ZH_TEXT + "\"}";
    String escaped = "{\"caf\\u00e9\":\"" + EU_TEXT + "\",\"\\u4f60\\u597d\":\"" + ZH_TEXT + "\"}";
    UnicodeFieldNames directValue = json.fromJson(direct, UnicodeFieldNames.class);
    UnicodeFieldNames escapedValue = json.fromJson(escaped, UnicodeFieldNames.class);
    UnicodeFieldNames bytesValue =
        json.fromJson(direct.getBytes(StandardCharsets.UTF_8), UnicodeFieldNames.class);
    assertEquals(directValue.café, EU_TEXT);
    assertEquals(directValue.你好, ZH_TEXT);
    assertEquals(escapedValue.café, EU_TEXT);
    assertEquals(escapedValue.你好, ZH_TEXT);
    assertEquals(bytesValue.café, EU_TEXT);
    assertEquals(bytesValue.你好, ZH_TEXT);
  }

  @Test
  public void readUnicodeEnum() {
    ForyJson json = newJson();
    String direct = "{\"kind\":\"你好\"}";
    String escaped = "{\"kind\":\"\\u4f60\\u597d\"}";
    String asciiDirect = "{\"kind\":\"FAST\"}";
    String asciiEscaped = "{\"kind\":\"F\\u0041ST\"}";
    assertEquals(
        new String(json.toJsonBytes(new UnicodeEnumValue()), StandardCharsets.UTF_8), direct);
    assertEquals(json.fromJson(direct, UnicodeEnumValue.class).kind, UnicodeKind.你好);
    assertEquals(json.fromJson(escaped, UnicodeEnumValue.class).kind, UnicodeKind.你好);
    assertEquals(
        json.fromJson(direct.getBytes(StandardCharsets.UTF_8), UnicodeEnumValue.class).kind,
        UnicodeKind.你好);
    assertEquals(json.fromJson(asciiDirect, Nested.class).kind, Kind.FAST);
    assertEquals(
        json.fromJson(asciiDirect.getBytes(StandardCharsets.UTF_8), Nested.class).kind, Kind.FAST);
    assertEquals(json.fromJson(asciiEscaped, Nested.class).kind, Kind.FAST);
    assertEquals(
        json.fromJson(asciiEscaped.getBytes(StandardCharsets.UTF_8), Nested.class).kind, Kind.FAST);
  }

  @Test
  public void writeLatin1NonAsciiBytes() {
    ForyJson json = newJson();
    PublicFields fields = new PublicFields();
    fields.name = "caf\u00e9";
    assertEquals(json.toJson(fields), "{\"active\":true,\"id\":7,\"name\":\"caf\u00e9\"}");
    assertEquals(
        new String(json.toJsonBytes(fields), StandardCharsets.UTF_8),
        "{\"active\":true,\"id\":7,\"name\":\"caf\u00e9\"}");
    fields.name = "\u0080";
    String expected = "{\"active\":true,\"id\":7,\"name\":\"\u0080\"}";
    assertEquals(json.toJson(fields), expected);
    assertEquals(new String(json.toJsonBytes(fields), StandardCharsets.UTF_8), expected);
    assertEquals(json.fromJson(json.toJsonBytes(fields), PublicFields.class).name, fields.name);
  }

  @Test
  public void rejectSurrogateChar() {
    ForyJson json = newJson();
    CharValue value = new CharValue();
    value.value = '\uD800';
    assertThrows(ForyJsonException.class, () -> json.toJson(value));
    assertThrows(ForyJsonException.class, () -> json.toJsonBytes(value));
  }

  @Test
  public void rejectSurrogateString() {
    ForyJson json = newJson();
    PublicFields fields = new PublicFields();
    fields.name = "\uD800";
    assertThrows(ForyJsonException.class, () -> json.toJson(fields));
    assertThrows(ForyJsonException.class, () -> json.toJsonBytes(fields));
  }

  @Test
  public void writeSurrogatePair() {
    ForyJson json = newJson();
    PublicFields fields = new PublicFields();
    fields.name = "a\uD83D\uDE00";
    String stringExpected = "{\"active\":true,\"id\":7,\"name\":\"a\uD83D\uDE00\"}";
    String utf8Expected = "{\"active\":true,\"id\":7,\"name\":\"a\uD83D\uDE00\"}";
    assertEquals(json.toJson(fields), stringExpected);
    assertEquals(new String(json.toJsonBytes(fields), StandardCharsets.UTF_8), utf8Expected);
    assertEquals(json.fromJson(stringExpected, PublicFields.class).name, fields.name);
  }

  @Test
  public void writeStringScanBoundaries() {
    ForyJson json = newJson();
    for (int length :
        new int[] {
          0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 16, 17, 20, 23, 24, 25, 30, 31, 32, 33, 63, 64, 65
        }) {
      String value = repeat('a', length);
      String expected = "\"" + value + "\"";
      assertEquals(json.toJson(value), expected);
      assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    }
    String escaped = repeat('b', 24) + "\n";
    String expected = "\"" + repeat('b', 24) + "\\n\"";
    assertEquals(json.toJson(escaped), expected);
    assertEquals(new String(json.toJsonBytes(escaped), StandardCharsets.UTF_8), expected);
  }

  @Test
  public void readStringScanBoundaries() {
    ForyJson json = newJson();
    for (int length : new int[] {0, 1, 7, 8, 15, 16, 17, 23, 24, 31, 32, 33, 63, 64, 65}) {
      String value = repeat('a', length);
      String input = "\"" + value + "\"";
      assertEquals(json.fromJson(input, String.class), value);
      assertEquals(json.fromJson(input.getBytes(StandardCharsets.UTF_8), String.class), value);
    }
    for (int length : new int[] {7, 8, 15, 16, 23, 24, 31, 32}) {
      String value = repeat('a', length) + "\u00e9" + repeat('b', 3);
      assertEquals(json.fromJson("\"" + value + "\"", String.class), value);
    }
    String escapedValue = repeat('b', 16) + "\n";
    String escapedInput = "\"" + repeat('b', 16) + "\\n\"";
    assertEquals(json.fromJson(escapedInput, String.class), escapedValue);
    assertEquals(
        json.fromJson(escapedInput.getBytes(StandardCharsets.UTF_8), String.class), escapedValue);

    String utf8Value = repeat('c', 16) + ZH_TEXT;
    String utf8Input = "\"" + utf8Value + "\"";
    assertEquals(
        json.fromJson(utf8Input.getBytes(StandardCharsets.UTF_8), String.class), utf8Value);

    String rawControl = "\"" + repeat('d', 16) + "\u001f\"";
    assertThrows(ForyJsonException.class, () -> json.fromJson(rawControl, String.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson(rawControl.getBytes(StandardCharsets.UTF_8), String.class));
    byte[] invalidUtf8 = {'"', 'a', (byte) 0xC0, (byte) 0xAF, '"'};
    assertThrows(ForyJsonException.class, () -> json.fromJson(invalidUtf8, String.class));
  }

  @Test
  public void readUtf8DecodedStrings() {
    ForyJson json = newJson();
    String ascii = readUtf8String(json, "\"plain ascii\"");
    String latin1 = readUtf8String(json, "\"caf\u00e9\"");
    String escaped = readUtf8String(json, "\"line\\n\\t\\\\\\\"\\/end\"");
    String escapedLatin1 = readUtf8String(json, "\"caf\\u00e9\"");
    String chinese = readUtf8String(json, "\"\u4e2d\u6587\"");
    String escapedChinese = readUtf8String(json, "\"\\u4e2d\\u6587\"");
    String supplementary = readUtf8String(json, "\"\\uD83D\\uDE00\"");

    assertEquals(ascii, "plain ascii");
    assertEquals(latin1, "caf\u00e9");
    assertEquals(escaped, "line\n\t\\\"/end");
    assertEquals(escapedLatin1, "caf\u00e9");
    assertEquals(chinese, "\u4e2d\u6587");
    assertEquals(escapedChinese, "\u4e2d\u6587");
    assertEquals(supplementary, "\uD83D\uDE00");
    assertLatin1String(ascii);
    assertLatin1String(latin1);
    assertLatin1String(escaped);
    assertLatin1String(escapedLatin1);
    assertUtf16String(chinese);
    assertUtf16String(escapedChinese);
    assertUtf16String(supplementary);

    byte[] rawControl = {'"', 'b', 'a', 'd', 0x1f, '"'};
    byte[] malformedUtf8 = {'"', (byte) 0xE4, (byte) 0xB8, '"'};
    assertThrows(ForyJsonException.class, () -> json.fromJson(rawControl, String.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson(malformedUtf8, String.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("\"\\uD800\"".getBytes(StandardCharsets.UTF_8), String.class));
  }

  @Test
  public void rejectInvalidSurrogates() {
    ForyJson json = newJson();
    assertEquals(json.fromJson("\"\\uD834\\uDD1E\"", String.class), "\uD834\uDD1E");
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"\\uD800\"", String.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"\\uDC00\"", String.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("\"" + Character.toString('\uD800') + "\"", String.class));
  }

  private static int writerBufferLength(StringJsonWriter writer) throws Exception {
    Field field = StringJsonWriter.class.getDeclaredField("buffer");
    field.setAccessible(true);
    return ((byte[]) field.get(writer)).length;
  }

  private static int readerBufferLength(Object reader) throws Exception {
    Field field = reader.getClass().getDeclaredField("stringDecodeBuffer");
    field.setAccessible(true);
    return ((byte[]) field.get(reader)).length;
  }

  private static String readUtf8String(ForyJson json, String input) {
    return json.fromJson(input.getBytes(StandardCharsets.UTF_8), String.class);
  }

  private static void assertLatin1String(String value) {
    if (StringSerializer.isBytesBackedString()) {
      assertTrue(StringSerializer.isLatin1Coder(StringSerializer.getStringCoder(value)));
    }
  }

  private static void assertUtf16String(String value) {
    if (StringSerializer.isBytesBackedString()) {
      assertTrue(StringSerializer.isUtf16Coder(StringSerializer.getStringCoder(value)));
    }
  }

  private static Utf16JsonReader utf16Reader(String input) {
    byte[] bytes = new byte[input.length() << 1];
    StringSerializer.copyStringCharsToBytes(input, bytes);
    return newUtf16Reader().reset(input, bytes);
  }

  private static long packedNameMask(int length) {
    return length == Long.BYTES ? -1L : (1L << (length << 3)) - 1L;
  }

  private static long utf16WordMask(int length) {
    return length == 4 ? -1L : (1L << (length << 4)) - 1L;
  }

  private static long utf16Word(String value, int start, int length) {
    long packed = 0;
    for (int i = 0; i < length; i++) {
      packed |= (long) (value.charAt(start + i) & 0xFF) << (i << 3);
    }
    long word = spreadLatin1ToUtf16(packed);
    return NativeByteOrder.IS_LITTLE_ENDIAN ? word : word << 8;
  }

  private static long spreadLatin1ToUtf16(long value) {
    value = (value | (value << 16)) & 0x0000FFFF0000FFFFL;
    return (value | (value << 8)) & 0x00FF00FF00FF00FFL;
  }
}
