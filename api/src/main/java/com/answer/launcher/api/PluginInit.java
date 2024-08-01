package com.answer.launcher.api;

/**
 * @Author AnswerDev
 * @Date 2024/07/11 21:18
 */
public interface PluginInit {

    public void load(LauncherInfo info);

    public PluginInfo initInfo();

}
