package com.answer.launcher.core.tool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import android.util.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;

/**
 * @Author AnswerDev
 * @Date 2024/07/10 11:36
 */
public class FileUtil {

    public static void copy(File origin, String target) {
        try {
            FileInputStream fis = new FileInputStream(origin);
            FileOutputStream fos = new FileOutputStream(new File(target));
            BufferedInputStream bufferedInput = new BufferedInputStream(fis);
            BufferedOutputStream bufferedOutput = new BufferedOutputStream(fos);

            byte[] buffer = new byte[8192];
            int count;
            while ((count = bufferedInput.read(buffer)) > 0) {
                bufferedOutput.write(buffer, 0, count);
            }
            bufferedOutput.flush();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


}
