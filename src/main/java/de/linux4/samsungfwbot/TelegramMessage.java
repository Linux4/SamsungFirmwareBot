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

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

public class TelegramMessage {

    private final String channelId;
    private final String text;
    private final InlineKeyboardMarkup keyboard;

    public TelegramMessage(String channelId, String text, InlineKeyboardMarkup keyboard) {
        this.channelId = channelId;
        this.text = text;
        this.keyboard = keyboard;
    }

    public TelegramMessage(String channelId, String text) {
        this(channelId, text, null);
    }

    public String getChannelId() {
        return channelId;
    }

    public String getText() {
        return text;
    }

    public InlineKeyboardMarkup getKeyboard() {
        return keyboard;
    }

}
