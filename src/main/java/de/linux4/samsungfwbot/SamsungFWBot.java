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

import de.linux4.samsungfwbot.kernel.SamsungKernelInfo;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatDescription;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SamsungFWBot extends TelegramLongPollingBot {

    public static final String REPO_URL = "https://github.com/Linux4/SamsungFirmwareBot";

    public static void main(String[] args) {
        if (args.length != 4) {
            if (args.length == 1 && args[0].equalsIgnoreCase("scrapeDevices")) {
                try {
                    SamsungDeviceScraper.main(args);
                } catch (Exception ignored) {
                }
                return;
            }
            // channels can be id or @channelname
            System.out.println(
                    "Usage: java -jar samsungfwbot.jar <bot name> <bot token> <firmware channel> <kernel channel>");
            System.out.println("Usage: java -jar samsungfwbot.jar scrapeDevices");
            return;
        }

        try {
            TelegramBotsApi botApi = new TelegramBotsApi(DefaultBotSession.class);
            botApi.registerBot(new SamsungFWBot(args[0], args[1], args[2], args[3]));
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

    public SamsungFWBot(String botName, String token, String channelFw, String channelKernel)
            throws TelegramApiException, IOException, ParseException {
        super(new DefaultBotOptions() {
            @Override
            public String getBaseUrl() {
                return "http://localhost:8082/bot";
            }
        });

        this.botName = botName;
        this.token = token;

        SamsungFWDatabase db = new SamsungFWDatabase("samsungfw.db");
        SamsungFWDatabase kernelDb = new SamsungFWDatabase("samsungkernel.db");
        SamsungDeviceDatabase deviceDb = new SamsungDeviceDatabase();

        new Thread(() -> {
            System.out.println("Upload thread start");
            while (!messageQueue.isEmpty() || !checksFinished) {
                System.out.println("Message Queue Size: " + messageQueue.size() + " checksFinished=" + checksFinished);
                if (!messageQueue.isEmpty()) {
                    TelegramMessage message = messageQueue.poll();

                    if (message.getFile() != null) {
                        System.out.println("Uploading " + message.getFile().getName());

                        SendDocument sd = new SendDocument();
                        sd.setChatId(message.getChannelId());
                        sd.setCaption(message.getText());
                        sd.setDocument(new InputFile(message.getFile()));

                        for (int i = 0; i < 5; i++) {
                            try {
                                execute(sd);
                                System.out.println("Finished upload of " + message.getFile().getName());
                                break;
                            } catch (TelegramApiException e) {
                                System.err.println("Upload of " + message.getFile().getName() + " failed (" + i + ")");
                                e.printStackTrace();
                                sleep();
                            }
                        }

                        message.getFile().delete();
                    } else {
                        SendMessage sm = new SendMessage();
                        sm.setChatId(message.getChannelId());
                        String text = message.getText();
                        text = text.substring(0, Math.min(text.length(), 4096));
                        sm.setText(text);

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
                }
                sleep();
            }
            System.out.println("Upload thread end");

            System.exit(0);
        }).start();

        List<Thread> threads = new LinkedList<>();

        System.out.println("Devices = " + deviceDb.getAllModels());

        for (String model : deviceDb.getAllModels()) {
            System.out.println("Processing model " + model);
            boolean found = false;

            for (String region : deviceDb.getRegionsByModel(model)) {
                SamsungFWInfo info = SamsungFWInfo.fetchLatest(model, region);

                if (info != null) {
                    System.out.printf("Found firmware %s/%s for model %s%n", info.getPDA(), region, model);
                    found = true;

                    if (info.isNewerThan(db.getPDA(model))) {
                        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboardBuilder = InlineKeyboardMarkup
                                .builder().keyboardRow(
                                        List.of(InlineKeyboardButton.builder().text("Download")
                                                .url(info.getDownloadURL()).build()));
                        InlineKeyboardMarkup keyboard = keyboardBuilder.build();

                        messageQueue.add(new TelegramMessage(channelFw, String.format(
                                """
                                        New firmware update available\s
                                        \s
                                        Device: %s\s
                                        Model: %s\s
                                        OS Version: %s\s
                                        PDA Version: %s\s
                                        Release Date: %s\s
                                        Security Patch Level: %s\s

                                Changelog: \s
                                %s\s
                                """,
                                info.getDeviceName(), info.getModel(), info.getOSVersion(), info.getPDA(),
                                SamsungFWInfo.DATE_FORMAT.format(info.getBuildDate()),
                                SamsungFWInfo.DATE_FORMAT.format(info.getSecurityPatch()),
                                info.getChangelog()),
                                keyboard));

                        db.setPDA(model, info.getPDA());
                    }
                }
            }

            if (!found) {
                System.err.println("ERROR: Firmware for " + model + " not found in regions "
                        + deviceDb.getRegionsByModel(model));
            }

            SamsungKernelInfo info = SamsungKernelInfo.fetchLatest(model);

            if (info != null) {
                if (info.isNewerThan(kernelDb.getPDA(model))) {
                    Thread thread = new Thread(() -> {
                        try {
                            System.out.println("Downloading kernel source for " + model);
                            File result = info.download(new File("/tmp"));

                            if (result != null) {
                                messageQueue.add(new TelegramMessage(channelKernel, String.format("""
                                        New kernel sources available!\s
                                            Model: %s\s
                                            PDA Version: %s\s
                                        %s
                                        """,
                                        info.getModel(), info.getPDA(),
                                        info.getPatchKernel() != null
                                                ? String.format("This is a patch over %s\s", info.getPatchKernel())
                                                : ""),
                                        result));
                                kernelDb.setPDA(model, info.getPDA());
                            } else {
                                System.err.println("ERROR: Failed to download " + info);
                            }
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                    });
                    thread.start();
                    threads.add(thread);
                }
            } else {
                System.err.println("ERROR: Model " + model + " does not have any kernel source available!");
            }
        }

        try {
            SetChatDescription sdesc = new SetChatDescription();
            sdesc.setDescription("Last updated: " + new Date(System.currentTimeMillis()));
            sdesc.setChatId(channelFw);
            execute(sdesc);
            if (channelFw != channelKernel) {
                sdesc.setChatId(channelKernel);
                execute(sdesc);
            }
        } catch (Exception ignored) {
        }

        int activeThreadsCount;
        do {
            activeThreadsCount = 0;
            for (Thread t : threads) {
                if (t.isAlive())
                    activeThreadsCount++;
            }
            System.out.println("Still active Threads: " + activeThreadsCount);
            sleep();
        } while (activeThreadsCount > 0);
        threads.clear();

        checksFinished = true;
        System.out.println("Checks finished");
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
