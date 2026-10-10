#define _GNU_SOURCE 1
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef __NR_renameat2
#error "NDK headers must provide __NR_renameat2 for every supported ABI"
#endif

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE (1u)
#endif

static int copy_bytes(JNIEnv *env, jbyteArray input, size_t cap, char **out) {
    if (input == NULL) return EINVAL;
    jsize length = (*env)->GetArrayLength(env, input);
    if (length <= 0 || (size_t)length > cap) return ENAMETOOLONG;
    char *buffer = (char *)malloc((size_t)length + 1u);
    if (buffer == NULL) return ENOMEM;
    (*env)->GetByteArrayRegion(env, input, 0, length, (jbyte *)buffer);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        free(buffer);
        return EINVAL;
    }
    if (memchr(buffer, '\0', (size_t)length) != NULL) {
        free(buffer);
        return EINVAL;
    }
    buffer[length] = '\0';
    *out = buffer;
    return 0;
}

static int valid_component(const char *value) {
    if (value == NULL || value[0] == '\0' || strcmp(value, ".") == 0 || strcmp(value, "..") == 0) return 0;
    for (const unsigned char *p = (const unsigned char *)value; *p != 0; ++p) {
        unsigned char c = *p;
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')) return 0;
    }
    return 1;
}

static int same_directory_path(int fd, const char *path) {
    struct stat opened;
    struct stat named;
    if (fstat(fd, &opened) != 0) return errno;
    if (fstatat(AT_FDCWD, path, &named, AT_SYMLINK_NOFOLLOW) != 0) return errno;
    if (!S_ISDIR(opened.st_mode) || !S_ISDIR(named.st_mode) ||
        opened.st_dev != named.st_dev || opened.st_ino != named.st_ino) return ESTALE;
    return 0;
}

static int same_source_path(int dirfd, int source_fd, const char *name) {
    struct stat opened;
    struct stat named;
    if (fstat(source_fd, &opened) != 0) return errno;
    if (fstatat(dirfd, name, &named, AT_SYMLINK_NOFOLLOW) != 0) return errno;
    if (!S_ISREG(opened.st_mode) || !S_ISREG(named.st_mode) ||
        opened.st_nlink != 1 || named.st_nlink != 1 ||
        opened.st_dev != named.st_dev || opened.st_ino != named.st_ino ||
        opened.st_size != named.st_size || opened.st_mode != named.st_mode) return ESTALE;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_padnote_android_streaming_NativeNoReplace_renameNoReplaceNative(
        JNIEnv *env, jclass type, jbyteArray source_parent_bytes, jbyteArray source_name_bytes,
        jbyteArray destination_parent_bytes, jbyteArray destination_name_bytes) {
    (void)type;
    char *source_parent = NULL, *source_name = NULL, *destination_parent = NULL, *destination_name = NULL;
    int error = copy_bytes(env, source_parent_bytes, 4096u, &source_parent);
    if (error == 0) error = copy_bytes(env, source_name_bytes, 255u, &source_name);
    if (error == 0) error = copy_bytes(env, destination_parent_bytes, 4096u, &destination_parent);
    if (error == 0) error = copy_bytes(env, destination_name_bytes, 255u, &destination_name);
    if (error == 0 && (!valid_component(source_name) || !valid_component(destination_name))) error = EINVAL;

    int source_dir_fd = -1, destination_dir_fd = -1, source_fd = -1;
    if (error == 0) {
        source_dir_fd = open(source_parent, O_RDONLY | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW);
        if (source_dir_fd < 0) error = errno;
    }
    if (error == 0) error = same_directory_path(source_dir_fd, source_parent);
    if (error == 0) {
        destination_dir_fd = open(destination_parent, O_RDONLY | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW);
        if (destination_dir_fd < 0) error = errno;
    }
    if (error == 0) error = same_directory_path(destination_dir_fd, destination_parent);
    if (error == 0) {
        struct stat source_directory, destination_directory;
        if (fstat(source_dir_fd, &source_directory) != 0 || fstat(destination_dir_fd, &destination_directory) != 0) error = errno;
        else if (source_directory.st_dev != destination_directory.st_dev) error = EXDEV;
    }
    if (error == 0) {
        source_fd = openat(source_dir_fd, source_name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (source_fd < 0) error = errno;
    }
    if (error == 0) error = same_source_path(source_dir_fd, source_fd, source_name);
    if (error == 0) {
        long result = syscall(__NR_renameat2, source_dir_fd, source_name, destination_dir_fd,
                              destination_name, (unsigned int)RENAME_NOREPLACE);
        if (result != 0) error = errno;
    }
    if (error == 0) {
        struct stat installed, opened_source;
        if (fstat(source_fd, &opened_source) != 0) error = errno;
        else if (fstatat(destination_dir_fd, destination_name, &installed, AT_SYMLINK_NOFOLLOW) != 0) error = errno;
        else if (!S_ISREG(installed.st_mode) || installed.st_nlink != 1 ||
                 installed.st_dev != opened_source.st_dev || installed.st_ino != opened_source.st_ino ||
                 installed.st_size != opened_source.st_size || installed.st_mode != opened_source.st_mode) error = ESTALE;
    }
    if (error == 0 && fsync(source_dir_fd) != 0) error = errno;
    if (error == 0 && fsync(destination_dir_fd) != 0) error = errno;
    if (source_fd >= 0 && close(source_fd) != 0 && error == 0) error = errno;
    if (destination_dir_fd >= 0 && close(destination_dir_fd) != 0 && error == 0) error = errno;
    if (source_dir_fd >= 0 && close(source_dir_fd) != 0 && error == 0) error = errno;
    free(source_parent); free(source_name); free(destination_parent); free(destination_name);
    return (jint)error;
}
