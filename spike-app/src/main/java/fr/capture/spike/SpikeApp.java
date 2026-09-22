package fr.capture.spike;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Minimal raw-JDBC workload used to validate the agent spike: create a table, insert a few rows
 * (prepared statement), read them back, and a manual transaction with a commit. Deliberately
 * plain Java 8 so the exact same jar runs under eclipse-temurin:8 and eclipse-temurin:25.
 */
public final class SpikeApp {

    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:mem:spike;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE person(id INT PRIMARY KEY, name VARCHAR(100))");
            }

            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO person(id, name) VALUES (?, ?)")) {
                for (int i = 1; i <= 3; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "name-" + i);
                    ps.executeUpdate();
                }
            }
            c.commit();

            int seen = 0;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, name FROM person ORDER BY id")) {
                while (rs.next()) {
                    seen++;
                    System.out.println("row: " + rs.getInt("id") + " / " + rs.getString("name"));
                }
            }
            System.out.println("SpikeApp done, rows read = " + seen);
        }
    }
}
