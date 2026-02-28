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

import 'dart:io';

/// Execute command-line command
///
/// [command] List of commands to be executed
/// [waitTimeoutSeconds] Timeout duration (in seconds) for waiting for command execution to complete
/// [env] Environment variable
///
/// Returns whether command execution succeeded (exit code 0)
///
class TestProcessUtil {
  /// Execute command-line command synchronously
  static bool executeCommandSync(
      List<String> command, int timeoutSec, Map<String, String> env) {
    try {
      print('Executing command synchronously: ${command.join(' ')}');
      // Execute using synchronous API
      ProcessResult result = Process.runSync(
        command.first,
        command.skip(1).toList(),
        environment: env,
        runInShell: true,
      );

      // Output result
      stdout.write(result.stdout);
      stderr.write(result.stderr);
      print("exitCode: ${result.exitCode}");

      return result.exitCode == 0;
    } catch (e) {
      print("Error: $e");
      return false;
    }
  }
}
