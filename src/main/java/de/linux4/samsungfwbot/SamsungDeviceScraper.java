package de.linux4.samsungfwbot;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.common.collect.Streams;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

public class SamsungDeviceScraper {
    private record DeviceMeta(
            String name,
            String url,
            String imgURL,
            String meta,
            Map<String, Map<String, String>> details) {
    }

    private static final String BASE_URL = "https://www.gsmarena.com/";
    private static final String DEVICES_LIST_URL = BASE_URL + "samsung-phones-f-9-0-p%d.php";
    private static final String MODELS_FILE_NAME = "devices.txt";
    private static final int FETCH_TIMEOUT = 1 * 60 * 1000;
    private static final int FETCH_INTERVAL = 3 * 1000;

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static Document request(String url) throws IOException {
        sleep(FETCH_INTERVAL);
        return Jsoup.connect(url).timeout(FETCH_TIMEOUT).get();
    }

    private static List<DeviceMeta> fetchPage(int pageNumber) {
        try {
            Document doc = request(String.format(DEVICES_LIST_URL, pageNumber));
            Elements el = doc.select("#review-body > div.makers > ul > li");
            return el.stream().map(element -> {
                String name = element.select("a > strong > span").first().text();
                String url = element.select("a").first().attributes().get("href");
                String imgURL = element.select("a > img").first().attributes().get("src");
                String meta = element.select("a > img").first().attributes().get("title");
                return new DeviceMeta(name, url, imgURL, meta, new HashMap<>());
            }).toList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    private static Set<String> getNormalizedModels(DeviceMeta deviceMeta) {
        String models = deviceMeta.details.getOrDefault("Misc", new HashMap<>()).getOrDefault("Models", "");
        String[] splitModels = models.split(",");
        Set<String> set = new HashSet<>();
        for (String model : splitModels) {
            set.add(model.trim().split("/")[0]);
        }
        Set<String> removeModels = new HashSet<>();
        for (String model : set) {
            // // remove models ending with N when same model ending with F exists
            // if (model.endsWith("N") && set.contains(model.substring(0, model.length() -
            // 1).concat("F")))
            // removeModels.add(model);
            // // remove models ending with N when same model ending with B exists
            // if (model.endsWith("N") && set.contains(model.substring(0, model.length() -
            // 1).concat("B")))
            // removeModels.add(model);
            // // remove models ending with V,U when same model ending with U1 exists
            // if ((model.endsWith("V") || model.endsWith("U"))
            // && set.contains(model.substring(0, model.length() - 1).concat("U1")))
            // removeModels.add(model);
            if (!model.startsWith("SM-"))
                removeModels.add(model);
        }
        removeModels.forEach(model -> set.remove(model));
        return set;
    }

    private static String getModelSupername(DeviceMeta deviceMeta) {
        List<String> models = new ArrayList<>(getNormalizedModels(deviceMeta));
        if (models.size() == 0)
            return "";
        return models.stream().reduce((x, y) -> {
            int i = 0;
            for (i = 0; i < Math.min(x.length(), y.length()); i++) {
                if (x.charAt(i) != y.charAt(i))
                    break;
            }
            return x.substring(0, i);
        }).get();
    }

    private static boolean isDeviceRelevant(DeviceMeta deviceMeta) {
        if (deviceMeta.details.size() == 0)
            return false;
        if (getNormalizedModels(deviceMeta).size() == 0)
            return false;
        if (getNormalizedModels(deviceMeta).stream().allMatch(model -> model.startsWith("SM-")))
            return true;
        return false;
    }

    private static DeviceMeta fillDetails(DeviceMeta deviceMeta) {
        try {
            Document doc = request(BASE_URL + deviceMeta.url);
            Elements tables = doc.select("#specs-list > table");
            tables.forEach(table -> {
                String category = table.select("tbody > tr:nth-child(1) > th").text();
                Elements tableRows = table.select("tbody > tr");
                Map<String, String> innerMap = deviceMeta.details.getOrDefault(category, new HashMap<>());
                tableRows.forEach(row -> {
                    String header = row.select("td.ttl").text();
                    String content = row.select("td.nfo").text();
                    innerMap.put(header, content);
                });
                deviceMeta.details.put(category, innerMap);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return deviceMeta;
    }

    public static void main(String[] args) throws IOException {
        Document doc = request(String.format(DEVICES_LIST_URL, 1));
        int pagesCount = Integer.parseInt(
                doc.select("#body > div > div.review-nav.pullNeg.col.pushT10 > div.nav-pages > a").last().text());
        System.out.println("Pages=" + pagesCount);
        List<DeviceMeta> devices = Stream.iterate(1, n -> n + 1).limit(pagesCount)
                .peek(i -> System.out.println("Fetching page " + i)).map(SamsungDeviceScraper::fetchPage)
                .reduce((x, y) -> Streams.concat(x.stream(), y.stream()).toList()).get();
        System.out.println("Total Devices = " + devices.size());
        devices = devices.stream().filter(device -> device.name.contains("Galaxy")).toList();
        devices = devices.stream().filter(device -> !device.name.contains("Watch")).toList();
        System.out.println("Filtered Devices (Stage 1) = " + devices.size());
        for (int i = 0; i < devices.size(); i++) {
            DeviceMeta device = devices.get(i);
            System.out.println(String.format("Fetching device details for %s (%d/%d)", device.name,
                    i, devices.size()));
            fillDetails(device);
        }
        devices = devices.stream().filter(SamsungDeviceScraper::isDeviceRelevant).toList();
        System.out.println("Filtered Devices (Stage 2) = " + devices.size());
        devices = devices.stream().sorted((x, y) -> getModelSupername(x).compareTo(getModelSupername(y))).toList();
        try {
            writeModelsToFile(devices);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void writeModelsToFile(List<DeviceMeta> devices) throws IOException {
        File file = new File(MODELS_FILE_NAME);
        if (file.exists())
            file.delete();
        file.createNewFile();
        PrintWriter writer = new PrintWriter(new FileWriter(file));
        devices.forEach(device -> {
            getNormalizedModels(device).stream().sorted().forEach(model -> writer.println(model));
        });
        writer.close();
    }
}
