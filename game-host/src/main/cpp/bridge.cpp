#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <unistd.h>
static void *game;
using Dispatch=void (*)(JNIEnv*,jobject,jstring,jobject);
static Dispatch call;
extern "C" JNIEXPORT void JNICALL Java_dev_mcpy_game_NativeBridge_initialize(JNIEnv *env,jclass,jstring path) {
 const char *p=env->GetStringUTFChars(path,nullptr);game=dlopen(p,RTLD_NOW|RTLD_NOLOAD);env->ReleaseStringUTFChars(path,p);
 if(game)call=reinterpret_cast<Dispatch>(dlsym(game,"Java_com_mojang_minecraftpe_MainActivity_nativeJsCall"));
 if(!call){const char *error=dlerror();env->ThrowNew(env->FindClass("java/lang/UnsatisfiedLinkError"),error?error:"Missing game Python bridge");}
 else __android_log_print(ANDROID_LOG_INFO,"McpyBridge","Resolved original Python JNI bridge");
}
extern "C" JNIEXPORT void JNICALL Java_dev_mcpy_game_NativeBridge_dispatch(JNIEnv *env,jclass,jobject activity,jstring message,jobject callback) { if(call)call(env,activity,message,callback); }

extern "C" JNIEXPORT void JNICALL Java_dev_mcpy_game_NativeBridge_setWorkingDirectory(JNIEnv *env,jclass,jstring path){
 const char *p=env->GetStringUTFChars(path,nullptr);int result=chdir(p);env->ReleaseStringUTFChars(path,p);
 if(result)env->ThrowNew(env->FindClass("java/io/IOException"),"Cannot select game working directory");
}
