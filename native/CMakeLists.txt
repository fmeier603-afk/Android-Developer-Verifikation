cmake_minimum_required(VERSION 3.22.1)
project("jarrunner_jni")

add_library(jarrunner_jni SHARED jvm_bridge.cpp)

find_library(log-lib log)

target_link_libraries(jarrunner_jni
    dl
    ${log-lib}
)
