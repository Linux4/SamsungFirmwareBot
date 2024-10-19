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

import de.linux4.samsungfwbot.io.ArchiveUtils;
import de.linux4.samsungfwbot.io.FileUtilsInternal;
import de.linux4.samsungfwbot.jgit.ForceAddFileTreeIterator;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatDescription;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class SamsungFWBot implements LongPollingSingleThreadUpdateConsumer{

    public static final String KERNEL_REPO_URL = "https://github.com/Linux4/samsung_kernel";
    public static final String GH_USER = "Linux4";
    public static final int MAX_CONCURRENT_DOWNLOADS = 2;

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
            System.out.println("Usage: java -jar samsungfwbot.jar <bot token> <capsolver token> <firmware channel> <kernel channel> [oneshot]");
            System.out.println("Usage: java -jar samsungfwbot.jar scrapeDevices");
            System.exit(1);
        }

        boolean oneshot = args.length == 5 && args[4].equalsIgnoreCase("oneshot");

        try {
            TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication();
            SamsungFWBot bot = new SamsungFWBot(args[0], args[1], args[2], args[3], oneshot);
            botsApplication.registerBot(args[0], bot);
            bot.run();
        } catch (TelegramApiException ex) {
            ex.printStackTrace();
        }
    }

    private void sleep() {
        try {
            Thread.sleep(3 * 1000); // 3s - prevent telegram spam protection
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private final CapSolver capSolver;
    private final String channelFw;
    private final String channelKernel;
    private final boolean oneshot;
    private final TelegramClient telegramClient;
    private boolean checksFinished = false;
    private final ConcurrentLinkedQueue<TelegramMessage> messageQueue = new ConcurrentLinkedQueue<>();

    public SamsungFWBot(String botToken, String capSolverToken, String channelFw, String channelKernel, boolean oneshot) {
        this.capSolver = new CapSolver(capSolverToken);
        this.channelFw = channelFw;
        this.channelKernel = channelKernel;
        this.oneshot = oneshot;

        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    public void run() {
        SamsungFWDatabase db = new SamsungFWDatabase("db/samsungfw.db");
        SamsungFWDatabase kernelDb = new SamsungFWDatabase("db/samsungkernel.db");
        SamsungDeviceDatabase deviceDb = new SamsungDeviceDatabase();

        ExecutorService messageExecutor = Executors.newSingleThreadExecutor();
        ThreadPoolExecutor firmwareCheckExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        ThreadPoolExecutor kernelCheckExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        ThreadPoolExecutor kernelDownloadExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
                MAX_CONCURRENT_DOWNLOADS);

        messageExecutor.submit(() -> {
            System.out.println("Message thread start");
            while (!messageQueue.isEmpty() || !checksFinished) {
                System.out.println("Message Queue Size: " + messageQueue.size() + " checksFinished=" + checksFinished);
                if (!messageQueue.isEmpty()) {
                    TelegramMessage message = messageQueue.poll();

                    // Telegram message length limit: 4096
                    SendMessage sm = new SendMessage(message.getChannelId(),
                            message.getText().substring(0, Math.min(message.getText().length(), 4096)));
                    sm.setChatId(message.getChannelId());
                    //sm.setParseMode("HTML");

                    if (message.getKeyboard() != null)
                        sm.setReplyMarkup(message.getKeyboard());

                    for (int i = 0; i < 5; i++) {
                        try {
                            telegramClient.execute(sm);
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
        });

        do {
            for (String model : deviceDb.getAllModels()) {
                System.out.println("Processing model " + model);

                firmwareCheckExecutor.submit(() -> {
                    boolean found = false;

                    for (String region : deviceDb.getRegionsByModel(model)) {
                        SamsungFWInfo info = SamsungFWInfo.fetchLatest(model, region);

                        if (info != null) {
                            System.out.printf("Found firmware %s/%s for model %s%n", info.getPDA(), region, model);
                            found = true;

                            if (info.isNewerThan(db.getPDA(model))) {
                                InlineKeyboardMarkup keyboard =
                                        InlineKeyboardMarkup.builder().keyboardRow(
                                                new InlineKeyboardRow(InlineKeyboardButton.builder().text("Download")
                                                        .url(info.getDownloadURL()).build())).build();

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

                                db.setPDA(model, info.getPDA());
                            }
                        }
                    }

                    if (!found) {
                        System.err.println("ERROR: Model " + model + " not found in any known region! Known Regions: " + deviceDb.getRegionsByModel(model));
                    }
                });

                kernelCheckExecutor.submit(() -> {
                    SamsungKernelInfo info = SamsungKernelInfo.fetchLatest(model);

                    if (info != null) {
                        if (info.isNewerThan(kernelDb.getPDA(model))) {
                            // Prevent duplicate DL
                            String oldPDA = kernelDb.getPDA(model);
                            kernelDb.setPDA(model, info.getPDA());

                            kernelDownloadExecutor.submit(() -> {
                                File result = null, tmpDir = null;
                                try {
                                    System.out.println("Downloading kernel source for " + model);
                                    result = info.download(capSolver, new File("."));

                                    if (result != null) {
                                        System.out.println("Uploading kernel source for " + model);
                                        ZipFile zipFile = null;
                                        if (ArchiveUtils.isZip(result))
                                            zipFile = new ZipFile(result);
                                        tmpDir = new File("./samsung_kernel_" + model);
                                        FileUtilsInternal.deleteRecursively(tmpDir);
                                        if (!tmpDir.mkdir()) System.err.println("Failed to create " + tmpDir);
                                        Git git = Git.init().setDirectory(tmpDir).call();
                                        git.remoteAdd().setName("origin").setUri(new URIish(KERNEL_REPO_URL)).call();
                                        try {
                                            git.fetch().setRefSpecs(new RefSpec("refs/heads/" + model)).call();
                                            git.checkout().setCreateBranch(true).setName(model)
                                                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                                                    .setStartPoint("FETCH_HEAD").call();
                                            git.pull().call();
                                        } catch (Exception ignored) {

                                        }

                                        List<String> ignoredFiles = new ArrayList<>();
                                        if (info.getPatchKernel() != null) {
                                            Enumeration<? extends ZipEntry> entries = zipFile.entries();
                                            for (ZipEntry entry = entries.nextElement(); entries.hasMoreElements(); entry = entries.nextElement()) {
                                                if (entry.getName().startsWith("Kernel/")) {
                                                    if (entry.getSize() <= ArchiveUtils.MAX_FILE_SIZE) {
                                                        File output = new File(tmpDir, entry.getName().substring("Kernel/".length()));
                                                        FileUtils.copyInputStreamToFile(zipFile.getInputStream(entry), output);
                                                    } else {
                                                        ignoredFiles.add(entry.getName().substring("Kernel/".length()));
                                                    }
                                                }
                                            }
                                        } else {
                                            try {
                                                gitRm(git, git.rm(), git.getRepository().getWorkTree()).call();
                                            } catch (NoFilepatternException ignored) {

                                            }
                                            File kernelTar = new File("/tmp/Kernel-" + info.getPDA() + ".tar.gz");
                                            if (zipFile != null) {
                                                ZipEntry kernel = zipFile.getEntry("Kernel.tar.gz");
                                                FileUtils.copyInputStreamToFile(zipFile.getInputStream(kernel), kernelTar);
                                            } else {
                                                kernelTar = result;
                                            }

                                            ignoredFiles.addAll(ArchiveUtils.extractTarGz(kernelTar, tmpDir));
                                            if (!kernelTar.delete()) System.err.println("Failed to delete " + result);
                                        }
                                        if (zipFile != null) {
                                            zipFile.close();
                                            if (!result.delete()) System.err.println("Failed to delete " + result);
                                        }

                                        try {
                                            StringBuilder extraBuilder = new StringBuilder();
                                            if (ignoredFiles.size() > 0) {
                                                extraBuilder.append("\n\nThe following files were removed because they exceed github's file size limit:");

                                                for (String ignoredFile : ignoredFiles) {
                                                    extraBuilder.append("\n - ");
                                                    extraBuilder.append(ignoredFile);
                                                }
                                            }

                                            git.add().setWorkingTreeIterator(new ForceAddFileTreeIterator(git.getRepository())).addFilepattern(".").call();
                                            git.commit().setMessage(model + ": Import " + info.getPDA() + " kernel source" + extraBuilder)
                                                    .setAuthor("github-actions[bot]", "41898282+github-actions[bot]@users.noreply.github.com")
                                                    .setSign(false).call();
                                            git.tag().setName(model + '/' + info.getPDA()).call();
                                            PushCommand push = git.push().setRemote("origin").setRefSpecs(new RefSpec("HEAD:refs/heads/" + model)).setPushTags();
                                            push.setCredentialsProvider(new UsernamePasswordCredentialsProvider(GH_USER, System.getenv("GH_TOKEN")));
                                            push.call();

                                            InlineKeyboardMarkup keyboard =
                                                    InlineKeyboardMarkup.builder().keyboardRow(
                                                            new InlineKeyboardRow(InlineKeyboardButton.builder().text("View")
                                                                    .url(KERNEL_REPO_URL + "/tree/" + model + '/' + info.getPDA()).build())).build();
                                            messageQueue.add(new TelegramMessage(channelKernel, "New kernel sources available! \n"
                                                    + "Model: " + info.getModel() + " \n"
                                                    + "PDA Version: " + info.getPDA() + " \n"
                                                    + (info.getPatchKernel() != null ? "This is a patch over " + info.getPatchKernel() + " " : "") + "\n",
                                                    keyboard));
                                        } catch (RefAlreadyExistsException ignored) {
                                            System.err.println(info.getPDA() + " is already pushed, skipping!");
                                        } finally {
                                            FileUtilsInternal.deleteRecursively(tmpDir);
                                        }
                                    } else {
                                        System.err.println("ERROR: Failed to download " + info);
                                        kernelDb.setPDA(model, oldPDA != null ? oldPDA : ""); // retry download
                                    }
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                    kernelDb.setPDA(model, oldPDA != null ? oldPDA : ""); // retry download
                                } finally {
                                    if (result != null && result.exists())
                                        if (!result.delete()) System.err.println("Failed to delete " + result);
                                    if (tmpDir != null && tmpDir.exists()) {
                                        try {
                                            FileUtilsInternal.deleteRecursively(tmpDir);
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            });
                        }
                    } else {
                        System.err.println("ERROR: Model " + model + " does not have any kernel source available!");
                    }
                });
            }

            try {
                SetChatDescription sdesc = new SetChatDescription(channelFw, "Last updated: "
                        + new Date(System.currentTimeMillis()));
                sdesc.setChatId(channelFw);
                telegramClient.execute(sdesc);
                sdesc.setChatId(channelKernel);
                telegramClient.execute(sdesc);
            } catch (TelegramApiException ex) {
                ex.printStackTrace();
            }

            if (!oneshot) {
                try {
                    Thread.sleep(60 * 60 * 1000); // 1h
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            int activeThreadsCount;
            do {
                activeThreadsCount = firmwareCheckExecutor.getActiveCount() + kernelCheckExecutor.getActiveCount()
                        + kernelDownloadExecutor.getActiveCount();
                System.out.println("Still active Threads: " + activeThreadsCount);
                sleep();
            } while (activeThreadsCount > 0);
        } while (!oneshot);

        checksFinished = true;
        System.out.println("Checks finished");

        messageExecutor.close();
        firmwareCheckExecutor.close();
        kernelCheckExecutor.close();
        kernelDownloadExecutor.close();
    }

    private RmCommand gitRm(Git git, RmCommand rm, File file) throws GitAPIException {
        File baseDir = git.getRepository().getWorkTree();

        if (file.equals(git.getRepository().getDirectory()))
            return rm;

        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                gitRm(git, rm, child);
            }
        } else {
            String path = baseDir.toURI().relativize(file.toURI()).getPath();
            rm.addFilepattern(path);
        }

        return rm;
    }

    private int getActiveThreadsCount(List<Thread> threads) {
        int activeThreadsCount = 0;
        for (Thread t : threads) {
            if (t.isAlive()) activeThreadsCount++;
        }

        return activeThreadsCount;
    }

    @Override
    public void consume(Update update) {
    }
}
