#!/usr/bin/env bash
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

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
csharp_dir="$repo_root/csharp"
version="$(
  sed -n 's:.*<Version>\(.*\)</Version>.*:\1:p' "$csharp_dir/Directory.Build.props" | head -n 1
)"
package_id="Apache.Fory"
package_name="$package_id.$version"
output_dir="$csharp_dir/artifacts/nuget"
package_source="${NUGET_SOURCE:-https://api.nuget.org/v3/index.json}"
symbol_source="${NUGET_SYMBOL_SOURCE:-$package_source}"

if [[ -z "$version" ]]; then
  echo "Could not resolve <Version> from $csharp_dir/Directory.Build.props." >&2
  exit 1
fi

if ! command -v dotnet >/dev/null 2>&1; then
  echo "dotnet SDK 8.0+ is required." >&2
  exit 1
fi

if [[ -z "${NUGET_API_KEY:-}" ]]; then
  echo "Set NUGET_API_KEY before publishing." >&2
  exit 1
fi

rm -rf "$output_dir"
mkdir -p "$output_dir"

echo "Restoring C# solution..."
dotnet restore "$csharp_dir/Fory.sln"

echo "Running C# tests..."
dotnet test "$csharp_dir/Fory.sln" -c Release --no-restore

echo "Packing $package_id $version..."
dotnet pack "$csharp_dir/src/Fory/Fory.csproj" \
  -c Release \
  --no-restore \
  -o "$output_dir" \
  -p:ContinuousIntegrationBuild=true

package_path="$output_dir/$package_name.nupkg"
symbols_path="$output_dir/$package_name.snupkg"

if [[ ! -f "$package_path" ]]; then
  echo "Expected package not found: $package_path" >&2
  exit 1
fi

if [[ ! -f "$symbols_path" ]]; then
  echo "Expected symbol package not found: $symbols_path" >&2
  exit 1
fi

echo "Publishing $package_name.nupkg..."
dotnet nuget push "$package_path" \
  --api-key "$NUGET_API_KEY" \
  --source "$package_source" \
  --skip-duplicate

echo "Publishing $package_name.snupkg..."
dotnet nuget push "$symbols_path" \
  --api-key "$NUGET_API_KEY" \
  --source "$symbol_source" \
  --skip-duplicate

echo "Published $package_name to NuGet."
