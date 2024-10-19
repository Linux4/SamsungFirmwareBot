/*
  Copyright (C) 2022-2024  Tim Zimmermann <tim@linux4.de>

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
package de.linux4.samsungfwbot.io;

import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ArchiveUtils {

    public static final int MAX_FILE_SIZE = 100 * 1000 * 1000; // 100 MB
    public static final byte[] ZIP_MAGIC = new byte[]{0x50, 0x4b, 0x03, 0x04};

    public static boolean isZip(File file) throws IOException {
        byte[] magic = new byte[ZIP_MAGIC.length];
        FileInputStream fin = new FileInputStream(file);

        fin.read(magic);
        fin.close();

        return Arrays.equals(magic, ZIP_MAGIC);
    }

    public static String permsToString(int mode) {
        StringBuilder modString = new StringBuilder();

        for (int num : List.of(mode >> 6, mode >> 3, mode)) {
            modString.append((num & 4) == 4 ? "r" : "-");
            modString.append((num & 2) == 2 ? "w" : "-");
            modString.append((num & 1) == 1 ? "x" : "-");
        }

        return modString.toString();
    }

    public static List<String> extractTarGz(File in, File targetDir) throws IOException {
        List<String> ignoredFiles = new ArrayList<>();

        GzipCompressorInputStream gzIn = new GzipCompressorInputStream(new FileInputStream(in));
        ArchiveInputStream tarIn = new TarArchiveInputStream(gzIn);
        TarArchiveEntry entry;
        while ((entry = (TarArchiveEntry) tarIn.getNextEntry()) != null) {
            if (!tarIn.canReadEntryData(entry)) continue;
            File output = new File(targetDir, entry.getName());
            File parentDir = output.getParentFile();
            if (Files.isSymbolicLink(parentDir.toPath())) {
                // Symbolic link?
                File target = Files.readSymbolicLink(parentDir.toPath()).toFile();
                if (!target.toString().startsWith("/")) { // Relative path
                    target = new File(parentDir.getParentFile(), target.toString());
                }
                target.mkdirs();
            } else {
                parentDir.mkdirs();
            }
            if (entry.isDirectory()) {
                if (!output.isDirectory() && !output.mkdirs()) {
                    throw new IOException("Failed to create directory " + output);
                }
            } else if (entry.isSymbolicLink()) {
                try {
                    Files.delete(output.toPath());
                } catch (NoSuchFileException ignored) {

                } catch (DirectoryNotEmptyException ex) {
                    System.err.println("Warning: Skipping creation of symlink " + output.toPath() + " because a non-empty directory already exists!");
                    continue;
                }
                Files.createSymbolicLink(output.toPath(), Path.of(entry.getLinkName()));
            } else {
                if (entry.getSize() <= MAX_FILE_SIZE) {
                    OutputStream out = Files.newOutputStream(output.toPath());
                    IOUtils.copy(tarIn, out);
                } else {
                    ignoredFiles.add(entry.getName());
                    continue;
                }
            }
            if (!entry.isSymbolicLink())
                Files.setPosixFilePermissions(output.toPath(), PosixFilePermissions.fromString(permsToString(entry.getMode())));
        }

        return ignoredFiles;
    }
}
