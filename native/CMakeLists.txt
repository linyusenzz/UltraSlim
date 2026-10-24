# UltraSlim native library: libultrahdr (JPEG/HEIF/AVIF gain map transcode) + JNI bridge.
#
# Requires the dependency stack (libjpeg-turbo, x265, aom, libde265, libheif) to be
# pre-built into native/prefix/<flavor> via native/deps/build_deps.sh.
cmake_minimum_required(VERSION 3.22.1)
project(ultraslim CXX C)

option(ULTRASLIM_HOST_TEST "Build host-side transcode test executable" OFF)

# 本仓库路径含空格与中文目录名，直接写进 __FILE__/断言串会随 .so 发布出去。
# 依赖库由 native/deps 另行映射（那里的构建机路径来自 build 目录），这里覆盖 App 自己编译的
# 源码（transcode.cpp、vendored libultrahdr、androidx.graphics.path）。
# 用 add_compile_options 而非 CMAKE_C_FLAGS：空格会被 CMake 当参数分隔符拆开。
# 必须 REALPATH 归一到仓库根：字面量 "${CMAKE_CURRENT_SOURCE_DIR}/.." 并不是
# ".../third_party/libultrahdr/..." 的文本前缀，直接写会漏掉 vendored libultrahdr
# 的 assert 串（实测 gainmapmath_neon.cpp 的路径因此残留在 debug .so 里）。
get_filename_component(ULTRASLIM_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." REALPATH)
add_compile_options("-ffile-prefix-map=${ULTRASLIM_REPO_ROOT}=/ultraslim-src")

if(ULTRASLIM_HOST_TEST)
  set(DEPS_PREFIX ${CMAKE_CURRENT_SOURCE_DIR}/prefix/host)
else()
  set(DEPS_PREFIX ${CMAKE_CURRENT_SOURCE_DIR}/prefix/android-${ANDROID_ABI})
endif()

if(NOT EXISTS ${DEPS_PREFIX}/lib/libheif.a)
  message(FATAL_ERROR "Native deps not found at ${DEPS_PREFIX}. Run native/deps/build_deps.sh first.")
endif()

list(APPEND CMAKE_PREFIX_PATH ${DEPS_PREFIX})
# The NDK toolchain restricts find_* to the sysroot; our deps live outside it.
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
set(JPEG_INCLUDE_DIR ${DEPS_PREFIX}/include)
set(JPEG_LIBRARY ${DEPS_PREFIX}/lib/libjpeg.a)

# libultrahdr (static "core" target), without its bundled-deps machinery
set(UHDR_BUILD_DEPS OFF)
set(UHDR_BUILD_EXAMPLES OFF)
set(UHDR_BUILD_TESTS OFF)
set(UHDR_BUILD_BENCHMARK OFF)
set(UHDR_BUILD_FUZZERS OFF)
set(UHDR_ENABLE_INSTALL OFF)
set(UHDR_ENABLE_HEIF ON)
set(UHDR_ENABLE_GLES OFF)
set(UHDR_ENABLE_SMPTE2094_50 OFF)
set(UHDR_ENABLE_LOGS OFF)
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr
                 ${CMAKE_CURRENT_BINARY_DIR}/libultrahdr)

