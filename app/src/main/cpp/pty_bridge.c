#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#ifndef TIOCSPTLCK
#define TIOCSPTLCK _IOW('T', 0x31, int)
#endif
#ifndef TIOCGPTN
#define TIOCGPTN _IOR('T', 0x30, unsigned int)
#endif

static jfieldID g_master;
static jfieldID g_pid;

static int get_fd(JNIEnv *env, jobject self) { return (*env)->GetIntField(env, self, g_master); }
static pid_t get_pid(JNIEnv *env, jobject self) { return (pid_t)(*env)->GetLongField(env, self, g_pid); }
static void set_fields(JNIEnv *env, jobject self, int master, pid_t pid) {
    (*env)->SetIntField(env, self, g_master, master);
    (*env)->SetLongField(env, self, g_pid, (jlong)pid);
}

/* Android devices normally provide posix_openpt/grantpt. Some older vendor
 * builds are more reliable when /dev/ptmx is opened and unlocked directly. */
static int open_ptmx(char *slave_name, size_t slave_capacity) {
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) {
        master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    }
    if (master < 0) return -1;

    int unlocked = 0;
    if (grantpt(master) == 0 && unlockpt(master) == 0) {
        char *name = ptsname(master);
        if (name) {
            strncpy(slave_name, name, slave_capacity - 1);
            slave_name[slave_capacity - 1] = '\0';
            unlocked = 1;
        }
    }

    if (!unlocked) {
        unsigned int pty_number = 0;
        int lock = 0;
        if (ioctl(master, TIOCGPTN, &pty_number) == 0 &&
            ioctl(master, TIOCSPTLCK, &lock) == 0) {
            int written = snprintf(slave_name, slave_capacity, "/dev/pts/%u", pty_number);
            unlocked = written > 0 && (size_t)written < slave_capacity;
        }
    }

    if (!unlocked) {
        close(master);
        return -1;
    }
    return master;
}

JNIEXPORT void JNICALL Java_com_hishow_terminal33_NativePty_nativeInit(JNIEnv *env, jobject self) {
    jclass cls = (*env)->GetObjectClass(env, self);
    g_master = (*env)->GetFieldID(env, cls, "masterFd", "I");
    g_pid = (*env)->GetFieldID(env, cls, "childPid", "J");
    signal(SIGPIPE, SIG_IGN);
    set_fields(env, self, -1, -1);
}

JNIEXPORT jboolean JNICALL Java_com_hishow_terminal33_NativePty_nativeStart(JNIEnv *env, jobject self, jobjectArray argv, jstring cwd) {
    if (get_fd(env, self) >= 0) return JNI_FALSE;
    jsize argc = (*env)->GetArrayLength(env, argv);
    if (argc < 1) return JNI_FALSE;

    char **args = calloc((size_t)argc + 1, sizeof(char *));
    if (!args) return JNI_FALSE;
    for (jsize i = 0; i < argc; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, argv, i);
        const char *utf = (*env)->GetStringUTFChars(env, item, NULL);
        if (!utf) {
            for (jsize j = 0; j < i; j++) free(args[j]);
            free(args);
            return JNI_FALSE;
        }
        args[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, item, utf);
        (*env)->DeleteLocalRef(env, item);
        if (!args[i]) {
            for (jsize j = 0; j < i; j++) free(args[j]);
            free(args);
            return JNI_FALSE;
        }
    }

    const char *cwd_utf = (*env)->GetStringUTFChars(env, cwd, NULL);
    if (!cwd_utf) {
        for (jsize i = 0; i < argc; i++) free(args[i]);
        free(args);
        return JNI_FALSE;
    }
    char *cwd_copy = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf);
    if (!cwd_copy) {
        for (jsize i = 0; i < argc; i++) free(args[i]);
        free(args);
        return JNI_FALSE;
    }

    char slave_name[128];
    int master = open_ptmx(slave_name, sizeof(slave_name));
    if (master < 0) {
        free(cwd_copy);
        for (jsize i = 0; i < argc; i++) free(args[i]);
        free(args);
        return JNI_FALSE;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(master);
        free(cwd_copy);
        for (jsize i = 0; i < argc; i++) free(args[i]);
        free(args);
        return JNI_FALSE;
    }

    if (pid == 0) {
        int slave = open(slave_name, O_RDWR | O_NOCTTY);
        if (slave < 0) _exit(127);
        if (setsid() < 0) _exit(127);
        if (ioctl(slave, TIOCSCTTY, 0) < 0) _exit(127);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);

        struct termios tio;
        if (tcgetattr(STDIN_FILENO, &tio) == 0) {
            tio.c_lflag |= (ECHO | ECHOE | ECHOK | ICANON | ISIG);
            tio.c_iflag |= ICRNL;
            tcsetattr(STDIN_FILENO, TCSANOW, &tio);
        }
        struct winsize ws = {0};
        ws.ws_row = 32;
        ws.ws_col = 120;
        ioctl(STDIN_FILENO, TIOCSWINSZ, &ws);

        if (chdir(cwd_copy) != 0) _exit(126);
        setenv("HOME", (access("/root", W_OK) == 0) ? "/root" : cwd_copy, 1);
        setenv("TERM", "xterm-256color", 1);
        setenv("LANG", "C.UTF-8", 1);
        setenv("LC_ALL", "C.UTF-8", 1);
        setenv("COLORTERM", "truecolor", 1);
        setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin", 1);
        execv(args[0], args);
        _exit(127);
    }

    set_fields(env, self, master, pid);
    free(cwd_copy);
    for (jsize i = 0; i < argc; i++) free(args[i]);
    free(args);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_com_hishow_terminal33_NativePty_nativeRead(JNIEnv *env, jobject self, jbyteArray buffer) {
    int fd = get_fd(env, self);
    if (fd < 0) return -1;
    jsize capacity = (*env)->GetArrayLength(env, buffer);
    if (capacity <= 0) return 0;
    jbyte *data = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!data) return -1;
    ssize_t n = read(fd, data, (size_t)capacity);
    if (n > 0) (*env)->ReleaseByteArrayElements(env, buffer, data, 0);
    else (*env)->ReleaseByteArrayElements(env, buffer, data, JNI_ABORT);
    return (jint)n;
}

JNIEXPORT jint JNICALL Java_com_hishow_terminal33_NativePty_nativeWrite(JNIEnv *env, jobject self, jbyteArray data, jint length) {
    int fd = get_fd(env, self);
    if (fd < 0 || length <= 0) return -1;
    jsize size = (*env)->GetArrayLength(env, data);
    if (length > size) length = size;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return -1;
    ssize_t n = write(fd, bytes, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return (jint)n;
}

JNIEXPORT void JNICALL Java_com_hishow_terminal33_NativePty_nativeResize(JNIEnv *env, jobject self, jint rows, jint cols) {
    int fd = get_fd(env, self);
    if (fd < 0) return;
    struct winsize ws = {0};
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(fd, TIOCSWINSZ, &ws);
    pid_t pid = get_pid(env, self);
    if (pid > 0) kill(pid, SIGWINCH);
}

JNIEXPORT void JNICALL Java_com_hishow_terminal33_NativePty_nativeClose(JNIEnv *env, jobject self) {
    int fd = get_fd(env, self);
    pid_t pid = get_pid(env, self);
    set_fields(env, self, -1, -1);
    if (fd >= 0) close(fd);
    if (pid > 0) {
        kill(pid, SIGHUP);
        waitpid(pid, NULL, WNOHANG);
    }
}
