# Minimal package config so ggml-vulkan's find_package(SPIRV-Headers CONFIG) uses the
# SPIRV-Headers git submodule instead of a system install.
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${ECO_SPIRV_HEADERS_INCLUDE}")
endif()
set(SPIRV-Headers_FOUND TRUE)
