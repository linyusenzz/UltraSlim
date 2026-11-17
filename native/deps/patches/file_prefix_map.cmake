# UltraSlim: 把构建机绝对路径从产物里去掉。
#
# 依赖源码里的 __FILE__、断言串会把 /Users/<用户名>/...（含中文目录名）写进 .a，
# 最终进入发布 APK 的 libultraslim.so，泄露本机目录结构。
#
# 为什么不用 -DCMAKE_C_FLAGS=-ffile-prefix-map=...：
# 本仓库路径含空格，该写法在 CMake 传参时被拆成两段（clang: error: no such file
# or directory），依赖直接构建失败。写成 add_compile_options 由 CMake 统一加引号。
#
# 注入方式：-DCMAKE_PROJECT_INCLUDE=<本文件>，在每个依赖自己的 project() 之后执行；
# ULTRASLIM_PATH_MAP_REV 只用于让已有 superbuild 的配置命令变化后重新配置，不在本文件读取。
if(DEFINED ULTRASLIM_MAP_ROOT AND NOT ULTRASLIM_MAP_ROOT STREQUAL "")
  add_compile_options("$<$<COMPILE_LANGUAGE:C,CXX,ASM>:-ffile-prefix-map=${ULTRASLIM_MAP_ROOT}=/ultraslim-deps>")
endif()
add_compile_options("$<$<COMPILE_LANGUAGE:C,CXX>:-ffunction-sections>"
                    "$<$<COMPILE_LANGUAGE:C,CXX>:-fdata-sections>")
if(DEFINED ULTRASLIM_EXTRA_FLAGS AND NOT ULTRASLIM_EXTRA_FLAGS STREQUAL "")
  separate_arguments(_ultraslim_extra UNIX_COMMAND "${ULTRASLIM_EXTRA_FLAGS}")
  add_compile_options("$<$<COMPILE_LANGUAGE:C,CXX>:${_ultraslim_extra}>")
endif()
