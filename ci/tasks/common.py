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
    # Unified to x86_64 (Windows returns AMD64, others return x86_64).
    return machine.replace("amd64", "x86_64")

def get_bazel_download_url():
    """Construct the URL to download bazel."""
    bazel_version = get_bazel_version()
    download_url_base = (
        f"https://github.com/bazelbuild/bazel/releases/download/{bazel_version}"
    )
    suffix = "exe" if is_windows() else "sh"
    return (
        f"{download_url_base}/bazel-{bazel_version}{'' if is_windows() else '-installer'}-"
        f"{get_os_name_lower()}-{get_os_machine()}.{suffix}"
    )

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
    local_name = "bazel.exe" if is_windows() else "bazel-installer.sh"
    bazel_download_url = get_bazel_download_url()
    logging.info(bazel_download_url)
    ulib.urlretrieve(bazel_download_url, local_name)
    os.chmod(local_name, 0o777)

    if is_windows():
        bazel_path = os.path.join(os.getcwd(), local_name)
        exec_cmd(f'setx path "%PATH%;{bazel_path}"')
    else:
        if shutil.which("bazel"):
            os.remove(shutil.which("bazel"))
        exec_cmd(f"./{local_name} --user")
        update_shell_profile()
        os.remove(local_name)

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
    exec_cmd(f"pip install pyarrow=={PYARROW_VERSION}")
    # Automatically install numpy
    exec_cmd("pip install psutil")
    install_bazel()
