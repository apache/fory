<!--
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Security Policy

## Reporting a Vulnerability

`apache/fory` follows the [Apache Software Foundation security process](https://www.apache.org/security/). Please report suspected
vulnerabilities privately to `security@apache.org`; do not open public
GitHub issues or pull requests for security reports.

## Security Models

User-facing guidance is capability- and runtime-specific:

- [Object Serialization runtime guides](docs/object-serialization/index.md) (each runtime section
  ends with its own Security page)
- [Fory JSON Security](docs/json/security.md)

For detailed implementation classification rules for untrusted deserialization, see the
[Deserialization Security Model](docs/security/deserialization.md).
