/*
  Copyright (C) 2021  Tim Zimmermann <tim@linux4.de>

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
package de.linux4.samsungfwbot.kernel;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SamsungKernelDatabase {

    private static final String file = "samsungkernel.db";
    private Connection conn = null;

    public SamsungKernelDatabase() {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file);

            conn.prepareStatement("CREATE TABLE IF NOT EXISTS pda (Model varchar(255) NOT NULL, PDA varchar(255) NOT NULL, UploadID varchar(255), PatchKernel varchar(255), GHRelease varchar(255), TGMessageID BIGINT, Timestamp BIGINT NOT NULL)").executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public List<String> getAllModels() {
        List<String> models = new ArrayList<>();

        try {
            PreparedStatement ps = conn.prepareStatement("SELECT Model FROM pda");
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                models.add(rs.getString("Model"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return models;
    }

    public String getLatestPDA(String model) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT PDA FROM pda WHERE Model = ? ORDER BY Timestamp DESC LIMIT 1");
            ps.setString(1, model);
            ResultSet rs = ps.executeQuery();

            if (rs.next())
                return rs.getString("PDA");
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        return "";
    }

    public String getUploadID(String pda) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT UploadID FROM pda WHERE PDA = ?");
            ps.setString(1, pda);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                return rs.getString("UploadID");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return null;
    }

    public String getPatchKernel(String pda) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT PatchKernel FROM pda WHERE PDA = ?");
            ps.setString(1, pda);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                return rs.getString("PatchKernel");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return null;
    }

    public String getGHRelease(String pda) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT GHRelease FROM pda WHERE PDA = ?");
            ps.setString(1, pda);
            ResultSet rs = ps.executeQuery();

            if (rs.next())
                return rs.getString("GHRelease");
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return null;
    }

    public long getTGMessageID(String pda) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT TGMessageID FROM pda WHERE PDA = ?");
            ps.setString(1, pda);
            ResultSet rs = ps.executeQuery();

            if (rs.next())
                return rs.getLong("TGMessageID");
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return 0;
    }

    public void addPDA(String model, String pda, String uploadId, String patchKernel) {
        try {
            PreparedStatement ps = conn.prepareStatement("INSERT INTO pda (Model, PDA, UploadID, PatchKernel, Timestamp) VALUES (?, ?, ?, ?, ?)");

            ps.setString(1, model);
            ps.setString(2, pda);
            ps.setString(3, uploadId);
            ps.setString(4, patchKernel);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public void setGHRelease(String pda, String release) {
        try {
            PreparedStatement ps = conn.prepareStatement("UPDATE pda SET GHRelease = ? WHERE PDA = ?");

            ps.setString(1, release);
            ps.setString(2, pda);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void setTGMessageID(String pda, long messageId) {
        try {
            PreparedStatement ps = conn.prepareStatement("UPDATE pda SET TGMessageID = ? WHERE PDA = ?");

            ps.setLong(1, messageId);
            ps.setString(2, pda);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

}
