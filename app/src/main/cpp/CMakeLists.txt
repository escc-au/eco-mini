cmake_minimum_required(VERSION 3.22)
project(eco C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Limit parallel compile jobs: some ggml sources need several GB of RAM each.
set_property(GLOBAL PROPERTY JOB_POOLS compile=4)
set(CMAKE_JOB_POOL_COMPILE compile)

set(TP ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)

# ---------------------------------------------------------------- OpenCL
# Built from source only to have something to link against. The APK does not ship it:
# at runtime the dynamic linker resolves libOpenCL.so to the phone's vendor driver
# (declared with <uses-native-library> in the manifest).
set(OPENCL_ICD_LOADER_HEADERS_DIR ${TP}/OpenCL-Headers CACHE PATH "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENCL_LAYERINFO OFF CACHE BOOL "" FORCE)
add_subdirectory(${TP}/OpenCL-ICD-Loader opencl-icd-loader EXCLUDE_FROM_ALL)
# The loader passes its version script unquoted, which breaks on paths with spaces.
set_target_properties(OpenCL PROPERTIES LINK_FLAGS "")
target_link_options(OpenCL PRIVATE "LINKER:--version-script=${TP}/OpenCL-ICD-Loader/loader/linux/icd_exports.map")
set(OpenCL_INCLUDE_DIR ${TP}/OpenCL-Headers CACHE PATH "" FORCE)
set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)

# ---------------------------------------------------------------- Vulkan
set(Vulkan_INCLUDE_DIR ${TP}/Vulkan-Headers/include CACHE PATH "" FORCE)
set(Vulkan_GLSLC_EXECUTABLE ${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc CACHE FILEPATH "" FORCE)
set(SPIRV-Headers_DIR ${CMAKE_CURRENT_LIST_DIR}/cmake CACHE PATH "" FORCE)
set(ECO_SPIRV_HEADERS_INCLUDE ${TP}/SPIRV-Headers/include)
# The shader generator is a small tool built for the build machine. Give its sub-build the
# host compilers and the same ninja the NDK build uses (it is not on PATH).
find_program(ECO_HOST_CC NAMES cc gcc clang NO_CMAKE_FIND_ROOT_PATH REQUIRED)
find_program(ECO_HOST_CXX NAMES c++ g++ clang++ NO_CMAKE_FIND_ROOT_PATH REQUIRED)
set(ECO_HOST_TOOLCHAIN ${CMAKE_BINARY_DIR}/eco-host-toolchain.cmake)
file(WRITE ${ECO_HOST_TOOLCHAIN}
    "set(CMAKE_BUILD_TYPE Release)\n"
    "set(CMAKE_C_COMPILER \"${ECO_HOST_CC}\")\n"
    "set(CMAKE_CXX_COMPILER \"${ECO_HOST_CXX}\")\n"
    "set(CMAKE_MAKE_PROGRAM \"${CMAKE_MAKE_PROGRAM}\" CACHE FILEPATH \"\" FORCE)\n")
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN ${ECO_HOST_TOOLCHAIN} CACHE FILEPATH "" FORCE)

# ---------------------------------------------------------------- llama.cpp
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)        # backends are separate .so files picked at runtime
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)  # armv8.0 ... armv9.2 (dotprod, i8mm, SVE) variants
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)     # would download sources at build time
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_BIN_KERNELS OFF CACHE BOOL "" FORCE)  # prebuilt blobs: not allowed
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
add_subdirectory(${TP}/llama.cpp llama.cpp)
# ggml-vulkan finds SPIRV-Headers but does not add its include path itself.
target_include_directories(ggml-vulkan PRIVATE ${TP}/SPIRV-Headers/include)

# ---------------------------------------------------------------- our libraries
add_library(ecosearch SHARED vecsearch.cpp)
target_compile_options(ecosearch PRIVATE -O3)

add_library(eco SHARED eco_jni.cpp)
target_link_libraries(eco PRIVATE llama ggml log)
