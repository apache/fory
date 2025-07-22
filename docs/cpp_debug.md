---
layout: global
title: Debugging C++
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

## Debugging C++

Debug C++ is tricky and binary protocol bug is hard to locate without debugging support, but setup
debugging support for bazel project is not easy.

Fory include an out-of-box `launch.json/tasks.json` config in `.vscode` directory for c++ debugging.

Just open the fory root directory in vscode, then open a test file such as `src/fory/row/row_test.cc`,
enter `F5`, then you can debug the c++ code line by line, watch variables, add breakpoints in vscode just like
java/python debugging.

<img src="images/vscode_debug_fory.jpg" alt="Fory Debugging Example" width="50%" height="50%" />

## Notes

- For Macos: Install `CodeLLDB` vscode plugin
- For Linux:
  - install gdb for fedora/centos by `sudo yum install -y gdb`
  - install gdb for fedora/centos by `sudo apt-get install gdb`
- If debugging doesn't work, please select correct debug/run options:
  <img src="images/vscode_select_debug_run.png" alt="select correct debug/run options" width="50%" height="50%" />
- Debug will jump to source files in bazel generated directory, we need to return raw files in source tree
  for debugging again: <img src="images/jmp_generate_file.png" alt="jump to generated files" width="50%" height="50%" />
