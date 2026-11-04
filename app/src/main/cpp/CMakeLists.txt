cmake_minimum_required(VERSION 3.22.1)
project(kalmanfit LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Host test is compiled with system g++ and is not a library source.
add_library(kalmanfit SHARED kalman_core.cpp kalman_jni.cpp)
target_include_directories(kalmanfit PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
