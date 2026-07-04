package launcher.launch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Простой параллельный загрузчик файлов. Пропускает файл, если он уже
 * скачан и его SHA1 совпадает (это то, что делает скачивание "инкрементальным" —
 * при повторном запуске перекачиваются только недостающие/битые файлы).
 */
public final class Downloader {

    private final HttpClient http = HttpClient.newHttpClient();

    /** Одна задача: откуда скачать, куда сохранить, и ожидаемый sha1 (можно null, если хэш неизвестен). */
    public record FileTask(String url, Path destination, String sha1) {}

    /** Скачивает список файлов в несколько потоков, сообщая прогресс через onProgress(done, total).
     *  Если хоть один файл не скачался — бросает исключение с перечнем ошибок (после попытки скачать все остальные). */
    public void downloadAll(List<FileTask> tasks, BiConsumer<Integer, Integer> onProgress) throws InterruptedException {
        int total = tasks.size();
        AtomicInteger done = new AtomicInteger(0);
        List<String> failures = Collections.synchronizedList(new java.util.ArrayList<>());
        int threads = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (FileTask task : tasks) {
            pool.submit(() -> {
                try {
                    downloadOne(task);
                } catch (Exception e) {
                    String msg = task.url() + ": " + e.getMessage();
                    System.err.println("Не удалось скачать " + msg);
                    failures.add(msg);
                } finally {
                    onProgress.accept(done.incrementAndGet(), total);
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.MINUTES);

        if (!failures.isEmpty()) {
            String preview = failures.stream().limit(5).collect(Collectors.joining("; "));
            throw new RuntimeException(failures.size() + " файл(ов) не скачалось: " + preview);
        }
    }

    private void downloadOne(FileTask task) throws Exception {
        Path dest = task.destination();
        if (Files.exists(dest) && (task.sha1() == null || sha1Of(dest).equalsIgnoreCase(task.sha1()))) {
            return; // уже скачано и хэш совпадает — пропускаем
        }
        Files.createDirectories(dest.getParent());

        Path tmp = Path.of(dest.toString() + ".tmp");
        Files.deleteIfExists(tmp);

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(task.url())).GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if (resp.statusCode() / 100 != 2) {
            Files.deleteIfExists(tmp);
            throw new IOException("HTTP " + resp.statusCode() + " для " + task.url());
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private String sha1Of(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
