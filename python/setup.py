# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import os
import platform
import subprocess
from os.path import abspath, join as pjoin

from setuptools import setup
from setuptools.dist import Distribution

DEBUG = os.environ.get("FORY_DEBUG", "False").lower() == "true"
BAZEL_BUILD_EXT = os.environ.get("BAZEL_BUILD_EXT", "True").lower() == "true"

if DEBUG:
    os.environ["CFLAGS"] = "-O0"
    BAZEL_BUILD_EXT = False

print(f"DEBUG = {DEBUG}, BAZEL_BUILD_EXT = {BAZEL_BUILD_EXT}, PATH = {os.environ.get('PATH')}")

setup_dir = abspath(os.path.dirname(__file__))
project_dir = abspath(pjoin(setup_dir, os.pardir))
fory_cpp_src_dir = abspath(pjoin(setup_dir, "../src/"))

print(f"setup_dir: {setup_dir}")
print(f"project_dir: {project_dir}")
print(f"fory_cpp_src_dir: {fory_cpp_src_dir}")


class BinaryDistribution(Distribution):
    def __init__(self, attrs=None):
        super().__init__(attrs=attrs)
        if BAZEL_BUILD_EXT:
            bazel_args = ["bazel", "build", "-s"]
            arch = platform.machine().lower()
            if arch in ("x86_64", "amd64"):
                bazel_args += ["--config=x86_64"]
            elif arch in ("aarch64", "arm64"):
                bazel_args += ["--copt=-fsigned-char"]
            bazel_args += ["//:cp_fory_so"]
            # Ensure Windows path compatibility
            cwd_path = os.path.normpath(project_dir)
            subprocess.check_call(bazel_args, cwd=cwd_path)

    def has_ext_modules(self):
        return True


if __name__ == "__main__":
    setup(distclass=BinaryDistribution)
