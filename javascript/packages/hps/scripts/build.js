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

const { spawn } = require("node:child_process");
const semver = require("semver");
const { engines } = require("../package.json");
const versionValid = semver.satisfies(process.version, engines.node);

function watchError(child) {
  child.on("error", (error) => {
    console.error(error);
    process.exit(1);
  });
  child.on("exit", (code, signal) => {
    if (code !== 0) {
      process.exit(code);
    }
  });
}

if (versionValid) {
  const gyp = spawn("npx", ["node-gyp", "rebuild"], {
    stdio: "inherit",
    shell: true,
  });
  watchError(gyp);
}
watchError(spawn("npx", ["tsc"], { stdio: "inherit", shell: true }));
