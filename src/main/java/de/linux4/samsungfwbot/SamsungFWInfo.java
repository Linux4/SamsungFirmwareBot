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

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SamsungFWInfo {

    private static final String DOC_BASE_URL = "https://doc.samsungmobile.com/";
    private static final String DOC_NAME = "/doc.html";
    private static final String DOC_ENG = "/eng.html";
    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    private final String model;
    private final String region;
    private final String osVersion;
    private final String pda;
    private final Date buildDate;
    private final Date securitypatch;
    private final String name;
    private final String changelog;

    public SamsungFWInfo(String model, String region, String osVersion, String pda, Date buildDate, Date securitypatch, String name, String changelog) {
        this.model = model;
        this.region = region;
        this.osVersion = osVersion;
        this.pda = pda;
        this.buildDate = buildDate;
        this.securitypatch = securitypatch;
        this.name = name;
        this.changelog = changelog;
    }

    public String getModel() {
        return model;
    }

    public String getRegion() {
        return region;
    }

    public String getOSVersion() {
        return osVersion;
    }

    public String getPDA() {
        return pda;
    }

    public Date getBuildDate() {
        return buildDate;
    }

    public Date getSecurityPatch() {
        return securitypatch;
    }

    public String getDownloadURL() {
        return "https://samfw.com/firmware/" + model + "/" + region + "/" + pda;
    }

    public String getDeviceName() {
        return name;
    }

    public String getChangelog() {
        if (changelog.length() > 1024)
            return "The changelog is too large to display here.";
        return changelog;
    }

    private static int getPDAVersion(String pda) {
        int version = 0;

        for (int i = pda.length() - 4; i < pda.length() - 1; i++) {
            version += pda.charAt(i);
        }

        return version;
    }

    private static int getMinorVersion(String pda) {
        return pda.charAt(pda.length() - 1);
    }

    public boolean isNewerThan(String oldPDA) {
        return oldPDA.length() < 4 || (pda.length() >= 4 && (getPDAVersion(oldPDA) < getPDAVersion(pda)
                || (getPDAVersion(oldPDA) == getPDAVersion(pda) && getMinorVersion(oldPDA) < getMinorVersion(pda))));
    }

    @Override
    public String toString() {
        return "SamsungFW(" + model + ", " + region + ", " + osVersion + ", " + pda + ", " + DATE_FORMAT.format(buildDate)
                + ", " + DATE_FORMAT.format(securitypatch) + ")";
    }

    public static SamsungFWInfo fetchLatest(String model, String region) throws IOException, ParseException {
        try {
            Document doc = Jsoup.connect(DOC_BASE_URL + model + "/" + region + DOC_NAME).timeout(10*60*1000).get();

            Element input = doc.getElementById("dflt_page");

            if (input != null) {
                String magic = input.val().split("/")[3];

                Document changelog = Jsoup.connect(DOC_BASE_URL + model + "/" + magic + DOC_ENG).timeout(10*60*1000).get();
                Elements changelogEntries = changelog.getElementsByClass("row");

                if (changelogEntries.size() >= 2) {
                    Element latestEntry = changelogEntries.get(1); // 2nd "row" item is first changelog entry
                    Elements info = latestEntry.getElementsByClass("col-md-3");

                    if (info.size() >= 4) {
                        String pda = info.get(0).text().split(":")[1].strip();
                        String osVersion = info.get(1).text().split(":")[1].strip().replaceAll("\\(Android ", " (");
                        String releaseDate = info.get(2).text().split(":")[1].strip();
                        String securityPatch = info.get(3).text().split(":")[1].strip();
                        String name = "";
                        String changelogTxt = "";
                        Elements h1 = changelog.getElementsByTag("h1");

                        if (h1.size() > 0)
                            name = h1.get(0).text().split("\\(")[0].strip();

                        Elements changelogText = changelog.getElementsByTag("span");

                        if (changelogText.size() > 1)
                            changelogTxt = changelogText.get(1).html().replaceAll("<br>", "\n");

                        return new SamsungFWInfo(model, region, osVersion, pda, DATE_FORMAT.parse(releaseDate),
                                DATE_FORMAT.parse(securityPatch), name, changelogTxt);
                    }
                }
            }
        } catch (HttpStatusException ignored) {

        }

        return null;
    }

}
