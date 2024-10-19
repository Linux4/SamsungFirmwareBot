/*
  Copyright (C) 2021-2024  Tim Zimmermann <tim@linux4.de>

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as
  published by the Free Software Foundation, either version 3 of the
  License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.linux4.samsungfwbot;

import java.sql.*;

public class SamsungFWDatabase {

    private Connection conn = null;

    public SamsungFWDatabase(String file) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file);

            conn.prepareStatement("CREATE TABLE IF NOT EXISTS pda (Model varchar(255), PDA varchar(255))").executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public String getPDA(String model) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT PDA FROM pda WHERE Model LIKE ?");
            ps.setString(1, model);
            ResultSet rs = ps.executeQuery();

            if (rs.next())
                return rs.getString("PDA");
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        return "";
    }

    private boolean checkModelExists(String model) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT PDA FROM pda WHERE Model LIKE ?");
            ps.setString(1, model);
            ResultSet rs = ps.executeQuery();

            return rs.next();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return false;
    }

    public void setPDA(String model, String pda) {
        try {
            PreparedStatement ps;

            // does not yet exist in db
            if (!checkModelExists(model))
                ps = conn.prepareStatement("INSERT INTO pda (PDA, Model) VALUES (?, ?)");
            else
                ps = conn.prepareStatement("UPDATE pda SET PDA = ? WHERE Model LIKE ?");

            ps.setString(1, pda);
            ps.setString(2, model);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

}
