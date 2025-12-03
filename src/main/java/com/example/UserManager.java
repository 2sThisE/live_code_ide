    package com.example;

    import java.sql.Connection;
    import java.sql.PreparedStatement;
    import java.sql.ResultSet;
    import java.sql.SQLException;
    import java.sql.SQLIntegrityConstraintViolationException;

    import org.json.JSONArray;
    import org.json.JSONObject;
    import org.mindrot.jbcrypt.BCrypt;

    public class UserManager {

        private final Connection connection;

        public UserManager(Connection connection) {
            this.connection = connection;
        }

        /**
         * 새로운 사용자를 등록합니다.
         * 비밀번호는 Bcrypt를 사용하여 해싱됩니다.
         * @param username 등록할 사용자 이름
         * @param password 등록할 비밀번호 (평문)
         * @return 사용자 등록 성공 시 true, 실패 시 (예: 사용자 이름 중복) false
         * @throws SQLException 데이터베이스 작업 중 오류 발생 시
         */
        public String signup(String id, String nickname, String tag, String password) throws SQLException {
            // 1. 닉네임과 태그 조합 중복 확인
            if (isUserExists(nickname, tag)) {
                System.out.println("닉네임과 태그 조합이 이미 존재합니다: " + nickname + "#" + tag);
                return "USERNAME_TAG_EXISTS";
            }

            // 2. ID 중복 확인 (ID는 고유해야 함)
            if (isIdExists(id)) {
                System.out.println("ID가 이미 존재합니다: " + id);
                return "ID_EXISTS";
            }

            // 3. 비밀번호 해싱
            String hashedPassword = hashPassword(password);

            // 4. 데이터베이스에 사용자 정보 저장
            String sql = "INSERT INTO users (id, nickname, tag, pwd) VALUES (?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                statement.setString(2, nickname);
                statement.setString(3, tag);
                statement.setString(4, hashedPassword);
                int rowsAffected = statement.executeUpdate();
                if (rowsAffected > 0) {
                    return "SUCCESS";
                } else {
                    return "DB_INSERT_FAILED";
                }
            }
        }

        /**
         * 사용자를 로그인합니다.
         * @param username 로그인할 사용자 이름
         * @param password 입력된 비밀번호 (평문)
         * @return 로그인 성공 시 true, 실패 시 false
         * @throws SQLException 데이터베이스 작업 중 오류 발생 시
         */
        public boolean login(String id, String password) throws SQLException {
            String sql = "SELECT pwd FROM users WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        String hashedPassword = resultSet.getString("pwd");
                        // 입력된 비밀번호와 저장된 해시된 비밀번호 비교
                        return checkPassword(password, hashedPassword);
                    } else {
                        // 사용자 이름이 존재하지 않음
                        return false;
                    }
                }
            }
        }

        /**
         * 주어진 사용자 이름이 데이터베이스에 존재하는지 확인합니다.
         * @param username 확인할 사용자 이름
         * @return 존재하면 true, 그렇지 않으면 false
         * @throws SQLException 데이터베이스 작업 중 오류 발생 시
         */
        private boolean isUserExists(String nickname, String tag) throws SQLException {
            String sql = "SELECT COUNT(*) FROM users WHERE nickname = ? AND tag = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, nickname);
                statement.setString(2, tag);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt(1) > 0;
                    }
                }
            }
            return false;
        }

        public JSONArray getProjectList(String id) throws SQLException{
            String sql = "SELECT * FROM projects WHERE user_id = ?";
            JSONArray jArray=new JSONArray();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while(resultSet.next()){
                        JSONObject j=new JSONObject();
                        j.put("projectID", resultSet.getString("id"));
                        j.put("projectNAME", resultSet.getString("name"));
                        j.put("owner", resultSet.getString("user_id"));
                        j.put("created_at", resultSet.getString("created_at"));
                        j.put("isShared", false);
                        jArray.put(j);
                    }
                }
            }
            String sql1 = """
                SELECT p.*
                FROM projects p
                JOIN shares s ON p.id = s.project_id
                WHERE s.shared_user_id = ?
            """;
            try (PreparedStatement statement = connection.prepareStatement(sql1)) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while(resultSet.next()){
                        JSONObject j=new JSONObject();
                        j.put("projectID", resultSet.getString("id"));
                        j.put("projectNAME", resultSet.getString("name"));
                        j.put("owner", resultSet.getString("user_id"));
                        j.put("created_at", resultSet.getString("created_at"));
                        j.put("isShared", true);
                        jArray.put(j);
                    }
                }
            }
            return jArray;
        }

        /**
         * 주어진 ID가 데이터베이스에 존재하는지 확인합니다.
         * @param id 확인할 ID
         * @return 존재하면 true, 그렇지 않으면 false
         * @throws SQLException 데이터베이스 작업 중 오류 발생 시
         */
        private boolean isIdExists(String id) throws SQLException {
            String sql = "SELECT COUNT(*) FROM users WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt(1) > 0;
                    }
                }
            }
            return false;
        }

        /**
         * 비밀번호를 Bcrypt로 해싱합니다.
         * @param password 평문 비밀번호
         * @return 해시된 비밀번호
         */
        private String hashPassword(String password) {
            return BCrypt.hashpw(password, BCrypt.gensalt());
        }

        /**
         * 평문 비밀번호와 해시된 비밀번호를 비교합니다.
         * @param candidatePassword 평문 비밀번호
         * @param hashedPassword 해시된 비밀번호
         * @return 일치하면 true, 그렇지 않으면 false
         */
        private boolean checkPassword(String candidatePassword, String hashedPassword) {
            return BCrypt.checkpw(candidatePassword, hashedPassword);
        }

        public Connection getConnection() {
            return this.connection;
        }

        /**
         * ID로 사용자 정보를 검색합니다.
         * @param id 검색할 사용자 ID
         * @return 해당 ID의 UserInfo 객체, 없으면 null
         * @throws SQLException 데이터베이스 작업 중 오류 발생 시
         */
        public UserInfo getUserInfoById(String id) throws SQLException {
            String sql = "SELECT id, nickname, tag FROM users WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        String nickname = resultSet.getString("nickname");
                        String tag = resultSet.getString("tag");
                        return new UserInfo(id, nickname, tag);
                    }
                }
            }
            return null;
        }

        public UserInfo getUserInfoByTag(String nick, String tag) throws SQLException {
            String sql = "SELECT id, nickname, tag FROM users WHERE nickname = ? and tag = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, nick);
                statement.setString(2, tag);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) return new UserInfo(resultSet.getString("id"), nick, tag);
                }
            }
            return null;
        }

        public boolean addShare(String nick, String tag, String project_id) throws SQLException{
            String sql = """
            INSERT INTO shares (project_id, owner, shared_user_id)
            SELECT ?, p.user_id, ?
            FROM projects p
            WHERE p.id = ?;
            """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                UserInfo uid = getUserInfoByTag(nick, tag);
                if (uid == null) return false; // 공유 대상 유저가 없으면 실패

                statement.setString(1, project_id);
                statement.setString(2, uid.getId()); // uid 변수를 재사용하여 NullPointerException 방지
                statement.setString(3, project_id);
                int affectedRows = statement.executeUpdate(); // INSERT 수행
                return affectedRows > 0; // 한 행 이상 삽입되면 true
            }catch(SQLIntegrityConstraintViolationException e){
                // 디버깅: 데이터베이스 제약 조건 위반 시 상세 에러를 콘솔에 출력
                System.err.println("### 공유 추가 실패: 데이터베이스 제약 조건 위반 ###");
                e.printStackTrace();
                System.err.println("#############################################");
                return false;
            }
        }

        public boolean createNewProject(String name, String userId) throws SQLException {
            String sql = "INSERT INTO projects(user_id, name) VALUES(?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, userId);
                statement.setString(2, name);
                int affectedRows = statement.executeUpdate(); // INSERT 수행
                return affectedRows > 0; // 한 행 이상 삽입되면 true
            }catch(SQLIntegrityConstraintViolationException e){
                return false;
            }
        }
        
        public boolean deleteProject(String pID, String id) throws SQLException{
            if(checkVerification(pID, null, id)){
                String sql = "DELETE FROM projects WHERE id=? AND user_id=?";
                System.out.println("Deleting project with id=" + pID + ", user_id=" + id);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, pID);
                    statement.setString(2, id);
                    int affectedRows = statement.executeUpdate(); // INSERT 수행
                    return affectedRows > 0; // 한 행 이상 삽입되면 true
                }
            }return false;
        }
        public JSONObject getProjectINFO(String id, String name, String pID) throws SQLException{
            String sql = "SELECT * FROM projects WHERE user_id = ? AND (name = ? OR id = ?)";
            JSONObject json=new JSONObject();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                statement.setString(2, name);
                statement.setString(3, pID);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if(resultSet.next()){
                        json.put("project_id", resultSet.getString("id"));
                        json.put("user_id", resultSet.getString("user_id"));
                        json.put("name", resultSet.getString("name"));
                        json.put("created_at", resultSet.getString("created_at"));
                    }
                }
                System.out.println(json.toString());
                return json;
            }
        }
        public boolean checkVerification(String project_id, UserInfo uesrinfo, String id) throws SQLException{
            String sql = "SELECT COUNT(*) FROM projects WHERE id = ? AND user_id = ?";
        
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, project_id);
                statement.setString(2, uesrinfo==null?id:uesrinfo.getId());
                
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        int count = rs.getInt(1);
                        return count > 0; // 존재하면 true
                    }
                }
            }
            return false; // 결과가 없거나 예외가 발생한 경우
        }

        public boolean checkShareVerification(String project_id, UserInfo uesrinfo, String id) throws SQLException{
            String sql = "SELECT COUNT(*) FROM shares WHERE project_id = ? AND shared_user_id = ?";
        
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, project_id);
                statement.setString(2, uesrinfo==null?id:uesrinfo.getId());
                
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        int count = rs.getInt(1);
                        return count > 0; // 존재하면 true
                    }
                }
            }
            return false; // 결과가 없거나 예외가 발생한 경우
        }

        public boolean deleteShare(String nick, String tag, String project_id) throws SQLException{
            String sql = "DELETE FROM shares WHERE project_id = ? AND shared_user_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, project_id);
                statement.setString(2, getUserInfoByTag(nick, tag).getId());
                int affectedRows = statement.executeUpdate(); // INSERT 수행
                return affectedRows > 0; // 한 행 이상 삽입되면 true
            }
        }

        public JSONObject getSharedList(String pID, UserInfo userInfo) throws SQLException {
            String sql = """
                SELECT u.nickname, u.tag
                FROM shares s
                JOIN users u ON s.shared_user_id = u.id
                WHERE s.project_id = ? AND s.owner = ?
                """;

            JSONObject jArray = new JSONObject();
            jArray.put("project_id", pID);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, pID);
                statement.setString(2, userInfo.getId());

                try (ResultSet rs = statement.executeQuery()) {
                    JSONArray t=new JSONArray();
                    while (rs.next()) {
                        JSONObject j = new JSONObject();
                        j.put("nickname", rs.getString("nickname"));
                        j.put("tag", rs.getString("tag"));
                        t.put(j);
                    }
                    jArray.put("shared_with",t);
                }
            }

            return jArray;
        }

        /**
         * 주어진 프로젝트 ID가 특정 사용자 ID에 의해 소유되었는지 확인합니다.
         * @param projectId 확인할 프로젝트 ID
         * @param ownerId 프로젝트의 소유자로 주장되는 사용자 ID
         * @return 프로젝트가 ownerId에 의해 소유되었다면 true, 그렇지 않으면 false
         * @throws SQLException 데이터베이스 작업 중 오류 발생 시
         */
        public boolean verifyProjectOwner(String projectId, String ownerId) throws SQLException {
            String sql = "SELECT COUNT(*) FROM projects WHERE id = ? AND user_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, projectId);
                statement.setString(2, ownerId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt(1) > 0;
                    }
                }
            }
            return false;
        }
    }