# Superbuild for UltraSlim native dependencies:
#   libjpeg-turbo (JPEG), x265 (HEVC enc), aom (AV1 enc/dec),
#   libde265 (HEVC dec), libheif (HEIF/AVIF container, ISO 21496-1 gain map patch)
cmake_minimum_required(VERSION 3.22)
project(ultraslim_deps C CXX)

include(ExternalProject)

set(DEPS_INNER_JOBS "2" CACHE STRING "Maximum direct build-tool jobs per dependency project")
if(NOT DEPS_INNER_JOBS MATCHES "^[1-9][0-9]*$")
  message(FATAL_ERROR "DEPS_INNER_JOBS must be a positive integer (got: ${DEPS_INNER_JOBS})")
endif()

if(NOT DEFINED DEPS_PREFIX)
  message(FATAL_ERROR "Pass -DDEPS_PREFIX=<install prefix>")
endif()
file(MAKE_DIRECTORY ${DEPS_PREFIX})

set(COMMON_CMAKE_ARGS
    -DCMAKE_INSTALL_PREFIX=${DEPS_PREFIX}
    -DCMAKE_BUILD_TYPE=Release
    -DBUILD_SHARED_LIBS=OFF
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON)

# 依赖源码里的 __FILE__ / 断言串会把构建机绝对路径写进产物（含用户名与中文目录名），
# 发布出去即泄露本机目录结构。映射从 superbuild 的 build 目录开始，覆盖所有依赖源码树。
#
# 不能写成 -DCMAKE_C_FLAGS=...：本仓库路径含空格，CMake 传参时会被拆成两段
# （clang: error: no such file or directory），必须借 add_compile_options 让 CMake 加引号。
# 注入文件在各依赖自己的 project() 之后执行；REV 让该文件的改动也能使已有 superbuild 失效。
file(SHA256 ${CMAKE_CURRENT_SOURCE_DIR}/patches/file_prefix_map.cmake ULTRASLIM_PATH_MAP_REV)
list(APPEND COMMON_CMAKE_ARGS
    -DULTRASLIM_MAP_ROOT=${CMAKE_CURRENT_BINARY_DIR}
    -DULTRASLIM_PATH_MAP_REV=${ULTRASLIM_PATH_MAP_REV}
    -DCMAKE_PROJECT_INCLUDE=${CMAKE_CURRENT_SOURCE_DIR}/patches/file_prefix_map.cmake)
if(DEFINED CMAKE_TOOLCHAIN_FILE AND NOT "${CMAKE_TOOLCHAIN_FILE}" STREQUAL "")
  list(APPEND COMMON_CMAKE_ARGS -DCMAKE_TOOLCHAIN_FILE:FILEPATH=${CMAKE_TOOLCHAIN_FILE})
endif()
if(DEFINED ANDROID_ABI AND NOT "${ANDROID_ABI}" STREQUAL "")
  list(APPEND COMMON_CMAKE_ARGS -DANDROID_ABI=${ANDROID_ABI})
endif()
if(DEFINED ANDROID_PLATFORM AND NOT "${ANDROID_PLATFORM}" STREQUAL "")
  list(APPEND COMMON_CMAKE_ARGS -DANDROID_PLATFORM=${ANDROID_PLATFORM})
endif()
if(APPLE)
  if(DEFINED CMAKE_OSX_ARCHITECTURES AND NOT "${CMAKE_OSX_ARCHITECTURES}" STREQUAL "")
    list(APPEND COMMON_CMAKE_ARGS -DCMAKE_OSX_ARCHITECTURES=${CMAKE_OSX_ARCHITECTURES})
  endif()
  list(APPEND COMMON_CMAKE_ARGS -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0)
endif()

# ---------------------------------------------------------------- libjpeg-turbo
ExternalProject_Add(libjpeg-turbo
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.1.1/libjpeg-turbo-3.1.1.tar.gz
    URL_HASH SHA256=aadc97ea91f6ef078b0ae3a62bba69e008d9a7db19b34e4ac973b19b71b4217c
    CMAKE_ARGS ${COMMON_CMAKE_ARGS}
               -DENABLE_SHARED=OFF
               -DENABLE_STATIC=ON
               -DWITH_TURBOJPEG=OFF
               -DWITH_JAVA=OFF
               -DWITH_12BIT=OFF
               -DWITH_ARITH_ENC=ON
               -DWITH_ARITH_DEC=ON
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --parallel ${DEPS_INNER_JOBS}
    INSTALL_COMMAND ${CMAKE_COMMAND} --install <BINARY_DIR> --config Release)

