package de.linux4.samsungfwbot;

import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

public class ArchiveUtils {

    public static final int MAX_FILE_SIZE = 100 * 1000 * 1000; // 100 MB

    public static String permsToString(int mode) {
        StringBuilder modString = new StringBuilder();

        for (int num : List.of(mode >> 6, mode >> 3, mode)) {
            modString.append((num & 4) == 4 ? "r" : "-");
            modString.append((num & 2) == 2 ? "w" : "-");
            modString.append((num & 1) == 1 ? "x" : "-");
        }

        return modString.toString();
    }

    public static List<String> extractTarGz(File in, File targetDir) {
        List<String> ignoredFiles = new ArrayList<>();

        try (GzipCompressorInputStream gzIn = new GzipCompressorInputStream(new FileInputStream(in))) {
            try (ArchiveInputStream tarIn = new TarArchiveInputStream(gzIn)) {
                TarArchiveEntry entry;
                while ((entry = (TarArchiveEntry)tarIn.getNextEntry()) != null) {
                    if (!tarIn.canReadEntryData(entry)) continue;
                    File output = new File(targetDir, entry.getName());
                    File parentDir = output.getParentFile();
                    if (Files.isSymbolicLink(parentDir.toPath())) {
                        // Symbolic link?
                        try {
                            File target = Files.readSymbolicLink(parentDir.toPath()).toFile();
                            if (!target.toString().startsWith("/")) { // Relative path
                                target = new File(parentDir.getParentFile(), target.toString());
                            }
                            target.mkdirs();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
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

                        }
                        Files.createSymbolicLink(output.toPath(), Path.of(entry.getLinkName()));
                    } else {
                        if (entry.getSize() <= MAX_FILE_SIZE) {
                            try (OutputStream out = Files.newOutputStream(output.toPath())) {
                                IOUtils.copy(tarIn, out);
                            }
                        } else {
                            ignoredFiles.add(entry.getName());
                            continue;
                        }
                    }
                    if (!entry.isSymbolicLink())
                        Files.setPosixFilePermissions(output.toPath(), PosixFilePermissions.fromString(permsToString(entry.getMode())));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return ignoredFiles;
    }
}
