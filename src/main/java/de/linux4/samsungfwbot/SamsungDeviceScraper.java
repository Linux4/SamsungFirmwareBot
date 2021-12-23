package de.linux4.samsungfwbot;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.collect.Streams;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

public class SamsungDeviceScraper {
    protected static class DeviceMeta {
        int id = -1;
        String name = null;
        String url = null;
        String imgURL = null;
        String shortDescription = null;
        Map<String, Map<String, String>> details = new HashMap<>();
        String modelSupername = null;
        Set<String> models = new HashSet<>();
        Map<String, Set<String>> regions = new HashMap<>();
    }

    private static final String GSMARENA_BASE_URL = "https://www.gsmarena.com/";
    private static final String SAMFW_BASE_URL = "https://samfw.com/";
    private static final String DEVICES_LIST_URL = GSMARENA_BASE_URL + "samsung-phones-f-9-0-p%d.php";
    private static final String REGIONS_URL = SAMFW_BASE_URL + "firmware/%s";
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
                DeviceMeta deviceMeta = new DeviceMeta();
                deviceMeta.name = element.select("a > strong > span").first().text();
                deviceMeta.url = element.select("a").first().attributes().get("href");
                deviceMeta.id = Integer.parseInt(deviceMeta.url.substring(deviceMeta.url.lastIndexOf("-") + 1,
                        deviceMeta.url.lastIndexOf(".php")));
                deviceMeta.imgURL = element.select("a > img").first().attributes().get("src");
                deviceMeta.shortDescription = element.select("a > img").first().attributes().get("title");
                return deviceMeta;
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
            if (model.contains(" "))
                removeModels.add(model);
            if (!model.startsWith("SM-"))
                removeModels.add(model);
        }
        removeModels.forEach(model -> set.remove(model));
        return set;
    }

    private static String getModelSupername(DeviceMeta deviceMeta) {
        if (deviceMeta.models.size() == 0)
            return "";
        return deviceMeta.models.stream().reduce((x, y) -> {
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
            Document doc = request(GSMARENA_BASE_URL + deviceMeta.url);
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
            deviceMeta.models.addAll(getNormalizedModels(deviceMeta));
            deviceMeta.modelSupername = getModelSupername(deviceMeta);
            deviceMeta.models.forEach(model -> {
                try {
                    Document document = request(String.format(REGIONS_URL, model));
                    Elements regionElements = document.select(
                            "body > div.intro.bg-light > div > div > div > div > div.card-body.text-justify.card-csc > div.item_csc > a > b");
                    Set<String> regionSet = regionElements.stream().map(element -> element.text())
                            .collect(Collectors.toSet());
                    deviceMeta.regions.put(model, regionSet);
                } catch (IOException e) {
                    e.printStackTrace();
                }
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
                    i + 1, devices.size()));
            fillDetails(device);
        }
        devices = devices.stream().filter(SamsungDeviceScraper::isDeviceRelevant).toList();
        System.out.println("Filtered Devices (Stage 2) = " + devices.size());
        devices = devices.stream().sorted((x, y) -> getModelSupername(x).compareTo(getModelSupername(y))).toList();
        try {
            saveDevicesToDb(devices);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void saveDevicesToDb(List<DeviceMeta> devices) {
        SamsungDeviceDatabase database = new SamsungDeviceDatabase();
        devices.forEach(device -> database.save(device));
    }
}
