cmake_minimum_required(VERSION 3.4.1)

project(nc1020 C)

add_library(
        nc1020
        SHARED
        org_liberty_android_nc1020emu_NC1020JNI.c
        android_audio.c
        wqx/cpu6502.c
        wqx/nc1020.c
        wqx/nc1020_io.c
        wqx/nc1020_dsp.c
        wqx/nc1020_sound.c)

target_link_libraries(
        nc1020
        android
        log
        OpenSLES)

set_target_properties(nc1020 PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")

set_property(TARGET nc1020 PROPERTY C_STANDARD 11)

