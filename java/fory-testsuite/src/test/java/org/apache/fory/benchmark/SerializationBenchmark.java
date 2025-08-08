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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.test.bean.Foo;
import org.nustaq.serialization.FSTConfiguration;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

public class SerializationBenchmark {

  private long iterNums;
  private static final Logger LOG = LoggerFactory.getLogger(SerializationBenchmark.class);

  @BeforeTest
  public void setArrSize() {
    int defaultIterNums = 20000000;
    iterNums = Integer.parseInt(System.getProperty("iterNums", String.valueOf(defaultIterNums)));
    LOG.info("iterNums: " + iterNums);
  }

  // mvn test -DargLine="-XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining"
  // ...
  // mvn test -Dtest=org.apache.fory.benchmark.SerializationBenchmark#serializationBenchmark
  // -DiterNums=10000000
  @Test(enabled = false)
  public void serializationBenchmark() throws Exception {
    @SuppressWarnings("unchecked")
    Object data = Foo.create();
    testFory(data);
    testFst(data);
    testKryo(data);
    testJDK(data);
  }

  private void testEncoder() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    RowEncoder<Foo> encoder = Encoders.bean(Foo.class, fory, 64);
    Foo data = Foo.create();
    // test encoder
    // warm
    for (int i = 0; i < iterNums; i++) {
      encoder.toRow(data);
    }
    // test
    long startTime = System.nanoTime();
    for (int i = 0; i < iterNums; i++) {
      encoder.toRow(data);
    }
    long duration = System.nanoTime() - startTime;

    LOG.info(
        "encoder\t take "
            + duration
            + " ns, "
            + duration / 1000_000
            + "ms. "
            + (double) duration / iterNums
            + "/ns, "
            + (double) duration / iterNums / 1000_000
            + "/ms\n");
  }

  private void testFory(Object obj) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    fory.register(obj.getClass());
    MemoryBuffer buffer = MemoryBuffer.buffer(32);
    // warm
    for (int i = 0; i < iterNums; i++) {
      buffer.writerIndex(0);
      fory.writeRef(buffer, obj);
    }
    // test
    long startTime = System.nanoTime();
    for (int i = 0; i < iterNums; i++) {
      buffer.writerIndex(0);
      fory.writeRef(buffer, obj);
    }
    long duration = System.nanoTime() - startTime;
    LOG.info(
        "fory\t size {} take "
            + duration
            + " ns, "
            + duration / 1000_000
            + "ms. "
            + (double) duration / iterNums
            + "/ns, "
            + (double) duration / iterNums / 1000_000
            + "/ms\n",
        buffer.writerIndex());
  }

  private void testFst(Object obj) {
    // test fst
    FSTConfiguration fstConf = FSTConfiguration.createDefaultConfiguration();
    fstConf.setPreferSpeed(true);
    fstConf.setShareReferences(false);
    fstConf.registerClass(obj.getClass());
    byte[] bytes = new byte[0];
    // warm
    for (int i = 0; i < iterNums; i++) {
      bytes = fstConf.asByteArray(obj);
    }

    // test
    long startTime = System.nanoTime();
    for (int i = 0; i < iterNums; i++) {
      bytes = fstConf.asByteArray(obj);
    }
    long duration = System.nanoTime() - startTime;
    LOG.info(
        "fst\t size {} take "
            + duration
            + " ns, "
            + duration / 1000_000
            + "ms. "
            + (double) duration / iterNums
            + "/ns, "
            + (double) duration / iterNums / 1000_000
            + "/ms\n",
        bytes.length);
  }

  private void testKryo(Object obj) {
    Kryo kryo = new Kryo();
    kryo.register(obj.getClass());
    // warm
    for (int i = 0; i < iterNums; i++) {
      Output output = new Output(64, Integer.MAX_VALUE);
      kryo.writeObject(output, obj);
      output.clear();
    }
    // test
    long startTime = System.nanoTime();
    for (int i = 0; i < iterNums; i++) {
      Output output = new Output(64, Integer.MAX_VALUE);
      kryo.writeObject(output, obj);
      output.clear();
    }
    long duration = System.nanoTime() - startTime;
    Output output = new Output(64, Integer.MAX_VALUE);
    kryo.writeObject(output, obj);
    LOG.info(
        "kryo\t {} take "
            + duration
            + " ns, "
            + duration / 1000_000
            + "ms. "
            + (double) duration / iterNums
            + "/ns, "
            + (double) duration / iterNums / 1000_000
            + "/ms\n",
        output.position());
  }

  private void testJDK(Object data) {
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    // warm
    for (int i = 0; i < iterNums; i++) {
      bas.reset();
      try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(bas)) {
        objectOutputStream.writeObject(data);
        objectOutputStream.flush();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    // test
    long startTime = System.nanoTime();
    for (int i = 0; i < iterNums; i++) {
      bas.reset();
      try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(bas)) {
        objectOutputStream.writeObject(data);
        objectOutputStream.flush();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    long duration = System.nanoTime() - startTime;
    LOG.info(
        "jdk\t {} take "
            + duration
            + " ns, "
            + duration / 1000_000
            + "ms. "
            + (double) duration / iterNums
            + "/ns, "
            + (double) duration / iterNums / 1000_000
            + "/ms\n",
        bas.size());
  }
}
