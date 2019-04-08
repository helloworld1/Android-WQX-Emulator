cmake_minimum_required(VERSION 3.4.1)

add_library(
        NC1020
        SHARED
        gmail_hackwaly_nc1020_NC1020_JNI.c
        wqx/nc1020.c)

target_link_libraries(
        NC1020
        android
        log)

