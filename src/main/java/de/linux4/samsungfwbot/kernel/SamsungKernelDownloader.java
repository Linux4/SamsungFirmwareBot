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
package de.linux4.samsungfwbot.kernel;

import de.linux4.samsungfwbot.SamsungDeviceDatabase;
import de.linux4.samsungfwbot.SamsungFWBot;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class SamsungKernelDownloader {

    public static void main(String[] args) throws IOException {
        fetchLatestPDAs();
        downloadAllKernels();
    }

    public static void fetchLatestPDAs() throws IOException {
        SamsungDeviceDatabase deviceDb = new SamsungDeviceDatabase();
        SamsungKernelDatabase kernelDb = new SamsungKernelDatabase();

        for (String model : deviceDb.getAllModels()) {
            SamsungKernelInfo info = SamsungKernelInfo.fetchLatest(model);

            if (info != null && info.isNewerThan(kernelDb.getLatestPDA(model))) {
                kernelDb.addPDA(model, info.getPDA(), info.getUploadID(), info.getPatchKernel());
            }
        }
    }

    public static void downloadAllKernels() {
        SamsungKernelDatabase kernelDb = new SamsungKernelDatabase();

        for (String model : kernelDb.getAllModels()) {
            String pda = kernelDb.getLatestPDA(model);

            if (kernelDb.getGHRelease(pda) == null) {
                SamsungKernelInfo info = new SamsungKernelInfo(model, pda, kernelDb.getUploadID(pda), kernelDb.getPatchKernel(pda));

                try {
                    File kernelSrc = info.download(new File("/tmp"));

                    if (kernelSrc != null && kernelSrc.exists()) {
                        File releaseMessage = new File("/tmp/" + pda + "-releasedesc.txt");
                        PrintWriter writer = new PrintWriter(new FileWriter(releaseMessage));
                        writer.println(pda);
                        writer.println();
                        writer.println("Model: " + model);
                        writer.println("PDA Version: " + pda);
                        if (info.getPatchKernel() != null)
                            writer.println("This is a patch over " + info.getPatchKernel());
                        writer.close();

                        Process process = Runtime.getRuntime().exec("hub release create -a "
                                + kernelSrc.getAbsolutePath() + " -F " + releaseMessage.getAbsolutePath() + " " + pda);
                        process.waitFor();
                        kernelSrc.delete();
                        releaseMessage.delete();

                        if (process.exitValue() == 0) {
                            kernelDb.setGHRelease(pda, SamsungFWBot.REPO_URL + "/releases/tags/" + pda);
                        }
                    }
                } catch (Exception ex) {
                    System.err.println("Failed to download kernel source " + info);
                }
            }
        }
    }

}
