#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

_Static_assert(sizeof(off64_t) == 8, "Torrent offsets require 64 bits");

static void throw_io(JNIEnv *env, const char *operation, int error) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s (errno %d)", operation, strerror(error), error);
    jclass exception = (*env)->FindClass(env, "java/io/IOException");
    if (exception != NULL) {
        (*env)->ThrowNew(env, exception, message);
    }
}

// Kotlin supplies a UTF-8 path with a trailing NUL, avoiding JNI modified UTF-8.
static jbyte *acquire_path(JNIEnv *env, jbyteArray path) {
    jsize length = (*env)->GetArrayLength(env, path);
    jbyte *bytes = (*env)->GetByteArrayElements(env, path, NULL);
    if (bytes == NULL) return NULL;
    if (length < 2 || bytes[length - 1] != 0 || memchr(bytes, 0, (size_t)length - 1) != NULL) {
        (*env)->ReleaseByteArrayElements(env, path, bytes, JNI_ABORT);
        throw_io(env, "Invalid cache file path", EINVAL);
        return NULL;
    }
    return bytes;
}

JNIEXPORT void JNICALL
Java_dev_lumastream_torrent_SparseFiles_punchNative(
        JNIEnv *env, jobject instance, jbyteArray path, jlong offset, jlong length) {
    (void)instance;
    if (offset < 0 || length < 0 || offset > INT64_MAX - length) {
        throw_io(env, "Invalid sparse-file range", EINVAL);
        return;
    }
    jbyte *bytes = acquire_path(env, path);
    if (bytes == NULL) return;
    int fd = open64((const char *)bytes, O_RDWR | O_CLOEXEC | O_NOFOLLOW);
    int error = errno;
    (*env)->ReleaseByteArrayElements(env, path, bytes, JNI_ABORT);
    if (fd < 0) {
        throw_io(env, "Opening sparse cache file", error);
        return;
    }
    int result = 0;
    if (length != 0) {
        result = fallocate64(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE,
                            (off64_t)offset, (off64_t)length);
        error = errno;
    }
    // Do not retry close after EINTR: Linux may already have released the descriptor.
    int close_result = close(fd);
    int close_error = errno;
    if (result != 0) {
        throw_io(env, "Sparse cache hole punching is unavailable or failed", error);
    } else if (close_result != 0) {
        throw_io(env, "Closing sparse cache file", close_error);
    }
}

JNIEXPORT jlong JNICALL
Java_dev_lumastream_torrent_SparseFiles_allocatedBytesNative(
        JNIEnv *env, jobject instance, jbyteArray path) {
    (void)instance;
    jbyte *bytes = acquire_path(env, path);
    if (bytes == NULL) return 0;
    struct stat64 status;
    int result = lstat64((const char *)bytes, &status);
    int error = errno;
    (*env)->ReleaseByteArrayElements(env, path, bytes, JNI_ABORT);
    if (result != 0) {
        throw_io(env, "Reading cache allocation", error);
        return 0;
    }
    if (S_ISLNK(status.st_mode)) {
        throw_io(env, "Cache allocation path is a symbolic link", ELOOP);
        return 0;
    }
    if (status.st_blocks < 0 || (uint64_t)status.st_blocks > INT64_MAX / 512) {
        throw_io(env, "Cache allocation exceeds supported range", EOVERFLOW);
        return 0;
    }
    return (jlong)status.st_blocks * 512;
}
