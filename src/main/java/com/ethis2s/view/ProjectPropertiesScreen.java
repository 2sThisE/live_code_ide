package com.ethis2s.view;

import com.ethis2s.model.UserProjectsInfo;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;

public class ProjectPropertiesScreen {
    
    public GridPane creatProjectProperties(UserProjectsInfo userProjectsInfo) {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.TOP_LEFT);
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(25, 25, 25, 25));
        Label title=new Label(userProjectsInfo.getProjectName()+" 속성");
        title.setId("properties-title");
        grid.add(title, 0, 0,2,1);
        Label pIDL = new Label("프로젝트 아이디: ");
        Label pID = new Label(userProjectsInfo.getProjectID());
        grid.add(pIDL, 0, 1);
        grid.add(pID, 1, 1);
        Label pNameL = new Label("프로젝트 이름: ");
        Label pName = new Label(userProjectsInfo.getProjectName());
        grid.add(pNameL, 0, 2);
        grid.add(pName, 1, 2);
        Label ownerL = new Label("소유주: ");
        Label owner = new Label(userProjectsInfo.getOwner());
        grid.add(ownerL, 0, 3);
        grid.add(owner, 1, 3);
        Label createAtL = new Label("생성일: ");
        Label createAt = new Label(userProjectsInfo.getCreatedAt());
        grid.add(createAtL, 0, 4);
        grid.add(createAt, 1, 4);

        return grid;
    }
}
