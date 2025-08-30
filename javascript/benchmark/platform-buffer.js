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

const { BrowserBuffer } = require('@apache-fory/fory/dist/lib/platformBuffer')
const Benchmark = require("benchmark");
const { spawn } = require("child_process");

const jsonString = JSON.stringify({
  id: 12346,
  name: "John Doe",
  email: "john@example.com",
});

async function start() {
  const result = {
    writeComparison: {},
    toStringComparison: {},
  }

  {
    const platformBufferA = new BrowserBuffer(jsonString.length);
    const platformBufferB = new BrowserBuffer(jsonString.length);
    const nativeBuffer = Buffer.alloc(jsonString.length);

    var suite = new Benchmark.Suite();
    suite
      .add("browser utf8Write", function () {
        platformBufferA.utf8Write(jsonString, 0);
      })
      .add("browser write", function () {
        platformBufferB.write(jsonString, 0, 'utf8');
      })
      .add("native write", function () {
        nativeBuffer.write(jsonString, 0, 'utf8');
      })
      .on("complete", function (e) {
        e.currentTarget.forEach(({ name, hz }) => {
          result.writeComparison[name] = Math.ceil(hz);
        });
      })
      .run({ async: false });
    console.log("Write operation per second")
    console.table(result.writeComparison);
  }

  {
    const browserBuffer = new BrowserBuffer(jsonString.length);
    const nativeBuffer = Buffer.alloc(jsonString.length);
    browserBuffer.write(jsonString, 0, 'utf8');
    nativeBuffer.write(jsonString, 0, 'utf8');

    var suite = new Benchmark.Suite();
    suite
      .add("browser toString", function () {
        browserBuffer.toString('utf8', 0, jsonString.length);
      })
      .add("native toString", function () {
        nativeBuffer.toString('utf8', 0, jsonString.length);
      })
      .on("complete", function (e) {
        e.currentTarget.forEach(({ name, hz }) => {
          result.toStringComparison[name] = Math.ceil(hz);
        });
      })
      .run({ async: false });
    console.log("toString operation per second")
    console.table(result.toStringComparison);
  }

  const args = ['platform-buffer-draw.py', result.writeComparison['browser utf8Write'], result.writeComparison['browser write'], result.writeComparison['native write'], result.toStringComparison['browser toString'], result.toStringComparison['native toString']];

  console.log("Running python script to draw the graph")
  console.log("python3", ...args)

  spawn(
    `python3`,
    ['platform-buffer-draw.py', result.writeComparison['browser utf8Write'], result.writeComparison['browser write'], result.writeComparison['native write'], result.toStringComparison['browser toString'], result.toStringComparison['native toString']],
    {
      cwd: __dirname,
    }
  )
}
start();
