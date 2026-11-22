# CMake operating system bootstrap module

include_guard(GLOBAL)

# OBS Director's plugin is built for Windows only.
if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
  set(CMAKE_C_EXTENSIONS FALSE)
  set(CMAKE_CXX_EXTENSIONS FALSE)
  list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake/windows")
  set(OS_WINDOWS TRUE)
else()
  message(FATAL_ERROR "The OBS Director plugin builds on Windows only.")
endif()
