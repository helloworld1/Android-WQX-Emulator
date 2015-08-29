cmake_minimum_required(VERSION 3.4.1)

add_library(NC1020
            SHARED
            gmail_hackwaly_nc1020_NC1020_JNI.cpp
            wqx/nc1020.cpp)

target_link_libraries(NC1020
                      android
                      log)

