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

import org.openjdk.jmh.Main;

public class Benchmark {
  // run from cmd:
  // cd .. && mvn -T10 install -DskipTests -Dcheckstyle.skip -Dlicense.skip -Dmaven.javadoc.skip
  // mvn exec:java -Dexec.args="-f 0 -wi 1 -i 1 -t 1 -w 1s -r 1s -rf csv"
  // add `-XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` to inspect
  // the compile process.
  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*_deserialize$ -f 3 -wi 5 -i 3 -t 1 -w 3s -r 3s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }
}