# codec/container static libraries from the deps prefix
find_library(AOM_LIB NAMES aom PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(X265_LIB NAMES x265 PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(DE265_LIB NAMES de265 PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(JPEG_LIB NAMES jpeg PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(HEIF_LIB NAMES heif PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(SVT_LIB NAMES SvtAv1Enc svtav1 PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(DAV1D_LIB NAMES dav1d PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(JXL_LIB NAMES jxl PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(JXL_CMS_LIB NAMES jxl_cms PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(JXL_THREADS_LIB NAMES jxl_threads PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(HWY_LIB NAMES hwy PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(BROTLIENC_LIB NAMES brotlienc PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(BROTLIDEC_LIB NAMES brotlidec PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(BROTLICOMMON_LIB NAMES brotlicommon PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
set(JXL_ALL_LIBS ${JXL_LIB} ${JXL_CMS_LIB} ${JXL_THREADS_LIB} ${HWY_LIB} ${BROTLIENC_LIB} ${BROTLIDEC_LIB} ${BROTLICOMMON_LIB})

set(TRANSCODE_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/jni/transcode.cpp)

if(ULTRASLIM_HOST_TEST)
  add_executable(transcode_test ${TRANSCODE_SRCS} ${CMAKE_CURRENT_SOURCE_DIR}/host_test/main.cpp)
  target_include_directories(transcode_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/lib/include ${DEPS_PREFIX}/include)
  target_link_libraries(transcode_test PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB} ${JXL_ALL_LIBS})

  add_executable(rendition_dump ${CMAKE_CURRENT_SOURCE_DIR}/host_test/rendition_dump.cpp)
  target_include_directories(rendition_dump PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/lib/include ${DEPS_PREFIX}/include)
  target_link_libraries(rendition_dump PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB} ${JXL_ALL_LIBS})

  add_executable(gm_dump ${CMAKE_CURRENT_SOURCE_DIR}/host_test/gm_dump.cpp)
  target_include_directories(gm_dump PRIVATE ${DEPS_PREFIX}/include ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr)
  target_link_libraries(gm_dump PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB} ${JXL_ALL_LIBS})
else()
  add_library(ultraslim SHARED ${TRANSCODE_SRCS} ${CMAKE_CURRENT_SOURCE_DIR}/jni/ultraslim_jni.cpp)
  # NDK r27 defaults to 4 KB. Align both LOAD segments and the RELRO boundary.
  # Hidden visibility + gc-sections keep the statically linked codecs' symbols
  # out of the dynamic symbol table (JNIEXPORT functions are unaffected).
  target_compile_options(ultraslim PRIVATE
      -fvisibility=hidden -fvisibility-inlines-hidden
      -ffunction-sections -fdata-sections)
  target_link_options(ultraslim PRIVATE
      "-Wl,-z,max-page-size=16384"
      "-Wl,-z,common-page-size=16384"
      "-Wl,--gc-sections"
      "-Wl,--exclude-libs,ALL")
  target_include_directories(ultraslim PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/lib/include ${DEPS_PREFIX}/include)
  # Keep the Maven 1.0.1 Kotlin API, rebuilding its JNI component with aligned RELRO.
  add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../third_party/androidx-graphics-path
                   ${CMAKE_CURRENT_BINARY_DIR}/graphics-path)
  target_compile_features(androidx.graphics.path PRIVATE cxx_std_17)
  target_compile_options(androidx.graphics.path PRIVATE -fno-exceptions -fno-rtti
      -ffast-math -ffp-contract=fast -fvisibility=hidden -fvisibility-inlines-hidden
      -fomit-frame-pointer -ffunction-sections -fdata-sections)
  target_link_options(androidx.graphics.path PRIVATE
      "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384"
      "-Wl,--gc-sections" "-Wl,-Bsymbolic-functions")
  find_library(log-lib log REQUIRED)
  # link order matters for static archives: consumers before providers
  target_link_libraries(ultraslim PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB} ${JXL_ALL_LIBS} ${log-lib})
endif()

# AGP Debug compiles in-tree code at -O0. Keep the prebuilt codecs as shipped.
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  set(_ultraslim_opt)
  if(TARGET ultraslim)
    list(APPEND _ultraslim_opt ultraslim)
  endif()
  if(TARGET transcode_test)
    list(APPEND _ultraslim_opt transcode_test)
  endif()
  if(TARGET core)
    list(APPEND _ultraslim_opt core)
  endif()
  if(TARGET image_io)
    list(APPEND _ultraslim_opt image_io)
  endif()
  if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    foreach(_t IN LISTS _ultraslim_opt)
      target_compile_options(${_t} PRIVATE -O2 -ffp-contract=fast -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
    endforeach()
  else()
    foreach(_t IN LISTS _ultraslim_opt)
      target_compile_options(${_t} PRIVATE -O3 -flto=thin -ffp-contract=fast -fvectorize -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
    endforeach()
    if(TARGET ultraslim)
      target_link_options(ultraslim PRIVATE -flto=thin)
    endif()
    if(TARGET transcode_test)
      target_link_options(transcode_test PRIVATE -flto=thin)
    endif()
  endif()
endif()

add_compile_definitions(WITH_EXPERIMENTAL_GAIN_MAP=1)

if(ULTRASLIM_HOST_TEST)
  enable_testing()
  add_executable(transcode_regression ${TRANSCODE_SRCS} ${CMAKE_CURRENT_SOURCE_DIR}/host_test/regression.cpp)
  target_include_directories(transcode_regression PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/lib/include ${DEPS_PREFIX}/include)
  target_link_libraries(transcode_regression PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB} ${JXL_ALL_LIBS})
  file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/regression-output)
  add_test(NAME transcode_regression COMMAND transcode_regression ${CMAKE_CURRENT_SOURCE_DIR}/../tests/fixtures/libultrahdr ${CMAKE_CURRENT_BINARY_DIR}/regression-output)
endif()

if(ULTRASLIM_HOST_TEST)
  add_executable(gainmap_preservation ${TRANSCODE_SRCS} ${CMAKE_CURRENT_SOURCE_DIR}/host_test/gainmap_preservation.cpp)
  target_include_directories(gainmap_preservation PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/lib/include ${DEPS_PREFIX}/include)
  target_link_libraries(gainmap_preservation PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB} ${JXL_ALL_LIBS})
  add_test(NAME gainmap_preservation COMMAND gainmap_preservation ${CMAKE_CURRENT_BINARY_DIR}/regression-output ${CMAKE_CURRENT_SOURCE_DIR}/../tests/fixtures/libultrahdr/apple_gainmap_new.jpg)
endif()

if(ULTRASLIM_HOST_TEST)
  add_executable(upstream_color host_test/upstream_color.cpp)
  get_target_property(_color_includes rendition_dump INCLUDE_DIRECTORIES)
  get_target_property(_color_libraries rendition_dump LINK_LIBRARIES)
  target_include_directories(upstream_color PRIVATE ${_color_includes})
  target_link_libraries(upstream_color PRIVATE ${_color_libraries})
  target_compile_definitions(upstream_color PRIVATE UHDR_ENABLE_INTRINSICS=1)
  add_test(NAME upstream_color COMMAND upstream_color)

  add_executable(avif_quality ${TRANSCODE_SRCS} host_test/avif_quality.cpp)
  target_include_directories(avif_quality PRIVATE ${_color_includes})
  target_link_libraries(avif_quality PRIVATE ${_color_libraries})
  add_test(NAME avif_quality COMMAND avif_quality ${CMAKE_CURRENT_BINARY_DIR}/regression-output)

  add_executable(preview_probe host_test/preview_probe.cpp)
  target_include_directories(preview_probe PRIVATE ${_color_includes})
  target_link_libraries(preview_probe PRIVATE ${_color_libraries})
  add_test(NAME preview_probe COMMAND preview_probe ${CMAKE_CURRENT_SOURCE_DIR}/../tests/fixtures/libultrahdr ${CMAKE_CURRENT_BINARY_DIR}/regression-output)

  add_executable(metadata_regression host_test/metadata_regression.cpp)
  target_include_directories(metadata_regression PRIVATE ${_color_includes})
  target_link_libraries(metadata_regression PRIVATE ${_color_libraries})
  add_test(NAME metadata_regression COMMAND metadata_regression ${CMAKE_CURRENT_BINARY_DIR}/regression-output)

  add_executable(jpeg_metadata_fallback host_test/jpeg_metadata_fallback.cpp)
  target_include_directories(jpeg_metadata_fallback PRIVATE ${_color_includes})
  target_link_libraries(jpeg_metadata_fallback PRIVATE ${_color_libraries})
  add_test(NAME jpeg_metadata_fallback COMMAND jpeg_metadata_fallback ${CMAKE_CURRENT_SOURCE_DIR}/../tests/fixtures/libultrahdr/apple_gainmap_new.jpg)
endif()
