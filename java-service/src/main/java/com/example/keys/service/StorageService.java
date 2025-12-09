package com.example.keys.service;

import com.example.keys.config.AppProperties;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class StorageService {
    private final AppProperties props;

    public StorageService(AppProperties props) {
        this.props = props;
    }

    public static String toSha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public Path bucketize(String sha256) throws Exception {
        String a = sha256.substring(0, 2);
        String b = sha256.substring(2, 4);
        Path dir = Paths.get(props.getCacheRoot(), a, b, sha256);
        Files.createDirectories(dir);
        return dir;
    }

    public record SaveResult(String sha256, Path finalPath, Path bucketDir, long size, String ext) {}

    public SaveResult saveAndHash(InputStream in, String originalName) throws Exception {
        String ext = "." + FilenameUtils.getExtension(originalName).toLowerCase();
        if (!(ext.equals(".zip") || ext.equals(".tar") || ext.equals(".tgz") || ext.equals(".tar.gz") || ext.equals(".gz"))) {
            throw new IllegalArgumentException("不支持的文件后缀");
        }

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Path tempDir = Paths.get("temp-uploads");
        Files.createDirectories(tempDir);
        Path tempFile = tempDir.resolve(System.currentTimeMillis() + "_" + Math.random() + ext);

        long total = 0L;
        try (DigestInputStream dis = new DigestInputStream(in, md); OutputStream out = Files.newOutputStream(tempFile)) {
            byte[] buf = new byte[1024 * 1024];
            int len;
            while ((len = dis.read(buf)) != -1) {
                out.write(buf, 0, len);
                total += len;
            }
        }
        String sha256 = toSha256Hex(md.digest());
        Path bucketDir = bucketize(sha256);
        Path finalPath = bucketDir.resolve("artifact" + ext);
        if (!Files.exists(finalPath)) {
            Files.move(tempFile, finalPath);
        } else {
            Files.deleteIfExists(tempFile);
        }
        return new SaveResult(sha256, finalPath, bucketDir, total, ext);
    }

    public Path saveThumbnail(InputStream in, String originalName, Path bucketDir) throws Exception {
        String ext = "." + FilenameUtils.getExtension(originalName).toLowerCase();
        if (!(ext.equals(".png") || ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".webp"))) {
            throw new IllegalArgumentException("缩略图仅支持 png/jpg/jpeg/webp");
        }
        Files.createDirectories(bucketDir);
        Path thumb = bucketDir.resolve("thumbnail" + ext);
        try (OutputStream out = Files.newOutputStream(thumb)) {
            byte[] buf = new byte[256 * 1024];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
        }
        return thumb;
    }

    public Path saveImage(InputStream in, String originalName, Path bucketDir, String targetBaseName) throws Exception {
        String ext = "." + FilenameUtils.getExtension(originalName).toLowerCase();
        if (!(ext.equals(".png") || ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".webp"))) {
            throw new IllegalArgumentException("图片仅支持 png/jpg/jpeg/webp");
        }
        Files.createDirectories(bucketDir);
        Path out = bucketDir.resolve(targetBaseName + ext);
        try (OutputStream os = Files.newOutputStream(out)) {
            byte[] buf = new byte[256 * 1024];
            int len; while ((len = in.read(buf)) != -1) os.write(buf, 0, len);
        }
        return out;
    }

    public Path releasesPath(String codeName, String version) throws Exception {
        Path p = Paths.get(props.getReleasesRoot(), codeName, version);
        Files.createDirectories(p);
        return p;
    }
}


