/*
  Copyright (C) 2021-2022  Tim Zimmermann <tim@linux4.de>

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

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatDescription;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class SamsungFWBot extends TelegramLongPollingBot {

    public static final HashMap<String, String> KNOWN_REGIONS = new HashMap<>();
    public static final List<String> KNOWN_MODELS = new ArrayList<>();
    public static final String KERNEL_REPO_URL = "https://github.com/Linux4/samsung_kernel";
    public static final String GH_USER = "Linux4";
    public static final int MAX_CONCURRENT_DOWNLOADS = 5;

    public static void main(String[] args) {
        if (args.length != 4 && args.length != 5) {
            if (args.length == 1 && args[0].equalsIgnoreCase("scrapeDevices")) {
                try {
                    SamsungDeviceScraper.main(args);
                } catch (Exception ignored) {
                }
                return;
            }
            // channels can be id or @channelname
            System.out.println("Usage: java -jar samsungfwbot.jar <bot name> <bot token> <firmware channel> <kernel channel> [oneshot]");
            System.out.println("Usage: java -jar samsungfwbot.jar scrapeDevices");
            return;
        }

        boolean oneshot = args.length == 5 && args[4].equalsIgnoreCase("oneshot");

        try {
            // load regions
            BufferedReader reader = new BufferedReader(new FileReader("regions.txt"));
            String line;

            while (((line = reader.readLine())) != null) {
                if (line.startsWith("#"))
                    continue;

                KNOWN_REGIONS.put(line.split(":")[0], line.split(":")[1]);
            }
            reader.close();
            // load devices
            reader = new BufferedReader(new FileReader("devices.txt"));

            while (((line = reader.readLine())) != null) {
                if (line.startsWith("#"))
                    continue;

                KNOWN_MODELS.add(line);
            }
            reader.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        try {
            TelegramBotsApi botApi = new TelegramBotsApi(DefaultBotSession.class);
            botApi.registerBot(new SamsungFWBot(args[0], args[1], args[2], args[3], oneshot));
        } catch (TelegramApiException | IOException | ParseException ex) {
            ex.printStackTrace();
        }
    }

    private void sleep() {
        try {
            Thread.sleep(20 * 1000); // 20s - prevent telegram spam protection
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private final String botName;
    private final String token;
    private boolean checksFinished = false;
    private final ConcurrentLinkedQueue<TelegramMessage> messageQueue = new ConcurrentLinkedQueue<>();
    public static final ConcurrentLinkedQueue<Thread> downloadThreadQueue = new ConcurrentLinkedQueue<>();

    public SamsungFWBot(String botName, String token, String channelFw, String channelKernel, boolean oneshot) throws TelegramApiException, IOException, ParseException {
        super(new DefaultBotOptions());

        this.botName = botName;
        this.token = token;

        SamsungFWDatabase db = new SamsungFWDatabase("samsungfw.db");
        SamsungFWDatabase kernelDb = new SamsungFWDatabase("samsungkernel.db");

        new Thread(() -> {
            System.out.println("Message thread start");
            while (!messageQueue.isEmpty() || !checksFinished) {
                System.out.println("Message Queue Size: " + messageQueue.size() + " checksFinished=" + checksFinished);
                if (!messageQueue.isEmpty()) {
                    TelegramMessage message = messageQueue.poll();

                    SendMessage sm = new SendMessage();
                    sm.setChatId(message.getChannelId());
                    //sm.setParseMode("HTML");
                    // Telegram message length limit: 4096
                    sm.setText(message.getText().substring(0, Math.min(message.getText().length(), 4096)));

                    if (message.getKeyboard() != null)
                        sm.setReplyMarkup(message.getKeyboard());

                    for (int i = 0; i < 5; i++) {
                        try {
                            execute(sm);
                            break;
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                            sleep();
                        }
                    }
                }
                sleep();
            }
            System.out.println("Message thread end");

            if (oneshot)
                System.exit(0);
        }).start();

        List<Thread> threads = new LinkedList<>();

        do {
            for (String model : KNOWN_MODELS) {
                String fwModel = model.contains(":") ? model.split(":")[0] : model;
                String kernelModel = model.contains(":") ? model.split(":")[1] : model;
                System.out.println("Processing model " + fwModel);
                boolean found = false;

                for (String region : KNOWN_REGIONS.keySet()) {
                    SamsungFWInfo info = SamsungFWInfo.fetchLatest(fwModel, region);

                    if (info != null) {
                        System.out.printf("Found firmware %s/%s for model %s%n", info.getPDA(), region, fwModel);
                        found = true;

                        if (info.isNewerThan(db.getPDA(fwModel))) {
                            InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboardBuilder =
                                    InlineKeyboardMarkup.builder().keyboardRow(
                                            List.of(InlineKeyboardButton.builder().text("Download")
                                                    .url(info.getDownloadURL()).build()));
                            InlineKeyboardMarkup keyboard = keyboardBuilder.build();

                            messageQueue.add(new TelegramMessage(channelFw, "New firmware update available \n \n"
                                    + "Device: " + info.getDeviceName() + " \n"
                                    + "Model: " + info.getModel() + " \n"
                                    + "OS Version: " + info.getOSVersion() + " \n"
                                    + "PDA Version: " + info.getPDA() + " \n"
                                    + "Release Date: " + SamsungFWInfo.DATE_FORMAT.format(info.getBuildDate()) + " \n"
                                    + "Security Patch Level: " + SamsungFWInfo.DATE_FORMAT.format(info.getSecurityPatch()) + " \n\n"
                                    + "Changelog:  \n"
                                    + info.getChangelog() + " \n",
                                    keyboard));

                            db.setPDA(fwModel, info.getPDA());
                        }
                    }
                }

                if (!found) {
                    System.err.println("ERROR: Model " + fwModel + " not found in any known region! Known Regions: " + KNOWN_REGIONS.keySet());
                }

                SamsungKernelInfo info = SamsungKernelInfo.fetchLatest(kernelModel);

                if (info != null) {
                    if (info.isNewerThan(kernelDb.getPDA(kernelModel))) {
                        // Prevent duplicate DL
                        String oldPDA = kernelDb.getPDA(kernelModel);
                        kernelDb.setPDA(kernelModel, info.getPDA());

                        Thread thread = new Thread(() -> {
                            File result = null, tmpDir = null;
                            try {
                                System.out.println("Downloading kernel source for " + kernelModel);
                                result = info.download(new File("."));

                                if (result != null) {
                                    System.out.println("Uploading kernel source for " + kernelModel);
                                    ZipFile zipFile = new ZipFile(result);
                                    tmpDir = new File("./samsung_kernel_" + kernelModel);
                                    FileUtils.deleteDirectory(tmpDir);
                                    if (!tmpDir.mkdir()) System.err.println("Failed to create " + tmpDir);
                                    Git git = Git.init().setDirectory(tmpDir).call();
                                    git.remoteAdd().setName("origin").setUri(new URIish(KERNEL_REPO_URL)).call();
                                    try {
                                        git.fetch().setRefSpecs(new RefSpec("refs/heads/" + kernelModel)).call();
                                        git.checkout().setCreateBranch(true).setName(kernelModel)
                                                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                                                .setStartPoint("FETCH_HEAD").call();
                                        git.pull().call();
                                    } catch (Exception ignored) {

                                    }

                                    if (info.getPatchKernel() != null) {
                                        Enumeration<? extends ZipEntry> entries = zipFile.entries();
                                        for (ZipEntry entry = entries.nextElement(); entries.hasMoreElements(); entry = entries.nextElement()) {
                                            if (entry.getName().startsWith("Kernel/")) {
                                                File output = new File(tmpDir, entry.getName().substring("Kernel/".length()));
                                                // TODO: This might need to be updated to support symlinks and file permissions as well
                                                FileUtils.copyInputStreamToFile(zipFile.getInputStream(entry), output);
                                            }
                                        }
                                    } else {
                                        git.rm().addFilepattern("*").call();
                                        ZipEntry kernel = zipFile.getEntry("Kernel.tar.gz");
                                        File kernelTar = new File("/tmp/Kernel-" + info.getPDA() + ".tar.gz");
                                        FileUtils.copyInputStreamToFile(zipFile.getInputStream(kernel), kernelTar);

                                        ArchiveUtils.extractTarGz(kernelTar, tmpDir);
                                        if (!kernelTar.delete()) System.err.println("Failed to delete " + result);
                                    }
                                    zipFile.close();
                                    if (!result.delete()) System.err.println("Failed to delete " + result);

                                    try {
                                        git.add().setWorkingTreeIterator(new ForceAddFileTreeIterator(git.getRepository())).addFilepattern(".").call();
                                        git.commit().setMessage(kernelModel + ": Import " + info.getPDA() + " kernel source")
                                                .setAuthor("github-actions[bot]", "41898282+github-actions[bot]@users.noreply.github.com").call();
                                        git.tag().setName(info.getPDA()).call();
                                        PushCommand push = git.push().setRemote("origin").setRefSpecs(new RefSpec("HEAD:refs/heads/" + kernelModel)).setPushTags();
                                        push.setCredentialsProvider(new UsernamePasswordCredentialsProvider(GH_USER, System.getenv("GH_TOKEN")));
                                        push.call();

                                        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboardBuilder =
                                                InlineKeyboardMarkup.builder().keyboardRow(
                                                        List.of(InlineKeyboardButton.builder().text("View")
                                                                .url(KERNEL_REPO_URL + "/tree/" + info.getPDA()).build()));
                                        InlineKeyboardMarkup keyboard = keyboardBuilder.build();
                                        messageQueue.add(new TelegramMessage(channelKernel, "New kernel sources available! \n"
                                                + "Model: " + info.getModel() + " \n"
                                                + "PDA Version: " + info.getPDA() + " \n"
                                                + (info.getPatchKernel() != null ? "This is a patch over " + info.getPatchKernel() + " " : "") + "\n",
                                                keyboard));
                                    } catch (RefAlreadyExistsException ignored) {
                                        System.err.println(info.getPDA() + " is already pushed, skipping!");
                                    } finally {
                                        FileUtils.deleteDirectory(tmpDir);
                                    }
                                } else {
                                    System.err.println("ERROR: Failed to download " + info);
                                    if (oldPDA != null && oldPDA.length() > 0)
                                        kernelDb.setPDA(kernelModel, oldPDA); // retry download
                                }
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                if (oldPDA != null && oldPDA.length() > 0)
                                    kernelDb.setPDA(kernelModel, oldPDA); // retry download
                            } finally {
                                if (result != null && result.exists()) if (!result.delete()) System.err.println("Failed to delete " + result);
                                if (tmpDir != null && tmpDir.exists()) {
                                    try {
                                        FileUtils.deleteDirectory(tmpDir);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }

                                if (downloadThreadQueue.size() > 0 && (getActiveThreadsCount(threads) - 1) < MAX_CONCURRENT_DOWNLOADS) {
                                    downloadThreadQueue.poll().start();
                                }
                            }
                        });
                        threads.add(thread);
                        if (getActiveThreadsCount(threads) < MAX_CONCURRENT_DOWNLOADS) {
                            thread.start();
                        } else {
                            downloadThreadQueue.add(thread);
                        }
                    }
                } else {
                    System.err.println("ERROR: Model " + model + " does not have any kernel source available!");
                }
            }

            SetChatDescription sdesc = new SetChatDescription();
            sdesc.setDescription("Last updated: " + new Date(System.currentTimeMillis()));
            sdesc.setChatId(channelFw);
            execute(sdesc);
            sdesc.setChatId(channelKernel);
            execute(sdesc);

            if (!oneshot) {
                try {
                    Thread.sleep(60 * 60 * 1000); // 1h
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            int activeThreadsCount;
            do {
                activeThreadsCount = getActiveThreadsCount(threads);
                System.out.println("Still active Threads: " + activeThreadsCount);
                sleep();
            } while (activeThreadsCount > 0);
            threads.clear();
        } while (!oneshot);

        checksFinished = true;
        System.out.println("Checks finished");
    }

    private int getActiveThreadsCount(List<Thread> threads) {
        int activeThreadsCount = 0;
        for (Thread t : threads) {
            if (t.isAlive()) activeThreadsCount++;
        }

        return activeThreadsCount;
    }

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public void onUpdateReceived(Update update) {
    }
}
