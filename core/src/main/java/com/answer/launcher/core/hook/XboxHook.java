package com.answer.launcher.core.hook;

import android.util.Log;
import com.answer.launcher.core.manager.MinecraftManager;
import com.microsoft.xal.browser.BrowserSelector;
import com.microsoft.xbox.idp.interop.Interop;
import java.lang.reflect.Field;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.canyie.pine.callback.MethodReplacement;
import android.content.*;
import static com.answer.launcher.core.tool.SystemUtil.checkClass;
/**
 * @Author AnswerDev
 * @Date 2024/07/06 19:39
 */
public class XboxHook extends BaseHook{

    public void init() {

        try {
            if(checkClass("com.microsoft.xal.browser.BrowserSelector")){
            Pine.hook(BrowserSelector.class.getDeclaredMethod("browserInfoImpliesNoUserDefault", Class.forName("com.microsoft.xal.browser.BrowserSelectionResult$BrowserInfo")),
                new MethodReplacement(){
                    @Override
                    protected Object replaceCall(Pine.CallFrame callFrame) throws Throwable {
                        if (callFrame.args[0] == null)return true;

                        Class<?> clazz = Class.forName("com.microsoft.xal.browser.BrowserSelectionResult$BrowserInfo");

                        Field codef = clazz.getDeclaredField("versionCode");
                        Field namef = clazz.getDeclaredField("versionName");
                        codef.setAccessible(true);
                        codef.setAccessible(true);

                        int versionCode =(int) codef.get(callFrame.args[0]);
                        String versionName = (String) namef.get(callFrame.args[0]);

                        if (versionName == null) return true;

                        return versionCode == 0 && "none".equals(versionName);
                    }
                });
                }
            
            //F answer.launche: java_vm_ext.cc:577]   at java.lang.String com.microsoft.xbox.idp.interop.Interop.ReadConfigFile(android.content.Context) (Interop.java:158)
            // For 1.14 xbox resources bug
            if(checkClass("com.microsoft.xbox.idp.interop.Interop")){
                Log.d("XboxHook", "ReadConfigFile");
            Pine.hook(Class.forName("com.microsoft.xbox.idp.interop.Interop").getDeclaredMethod("ReadConfigFile", Context.class),
                new MethodHook(){
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
                            Log.d("XboxHook", "ReadConfigFile Start");
                        callFrame.args[0] = MinecraftManager.getManager().getContext();
                    }
                });
            }
            
                
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


}
