package mom.fuckers.stoatbridge.api;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

public class StoatDatabase {
    private final File dbFile;
    private final Logger logger;
    private Connection connection;

    public StoatDatabase(File dataFolder, Logger logger) {
        this.dbFile = new File(dataFolder, "data.db");
        this.logger = logger;
    }

    public void initialize() {
        if (!dbFile.getParentFile().exists()) {
            dbFile.getParentFile().mkdirs();
        }

        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS user_mappings (" +
                        "username TEXT PRIMARY KEY," +
                        "stoat_id TEXT NOT NULL" +
                        ")");
                statement.execute("CREATE TABLE IF NOT EXISTS id_mappings (" +
                        "stoat_id TEXT PRIMARY KEY," +
                        "username TEXT NOT NULL" +
                        ")");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_username ON user_mappings(username)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_stoat_id ON id_mappings(stoat_id)");
            }
        } catch (ClassNotFoundException | SQLException e) {
            logger.severe("Could not initialize SQLite database: " + e.getMessage());
        }
    }

    public synchronized String getUserId(String username) {
        String sql = "SELECT stoat_id FROM user_mappings WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.toLowerCase());
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("stoat_id");
            }
        } catch (SQLException e) {
            logger.warning("Error fetching User ID for " + username + ": " + e.getMessage());
        }
        return null;
    }

    public synchronized String getUsername(String stoatId) {
        String sql = "SELECT username FROM id_mappings WHERE stoat_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, stoatId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("username");
            }
        } catch (SQLException e) {
            logger.warning("Error fetching username for ID " + stoatId + ": " + e.getMessage());
        }
        return null;
    }

    public synchronized void updateMapping(String username, String stoatId) {
        String sqlUser = "INSERT OR REPLACE INTO user_mappings(username, stoat_id) VALUES(?, ?)";
        String sqlId = "INSERT OR REPLACE INTO id_mappings(stoat_id, username) VALUES(?, ?)";
        try {
            try (PreparedStatement pstmt = connection.prepareStatement(sqlUser)) {
                pstmt.setString(1, username.toLowerCase());
                pstmt.setString(2, stoatId);
                pstmt.executeUpdate();
            }
            try (PreparedStatement pstmt = connection.prepareStatement(sqlId)) {
                pstmt.setString(1, stoatId);
                pstmt.setString(2, username);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("Error updating mapping for " + username + ": " + e.getMessage());
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.warning("Error closing database connection: " + e.getMessage());
        }
    }
}
