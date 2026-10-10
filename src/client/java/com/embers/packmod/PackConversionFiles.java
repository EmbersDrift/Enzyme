package com.embers.packmod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Filesystem operations shared by the batch manager and the real conversion checks. */
final class PackConversionFiles {
    static String signature(Path path) throws IOException {
        String signature = Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        long[] bytes = {0};
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.walk(path)) {
                var entries = files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).sorted().iterator();
                int count = 0;
                while (entries.hasNext()) {
                    Path file = entries.next();
                    if (++count > 100_000) throw new IOException("Resource pack exceeds 100,000 files");
                    byte[] relative = path.relativize(file).toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(relative.length).array());
                    digest.update(relative);
                    hashFile(file, digest, bytes);
                }
            }
        } else { hashFile(path, digest, bytes); }
        return signature + ":" + java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void hashFile(Path file, java.security.MessageDigest digest, long[] total) throws IOException {
        long size = Files.size(file), modified = Files.getLastModifiedTime(file).toMillis();
        digest.update(java.nio.ByteBuffer.allocate(16).putLong(size).putLong(modified).array());
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total[0] += count;
                if (total[0] > 500L * 1024 * 1024) throw new IOException("Resource pack exceeds 500 MiB");
                digest.update(buffer, 0, count);
            }
        }
        if (size != Files.size(file) || modified != Files.getLastModifiedTime(file).toMillis())
            throw new IOException("Resource pack changed while computing its content signature");
    }

    static boolean candidate(Path path) {
        return !path.getFileName().toString().equalsIgnoreCase("Old Pack") && PackInspector.candidate(path);
    }

    static Path archive(Path original, String expectedSignature, Path packDirectory) throws IOException {
        return archive(original, expectedSignature, packDirectory, ignored -> {});
    }
    static Path archive(Path original, String expectedSignature, Path packDirectory, java.util.function.Consumer<Path> beforeMove) throws IOException {
        Path root = packDirectory.toAbsolutePath().normalize();
        Path source = original.toAbsolutePath().normalize();
        if (!root.equals(source.getParent()) || !candidate(source) || Files.isSymbolicLink(source))
            throw new IOException("Original resource pack is outside its expected directory");
        Path realRoot = root.toRealPath();
        if (!realRoot.equals(source.toRealPath().getParent()))
            throw new IOException("Original resource pack resolves outside its expected directory");
        if (!expectedSignature.equals(signature(source))) throw new IOException("Original resource pack changed during conversion");
        Path archive = root.resolve("Old Pack");
        if (Files.isSymbolicLink(archive)) throw new IOException("Old Pack must be a local folder, not a symbolic link");
        Files.createDirectories(archive);
        if (!realRoot.equals(archive.toRealPath().getParent()))
            throw new IOException("Old Pack resolves outside the resource pack directory");
        String name = source.getFileName().toString();
        int extension = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) ? -1 : name.lastIndexOf('.');
        String stem = extension > 0 ? name.substring(0, extension) : name;
        String suffix = extension > 0 ? name.substring(extension) : "";
        int number = 0;
        while (true) {
            Path destination = archive.resolve(number == 0 ? name : stem + " (" + number + ")" + suffix).normalize();
            // Verify resolved paths before moving even directory-form packs.
            if (!archive.equals(destination.getParent())) throw new IOException("Invalid resource pack archive path");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { number++; continue; }
            beforeMove.accept(destination);
            try { return Files.move(source, destination); }
            catch (java.nio.file.FileAlreadyExistsException collision) { number++; }
        }
    }
}
