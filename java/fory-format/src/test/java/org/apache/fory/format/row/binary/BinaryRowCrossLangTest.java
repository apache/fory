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

package org.apache.fory.format.row.binary;

import java.util.Arrays;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Schema;
import org.testng.Assert;
import org.testng.annotations.Test;

// Golden-byte tests for cross-language row format compatibility.
// Each test encodes a row using the Java BinaryRowWriter and asserts the exact hex output,
// which is used as the reference fixture in Go (and future Swift/Dart/JS) implementations.
public class BinaryRowCrossLangTest {

  // Generates the canonical golden hex for {id=42, name="Alice"} (basic cross-language test).
  @Test
  public void testGoldenFile() {
    Schema schema =
        new Schema(
            Arrays.asList(
                DataTypes.field("id", DataTypes.int64(), false),
                DataTypes.field("name", DataTypes.utf8())));

    BinaryRowWriter w = new BinaryRowWriter(schema);
    w.reset();
    w.write(0, 42L);
    w.write(1, "Alice");
    BinaryRow row = w.getRow();

    byte[] bytes = row.toBytes();
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    String hex = sb.toString();
    System.out.println("Golden hex: " + hex);

    Assert.assertEquals(
        hex,
        "00000000000000002a000000000000000500000018000000416c696365000000",
        "Java golden bytes changed — update javaGoldenHex in go/fory/row/row_test.go");
  }

  // Generates the canonical golden hex for {id=1, value=null} (null-field cross-language test).
  @Test
  public void testGoldenNull() {
    Schema schema =
        new Schema(
            Arrays.asList(
                DataTypes.field("id", DataTypes.int64(), false),
                DataTypes.field("value", DataTypes.int64(), true)));

    BinaryRowWriter w = new BinaryRowWriter(schema);
    w.reset();
    w.write(0, 1L);
    w.setNullAt(1);
    BinaryRow row = w.getRow();

    byte[] bytes = row.toBytes();
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    String hex = sb.toString();
    System.out.println("Golden null hex: " + hex);

    Assert.assertEquals(
        hex,
        "020000000000000001000000000000000000000000000000",
        "Java null golden bytes changed — update javaGoldenNullHex in go/fory/row/row_test.go");
  }

  // Generates the canonical golden hex for {id=7, scores=[10,20,30]} (array cross-language test).
  @Test
  public void testGoldenArray() {
    Schema schema =
        new Schema(
            Arrays.asList(
                DataTypes.field("id", DataTypes.int64(), false),
                DataTypes.primitiveArrayField("scores", DataTypes.int64())));

    BinaryRowWriter w = new BinaryRowWriter(schema);
    w.reset();
    w.write(0, 7L);

    BinaryArrayWriter aw = new BinaryArrayWriter(DataTypes.primitiveArrayField(DataTypes.int64()));
    aw.reset(3);
    aw.write(0, 10L);
    aw.write(1, 20L);
    aw.write(2, 30L);
    w.write(1, aw.toArray());

    BinaryRow row = w.getRow();
    byte[] bytes = row.toBytes();
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    String hex = sb.toString();
    System.out.println("Golden array hex: " + hex);

    Assert.assertEquals(
        hex,
        "000000000000000007000000000000002800000018000000"
            + "030000000000000000000000000000000a00000000000000"
            + "14000000000000001e00000000000000",
        "Java array golden bytes changed — update javaGoldenArrayHex in go/fory/row/row_test.go");
  }

  // Generates the canonical golden hex for {id=1, config={"a":100}} (map cross-language test).
  @Test
  public void testGoldenMap() {
    Schema schema =
        new Schema(
            Arrays.asList(
                DataTypes.field("id", DataTypes.int64(), false),
                DataTypes.mapField("config", DataTypes.utf8(), DataTypes.int64())));

    BinaryRowWriter w = new BinaryRowWriter(schema);
    w.reset();
    w.write(0, 1L);

    // Inline map write: 8-byte keys-array-size prefix, then keys array, then values array.
    int mapOffset = w.writerIndex();
    w.writeDirectly(-1); // 8-byte placeholder for keysArraySize

    BinaryArrayWriter keysWriter =
        new BinaryArrayWriter(DataTypes.arrayField(DataTypes.utf8()), w);
    keysWriter.reset(1);
    keysWriter.write(0, "a");
    w.writeDirectly(mapOffset, (long) keysWriter.size()); // fill keysArraySize

    BinaryArrayWriter valuesWriter =
        new BinaryArrayWriter(DataTypes.primitiveArrayField(DataTypes.int64()), w);
    valuesWriter.reset(1);
    valuesWriter.write(0, 100L);

    int mapSize = w.writerIndex() - mapOffset;
    w.setNotNullAt(1);
    w.setOffsetAndSize(1, mapOffset, mapSize);

    BinaryRow row = w.getRow();
    byte[] bytes = row.toBytes();
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    String hex = sb.toString();
    System.out.println("Golden map hex: " + hex);

    Assert.assertEquals(
        hex,
        "00000000000000000100000000000000400000001800000020000000000000000100000000000000"
            + "000000000000000001000000180000006100000000000000010000000000000000000000000000006400000000000000",
        "Java map golden bytes changed — update javaGoldenMapHex in go/fory/row/row_test.go");
  }
}
