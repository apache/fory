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

import logging
import os
import subprocess
from . import common


def run():
    """Run Rust CI tasks."""
    logging.info("Executing fory rust tests")
    common.cd_project_subdir("rust")

    # Install protoc for protobuf compilation
    try:
        if common.is_windows():
            raise Exception("Not supported on Windows")
        else:
            # On Linux/macOS, install via package manager
            logging.info("Installing protoc")
            import shutil

            if shutil.which("apt-get"):
                # Ubuntu/Debian
                common.exec_cmd("sudo apt-get update")
                common.exec_cmd("sudo apt-get install -y protobuf-compiler")
            elif shutil.which("brew"):
                # macOS
                common.exec_cmd("brew install protobuf")
            elif shutil.which("yum"):
                # CentOS/RHEL
                common.exec_cmd("sudo yum install -y protobuf-compiler")
            else:
                # Fallback: download binary
                logging.info("Package manager not found, downloading protoc binary")
                common.exec_cmd(
                    "curl -LO https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protoc-21.12-linux-x86_64.zip"
                )
                common.exec_cmd("unzip protoc-21.12-linux-x86_64.zip -d protoc")
                common.exec_cmd("sudo mv protoc/bin/* /usr/local/bin/")
                common.exec_cmd("sudo mv protoc/include/* /usr/local/include/")
    except Exception as e:
        logging.warning(f"Failed to install protoc: {e}")
        logging.warning("Continuing without protoc - benchmarks may fail")

    # From run_ci.sh, we should also add rustup components
    try:
        common.exec_cmd("rustup component add clippy-preview")
        common.exec_cmd("rustup component add rustfmt")
    except Exception as e:
        logging.warning(f"Failed to add rustup components: {e}")
        logging.warning("Continuing with existing components")

    cmds = (
        "cargo doc --no-deps --document-private-items --all-features",
        "cargo fmt --all -- --check",
        "cargo fmt --all",
        "cargo clippy --workspace --all-features --all-targets -- -D warnings",
        "cargo doc",
        "cargo build --all-features --all-targets",
        "cargo test",
        "cargo clean",
    )
    for cmd in cmds:
        common.exec_cmd(cmd)

    logging.info("Executing Rust <-> Java11 cross-language tests")
    os.environ["FORY_RUST_JAVA_CI"] = "1"
    java_dir = os.path.join("..", "java")
    subprocess.check_call(["mvn", "clean", "install", "-DskipTests"], cwd=java_dir)
    subprocess.check_call(
        ["mvn", "test", "-Dtest=org.apache.fory.RustXlangTest"],
        cwd=os.path.join(java_dir, "fory-core"),
    )
