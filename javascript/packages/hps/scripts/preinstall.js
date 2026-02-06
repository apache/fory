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
const path = require("node:path");
const version = process.version;
const fs = require("fs-extra");
const semver = require("semver");
const { engines } = require("../package.json");
const versionValid = semver.satisfies(process.version, engines.node);

async function downloadDeps(urls) {
  await Promise.all(
    urls.map(({ url, dist }) => {
      new Promise((resolve, reject) => {
        fs.ensureDirSync(path.dirname(dist));
        const process = spawn("curl", [url, "-o", dist]);
        process.on("close", () => {
          console.log("finish");
          resolve();
        });
        process.on("error", () => {
          console.error(`download from ${url} failed`);
          reject();
        });
      });
    }),
  );
}

async function main() {
  await downloadDeps([
    {
      url: `https://raw.githubusercontent.com/nodejs/node/refs/tags/${version}/deps/v8/include/v8-fast-api-calls.h`,
      dist: path.join(__dirname, "..", "includes/v8-fast-api-calls.h"),
    },
  ]);
}

if (versionValid) {
  main();
}
