#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <stdlib.h>
#include <errno.h>

JNIEXPORT jint JNICALL
Java_com_hishow_terminal33_NativePty_spawn(JNIEnv *env, jclass clazz, jobjectArray argv, jstring cwd) {
    (void)clazz;
    jsize argc = (*env)->GetArrayLength(env, argv);
    if (argc < 1) return -1;

    char **args = calloc((size_t)argc + 1, sizeof(char *));
    if (!args) return -1;

    for (jsize i = 0; i < argc; ++i) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, argv, i);
        const char *value = (*env)->GetStringUTFChars(env, item, NULL);
        if (!value) {
            for (jsize j = 0; j < i; ++j) free(args[j]);
            free(args);
            return -1;
        }
        args[i] = strdup(value);
        (*env)->ReleaseStringUTFChars(env, item, value);
        (*env)->DeleteLocalRef(env, item);
        if (!args[i]) {
            for (jsize j = 0; j <= i; ++j) free(args[j]);
            free(args);
            return -1;
        }
    }

    const char *dir = (*env)->GetStringUTFChars(env, cwd, NULL);
    if (!dir) {
        for (jsize i = 0; i < argc; ++i) free(args[i]);
        free(args);
        return -1;
    }

    struct winsize ws = { .ws_row = 32, .ws_col = 110, .ws_xpixel = 0, .ws_ypixel = 0 };
    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);

    if (pid == 0) {
        setenv("TERM", "xterm-256color", 1);
        setenv("HOME", "/root", 1);
        setenv("LANG", "C.UTF-8", 1);
        setenv("LC_ALL", "C.UTF-8", 1);
        setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
        setenv("PROOT_TMP_DIR", "/tmp", 1);
        chdir(dir);
        execv(args[0], args);
        _exit(127);
    }

    (*env)->ReleaseStringUTFChars(env, cwd, dir);
    for (jsize i = 0; i < argc; ++i) free(args[i]);
    free(args);

    if (pid < 0) {
        if (master >= 0) close(master);
        return -1;
    }
    return master;
}

JNIEXPORT jint JNICALL
Java_com_hishow_terminal33_NativePty_resize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void)env;
    (void)clazz;
    struct winsize ws = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols, .ws_xpixel = 0, .ws_ypixel = 0 };
    return ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_hishow_terminal33_NativePty_close(JNIEnv *env, jclass clazz, jint fd) {
    (void)env;
    (void)clazz;
    return close(fd);
}
