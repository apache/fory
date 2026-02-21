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
import logging
import os
import shutil
import subprocess
import sys

from tasks import cpp, java, javascript, kotlin, rust, python, go, format
from tasks.common import is_windows

# Configure logging
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
)

# Migration Strategy:
# This script supports a gradual migration from the shell script (run_ci.sh) to Python.
# You can control which languages use the Python implementation by setting environment variables.
#
# To use the shell script for a specific language, set the corresponding environment variable to "0".
# To use the Python implementation, set it to "1" or leave it unset (default is Python).
#
# Example:
#   # Use shell script for Java, Python implementation for everything else
#   export USE_PYTHON_JAVA=0
#   python run_ci.py java --version 17
#
#   # Use shell script for multiple languages
#   export USE_PYTHON_JAVA=0 USE_PYTHON_CPP=0 USE_PYTHON_RUST=0
#   python run_ci.py cpp
#
# Available environment variables:
#   USE_PYTHON_CPP        - C++ implementation
#   USE_PYTHON_RUST       - Rust implementation
#   USE_PYTHON_JAVASCRIPT - JavaScript implementation
#   USE_PYTHON_JAVA       - Java implementation
#   USE_PYTHON_KOTLIN     - Kotlin implementation
#   USE_PYTHON_PYTHON     - Python implementation
#   USE_PYTHON_GO         - Go implementation
#   USE_PYTHON_FORMAT     - Format implementation
#
# By default, JavaScript, Rust, and C++ use the Python implementation,
# while Java, Kotlin, Python, Go, and Format use the shell script implementation.

# Environment variables to control which languages use the Python implementation
# Default to the status quo before migration:
# - JavaScript, Rust, and C++ use Python implementation
# - Java, Kotlin, Python, Go, and Format use shell script implementation
USE_PYTHON_CPP = os.environ.get("USE_PYTHON_CPP", "1") == "1"
USE_PYTHON_RUST = os.environ.get("USE_PYTHON_RUST", "1") == "1"
USE_PYTHON_JAVASCRIPT = os.environ.get("USE_PYTHON_JAVASCRIPT", "1") == "1"
USE_PYTHON_JAVA = os.environ.get("USE_PYTHON_JAVA", "0") == "1"
USE_PYTHON_KOTLIN = os.environ.get("USE_PYTHON_KOTLIN", "0") == "1"
USE_PYTHON_PYTHON = os.environ.get("USE_PYTHON_PYTHON", "0") == "1"
USE_PYTHON_GO = os.environ.get("USE_PYTHON_GO", "0") == "1"
USE_PYTHON_FORMAT = os.environ.get("USE_PYTHON_FORMAT", "0") == "1"


def run_shell_script(command, *args):
    """Run the shell script with the given command and arguments."""
    script_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "run_ci.sh")

    if is_windows():
        # On Windows, try to use bash if available
        bash_path = shutil.which("bash")
        if bash_path:
            cmd = [bash_path, script_path, command]
            cmd.extend(args)
            logging.info(f"Falling back to shell script with bash: {' '.join(cmd)}")
            sys.exit(subprocess.call(cmd))
        else:
            logging.error(
                "Bash is not available on this Windows system. Cannot run shell script."
            )
            logging.error(
                "Please install Git Bash, WSL, or Cygwin to run shell scripts on Windows."
            )
            logging.error(
                "Alternatively, set USE_PYTHON_JAVA=1 to use the Python implementation."
            )
            sys.exit(1)
    else:
        # On Unix-like systems, run the script directly
        cmd = [script_path, command]
        cmd.extend(args)
        logging.info(f"Falling back to shell script: {' '.join(cmd)}")
        sys.exit(subprocess.call(cmd))


