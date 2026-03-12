// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

module github.com/apache/fory/benchmarks/go

go 1.24.0

require (
	github.com/apache/fory/go/fory v0.16.0-alpha.0
	github.com/vmihailenco/msgpack/v5 v5.4.1
	google.golang.org/protobuf v1.36.0
)

require github.com/vmihailenco/tagparser/v2 v2.0.0 // indirect

replace github.com/apache/fory/go/fory => ../../go/fory
