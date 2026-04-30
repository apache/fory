/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#include "fory/util/logging.h"
#include "fory/util/time_util.h"

#if !defined(_WIN32)
#include <execinfo.h>
#endif

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdlib>
#include <sstream>
#include <unordered_map>

namespace std {
template <> struct hash<fory::ForyLogLevel> {
  size_t operator()(fory::ForyLogLevel t) const { return size_t(t); }
};
} // namespace std

namespace fory {

const ForyLogLevel fory_severity_threshold = ForyLog::get_log_level();

std::string get_call_trace() {
#if !defined(_WIN32)
  std::array<void *, 100> local_stack{};
  int frames =
      backtrace(local_stack.data(), static_cast<int>(local_stack.size()));
  std::string output;
  char **symbols = backtrace_symbols(local_stack.data(), frames);
  if (symbols != nullptr) {
    for (int i = 0; i < frames; ++i) {
      output.append("    ").append(symbols[i]).append("\n");
    }
    std::free(symbols);
    return output;
  }
  for (int i = 0; i < frames; ++i) {
    std::ostringstream stream;
    stream << "    frame[" << i << "] " << local_stack[static_cast<size_t>(i)]
           << "\n";
    output.append(stream.str());
  }
  return output;
#else
  std::string output = "    Stack trace is not supported on this platform.\n";
  return output;
#endif
}

std::unordered_map<ForyLogLevel, std::string> log_level_to_str = {
    {ForyLogLevel::FORY_DEBUG, "DEBUG"},
    {ForyLogLevel::FORY_INFO, "INFO"},
    {ForyLogLevel::FORY_WARNING, "WARNING"},
    {ForyLogLevel::FORY_ERROR, "ERROR"},
    {ForyLogLevel::FORY_FATAL, "FATAL"},
};

std::string log_level_as_string(ForyLogLevel level) {
  auto it = log_level_to_str.find(level);
  if (it == log_level_to_str.end()) {
    return "UNKNOWN";
  }
  return it->second;
}

ForyLogLevel ForyLog::get_log_level() {
  ForyLogLevel severity_threshold = ForyLogLevel::FORY_INFO;
  const char *var_value = std::getenv("FORY_LOG_LEVEL");
  if (var_value != nullptr) {
    std::string data = var_value;
    std::transform(data.begin(), data.end(), data.begin(), ::tolower);
    if (data == "debug") {
      severity_threshold = ForyLogLevel::FORY_DEBUG;
    } else if (data == "info") {
      severity_threshold = ForyLogLevel::FORY_INFO;
    } else if (data == "warning") {
      severity_threshold = ForyLogLevel::FORY_WARNING;
    } else if (data == "error") {
      severity_threshold = ForyLogLevel::FORY_ERROR;
    } else if (data == "fatal") {
      severity_threshold = ForyLogLevel::FORY_FATAL;
    } else {
      FORY_LOG_INTERNAL(FORY_WARNING)
          << "Unrecognized setting of ForyLogLevel=" << var_value;
    }
    FORY_LOG_INTERNAL(FORY_INFO)
        << "Set ray log level from environment variable RAY_BACKEND_LOG_LEVEL"
        << " to " << static_cast<int>(severity_threshold);
  }
  return severity_threshold;
}

ForyLog::ForyLog(const char *file_name, int line_number, ForyLogLevel severity)
    : severity_(severity) {
  stream() << "[" << format_time_point(std::chrono::system_clock::now()) << "] "
           << log_level_as_string(severity) << " " << file_name << ":"
           << line_number << ": ";
}

ForyLog::~ForyLog() {
  if (severity_ == ForyLogLevel::FORY_FATAL) {
    stream() << "\n*** StackTrace Information ***\n"
             << ::fory::get_call_trace();
    stream() << std::endl;
    stream().flush();
    std::cerr.flush();
    std::cout.flush();
    std::_Exit(EXIT_FAILURE);
  }
  stream() << "\n" << std::endl;
}

bool ForyLog::is_level_enabled(ForyLogLevel log_level) {
  return log_level >= fory_severity_threshold;
}

} // namespace fory