def parse_args():
    """Parse command-line arguments and dispatch to the appropriate task module."""
    parser = argparse.ArgumentParser(
        description="Fory CI Runner",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.set_defaults(func=lambda: parser.print_help())
    subparsers = parser.add_subparsers(dest="command")

    # C++ subparser
    cpp_parser = subparsers.add_parser(
        "cpp",
        description="Run C++ CI",
        help="Run C++ CI",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    cpp_parser.add_argument(
        "--install-deps-only",
        action="store_true",
        help="Only install dependencies without running tests",
    )
    cpp_parser.add_argument(
        "--skip-doc-tests",
        action="store_true",
        help="Skip documentation example tests",
    )
    cpp_parser.add_argument(
        "--doc-tests-only",
        action="store_true",
        help="Only run documentation example tests",
    )
    cpp_parser.set_defaults(
        func=lambda install_deps_only, skip_doc_tests, doc_tests_only: cpp.run(
            install_deps_only, skip_doc_tests, doc_tests_only
        )
    )

    # Rust subparser
    rust_parser = subparsers.add_parser(
        "rust",
        description="Run Rust CI",
        help="Run Rust CI",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    rust_parser.set_defaults(func=rust.run)

    # JavaScript subparser
    js_parser = subparsers.add_parser(
        "javascript",
        description="Run JavaScript CI",
        help="Run JavaScript CI",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    js_parser.set_defaults(func=javascript.run)

    # Java subparser
    java_parser = subparsers.add_parser(
        "java",
        description="Run Java CI",
        help="Run Java CI",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    java_parser.add_argument(
        "--version",
        choices=[
            "8",
            "11",
            "17",
            "21",
            "25",
            "windows_java21",
            "integration_tests",
            "graalvm",
        ],
        default=None,
        help="Java version to use for testing",
    )
    java_parser.add_argument(
        "--release",
        action="store_true",
        help="Release to Maven Central",
    )
    java_parser.add_argument(
        "--install-jdks",
        action="store_true",
        help="Install JDKs",
    )
    java_parser.add_argument(
        "--install-fory",
        action="store_true",
        help="Install Fory",
    )
    java_parser.set_defaults(func=java.run)

    # Kotlin subparser
    kotlin_parser = subparsers.add_parser(
        "kotlin",
        description="Run Kotlin CI",
        help="Run Kotlin CI",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    kotlin_parser.set_defaults(func=kotlin.run)

    # Python subparser
    python_parser = subparsers.add_parser(
        "python",
        description="Run Python CI",
        help="Run Python CI",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    python_parser.set_defaults(func=python.run)

    # Go subparser
    go_parser = subparsers.add_parser(
        "go",
        description="Run Go CI",
        help="Run Go CI",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    go_parser.set_defaults(func=go.run)

    # Format subparser
    format_parser = subparsers.add_parser(
        "format",
        description="Run format checks",
        help="Run format checks",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    format_parser.set_defaults(func=format.run)

    args = parser.parse_args()

    # If no arguments were provided, print help and exit
    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit(1)

    # Extract arguments to pass to the function
    arg_dict = vars(args)
    func = arg_dict.pop("func")
    command = arg_dict.pop("command", None)

    # Call the appropriate function with the remaining arguments
    if command == "java":
        if USE_PYTHON_JAVA:
            func(**arg_dict)
        else:
            if not arg_dict.get("version"):
                func(**arg_dict)
                return
            # Map Python version argument to shell script command
            version = arg_dict.get("version", "17")
            release = arg_dict.get("release", False)

            if release:
                logging.info("Release mode requested - using Python implementation")
                func(**arg_dict)
            # For windows_java21 on Windows, use the Python implementation directly
            elif version == "windows_java21" and is_windows():
                logging.info(
                    "Using Python implementation for windows_java21 on Windows"
                )
                func(**arg_dict)
            elif version == "integration_tests":
                run_shell_script("integration_tests")
            elif version == "windows_java21":
                run_shell_script("windows_java21")
            elif version == "graalvm":
                run_shell_script("graalvm_test")
            else:
                run_shell_script(f"java{version}")
    elif command == "cpp":
        if USE_PYTHON_CPP:
            func(
                arg_dict.get("install_deps_only", False),
                arg_dict.get("skip_doc_tests", False),
                arg_dict.get("doc_tests_only", False),
            )
        else:
            if arg_dict.get("install_deps_only", False):
                run_shell_script("install_bazel")
            else:
                run_shell_script("cpp")
    elif command == "rust":
        if USE_PYTHON_RUST:
            func()
        else:
            run_shell_script("rust")
    elif command == "javascript":
        if USE_PYTHON_JAVASCRIPT:
            func()
        else:
            run_shell_script("javascript")
    elif command == "kotlin":
        if USE_PYTHON_KOTLIN:
            func()
        else:
            run_shell_script("kotlin")
    elif command == "python":
        if USE_PYTHON_PYTHON:
            func()
        else:
            run_shell_script("python")
    elif command == "go":
        if USE_PYTHON_GO:
            func()
        else:
            run_shell_script("go")
            pass
    elif command == "format":
        if USE_PYTHON_FORMAT:
            func()
        else:
            run_shell_script("format")
    else:
        func()


if __name__ == "__main__":
    parse_args()
