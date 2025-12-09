package com.example.keys.service;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ZipService {
    public void extractZip(Path zipFile, Path destDir) throws Exception {
        try (InputStream in = Files.newInputStream(zipFile); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path outPath = destDir.resolve(e.getName());
                Path norm = outPath.normalize();
                if (!norm.startsWith(destDir.normalize())) {
                    throw new IllegalArgumentException("非法的ZIP条目: " + e.getName());
                }
                if (e.isDirectory() || e.getName().endsWith("/")) {
                    Files.createDirectories(norm);
                } else {
                    Files.createDirectories(norm.getParent());
                    try (OutputStream out = Files.newOutputStream(norm)) {
                        byte[] buf = new byte[1024 * 1024];
                        int len;
                        while ((len = zis.read(buf)) != -1) out.write(buf, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
    }
}







