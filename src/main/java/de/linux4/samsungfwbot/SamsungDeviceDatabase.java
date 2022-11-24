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
package de.linux4.samsungfwbot;

import java.sql.*;
import java.util.*;

public class SamsungDeviceDatabase {

    private static final String file = "db/devices.db";

    private Connection conn = null;

    public SamsungDeviceDatabase() {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file);

            conn.prepareStatement("CREATE TABLE IF NOT EXISTS devices (DeviceID INT, Name varchar(255), URL varchar(255), ImgURL varchar(255), ShortDescription varchar(255), PRIMARY KEY (DeviceID))").executeUpdate();
            conn.prepareStatement("CREATE TABLE IF NOT EXISTS models (DeviceID INT, Model varchar(255), FOREIGN KEY (DeviceID) REFERENCES devices(DeviceID), PRIMARY KEY (Model))").executeUpdate();
            conn.prepareStatement("CREATE TABLE IF NOT EXISTS regions (Model varchar(255), Region varchar(3), FOREIGN KEY (Model) REFERENCES models(Model))").executeUpdate();

            conn.prepareStatement("CREATE TABLE IF NOT EXISTS details (DeviceID INT, Category varchar(255), Name varchar(255), Value varchar(255), FOREIGN KEY (DeviceID) REFERENCES devices(DeviceID))").executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public void save(SamsungDeviceScraper.DeviceMeta deviceMeta) {
        try {
            // Delete old data
            PreparedStatement ps = conn.prepareStatement("SELECT Model FROM models WHERE DeviceID = " + deviceMeta.id);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String model = rs.getString("Model");

                ps = conn.prepareStatement("DELETE FROM regions WHERE Model = ?");
                ps.setString(1, model);
                ps.executeUpdate();
            }

            conn.prepareStatement("DELETE FROM details WHERE DeviceID = " + deviceMeta.id).executeUpdate();
            conn.prepareStatement("DELETE FROM models WHERE DeviceID = " + deviceMeta.id).executeUpdate();
            conn.prepareStatement("DELETE FROM devices WHERE DeviceID = " + deviceMeta.id).executeUpdate();

            // Insert new data
            ps = conn.prepareStatement("INSERT INTO devices (DeviceID, Name, URL, ImgURL, ShortDescription) "
                    + "VALUES (?, ?, ?, ?, ?)");
            ps.setInt(1, deviceMeta.id);
            ps.setString(2, deviceMeta.name);
            ps.setString(3, deviceMeta.url);
            ps.setString(4, deviceMeta.imgURL);
            ps.setString(5, deviceMeta.shortDescription);
            ps.executeUpdate();

            for (String model : deviceMeta.models) {
                ps = conn.prepareStatement("INSERT INTO models (DeviceID, Model) VALUES (?, ?)");
                ps.setInt(1, deviceMeta.id);
                ps.setString(2, model);
                ps.executeUpdate();
            }

            for (String model : deviceMeta.regions.keySet()) {
                for (String region : deviceMeta.regions.get(model)) {
                    ps = conn.prepareStatement("INSERT INTO regions (Model, Region) VALUES (?, ?)");
                    ps.setString(1, model);
                    ps.setString(2, region);
                    ps.executeUpdate();
                }
            }

            for (String category : deviceMeta.details.keySet()) {
                for (String property : deviceMeta.details.get(category).keySet()) {
                    ps = conn.prepareStatement("INSERT INTO details (DeviceID, Category, Name, Value) VALUES (?, ?, ?, ?)");
                    ps.setInt(1, deviceMeta.id);
                    ps.setString(2, category);
                    ps.setString(3, property);
                    ps.setString(4, deviceMeta.details.get(category).get(property));
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public SamsungDeviceScraper.DeviceMeta findById(int id) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM devices WHERE DeviceID = " + id);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                SamsungDeviceScraper.DeviceMeta meta = new SamsungDeviceScraper.DeviceMeta();
                meta.id = id;
                meta.name = rs.getString("Name");
                meta.url = rs.getString("URL");
                meta.imgURL = rs.getString("ImgURL");
                meta.shortDescription = rs.getString("ShortDescription");

                ps = conn.prepareStatement("SELECT Model FROM models WHERE DeviceID = " + id);
                rs = ps.executeQuery();
                while (rs.next()) {
                    meta.models.add(rs.getString("Model"));
                }

                for (String model : meta.models) {
                    meta.regions.put(model, new HashSet<>());
                    ps = conn.prepareStatement("SELECT Region FROM regions WHERE Model = ?");
                    ps.setString(1, model);
                    rs = ps.executeQuery();

                    while (rs.next()) {
                        meta.regions.get(model).add(rs.getString("Region"));
                    }
                }

                ps = conn.prepareStatement("SELECT * FROM details WHERE DeviceID = " + id);
                rs = ps.executeQuery();
                while (rs.next()) {
                    String category = rs.getString("Category");
                    String property = rs.getString("Name");
                    String value = rs.getString("Value");

                    Map<String, String> properties = meta.details.getOrDefault(category, new HashMap<>());
                    properties.put(property, value);
                    meta.details.put(category, properties);
                }

                return meta;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return null;
    }

    public Set<String> getAllModels() {
        Set<String> models = new HashSet<>();

        try {
            ResultSet rs = conn.prepareStatement("SELECT Model FROM models").executeQuery();

            while (rs.next()) {
                models.add(rs.getString("Model"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return models;
    }

    public Set<String> getRegionsByModel(String model) {
        Set<String> regions = new HashSet<>();

        try {
            PreparedStatement ps = conn.prepareStatement("SELECT Region FROM regions WHERE MODEL = ?");
            ps.setString(1, model);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                regions.add(rs.getString("Region"));
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        return regions;
    }
}