# ---------------------------------------------------------------- x265 (HEVC encoder, 8-bit)
# x265 4.1's CMake gates the ARM64 feature detection behind if(GCC), which Clang
# never enters, so HAVE_NEON / X265_ARCH_ARM64 never get defined. Define them
# ourselves when targeting arm64.
set(X265_ARM_FLAGS "")
if((DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a") OR
   (APPLE AND CMAKE_OSX_ARCHITECTURES MATCHES "arm64") OR
   (CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64)$"))
  set(X265_ARM_FLAGS "-DHAVE_NEON=1 -DX265_ARCH_ARM64=1 -flax-vector-conversions=none")
endif()
# x265's handwritten aarch64 .S uses absolute relocations, which Android's
# linker forbids in shared libraries (and bionic rejects textrel). The NEON
# intrinsics are PIC-safe and provide most of the speedup, so assembly stays off.
set(X265_ASM_INJECT "${CMAKE_ASM_FLAGS} ${X265_ARM_FLAGS}")
set(X265_ENABLE_ASSEMBLY OFF)
ExternalProject_Add(x265
    URL https://bitbucket.org/multicoreware/x265_git/downloads/x265_4.1.tar.gz
    URL_HASH SHA256=a31699c6a89806b74b0151e5e6a7df65de4b49050482fe5ebf8a4379d7af8f29
    SOURCE_SUBDIR source
    PATCH_COMMAND bash ${CMAKE_CURRENT_SOURCE_DIR}/patches/patch_x265.sh <SOURCE_DIR> "${X265_ASM_INJECT}"
    CMAKE_ARGS ${COMMON_CMAKE_ARGS}
               -DCMAKE_C_FLAGS=${X265_ARM_FLAGS}
               -DCMAKE_CXX_FLAGS=${X265_ARM_FLAGS}
               -DENABLE_ASSEMBLY=${X265_ENABLE_ASSEMBLY}
               -DULTRASLIM_ARM_INTRINSICS=ON
               -DENABLE_SHARED=OFF
               -DENABLE_CLI=OFF
               -DHIGH_BIT_DEPTH=OFF
               -DENABLE_HDR10_PLUS=OFF
               -DENABLE_LIBVMAF=OFF
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --parallel ${DEPS_INNER_JOBS}
    INSTALL_COMMAND ${CMAKE_COMMAND} --install <BINARY_DIR> --config Release)

# ---------------------------------------------------------------- aom (AV1 decoder + lossless 4:4:4 gain map encoder)
ExternalProject_Add(aom
    URL https://storage.googleapis.com/aom-releases/libaom-3.12.1.tar.gz
    URL_HASH SHA256=9e9775180dec7dfd61a79e00bda3809d43891aee6b2e331ff7f26986207ea22e
    CMAKE_ARGS ${COMMON_CMAKE_ARGS}
               -DENABLE_TESTS=0
               -DENABLE_DOCS=0
               -DENABLE_EXAMPLES=0
               -DENABLE_TOOLS=0
               -DENABLE_NASM=0
               -DCONFIG_AV1_ENCODER=1
               -DCONFIG_AV1_DECODER=1
               -DCONFIG_MULTITHREAD=1
               -DCONFIG_WEBM_IO=0
               -DCONFIG_LIBYUV=0
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --parallel ${DEPS_INNER_JOBS}
    INSTALL_COMMAND ${CMAKE_COMMAND} --install <BINARY_DIR> --config Release)

# ---------------------------------------------------------------- svt-av1 (fast AV1 encoder)
ExternalProject_Add(svt-av1
    URL https://gitlab.com/AOMediaCodec/SVT-AV1/-/archive/v3.0.0/SVT-AV1-v3.0.0.tar.gz
    URL_HASH SHA256=7c5c357388859d15ba2b9b139c24e17ad6b84b94ca9b281921a6f0b839e78ebc
    PATCH_COMMAND bash ${CMAKE_CURRENT_SOURCE_DIR}/patches/patch_svt.sh <SOURCE_DIR>
    CMAKE_ARGS ${COMMON_CMAKE_ARGS}
               -DBUILD_APPS=OFF
               -DBUILD_TESTING=OFF
               -DSVT_AV1_LTO=ON
               -DCOMPILE_C_ONLY=OFF
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --parallel ${DEPS_INNER_JOBS}
    INSTALL_COMMAND ${CMAKE_COMMAND} --install <BINARY_DIR> --config Release)

# ---------------------------------------------------------------- dav1d (AV1 decoder)
set(DAV1D_MESON_CROSS_ARGS "")
if(DEFINED ANDROID_ABI)
  set(DAV1D_MESON_CROSS_ARGS --cross-file ${CMAKE_CURRENT_BINARY_DIR}/android-meson-cross.ini)
endif()
ExternalProject_Add(dav1d
    URL https://code.videolan.org/videolan/dav1d/-/archive/1.5.1/dav1d-1.5.1.tar.gz
    URL_HASH SHA256=fa635e2bdb25147b1384007c83e15de44c589582bb3b9a53fc1579cb9d74b695
    CONFIGURE_COMMAND meson setup <BINARY_DIR> <SOURCE_DIR>
        --buildtype release --default-library static --prefix=${DEPS_PREFIX}
        -Denable_tests=false -Denable_tools=false -Dbitdepths=8
        ${DAV1D_MESON_CROSS_ARGS}
    BUILD_COMMAND ninja -C <BINARY_DIR> -j ${DEPS_INNER_JOBS}
    INSTALL_COMMAND meson install -C <BINARY_DIR>)

# ---------------------------------------------------------------- libde265 (HEVC decoder)
ExternalProject_Add(libde265
    URL https://github.com/strukturag/libde265/releases/download/v1.0.16/libde265-1.0.16.tar.gz
    URL_HASH SHA256=b92beb6b53c346db9a8fae968d686ab706240099cdd5aff87777362d668b0de7
    CMAKE_ARGS ${COMMON_CMAKE_ARGS}
               -DENABLE_SDL=OFF
               -DENABLE_DECODER=OFF
               -DENABLE_ENCODER=OFF
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --parallel ${DEPS_INNER_JOBS}
    INSTALL_COMMAND ${CMAKE_COMMAND} --install <BINARY_DIR> --config Release)

# ---------------------------------------------------------------- libheif (container)
ExternalProject_Add(libheif
    DEPENDS x265 aom libde265 dav1d svt-av1
    URL https://github.com/strukturag/libheif/archive/4a3f74bc593ebfc29becc1ed5dd0a61cc66d40e1.tar.gz
    URL_HASH SHA256=b497316d557b8a5683066b446dc0df500a6d19da36ecf03753f40f22ac4c05d3
    PATCH_COMMAND bash ${CMAKE_CURRENT_SOURCE_DIR}/patches/patch_libheif.sh <SOURCE_DIR> ${CMAKE_CURRENT_SOURCE_DIR}/patches/libheif_pr1503.patch
    CMAKE_ARGS ${COMMON_CMAKE_ARGS}
               -DCMAKE_PREFIX_PATH=${DEPS_PREFIX}
               -DCMAKE_DISABLE_FIND_PACKAGE_Doxygen=ON
               -DWITH_DOCS=OFF
               -DENABLE_PLUGIN_LOADING=OFF
               -DWITH_EXAMPLES=OFF
               -DWITH_GDK_PIXBUF=OFF
               -DWITH_LIBSHARPYUV=OFF
               -DBUILD_TESTING=OFF
               -DWITH_EXPERIMENTAL_GAIN_MAP=ON
               -DWITH_HEIFIO=OFF
               -DWITH_JPEG_DECODER=OFF
               -DWITH_JPEG_ENCODER=OFF
               -DWITH_OpenJPEG=OFF
               -DWITH_OpenJPH=OFF
               -DWITH_LIBPNG=OFF
               -DWITH_LIBTIFF=OFF
               -DWITH_FFMPEG_DECODER=OFF
               -DWITH_X265=ON
               -DWITH_AOM_DECODER=OFF
               -DWITH_AOM_ENCODER=ON
               -DWITH_SvtEnc=ON
               -DWITH_LIBDE265=ON
               -DWITH_DAV1D=ON
               -DWITH_RAV1E=OFF
               -DWITH_OpenH264=OFF
               -DWITH_VVENC=OFF
               -DWITH_UVG266=OFF
               -DWITH_KVAZAAR=OFF
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --parallel ${DEPS_INNER_JOBS} --target heif
    INSTALL_COMMAND ${CMAKE_COMMAND} --install <BINARY_DIR> --config Release)

# Changes to local patches must invalidate an existing superbuild, not only a fresh clone.
ExternalProject_Add_Step(x265 patch_inputs
    INDEPENDENT TRUE
    DEPENDEES update DEPENDERS patch
    COMMAND ${CMAKE_COMMAND} -E true
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/patches/patch_x265.sh)
ExternalProject_Add_Step(svt-av1 patch_inputs
    INDEPENDENT TRUE
    DEPENDEES update DEPENDERS patch
    COMMAND ${CMAKE_COMMAND} -E true
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/patches/patch_svt.sh)
ExternalProject_Add_Step(libheif patch_inputs
    INDEPENDENT TRUE
    DEPENDEES update DEPENDERS patch
    COMMAND ${CMAKE_COMMAND} -E true
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/patches/patch_libheif.sh
            ${CMAKE_CURRENT_SOURCE_DIR}/patches/patch_gainmap_grid.py
            ${CMAKE_CURRENT_SOURCE_DIR}/patches/patch_avif_grid.py
            ${CMAKE_CURRENT_SOURCE_DIR}/patches/libheif_pr1503.patch)

# ---------------------------------------------------------------- libjxl (JPEG XL encoder/decoder)
# GIT_SHALLOW only accepts branch names and tags, so the tag is cloned shallowly and the
# resulting commit is verified below; a moved tag fails the build instead of silently
# changing the linked code.
set(ULTRASLIM_LIBJXL_TAG v0.11.1)
set(ULTRASLIM_LIBJXL_COMMIT 794a5dcf0d54f9f0b20d288a12e87afb91d20dfc)
ExternalProject_Add(libjxl
    GIT_REPOSITORY https://github.com/libjxl/libjxl.git
    GIT_TAG ${ULTRASLIM_LIBJXL_TAG}
    GIT_SHALLOW TRUE
    GIT_SUBMODULES "third_party/brotli" "third_party/highway" "third_party/skcms"
    CMAKE_ARGS ${COMMON_CMAKE_ARGS}
               -DBUILD_TESTING=OFF
               -DJPEGXL_ENABLE_TOOLS=OFF
               -DJPEGXL_ENABLE_EXAMPLES=OFF
               -DJPEGXL_ENABLE_MANPAGES=OFF
               -DJPEGXL_ENABLE_BENCHMARK=OFF
               -DJPEGXL_ENABLE_JPEGLI=OFF
               -DJPEGXL_ENABLE_JPEGLI_LIBJPEG=OFF
               -DJPEGXL_ENABLE_DOXYGEN=OFF
               -DJPEGXL_ENABLE_VIEWERS=OFF
               -DJPEGXL_ENABLE_DEVTOOLS=OFF
               -DJPEGXL_ENABLE_SJPEG=OFF
               -DJPEGXL_ENABLE_SKCMS=ON
               -DJPEGXL_ENABLE_PLUGINS=OFF
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target jxl jxl_cms jxl_threads brotli --parallel ${DEPS_INNER_JOBS}
    INSTALL_COMMAND ${CMAKE_COMMAND} --install <BINARY_DIR> --config Release)
ExternalProject_Add_Step(libjxl verify_pin
    COMMAND ${CMAKE_COMMAND} -DSRC_DIR=<SOURCE_DIR> -DEXPECTED=${ULTRASLIM_LIBJXL_COMMIT}
            -P ${CMAKE_CURRENT_SOURCE_DIR}/patches/verify_git_pin.cmake
    COMMENT "Verifying libjxl resolves to the pinned commit"
    DEPENDEES update DEPENDERS patch ALWAYS TRUE)
