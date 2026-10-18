cmake_minimum_required(VERSION 3.22.1)
project(lumastream_storage LANGUAGES C)

add_library(lumastream_storage SHARED sparse_files.c)
target_compile_features(lumastream_storage PRIVATE c_std_11)
target_compile_definitions(lumastream_storage PRIVATE _GNU_SOURCE _FILE_OFFSET_BITS=64)
target_compile_options(lumastream_storage PRIVATE -Wall -Wextra -Werror)
