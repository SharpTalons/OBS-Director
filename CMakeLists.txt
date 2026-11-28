cmake_minimum_required(VERSION 3.28...3.30)

include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/common/bootstrap.cmake" NO_POLICY_SCOPE)

project(${_name} VERSION ${_version})

# OBS Director needs the frontend API (record control, docks, chapters) and Qt (dock, network).
option(ENABLE_FRONTEND_API "Use obs-frontend-api for UI functionality" ON)
option(ENABLE_QT "Use Qt functionality" ON)

include(compilerconfig)
include(defaults)
include(helpers)

add_library(${CMAKE_PROJECT_NAME} MODULE)

find_package(libobs REQUIRED)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE OBS::libobs)

find_package(obs-frontend-api REQUIRED)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE OBS::obs-frontend-api)

find_package(Qt6 REQUIRED COMPONENTS Core Widgets Network)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE Qt6::Core Qt6::Widgets Qt6::Network)
set_target_properties(
  ${CMAKE_PROJECT_NAME}
  PROPERTIES AUTOMOC ON AUTOUIC ON AUTORCC ON
)

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE ws2_32)

# Save as AAC uses the FFmpeg that OBS ships (from obs-deps). It is loaded on first use
# (delay-loaded), so OBS still loads the plugin if its FFmpeg is a different version.
find_path(FFMPEG_INCLUDE_DIR NAMES libavcodec/avcodec.h REQUIRED)
set(_ffmpeg_dlls)
foreach(_lib IN ITEMS avutil avcodec avformat swresample)
  find_library(FFMPEG_${_lib}_LIBRARY NAMES ${_lib} REQUIRED)
  target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE "${FFMPEG_${_lib}_LIBRARY}")
  string(TOUPPER "${_lib}" _upper)
  set(_header "${FFMPEG_INCLUDE_DIR}/lib${_lib}/version_major.h")
  if(NOT EXISTS "${_header}")
    set(_header "${FFMPEG_INCLUDE_DIR}/lib${_lib}/version.h")
  endif()
  file(STRINGS "${_header}" _major REGEX "#define LIB${_upper}_VERSION_MAJOR[ \t]+[0-9]+")
  string(REGEX REPLACE ".*VERSION_MAJOR[ \t]+([0-9]+).*" "\\1" _major "${_major}")
  message(STATUS "OBS Director: FFmpeg ${_lib}-${_major}")
  target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "/DELAYLOAD:${_lib}-${_major}.dll")
endforeach()
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE "${FFMPEG_INCLUDE_DIR}")
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE delayimp)

target_sources(
  ${CMAKE_PROJECT_NAME}
  PRIVATE
    src/plugin-main.cpp
    src/director-server.cpp
    src/director-server.hpp
    src/udp-audio.cpp
    src/udp-audio.hpp
    src/video-preview.cpp
    src/video-preview.hpp
    src/desk-dock.cpp
    src/desk-dock.hpp
    src/web-server.cpp
    src/update-check.cpp
    src/update-check.hpp
    src/aac-export.cpp
    src/aac-export.hpp
    src/web/web.qrc
)

set_target_properties_plugin(${CMAKE_PROJECT_NAME} PROPERTIES OUTPUT_NAME ${_name})
