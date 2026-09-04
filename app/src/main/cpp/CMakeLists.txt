cmake_minimum_required(VERSION 3.22.1)
project("terminal33pty")

add_library(terminal33pty SHARED pty_bridge.c)

target_link_libraries(terminal33pty log)
