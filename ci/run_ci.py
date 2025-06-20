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


import argparse
import shutil
import subprocess
import platform
import urllib.request as ulib
import os
import logging
import importlib

def _get_bazel_version():
    with open(os.path.join(PROJECT_ROOT_DIR, ".bazelversion")) as f:
        return f.read().strip()

PYARROW_VERSION = "15.0.0"

PROJECT_ROOT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../")

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
)


def _bazel(cmd: str):
    bazel_cmd = "bazel" if _is_windows() else "~/bin/bazel"
    return _exec_cmd(f"{bazel_cmd} {cmd}")


def _exec_cmd(cmd: str):
    logging.info(f"running command: {cmd}")
    try:
        result = subprocess.check_output(cmd, shell=True, universal_newlines=True)
    except subprocess.CalledProcessError as error:
        logging.error(error.stdout)
        raise

    logging.info(f"command result: {result}")
    return result


def _get_os_name_lower():
    return platform.system().lower()


def _is_windows():
    return _get_os_name_lower() == "windows"


def _get_os_machine():
    machine = platform.machine().lower()
    # Unified to x86_64(Windows return AMD64, others return x86_64).
    return machine.replace("amd64", "x86_64")


def _get_bazel_download_url():
    bazel_version = _get_bazel_version()
    download_url_base = (
        f"https://github.com/bazelbuild/bazel/releases/download/{bazel_version}"
    )
    suffix = "exe" if _is_windows() else "sh"
    return (
        f"{download_url_base}/bazel-{bazel_version}{'' if _is_windows() else '-installer'}-"
        f"{_get_os_name_lower()}-{_get_os_machine()}.{suffix}"
    )


def _cd_project_subdir(subdir):
    os.chdir(os.path.join(PROJECT_ROOT_DIR, subdir))


def _run_cpp():
    _install_cpp_deps()
    # run test
    query_result = _bazel("query //...")
    _bazel("test {}".format(query_result.replace("\n", " ").replace("\r", " ")))


def _run_rust():
    logging.info("Executing fory rust tests")
    _cd_project_subdir("rust")

    cmds = (
        "cargo doc --no-deps --document-private-items --all-features --open",
        "cargo fmt --all -- --check",
        "cargo fmt --all",
        "cargo clippy --workspace --all-features --all-targets -- -D warnings",
        "cargo doc",
        "cargo build --all-features --all-targets",
        "cargo test",
        "cargo clean",
    )
    for cmd in cmds:
        _exec_cmd(cmd)
    logging.info("Executing fory rust tests succeeds")


def _run_js():
    logging.info("Executing fory javascript tests.")
    _cd_project_subdir("javascript")
    _exec_cmd("npm install")
    _exec_cmd("npm run test")
    logging.info("Executing fory javascript tests succeeds.")


def _install_cpp_deps():
    _exec_cmd(f"pip install pyarrow=={PYARROW_VERSION}")
    # Automatically install numpy
    _exec_cmd("pip install psutil")
    _install_bazel()


def _install_bazel():
    local_name = "bazel.exe" if _is_windows() else "bazel-installer.sh"
    bazel_download_url = _get_bazel_download_url()
    logging.info(bazel_download_url)
    ulib.urlretrieve(bazel_download_url, local_name)
    os.chmod(local_name, 0o777)

    if _is_windows():
        bazel_path = os.path.join(os.getcwd(), local_name)
        _exec_cmd(f'setx path "%PATH%;{bazel_path}"')
    else:
        if shutil.which("bazel"):
            os.remove(shutil.which("bazel"))
        _exec_cmd(f"./{local_name} --user")
        _update_shell_profile()
        os.remove(local_name)

    # bazel install status check
    _bazel("--version")

    # default is byte
    psutil = importlib.import_module("psutil")
    total_mem = psutil.virtual_memory().total
    limit_jobs = int(total_mem / 1024 / 1024 / 1024 / 3)
    with open(".bazelrc", "a") as file:
        file.write(f"\nbuild --jobs={limit_jobs}")


def _update_shell_profile():
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


def _parse_args():
    parser = argparse.ArgumentParser(
        formatter_class=argparse.ArgumentDefaultsHelpFormatter
    )
    parser.set_defaults(func=parser.print_help)
    subparsers = parser.add_subparsers()

    cpp_parser = subparsers.add_parser(
        "cpp",
        description="Run C++ CI",
        help="Run C++ CI",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    cpp_parser.set_defaults(func=_run_cpp)

    rust_parser = subparsers.add_parser(
        "rust",
        description="Run Rust CI",
        help="Run Rust CI",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    rust_parser.set_defaults(func=_run_rust)

    js_parser = subparsers.add_parser(
        "javascript",
        description="Run Javascript CI",
        help="Run Javascript CI",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    js_parser.set_defaults(func=_run_js)

    args = parser.parse_args()
    arg_dict = dict(vars(args))
    del arg_dict["func"]
    args.func(**arg_dict)


if __name__ == "__main__":
    _parse_args()
