cmake_minimum_required(VERSION 3.4.1)

project(nc1020 C)

add_library(
        nc1020
        SHARED
        org_liberty_android_nc1020emu_NC1020JNI.c
        wqx/cpu6502.c
        wqx/nc1020.c
        wqx/nc1020_io.c)

target_link_libraries(
        nc1020
        android
        log)

set_property(TARGET nc1020 PROPERTY C_STANDARD 11)

