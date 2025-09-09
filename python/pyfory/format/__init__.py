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

import os
import sys
import warnings

try:
    import pyarrow as pa

    # Ensure fory can link to arrow shared library
    sys.path.extend(pa.get_library_dirs() + [os.path.dirname(__file__)])

    from pyfory.format._format import (  # noqa: F401 # pylint: disable=unused-import
        create_row_encoder,
        RowData,
        ArrowWriter,
    )  # noqa: E402
    from pyfory.format.infer import (  # noqa: F401 # pylint: disable=unused-import
        get_cls_by_schema,
        remove_schema,
        reset,
    )
    from pyfory.format.encoder import (  # noqa: F401 # pylint: disable=unused-import
        encoder,
        Encoder,
    )
except (ImportError, AttributeError) as e:
    warnings.warn(
        f"Fory format initialization failed, please ensure pyarrow is installed with version which fory is compiled with: {e}",
        RuntimeWarning,
        stacklevel=2,
    )
