cmake_minimum_required(VERSION 3.4.1)

project(NC1020 C)

add_library(
        NC1020
        SHARED
        gmail_hackwaly_nc1020_NC1020_JNI.c
        wqx/cpu6502.c
        wqx/nc1020.c)

target_link_libraries(
        NC1020
        android
        log)

set_property(TARGET NC1020 PROPERTY C_STANDARD 11)

