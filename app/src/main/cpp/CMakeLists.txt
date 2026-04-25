cmake_minimum_required(VERSION 3.18.1)
project(ml_scanner)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fexceptions -frtti")

# ============================================================
# OpenCV Integration (Local Copy)
# ============================================================

set(OPENCV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencv)
message(STATUS "Looking for OpenCV at: ${OPENCV_DIR}")

if(EXISTS ${OPENCV_DIR}/include/opencv2)
    message(STATUS "✅ OpenCV found (local copy)")
    
    # Include OpenCV headers
    include_directories(${OPENCV_DIR}/include)
    
    # Import OpenCV shared library
    add_library(opencv_java4 SHARED IMPORTED)
    set_target_properties(opencv_java4 PROPERTIES
        IMPORTED_LOCATION ${OPENCV_DIR}/libs/${ANDROID_ABI}/libopencv_java4.so
        IMPORTED_NO_SONAME TRUE
    )
    
    set(OPENCV_AVAILABLE TRUE)
    set(OpenCV_LIBS opencv_java4)
else()
    message(WARNING "⚠️ OpenCV not found at ${OPENCV_DIR}")
    message(WARNING "   Run: cp -r OpenCV-android-sdk/sdk/native/jni/include/opencv2 app/src/main/cpp/third_party/opencv/include/")
    message(WARNING "   Run: cp OpenCV-android-sdk/sdk/native/libs/*/libopencv_java4.so app/src/main/cpp/third_party/opencv/libs/")
    set(OPENCV_AVAILABLE FALSE)
endif()

# ============================================================
# Source Files — Pipeline (for future extensibility)
# ============================================================

set(PIPELINE_SOURCES
    pipeline/pipeline.cc
    pipeline/det_process.cc
    pipeline/rec_process.cc
    pipeline/cls_process.cc
    pipeline/db_post_process.cc
    pipeline/clipper.cpp
    pipeline/utils.cc
)

# Check if pipeline sources exist
set(PIPELINE_EXISTS FALSE)
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/pipeline/pipeline.cc")
    set(PIPELINE_EXISTS TRUE)
    message(STATUS "✅ Pipeline sources found")
endif()

add_library(
    ml_scanner_jni
    SHARED
    ${PIPELINE_SOURCES}
)

# ============================================================
# Include Directories
# ============================================================

target_include_directories(ml_scanner_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/pipeline
)

# ============================================================
# Android System Libraries
# ============================================================

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(glesv2-lib GLESv2)
find_library(egl-lib EGL)

# ============================================================
# Linking
# ============================================================

target_link_libraries(
    ml_scanner_jni
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    ${glesv2-lib}
    ${egl-lib}
)

# Link OpenCV if available
if(OPENCV_AVAILABLE)
    target_link_libraries(ml_scanner_jni ${OpenCV_LIBS})
    target_compile_definitions(ml_scanner_jni PRIVATE HAVE_OPENCV=1)
endif()

# ============================================================
# Compiler Flags
# ============================================================

target_compile_options(ml_scanner_jni PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-deprecated-declarations
    -O3
    -fvisibility=hidden
    -ffast-math
    -fno-strict-aliasing
)

# NDK 25 specific flags
if(ANDROID_NDK_MAJOR AND ANDROID_NDK_MAJOR GREATER_EQUAL 25)
    target_compile_options(ml_scanner_jni PRIVATE
        -Wno-unused-command-line-argument
    )
endif()

# ============================================================
# Build Information
# ============================================================

message(STATUS "============================================================")
message(STATUS "ML Scanner Native Build Configuration")
message(STATUS "============================================================")
message(STATUS "CMake Version: ${CMAKE_VERSION}")
message(STATUS "Android ABI: ${ANDROID_ABI}")
message(STATUS "Android Platform: ${ANDROID_PLATFORM}")
message(STATUS "C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "OpenCV Available: ${OPENCV_AVAILABLE}")
message(STATUS "============================================================")