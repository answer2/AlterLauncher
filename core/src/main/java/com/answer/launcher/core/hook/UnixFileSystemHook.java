package com.answer.launcher.core.hook;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.lsposed.hiddenapibypass.HiddenApiBypass;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

import com.answer.launcher.core.manager.IOManager;

/**
 * @Author AnswerDev
 * @Date 2024/04/13 08:40
 */

public class UnixFileSystemHook extends BaseHook{

    public void init() {
        try {
            Class<?> clazz = Class.forName("java.io.UnixFileSystem");
            
            Method canonicalize0 = HiddenApiBypass.getDeclaredMethod(clazz, "canonicalize0", String.class);
            Method getBooleanAttributes0 = HiddenApiBypass.getDeclaredMethod(clazz, "getBooleanAttributes0", String.class);
            Method getLastModifiedTime0 = HiddenApiBypass.getDeclaredMethod(clazz, "getLastModifiedTime0", File.class);
            Method setPermission0 = HiddenApiBypass.getDeclaredMethod(clazz, "setPermission0", File.class, int.class, boolean.class, boolean.class);
            Method createFileExclusively0 = HiddenApiBypass.getDeclaredMethod(clazz, "createFileExclusively0", String.class);
            Method list0 = HiddenApiBypass.getDeclaredMethod(clazz, "list0", File.class);
            Method createDirectory0 = HiddenApiBypass.getDeclaredMethod(clazz, "createDirectory0", File.class);
            Method setLastModifiedTime0 = HiddenApiBypass.getDeclaredMethod(clazz, "setLastModifiedTime0", File.class, long.class);
            Method setReadOnly0 = HiddenApiBypass.getDeclaredMethod(clazz, "setReadOnly0", File.class);
            Method getSpace0 = HiddenApiBypass.getDeclaredMethod(clazz, "getSpace0", File.class, int.class);
            //public static int setPermissions(String path, int mode, int uid, int gid) {
            Method setPermissions = HiddenApiBypass.getDeclaredMethod(Class.forName("android.os.FileUtils"), "setPermissions", String.class,int.class, int.class, int.class);
            Constructor File_S = File.class.getDeclaredConstructor(String.class);
            Constructor File_S_S = File.class.getDeclaredConstructor(String.class, String.class);
            
            MethodHook hook = new MethodHook(){
                @Override
                public void beforeCall(Pine.CallFrame params) throws Throwable {
                    super.beforeCall(params);
                    if (params.args[0] == null) return;
                    
                    if (params.args[0] instanceof String) {
                        String path = (String) params.args[0] ;
                        params.args[0] = IOManager.get().redirectPath(path);
                    } else if (params.args[0] instanceof File) {
                        File path = (File) params.args[0] ;
                        params.args[0] = IOManager.get().redirectPath(path);
                    }

                }
            };
            

            Pine.hook(canonicalize0, hook);
            Pine.hook(getBooleanAttributes0, hook);
            Pine.hook(getLastModifiedTime0, hook);
            Pine.hook(setPermission0, hook);
            Pine.hook(createFileExclusively0, hook);
            Pine.hook(list0, hook);
            Pine.hook(createDirectory0, hook);
            Pine.hook(setLastModifiedTime0, hook);
            Pine.hook(setReadOnly0, hook);
            Pine.hook(getSpace0, hook);
            Pine.hook(setPermissions, hook);
            Pine.hook(File_S, hook);
            Pine.hook(File_S_S, hook);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }


}
