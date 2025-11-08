package com.ethis2s.model;

public class UserInfo {
    private String id;
    private String nickname;
    private String tag;
    public UserInfo(String id, String nickname, String tag) {
        this.id = id;
        this.nickname = nickname;
        this.tag = tag;
    }
    public String getId() {
        return id;
    }
    public String getNickname() {
        return nickname;
    }
    public String getTag() {
        return tag;
    }
    
}
