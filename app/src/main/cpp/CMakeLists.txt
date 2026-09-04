cmake_minimum_required(VERSION 3.22.1)
project(terminal_pty C)

add_library(terminal_pty SHARED pty_bridge.c)

target_link_libraries(terminal_pty log)
