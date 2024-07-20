#include <jni.h>
#include <string>
#include <iostream>

using namespace std;

#pragma once
#ifndef ALTER_NATIVE_STRING_H
#define ALTER_NATIVE_STRING_H


        jstring toJstring(JNIEnv* env, const string& str);
        const char* toChar(const string& str);

        const char* jstringToCharArr(JNIEnv *env, jstring str);

        jstring charArrToJstring(JNIEnv *env, const char *arr);

        string charArrTostring(const char *arr);

        string replaceString(const string& str, const string& target,const string& replacement);

        bool stringcmp(const char *str1, const char *str2);

       //by Gao
        jbyteArray encrypt(JNIEnv *env, int encryptKey, jbyteArray bytes);

        jbyteArray decrypt(JNIEnv *env, int decryptKey, jbyteArray bytes);

#endif //ALTER_NATIVE_STRING_H
