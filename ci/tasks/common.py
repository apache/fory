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
import urllib.request as ulib
import os
import logging
import importlib

# Constants
PYARROW_VERSION = "15.0.0"
PROJECT_ROOT_DIR = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../")
)

# Configure logging
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
)


def get_bazel_version():
    """Get the bazel version from the .bazelversion file."""
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
    """Get the normalized machine architecture."""
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


def urlretrieve_with_retries(url, filename, max_attempts=5, initial_delay=2):
    """Download a URL to filename with retries and exponential backoff.

    Raises the last exception if all attempts fail.
    """
    attempt = 1
    delay = initial_delay
    last_exc = None
    while attempt <= max_attempts:
        try:
            logging.info(f"Downloading (attempt {attempt}) {url} -> {filename}")
            ulib.urlretrieve(url, filename)
            return
        except Exception as e:
            logging.error(f"Download attempt {attempt} failed: {e}")
            last_exc = e
            if attempt == max_attempts:
                break
            logging.info(f"Retrying in {delay} seconds...")
            import time

            time.sleep(delay)
            delay *= 2
            attempt += 1
    logging.error(f"All {max_attempts} download attempts failed for URL: {url}")
    raise last_exc


def cd_project_subdir(subdir):
    """Change to a subdirectory of the project."""
    os.chdir(os.path.join(PROJECT_ROOT_DIR, subdir))


def bazel(cmd: str):
    """Execute a bazel command from the project root directory."""
    # Ensure we're in the project root directory where MODULE.bazel is located
    original_dir = os.getcwd()
    os.chdir(PROJECT_ROOT_DIR)
    try:
        bazel_cmd = "bazel" if is_windows() else "~/bin/bazel"
        return exec_cmd(f"{bazel_cmd} {cmd}")
    finally:
        os.chdir(original_dir)


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
    required_version = get_bazel_version()

    # Check if bazel is already cached (from GitHub Actions cache)
    if not is_windows():
        home_bin = os.path.expanduser("~/bin")
        bazel_path = os.path.join(home_bin, "bazel")

        # Also check ~/.local/bin for some systems
        alt_bin = os.path.expanduser("~/.local/bin")
        alt_bazel_path = os.path.join(alt_bin, "bazel")

        for path in [bazel_path, alt_bazel_path]:
            if os.path.exists(path) and os.access(path, os.X_OK):
                logging.info(f"Bazel already exists at {path}, verifying...")
                try:
                    # Verify it works and has the correct version
                    result = exec_cmd(f"{path} --version")
                    installed_version = result.strip().replace("bazel ", "")
                    if installed_version == required_version:
                        logging.info(f"Cached Bazel binary is valid: {result.strip()}")
                        logging.info("Skipping Bazel download, using cached binary")
                        return
                    else:
                        logging.warning(
                            f"Cached Bazel version {installed_version} does not match "
                            f"required version {required_version}"
                        )
                        logging.info("Re-downloading Bazel with correct version...")
                        try:
                            os.remove(path)
                        except Exception:
                            pass
                except Exception as e:
                    logging.warning(f"Cached Bazel binary at {path} is invalid: {e}")
                    logging.info("Re-downloading Bazel...")
                    try:
                        os.remove(path)
                    except Exception:
                        pass

    bazel_download_url = get_bazel_download_url()
    logging.info(f"Downloading bazel from: {bazel_download_url}")

    if is_windows():
        # For Windows, download the installer and add it to PATH
        local_name = "bazel.exe"
        try:
            urlretrieve_with_retries(bazel_download_url, local_name)
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
            urlretrieve_with_retries(bazel_download_url, bazel_path)
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
    bazelrc_path = os.path.join(PROJECT_ROOT_DIR, ".bazelrc")
    with open(bazelrc_path, "a") as file:
        file.write(f"\nbuild --jobs={limit_jobs}")


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
    install_bazel()
