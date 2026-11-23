#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <vector>

#define LOG_TAG "JarRunnerJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef jint (JNICALL *CreateJavaVM_t)(JavaVM **pvm, void **penv, void *args);

struct JniJavaVMOption {
    char *optionString;
    void *extraInfo;
};

struct JniJavaVMInitArgs {
    jint version;
    jint nOptions;
    JniJavaVMOption *options;
    jboolean ignoreUnrecognized;
};

static JavaVM *g_embeddedJvm = nullptr;

extern "C" JNIEXPORT jint JNICALL
Java_com_example_runtime_JniJvmBridge_nativeLaunchJar(
    JNIEnv *env,
    jobject /* thiz */,
    jstring jLibJvmPath,
    jstring jClassPath,
    jstring jMainClass,
    jstring jSandboxDir
) {
    const char *libJvmPath = env->GetStringUTFChars(jLibJvmPath, nullptr);
    const char *classPath = env->GetStringUTFChars(jClassPath, nullptr);
    const char *mainClass = env->GetStringUTFChars(jMainClass, nullptr);
    const char *sandboxDir = env->GetStringUTFChars(jSandboxDir, nullptr);

    void *handle = dlopen(libJvmPath, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        LOGE("dlopen(%s) fehlgeschlagen: %s", libJvmPath, dlerror());
        env->ReleaseStringUTFChars(jLibJvmPath, libJvmPath);
        env->ReleaseStringUTFChars(jClassPath, classPath);
        env->ReleaseStringUTFChars(jMainClass, mainClass);
        env->ReleaseStringUTFChars(jSandboxDir, sandboxDir);
        return -1;
    }

    auto createVmFn = reinterpret_cast<CreateJavaVM_t>(dlsym(handle, "JNI_CreateJavaVM"));
    if (!createVmFn) {
        LOGE("dlsym(JNI_CreateJavaVM) fehlgeschlagen: %s", dlerror());
        env->ReleaseStringUTFChars(jLibJvmPath, libJvmPath);
        env->ReleaseStringUTFChars(jClassPath, classPath);
        env->ReleaseStringUTFChars(jMainClass, mainClass);
        env->ReleaseStringUTFChars(jSandboxDir, sandboxDir);
        return -2;
    }

    JNIEnv *jvmEnv = nullptr;
    if (g_embeddedJvm == nullptr) {
        std::string cpArg = std::string("-Djava.class.path=") + classPath;
        std::string homeArg = std::string("-Duser.home=") + sandboxDir;
        std::string tmpArg = std::string("-Djava.io.tmpdir=") + sandboxDir + "/tmp";
        std::string memArg = "-Xmx256m";

        std::vector<JniJavaVMOption> options = {
            { const_cast<char *>(cpArg.c_str()), nullptr },
            { const_cast<char *>(homeArg.c_str()), nullptr },
            { const_cast<char *>(tmpArg.c_str()), nullptr },
            { const_cast<char *>(memArg.c_str()), nullptr }
        };

        JniJavaVMInitArgs vmArgs{};
        vmArgs.version = 0x00150000; // JNI_VERSION_21 (fallback JNI_VERSION_1_8 = 0x00010008)
        vmArgs.nOptions = static_cast<jint>(options.size());
        vmArgs.options = options.data();
        vmArgs.ignoreUnrecognized = JNI_TRUE;

        jint res = createVmFn(&g_embeddedJvm, reinterpret_cast<void **>(&jvmEnv), &vmArgs);
        if (res != JNI_OK || jvmEnv == nullptr) {
            LOGE("JNI_CreateJavaVM fehlgeschlagen: %d", res);
            return -3;
        }
    } else {
        g_embeddedJvm->AttachCurrentThread(&jvmEnv, nullptr);
    }

    jclass hostCls = jvmEnv->FindClass("de/jarrunner/api/internal/ChildProcessHost");
    if (hostCls != nullptr) {
        jmethodID mainMid = jvmEnv->GetStaticMethodID(hostCls, "main", "([Ljava/lang/String;)V");
        jclass strCls = jvmEnv->FindClass("java/lang/String");
        jobjectArray args = jvmEnv->NewObjectArray(2, strCls, nullptr);
        jvmEnv->SetObjectArrayElement(args, 0, jvmEnv->NewStringUTF(classPath));
        jvmEnv->SetObjectArrayElement(args, 1, jvmEnv->NewStringUTF(mainClass));
        jvmEnv->CallStaticVoidMethod(hostCls, mainMid, args);
    }

    env->ReleaseStringUTFChars(jLibJvmPath, libJvmPath);
    env->ReleaseStringUTFChars(jClassPath, classPath);
    env->ReleaseStringUTFChars(jMainClass, mainClass);
    env->ReleaseStringUTFChars(jSandboxDir, sandboxDir);
    return 0;
}
