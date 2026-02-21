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
import time
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


def _configure_bazel_shell_for_windows():
    if os.name != "nt":
        return
    # Bazel genrules require a POSIX shell; prefer Git Bash on Windows.
    candidates = []
    for env_key in ("BAZEL_SH", "GIT_BASH", "BASH"):
        value = os.environ.get(env_key)
        if value:
            candidates.append(value)
    program_files = [os.environ.get("ProgramFiles"), os.environ.get("ProgramFiles(x86)")]
    for base in program_files:
        if not base:
            continue
        candidates.append(pjoin(base, "Git", "bin", "bash.exe"))
        candidates.append(pjoin(base, "Git", "usr", "bin", "bash.exe"))
    for path in candidates:
        if os.path.exists(path):
            os.environ["BAZEL_SH"] = path
            print(f"Using BAZEL_SH={path}")
            return


class BinaryDistribution(Distribution):
    def __init__(self, attrs=None):
        super().__init__(attrs=attrs)
        if BAZEL_BUILD_EXT:
            _configure_bazel_shell_for_windows()
            import sys

            python_version = f"{sys.version_info.major}.{sys.version_info.minor}"
            bazel_args = ["bazel", "build", "-s"]
            # Pass Python version to select the correct toolchain for C extension headers
            bazel_args += [f"--@rules_python//python/config_settings:python_version={python_version}"]
            arch = platform.machine().lower()
            if arch in ("x86_64", "amd64"):
                bazel_args += ["--config=x86_64"]
            elif arch in ("aarch64", "arm64"):
                bazel_args += ["--copt=-fsigned-char"]
            bazel_args += ["//:cp_fory_so"]
            # Ensure Windows path compatibility
            cwd_path = os.path.normpath(project_dir)
            max_attempts = 3
            for attempt in range(1, max_attempts + 1):
                try:
                    subprocess.check_call(bazel_args, cwd=cwd_path)
                    break
                except subprocess.CalledProcessError:
                    if attempt == max_attempts:
                        raise
                    # Retry transient dependency fetch failures (e.g. 502 from external archives).
                    backoff_seconds = 5 * attempt
                    print(
                        f"Bazel build failed (attempt {attempt}/{max_attempts}), "
                        f"retrying in {backoff_seconds}s..."
                    )
                    time.sleep(backoff_seconds)

    def has_ext_modules(self):
        return True


if __name__ == "__main__":
    setup(distclass=BinaryDistribution)
