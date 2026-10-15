package com.betteranalytics.crm.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * File/image storage abstraction for attachments.
 *
 * Backend is picked at startup, tenant-isolated either way:
 *   - "azure_blob" when AZURE_STORAGE_CONNECTION_STRING is set — objects are
 *     written under "{orgId}/{relatedType}/{relatedId}/{uuid}-{filename}" in
 *     the configured container (azure.blob.container, default
 *     "bettercrm-docs"), matching the spec's tenant-prefixed-blob-path rule.
 *   - "local" otherwise — the same key layout under app.storage.local-dir
 *     (default ./data/uploads), so the product works end-to-end with zero
 *     cloud credentials in dev/self-hosted deployments, and is a drop-in
 *     swap to Azure Blob later (same key scheme, same caller API).
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path localRoot;
    private final BlobServiceClient blobServiceClient; // null when Azure isn't configured
    private final String containerName;
    private final boolean azureEnabled;

    public FileStorageService(
            @Value("${app.storage.local-dir:./data/uploads}") String localDir,
            @Value("${azure.blob.connection-string:}") String azureConnectionString,
            @Value("${azure.blob.container:bettercrm-docs}") String containerName) {
        this.localRoot = Path.of(localDir).toAbsolutePath().normalize();
        this.containerName = containerName;

        if (azureConnectionString != null && !azureConnectionString.isBlank()) {
            BlobServiceClient client;
            try {
                client = new BlobServiceClientBuilder().connectionString(azureConnectionString).buildClient();
                BlobContainerClient container = client.getBlobContainerClient(containerName);
                if (!container.exists()) container.create();
                log.info("FileStorageService: using Azure Blob Storage (container={})", containerName);
            } catch (Exception e) {
                log.warn("Azure Blob configured but unreachable ({}); falling back to local disk storage", e.getMessage());
                client = null;
            }
            this.blobServiceClient = client;
            this.azureEnabled = client != null;
        } else {
            this.blobServiceClient = null;
            this.azureEnabled = false;
            log.info("FileStorageService: using local disk storage at {}", localRoot);
        }

        if (!azureEnabled) {
            try {
                Files.createDirectories(localRoot);
            } catch (IOException e) {
                throw new IllegalStateException("Could not create local upload directory " + localRoot, e);
            }
        }
    }

    public record StoredFile(String backend, String storageKey, long sizeBytes) {}

    /** New keys must look exactly like buildKey() output: {orgId}/{Type}/{id}/{uuid}-{safe-name}. */
    private static final Pattern NEW_KEY_PATTERN = Pattern.compile(
            "^[0-9]{1,19}/[A-Za-z]{1,40}/[0-9]{1,19}/[0-9a-f-]{36}-[A-Za-z0-9._-]{1,120}$");
    private static final Pattern RELATED_TYPE_PATTERN = Pattern.compile("^[A-Za-z]{1,40}$");

    /** Content types that are safe to render inline in the browser (never SVG/HTML/XML). */
    public static final Set<String> SAFE_INLINE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf");

    /** Tenant-prefixed key: {orgId}/{relatedType}/{relatedId}/{uuid}-{filename}. */
    public String buildKey(Long orgId, String relatedType, Long relatedId, String originalFileName) {
        if (orgId == null || relatedId == null) throw new IllegalArgumentException("invalid attachment target");
        if (relatedType == null || !RELATED_TYPE_PATTERN.matcher(relatedType).matches()) {
            throw new IllegalArgumentException("invalid relatedType");
        }
        return orgId + "/" + relatedType + "/" + relatedId + "/" + UUID.randomUUID() + "-" + safeKeyName(originalFileName);
    }

    /** Reduces a client-supplied filename to a conservative [A-Za-z0-9._-] token for use inside a storage key. */
    static String safeKeyName(String originalFileName) {
        String name = originalFileName == null ? "" : originalFileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("\\.{2,}", "_");
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isBlank()) name = "file";
        if (name.length() > 120) name = name.substring(name.length() - 120);
        return name;
    }

    /**
     * Sniffs the real content type from the file's magic bytes. Only a small
     * allow-list of formats is recognised; everything else is served as an
     * opaque application/octet-stream download. The client's declared
     * Content-Type is never trusted.
     */
    public static String sniffContentType(byte[] head) {
        if (head == null) return "application/octet-stream";
        if (startsWith(head, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) return "image/png";
        if (startsWith(head, new int[]{0xFF, 0xD8, 0xFF})) return "image/jpeg";
        if (startsWith(head, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(head, "GIF89a".getBytes(StandardCharsets.US_ASCII))) return "image/gif";
        if (head.length >= 12 && startsWith(head, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return "image/webp";
        if (startsWith(head, "%PDF-".getBytes(StandardCharsets.US_ASCII))) return "application/pdf";
        return "application/octet-stream";
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    private static boolean startsWith(byte[] data, int[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((data[i] & 0xFF) != prefix[i]) return false;
        return true;
    }

    /**
     * Keys read back from the DB (possibly written by older code with a looser
     * filename sanitizer) must still never escape the tenant prefix tree.
     */
    private static void requireSafeExistingKey(String storageKey) throws IOException {
        if (storageKey == null || storageKey.isBlank() || storageKey.length() > 600
                || storageKey.startsWith("/") || storageKey.contains("\\") || storageKey.contains(":")
                || storageKey.indexOf((char) 0) >= 0) {
            throw new IOException("Invalid storage key");
        }
        for (String segment : storageKey.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw new IOException("Invalid storage key");
        }
    }

    private Path resolveLocal(String storageKey) throws IOException {
        requireSafeExistingKey(storageKey);
        Path target = localRoot.resolve(storageKey).normalize();
        if (!target.startsWith(localRoot) || target.equals(localRoot)) throw new IOException("Invalid storage key");
        return target;
    }

    public StoredFile store(String storageKey, InputStream data, long sizeHint, String contentType) throws IOException {
        if (storageKey == null || !NEW_KEY_PATTERN.matcher(storageKey).matches()) {
            throw new IOException("Invalid storage key");
        }
        if (azureEnabled) {
            BlobContainerClient container = blobServiceClient.getBlobContainerClient(containerName);
            BlobClient blob = container.getBlobClient(storageKey);
            blob.upload(data, sizeHint > 0 ? sizeHint : -1, true);
            if (contentType != null) {
                blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
            }
            long size = blob.getProperties().getBlobSize();
            return new StoredFile("azure_blob", storageKey, size);
        }
        Path target = resolveLocal(storageKey);
        Files.createDirectories(target.getParent());
        long size = Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredFile("local", storageKey, size);
    }

    public InputStream load(String backend, String storageKey) throws IOException {
        requireSafeExistingKey(storageKey);
        if ("azure_blob".equals(backend)) {
            if (!azureEnabled) throw new IOException("Azure Blob storage is not currently configured");
            BlobContainerClient container = blobServiceClient.getBlobContainerClient(containerName);
            return container.getBlobClient(storageKey).openInputStream();
        }
        return Files.newInputStream(resolveLocal(storageKey));
    }

    public void delete(String backend, String storageKey) {
        try {
            requireSafeExistingKey(storageKey);
            if ("azure_blob".equals(backend)) {
                if (azureEnabled) blobServiceClient.getBlobContainerClient(containerName).getBlobClient(storageKey).deleteIfExists();
            } else {
                Files.deleteIfExists(resolveLocal(storageKey));
            }
        } catch (Exception e) {
            log.warn("Failed to delete attachment blob (backend={}): {}", backend, e.getClass().getSimpleName());
        }
    }
}
