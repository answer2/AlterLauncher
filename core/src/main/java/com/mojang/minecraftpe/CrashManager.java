package com.mojang.minecraftpe;
import java.io.File;

public class CrashManager {
    public CrashManager(CrashManagerOwner crashManagerOwner, String str, String str2, SentryEndpointConfig sentryEndpointConfig, SessionInfo sessionInfo) {
        
    }
    
    public String getCrashUploadURI() {
        return "http://localhost";
    }

    public String getExceptionUploadURI() {
        return "http://localhost";
    }

    private String uploadCrashFile(String var1, String var2, String var3) {
        return null;
    }

    private static native String nativeNotifyUncaughtException();

    public CrashManager(String str, String str2, SentryEndpointConfig sentryEndpointConfig) {
    }

    public void installGlobalExceptionHandler() {

    }
    
    public void handleUncaughtException(Thread thread, Throwable th) {
    }

    private Object uploadException(File file) {
        return null;
    }

    private static Object uploadDump(File file, String str, String str2, String str3) {
        return null;
    }
    
    
    public void handlePreviousDumps() {
        
    }
    
    public void handlePreviousDumpsWorkerThread() {
        
    }
    
    public static String createLogFile(String str, String str2, String str3, String str4) {
        return null;
    }
    
    private static void deleteWithLogging(File file) {
        
    }

}
