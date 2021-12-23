package de.linux4.samsungfwbot;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class SamsungDeviceDatabase {

    private static String file = "samsungdevices.db";

    private Connection conn = null;

    public SamsungDeviceDatabase() {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file);

            conn.prepareStatement("CREATE TABLE IF NOT EXISTS pda (Model varchar(255), PDA varchar(255))")
                    .executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }
    
}