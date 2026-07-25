package crabcraft.net.crabUtilities.velocity.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

public class UpdateDownloader {

    private static final long MAX_UPDATE_BYTES = 128L * 1024 * 1024;
    private static final int MAX_CHECKSUM_BYTES = 64 * 1024;

    private final HttpClient http;
    private final String token;
    private final String userAgent;

    public UpdateDownloader(String token, String userAgent) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.token = token;
        this.userAgent = userAgent;
    }

    public Path download(ReleaseInfo info, Path targetDir, String targetFilename)
            throws IOException, InterruptedException {

        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(targetFilename);
        Path part = targetDir.resolve("." + targetFilename + ".part");

        if (info.checksumUrl() == null || info.checksumUrl().isBlank()) {
            throw new IOException("Release is missing SHA256SUMS.txt");
        }
        String expected = fetchChecksum(info.checksumUrl(), info.jarAssetName());
        if (expected == null || !expected.matches("(?i)^[0-9a-f]{64}$")) {
            throw new IOException("Release checksum is missing or invalid for " + info.jarAssetName());
        }
        if (info.size() <= 0 || info.size() > MAX_UPDATE_BYTES) {
            throw new IOException("Release asset has an invalid size: " + info.size());
        }

        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }

        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(info.jarUrl()))
                    .timeout(Duration.ofMinutes(5))
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", userAgent)
                    .GET();
            if (token != null && !token.isEmpty()) {
                b.header("Authorization", "Bearer " + token);
            }
            HttpResponse<InputStream> res = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() / 100 != 2) {
                res.body().close();
                throw new IOException("HTTP " + res.statusCode() + " downloading " + info.jarUrl());
            }
            long contentLength = res.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > info.size() || contentLength > MAX_UPDATE_BYTES) {
                res.body().close();
                throw new IOException("Release asset exceeds its declared size");
            }
            try (InputStream in = res.body();
                 OutputStream out = Files.newOutputStream(part,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[8192];
                int n;
                long downloaded = 0;
                while ((n = in.read(buf)) > 0) {
                    downloaded += n;
                    if (downloaded > info.size() || downloaded > MAX_UPDATE_BYTES) {
                        throw new IOException("Release asset exceeds its declared size");
                    }
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
                if (downloaded != info.size()) {
                    throw new IOException("Release asset size mismatch: expected "
                            + info.size() + ", got " + downloaded);
                }
            }

            String actual = toHex(sha.digest());
            if (!expected.equalsIgnoreCase(actual)) {
                throw new UpdateExceptions.ChecksumMismatchException(
                        "SHA-256 mismatch for " + info.jarAssetName()
                                + ": expected " + expected + ", got " + actual);
            }

            try {
                Files.move(part, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
        }
    }

    private String fetchChecksum(String url, String assetName) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/plain")
                .header("User-Agent", userAgent)
                .GET();
        if (token != null && !token.isEmpty()) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<InputStream> res = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) {
            res.body().close();
            return null;
        }
        String body;
        try (InputStream in = res.body()) {
            byte[] bytes = in.readNBytes(MAX_CHECKSUM_BYTES + 1);
            if (bytes.length > MAX_CHECKSUM_BYTES) {
                throw new IOException("Checksum file exceeds 64 KiB");
            }
            body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length != 2) continue;
            String name = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
            if (name.equalsIgnoreCase(assetName)) {
                return parts[0];
            }
        }
        return null;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
