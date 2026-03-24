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

package org.apache.fory.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.reflect.ReflectDatumReader;
import org.apache.avro.reflect.ReflectDatumWriter;
import org.apache.fory.format.encoder.Encoder;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;

public class RowSuite {
  private static final Logger LOG = LoggerFactory.getLogger(RowSuite.class);

  public static final class TestStruct implements Serializable {
    private short f1;
    private int f2;
    private long f3;
    private float f4;
    private double f5;
    private double f6;
    private long f7;
    private short f11;
    private int f12;
    private long f13;
    private float f14;
    private double f15;
    private double f16;
    private long f17;
    private List<Integer> intList;
  }

  public static TestStruct createBeanB(int arrSize) {
    Random rnd = new Random(37);
    TestStruct testStruct = new TestStruct();
    testStruct.f1 = (short) rnd.nextInt();
    testStruct.f2 = rnd.nextInt();
    testStruct.f3 = rnd.nextLong();
    testStruct.f4 = rnd.nextFloat();
    testStruct.f5 = rnd.nextDouble();
    testStruct.f6 = rnd.nextDouble();
    testStruct.f7 = rnd.nextLong();
    testStruct.f11 = (short) rnd.nextInt();
    testStruct.f12 = rnd.nextInt();
    testStruct.f13 = rnd.nextLong();
    testStruct.f14 = rnd.nextFloat();
    testStruct.f15 = rnd.nextDouble();
    testStruct.f16 = rnd.nextDouble();
    testStruct.f17 = rnd.nextLong();
    List<Integer> integers = new ArrayList<>();
    for (int i = 0; i < arrSize; i++) {
      integers.add(rnd.nextInt());
    }
    testStruct.intList = integers;
    return testStruct;
  }

  private static TestStruct object = createBeanB(10);

  private static DatumWriter<TestStruct> avroWriter = new ReflectDatumWriter<>(TestStruct.class);
  private static ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
  private static BinaryEncoder avroEncoder;
  private static final byte[] avroData;
  private static BinaryDecoder avroDecoder =
      DecoderFactory.get().binaryDecoder(outputStream.toByteArray(), null);
  private static DatumReader<TestStruct> avroReader = new ReflectDatumReader<>(TestStruct.class);

  private static Encoder<TestStruct> foryEncoder = Encoders.bean(TestStruct.class);
  private static final byte[] foryData;

  static {
    // create a file of packets
    DatumWriter<TestStruct> writer = new ReflectDatumWriter<>(TestStruct.class);
    avroEncoder = EncoderFactory.get().binaryEncoder(outputStream, null);
    outputStream.reset();
    try {
      writer.write(object, avroEncoder);
      avroEncoder.flush();
      avroData = outputStream.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    foryData = foryEncoder.encode(object);
  }

  @Benchmark
  public Object forySerialize() throws IOException {
    return foryEncoder.encode(object);
  }

  @Benchmark
  public Object foryDeserialize() throws IOException {
    return foryEncoder.decode(foryData);
  }

  @Benchmark
  public Object avroSerialize() throws IOException {
    outputStream.reset();
    avroWriter.write(object, avroEncoder);
    avroEncoder.flush();
    return outputStream.toByteArray();
  }

  @Benchmark
  public Object avroDeserialize() throws IOException {
    avroDecoder = DecoderFactory.get().binaryDecoder(avroData, avroDecoder);
    return avroReader.read(null, avroDecoder);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      String commandLine = "org.apache.fory.*RowSuite.* -f 3 -wi 3 -i 3 -t 1 -w 2s -r 2s -rf csv";
      args = commandLine.split(" ");
    }
    LOG.info("command line: {}", Arrays.toString(args));
    Main.main(args);
  }
}
