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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

public class SamsungFWBot extends TelegramLongPollingBot {

    public static final HashMap<String, String> KNOWN_REGIONS = new HashMap<>();
    public static final List<String> KNOWN_MODELS = new ArrayList<>();

    public static void main(String[] args) {
        if (args.length != 4 && args.length != 5) {
            // channels can be id or @channelname
            System.out.println("Usage: java -jar samsungfwbot.jar <bot name> <bot token> <firmware channel> <kernel channel> [oneshot]");
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

    private final String botName;
    private final String token;

    public SamsungFWBot(String botName, String token, String channelFw, String channelKernel, boolean oneshot) throws TelegramApiException, IOException, ParseException {
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

        do {
            for (String model : KNOWN_MODELS) {
                System.out.println("Processing model " + model);
                boolean found = false;

                for (String region : KNOWN_REGIONS.keySet()) {
                    SamsungFWInfo info = SamsungFWInfo.fetchLatest(model, region);

                    if (info != null) {
                        System.out.println(String.format("Found firmware %s/%s for model %s", info.getPDA(), region, model));
                        found = true;

                        if (info.isNewerThan(db.getPDA(model))) {
                            InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboardBuilder =
                                    InlineKeyboardMarkup.builder().keyboardRow(
                                            List.of(InlineKeyboardButton.builder().text("Download")
                                                    .url(info.getDownloadURL()).build()));
                            InlineKeyboardMarkup keyboard = keyboardBuilder.build();

                            SendMessage sm = new SendMessage();
                            sm.setText(String.format("New firmware update available \n \n "
                            + "Device: %s \n"
                            + "Model: %s \n"
                            + "Region: %s \n"
                            + "OS Version: %s \n"
                            + "PDA Version: %s \n"
                            + "Release Date: %s \n"
                            + "Security Patch Level: %s \n"
                            + "\n Changelog: %s \n", 
                            info.getDeviceName(), info.getModel(), info.getRegion(), info.getOSVersion(), info.getPDA(),
                            SamsungFWInfo.DATE_FORMAT.format(info.getBuildDate()), SamsungFWInfo.DATE_FORMAT.format(info.getSecurityPatch()), info.getChangelog()));
                            sm.setReplyMarkup(keyboard);
                            sm.setChatId(channelFw);

                            execute(sm);

                            db.setPDA(model, info.getPDA());
                        }
                    }
                }

                if (!found) {
                    System.err.println("ERROR: Model " + model + " not found in any known region! Known Regions: " + KNOWN_REGIONS.keySet());
                }

                SamsungKernelInfo info = SamsungKernelInfo.fetchLatest(model);

                if (info != null) {
                    if (info.isNewerThan(kernelDb.getPDA(model))) {
                        // Prevent duplicate DL
                        String oldPDA = kernelDb.getPDA(model);
                        kernelDb.setPDA(model, info.getPDA());

                        new Thread(() -> {
                            try {
                                SendDocument sd = new SendDocument();
                                sd.setCaption(String.format("New kernel sources available! \n"
                                + "Model: %s \n"
                                + "PDA Version: %s \n", 
                                info.getModel(), info.getPDA()));
                                sd.setChatId(channelKernel);

                                System.out.println("Downloading kernel source for " + model);
                                File result = info.download(new File("/tmp"));

                                if (result != null) {
                                    System.out.println("Uploading kernel source for " + model);
                                    sd.setDocument(new InputFile(result));
                                    execute(sd);

                                    result.delete();
                                    System.out.println("Finished kernel source upload for " + model);
                                } else {
                                    System.err.println("ERROR: Failed to download " + info);
                                    kernelDb.setPDA(model, oldPDA); // retry download
                                }
                            } catch (TelegramApiException | IOException ex) {
                                ex.printStackTrace();
                            }
                        }).start();
                    }
                } else {
                    System.err.println("ERROR: Model " + model + " does not have any kernel source available!");
                }

                // Sleep to prevent telegram spam protection
                try {
                    Thread.sleep(10*1000); // 10s
                } catch (InterruptedException e) {
                    e.printStackTrace();
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
                    Thread.sleep(60*60*1000); // 1h
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } while (!oneshot);
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
