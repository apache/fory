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
from . import common

def run():
    """Run Rust CI tasks."""
    logging.info("Executing fory rust tests")
    common.cd_project_subdir("rust")

    # From run_ci.sh, we should also add rustup components
    try:
        common.exec_cmd("rustup component add clippy-preview")
        common.exec_cmd("rustup component add rustfmt")
    except Exception as e:
        logging.warning(f"Failed to add rustup components: {e}")
        logging.warning("Continuing with existing components")

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
        common.exec_cmd(cmd)
    logging.info("Executing fory rust tests succeeds")
