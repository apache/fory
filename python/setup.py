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
import sys
import threading
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

_RETRYABLE_NETWORK_ERROR_PATTERNS = (
    "error downloading",
    "download_and_extract",
    "download from",
    "http archive",
    "get returned 500",
    "get returned 502",
    "get returned 503",
    "get returned 504",
    "network is unreachable",
    "connection refused",
    "connection reset",
    "connection timed out",
    "timed out waiting for",
    "timed out",
    "name resolution",
    "temporary failure in name resolution",
    "tls handshake timeout",
    "temporary failure",
)


def _is_retryable_network_error(output: str) -> bool:
    lowered = output.lower()
    return any(pattern in lowered for pattern in _RETRYABLE_NETWORK_ERROR_PATTERNS)


def _stream_pipe(pipe, sink, chunks):
    try:
        for line in iter(pipe.readline, ""):
            chunks.append(line)
            sink.write(line)
            sink.flush()
    finally:
        pipe.close()


def _run_with_retry(args, cwd, max_attempts=3):
    for attempt in range(1, max_attempts + 1):
        process = subprocess.Popen(
            args,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            errors="replace",
            bufsize=1,
        )

        stdout_chunks = []
        stderr_chunks = []
        stdout_thread = threading.Thread(target=_stream_pipe, args=(process.stdout, sys.stdout, stdout_chunks))
        stderr_thread = threading.Thread(target=_stream_pipe, args=(process.stderr, sys.stderr, stderr_chunks))
        stdout_thread.start()
        stderr_thread.start()
        returncode = process.wait()
        stdout_thread.join()
        stderr_thread.join()

        stdout_text = "".join(stdout_chunks)
        stderr_text = "".join(stderr_chunks)
        combined_output = f"{stdout_text}\n{stderr_text}"
        if returncode == 0:
            return

        if attempt >= max_attempts or not _is_retryable_network_error(combined_output):
            raise subprocess.CalledProcessError(returncode, args, output=stdout_text, stderr=stderr_text)

        backoff_seconds = attempt * 5
        print(
            f"Detected transient network/download error while running {' '.join(args)} "
            f"(attempt {attempt}/{max_attempts}); retrying in {backoff_seconds}s.",
            file=sys.stderr,
        )
        time.sleep(backoff_seconds)


class BinaryDistribution(Distribution):
    def __init__(self, attrs=None):
        super().__init__(attrs=attrs)
        if BAZEL_BUILD_EXT:
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
            _run_with_retry(bazel_args, cwd=cwd_path)

    def has_ext_modules(self):
        return True


if __name__ == "__main__":
    setup(distclass=BinaryDistribution)
