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

import subprocess
import platform
import os
import logging

# Constants
PYARROW_VERSION = "15.0.0"
PROJECT_ROOT_DIR = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../")
)

# Configure logging
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
)


def exec_cmd(cmd: str):
    """Execute a shell command and return its output."""
    logging.info(f"running command: {cmd}")
    try:
        result = subprocess.check_output(cmd, shell=True, universal_newlines=True)
    except subprocess.CalledProcessError as error:
        logging.error(error.stdout)
        raise

    logging.info(f"command result: {result}")
    return result


def get_os_name_lower():
    """Get the lowercase name of the operating system."""
    return platform.system().lower()


def is_windows():
    """Check if the operating system is Windows."""
    return get_os_name_lower() == "windows"


def get_os_machine():
    """Get the normalized machine architecture."""
    machine = platform.machine().lower()
    # Normalize architecture names
    if machine in ["x86_64", "amd64"]:
        return "x86_64"
    elif machine in ["aarch64", "arm64"]:
        return "arm64"
    return machine


def cd_project_subdir(subdir):
    """Change to a subdirectory of the project."""
    os.chdir(os.path.join(PROJECT_ROOT_DIR, subdir))


def bazel(cmd: str):
    """Execute a bazel command from the project root directory."""
    # Ensure we're in the project root directory where MODULE.bazel is located
    original_dir = os.getcwd()
    os.chdir(PROJECT_ROOT_DIR)
    try:
        return exec_cmd(f"bazel {cmd}")
    finally:
        os.chdir(original_dir)


def install_cpp_deps():
    """Install dependencies for C++ development."""
    # Check the Python version and install the appropriate pyarrow version
    python_version = platform.python_version()
    if python_version.startswith("3.13"):
        exec_cmd("pip install pyarrow==18.0.0")
        exec_cmd("pip install numpy")
    else:
        exec_cmd(f"pip install pyarrow=={PYARROW_VERSION}")
        # Automatically install numpy
    exec_cmd("pip install psutil")
