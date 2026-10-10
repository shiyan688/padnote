LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := padnote_noreplace
LOCAL_SRC_FILES := padnote_noreplace.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror -fvisibility=hidden
include $(BUILD_SHARED_LIBRARY)
