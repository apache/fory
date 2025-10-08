#!/bin/bash
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
#
# Script to remove commented lines (# or HTML comments) from commit messages.

echo "Running remove-commented-commit.sh"
# remove-commented-commit.sh
# Remove commented lines (# or <!-- -->) from Git commit messages
# Usage: ./remove-commented-commit.sh COMMIT_MSG_FILE

COMMIT_MSG_FILE="$1"

if [ ! -f "$COMMIT_MSG_FILE" ]; then
    echo "Commit message file not found!"
    exit 1
fi

# Remove lines starting with '#' (Git comments)
sed -i '/^#/d' "$COMMIT_MSG_FILE"

# Remove HTML-style comment blocks (<!-- -->)
sed -i '/^<!--/,/-->$/d' "$COMMIT_MSG_FILE"
