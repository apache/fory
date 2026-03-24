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

"use strict";

const path = require("node:path");
const readline = require("node:readline");
const { runCLI } = require("jest");

const PROTOCOL_READY = "__FORY_XLANG_READY__";
const PROTOCOL_RESULT = "__FORY_XLANG_RESULT__";
const PROTOCOL_SHUTDOWN = "__FORY_XLANG_SHUTDOWN__";
const CROSS_LANGUAGE_TEST_FILE = path.join("test", "crossLanguage.test.ts");

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function normalizeMessage(message) {
  return message.replace(/[\r\n\t]+/g, " ").trim();
}

function failureMessage(results) {
  if (!results) {
    return "Unknown test failure";
  }
  if (results.numFailedTests > 0) {
    return `Failed tests: ${results.numFailedTests}`;
  }
  if (results.numRuntimeErrorTestSuites > 0) {
    return `Runtime error suites: ${results.numRuntimeErrorTestSuites}`;
  }
  if (results.numTotalTests === 0) {
    return "No tests executed for case";
  }
  return "Test execution failed";
}

async function runCase(caseName, dataFile) {
  process.env.DATA_FILE = dataFile;
  const { results } = await runCLI(
    {
      $0: "xlang-peer",
      _: [CROSS_LANGUAGE_TEST_FILE],
      runInBand: true,
      testNamePattern: `${escapeRegex(caseName)}$`,
      coverage: false,
      silent: true,
      passWithNoTests: false,
      ci: true,
    },
    [process.cwd()]
  );
  return results;
}

function writeProtocol(status, message) {
  if (status === "OK") {
    process.stdout.write(`${PROTOCOL_RESULT}\tOK\n`);
    return;
  }
  process.stdout.write(`${PROTOCOL_RESULT}\tERR\t${normalizeMessage(message)}\n`);
}

async function handleCommand(line) {
  if (line === PROTOCOL_SHUTDOWN) {
    writeProtocol("OK");
    process.exit(0);
    return;
  }

  const separatorIndex = line.indexOf("\t");
  if (separatorIndex <= 0 || separatorIndex === line.length - 1) {
    writeProtocol("ERR", "Invalid command format, expected <caseName>\\t<dataFile>");
    return;
  }

  const caseName = line.substring(0, separatorIndex);
  const dataFile = line.substring(separatorIndex + 1);

  try {
    const results = await runCase(caseName, dataFile);
    if (!results.success) {
      writeProtocol("ERR", failureMessage(results));
      return;
    }
    writeProtocol("OK");
  } catch (error) {
    writeProtocol(
      "ERR",
      error && error.message ? error.message : "Unexpected peer execution error"
    );
  }
}

const input = readline.createInterface({
  input: process.stdin,
  crlfDelay: Infinity,
});

let commandQueue = Promise.resolve();

input.on("line", (line) => {
  commandQueue = commandQueue.then(() => handleCommand(line));
});

input.on("close", () => {
  commandQueue.finally(() => process.exit(0));
});

process.stdout.write(`${PROTOCOL_READY}\n`);
