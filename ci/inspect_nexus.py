# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

import base64
import http.server
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import urllib.error
import urllib.request


credentials = f"{os.environ['NEXUS_USERNAME']}:{os.environ['NEXUS_PASSWORD']}"
authorization = "Basic " + base64.b64encode(credentials.encode()).decode()
for path, method in (
    ("/service/local/status", "GET"),
    (
        "/service/local/authentication/login?perms=nexus:stagingprofiles,nexus:stagingrepos",
        "GET",
    ),
    ("/service/local/staging/profiles", "GET"),
    ("/service/local/staging/profile_repositories", "GET"),
    (
        "/service/local/staging/deploy/maven2/org/apache/fory/fory-parent/1.7.8/fory-parent-1.7.8.pom",
        "HEAD",
    ),
):
    request = urllib.request.Request(
        "https://repository.apache.org" + path,
        headers={"Authorization": authorization, "Accept": "application/json"},
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            print(method, path, response.status, flush=True)
            if method == "HEAD":
                continue
            data = json.load(response).get("data")
            if isinstance(data, list):
                print("Number of entries:", len(data), flush=True)
                if path == "/service/local/staging/profiles":
                    print(
                        "Visible profile names:",
                        [item.get("name") for item in data],
                        flush=True,
                    )
                for item in data:
                    if any(
                        name in json.dumps(item).lower() for name in ("fory", "fury")
                    ):
                        print(json.dumps(item, sort_keys=True), flush=True)
            else:
                print(
                    json.dumps(
                        {
                            key: data.get(key)
                            for key in (
                                "state",
                                "version",
                                "edition",
                                "loggedIn",
                                "permissions",
                                "clientPermissions",
                            )
                        }
                    ),
                    flush=True,
                )
    except urllib.error.HTTPError as error:
        print(method, path, error.code, flush=True)
        detail = error.read(2048).decode(errors="replace")
        for secret in (
            os.environ["NEXUS_USERNAME"],
            os.environ["NEXUS_PASSWORD"],
            authorization,
        ):
            detail = detail.replace(secret, "[REDACTED]")
        print(detail, flush=True)

for accept in ("application/problem+json,*/*", "*/*"):
    path = "/service/local/staging/deploy/maven2/org/apache/fory/fory-parent/1.7.8/fory-parent-1.7.8.pom"
    request = urllib.request.Request(
        "https://repository.apache.org" + path,
        headers={"Authorization": authorization, "Accept": accept},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            print("GET deployment endpoint", accept, response.status, flush=True)
    except urllib.error.HTTPError as error:
        print("GET deployment endpoint", accept, error.code, flush=True)
        detail = error.read(2048).decode(errors="replace")
        for secret in (
            os.environ["NEXUS_USERNAME"],
            os.environ["NEXUS_PASSWORD"],
            authorization,
        ):
            detail = detail.replace(secret, "[REDACTED]")
        print(detail, flush=True)


class Capture(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_PUT(self):
        headers = {
            name: self.headers.get(name)
            for name in (
                "User-Agent",
                "Content-Length",
                "Transfer-Encoding",
                "Expect",
                "Content-Type",
                "Accept",
            )
        }
        print("LOCAL PUT", self.path, json.dumps(headers), flush=True)
        if self.headers.get("Transfer-Encoding") == "chunked":
            while True:
                size = int(self.rfile.readline().split(b";")[0], 16)
                self.rfile.read(size + 2)
                if size == 0:
                    break
        else:
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.send_response(201)
        self.send_header("Content-Length", "0")
        self.end_headers()


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Capture)
threading.Thread(target=server.serve_forever, daemon=True).start()
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    (root / "probe.pom").write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>org.apache.fory</groupId><artifactId>transport-probe</artifactId><version>1.7.8</version><packaging>pom</packaging></project>"
    )
    (root / "settings.xml").write_text("<settings/>")
    subprocess.run(
        [
            "mvn",
            "-B",
            "-N",
            "-s",
            str(root / "settings.xml"),
            "org.apache.maven.plugins:maven-deploy-plugin:2.7:deploy-file",
            "-Dfile=" + str(root / "probe.pom"),
            "-DpomFile=" + str(root / "probe.pom"),
            "-Durl=http://127.0.0.1:" + str(server.server_port) + "/maven2",
            "-DrepositoryId=localhost-diagnostic",
        ],
        cwd=directory,
        check=True,
        timeout=120,
    )
server.shutdown()
