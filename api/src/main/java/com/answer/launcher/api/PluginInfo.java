package com.answer.launcher.api;

import core.Constants;

/**
 * @Author AnswerDev
 * @Date 2024/07/11 21:16
 */
public class PluginInfo {
    private String name  = "undefined";
    private String author  = "undefined";
    private String version  = "undefined";
    private String description  = "undefined";

    private String supportVersion = Constants.MINECRAFT_VERSION;
    
    public PluginInfo(String name, String author, String version, String description) {
        this.name = name;
        this.author = author;
        this.version = version;
        this.description = description;
    }

    protected PluginInfo() {
        this.name = "undefined";
        this.author = "undefined";
        this.version = "undefined";
        this.description = "undefined";
    }
    
    public PluginInfo setSupportVersion(String version) {
    	this.supportVersion = version;
        return this;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String[] info() {
        return new String[]{name, author, version, description};
    }

    public static PluginInfo create(String[] args) {
        if (args.length != 4) {
            return new PluginInfo();
        }
        return new PluginInfo(args[0], args[1], args[2], args[3]);
    }
}

