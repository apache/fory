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

import shutil
import subprocess
import platform
import urllib.request as ulib
import os
import logging
import importlib

# Constants
PYARROW_VERSION = "15.0.0"
PROJECT_ROOT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../")

# Configure logging
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
)


def get_bazel_version():
    """Get the bazel version from .bazelversion file."""
    with open(os.path.join(PROJECT_ROOT_DIR, ".bazelversion")) as f:
        return f.read().strip()


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
    """Get the machine architecture, normalized."""
    machine = platform.machine().lower()
    # Normalize architecture names
    if machine in ["x86_64", "amd64"]:
        return "x86_64"
    elif machine in ["aarch64", "arm64"]:
        return "arm64"
    return machine


def get_bazel_download_url():
    """Construct the URL to download bazel."""
    bazel_version = get_bazel_version()
    download_url_base = (
        f"https://github.com/bazelbuild/bazel/releases/download/{bazel_version}"
    )

    # For Windows, use the .exe installer
    if is_windows():
        return f"{download_url_base}/bazel-{bazel_version}-windows-x86_64.exe"

    # For Unix-like systems, use the binary directly (not the installer)
    return f"{download_url_base}/bazel-{bazel_version}-{get_os_name_lower()}-{get_os_machine()}"


def cd_project_subdir(subdir):
    """Change to a subdirectory of the project."""
    os.chdir(os.path.join(PROJECT_ROOT_DIR, subdir))


def bazel(cmd: str):
    """Execute a bazel command."""
    bazel_cmd = "bazel" if is_windows() else "~/bin/bazel"
    return exec_cmd(f"{bazel_cmd} {cmd}")


def update_shell_profile():
    """Update shell profile to include bazel in PATH."""
    home = os.path.expanduser("~")
    profiles = [".bashrc", ".bash_profile", ".zshrc"]
    path_export = 'export PATH="$PATH:$HOME/bin" # Add Bazel to PATH\n'
    for profile in profiles:
        profile_path = os.path.join(home, profile)
        if os.path.exists(profile_path):
            with open(profile_path, "a") as f:
                f.write(path_export)
            logging.info(f"Updated {profile} to include Bazel PATH.")
            break
    else:
        logging.info("No shell profile found. Please add Bazel to PATH manually.")


def install_bazel():
    """Download and install bazel."""
    bazel_download_url = get_bazel_download_url()
    logging.info(f"Downloading bazel from: {bazel_download_url}")

    if is_windows():
        # For Windows, download the installer and add it to PATH
        local_name = "bazel.exe"
        try:
            ulib.urlretrieve(bazel_download_url, local_name)
        except Exception as e:
            logging.error(f"Failed to download bazel: {e}")
            logging.error(f"URL: {bazel_download_url}")
            logging.error(
                f"OS: {get_os_name_lower()}, Machine: {get_os_machine()}, Original Machine: {platform.machine().lower()}"
            )
            raise
        os.chmod(local_name, 0o777)
        bazel_path = os.path.join(os.getcwd(), local_name)
        exec_cmd(f'setx path "%PATH%;{bazel_path}"')
    else:
        # For Unix-like systems, download the binary directly to ~/bin/bazel
        home_bin = os.path.expanduser("~/bin")
        os.makedirs(home_bin, exist_ok=True)
        bazel_path = os.path.join(home_bin, "bazel")

        try:
            ulib.urlretrieve(bazel_download_url, bazel_path)
        except Exception as e:
            logging.error(f"Failed to download bazel: {e}")
            logging.error(f"URL: {bazel_download_url}")
            logging.error(
                f"OS: {get_os_name_lower()}, Machine: {get_os_machine()}, Original Machine: {platform.machine().lower()}"
            )
            raise

        os.chmod(bazel_path, 0o755)
        update_shell_profile()

    # bazel install status check
    bazel("--version")

    # default is byte
    psutil = importlib.import_module("psutil")
    total_mem = psutil.virtual_memory().total
    limit_jobs = int(total_mem / 1024 / 1024 / 1024 / 3)
    with open(".bazelrc", "a") as file:
        file.write(f"\nbuild --jobs={limit_jobs}")


def install_cpp_deps():
    """Install dependencies for C++ development."""
    # Check Python version and install appropriate pyarrow version
    python_version = platform.python_version()
    if python_version.startswith("3.13"):
        exec_cmd("pip install pyarrow==18.0.0")
        exec_cmd("pip install numpy")
    else:
        exec_cmd(f"pip install pyarrow=={PYARROW_VERSION}")
        # Automatically install numpy
    exec_cmd("pip install psutil")
    install_bazel()
