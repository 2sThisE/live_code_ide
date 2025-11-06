package com.example.model;

import org.json.JSONObject;

public class UserProjectsInfo {
    private String pid;
    private String pname;
    private String owner;
    private String createdAt;
    private Boolean isShared;
    public UserProjectsInfo(JSONObject juser) {
        pid=juser.getString("projectID");
        pname=juser.getString("projectNAME");
        owner=juser.getString("owner");
        isShared=juser.getBoolean("isShared");
        createdAt=juser.getString("created_at");
    }
    public UserProjectsInfo(String juser) {
        this.pname=juser;
    }
    public String getProjectID() {
        return pid;
    }
    public Boolean getIsShared() {
        return isShared;
    }
    public String getProjectName() {
        return pname;
    }
    public String getOwner() {
        return owner;
    }
    public String getCreatedAt() {
        return createdAt;
    }
    @Override
    public String toString(){
        return pname;
    }
}
