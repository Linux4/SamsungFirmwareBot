/*
  Copyright (C) 2022  Tim Zimmermann <tim@linux4.de>

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

import java.io.File;
import java.io.IOException;

public class FileUtilsInternal {

    public static void deleteRecursively(File dir) throws IOException {
        if (dir.isDirectory()) {
            for (File f : dir.listFiles()) {
                deleteRecursively(f);
            }
        }
        dir.delete();
    }

}
