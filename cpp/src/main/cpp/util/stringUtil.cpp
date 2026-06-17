#include <jni.h>
#include <string>
#include <regex>

#include <util/stringUtil.h>

using namespace std;


jstring toJstring(JNIEnv* env, const string& str) {
    return env->NewStringUTF(str.c_str());
}

const char* toChar(const string& str) {
    return str.c_str();
}

const char* jstringToCharArr(JNIEnv *env, jstring str) {
    return  env->GetStringUTFChars( str, 0);
}

jstring charArrToJstring(JNIEnv *env, const char *arr) {
    return  env->NewStringUTF(arr);
}

string charArrTostring(const char *arr){
    string str(arr);
    return str;
}

string replaceString(const string& str, const string& target,const string& replacement ){
    regex regex(target);
    string result = regex_replace(str, regex, replacement);

    return result;
}

bool stringcmp(const char *str1, const char *str2) {
    return strcmp(str1, str2) == 0;
}

jbyteArray encrypt(JNIEnv *env, int encryptKey, jbyteArray bytes) {
    if (bytes == NULL)return NULL;
    
    jbyte *jb_Array = env->GetByteArrayElements(bytes, 0);
    int length = env->GetArrayLength(bytes);
    int key = encryptKey;
    
    jbyteArray jb_result = env->NewByteArray(length);
    jbyte jb_buf[length];
    
    for (int i = 0; i < length; i++) {
        jb_buf[i] = (jbyte) (jb_Array[i] ^ key);
        key = jb_buf[i];
    }
    
    env->ReleaseByteArrayElements(bytes, jb_Array, 0);
    env->SetByteArrayRegion(jb_result, 0, length, jb_buf);
    return jb_result;
}


jbyteArray decrypt(JNIEnv *env, int decryptKey, jbyteArray bytes) {
    if (bytes == NULL)return NULL;
    
    jbyte *jb_Array = env->GetByteArrayElements(bytes, 0);
    int length = env->GetArrayLength(bytes);
    int key = decryptKey;
    
    jbyteArray jb_result = env->NewByteArray(length);
    jbyte jb_buf[length];
    
    for (int i = length; i > 0; i--) {
        jb_buf[i] = (jbyte) (jb_Array[i] ^ jb_Array[i - 1]);
    }
    
    jb_buf[0] = (jbyte) (jb_Array[0] ^ key);
    
    env->ReleaseByteArrayElements(bytes, jb_Array, 0);
    env->SetByteArrayRegion(jb_result, 0, length, jb_buf);
    return jb_result;
}



