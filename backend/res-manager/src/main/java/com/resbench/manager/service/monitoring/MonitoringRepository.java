package com.resbench.manager.service.monitoring;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.manager.config.properties.MonitoringProperties;
import com.resbench.manager.model.monitoring.MonitoringModels.SeriesResponse;
import com.resbench.manager.model.monitoring.MonitoringModels.Session;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Repository;

@Repository
public class MonitoringRepository {
  private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9._-]{1,160}");

  private final ObjectMapper objectMapper;
  private final Path root;
  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
  private final ExecutorService deleteExecutor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "monitoring-archive-delete");
    thread.setDaemon(true);
    return thread;
  });

  public MonitoringRepository(ObjectMapper objectMapper, MonitoringProperties properties) {
    this.objectMapper = objectMapper;
    this.root = Path.of(properties.getDataDir()).toAbsolutePath().normalize();
    try {
      Files.createDirectories(root);
      Files.createDirectories(root.resolve("deleted"));
      loadSessions();
    } catch (IOException ex) {
      throw new IllegalStateException("cannot initialize monitoring archive directory", ex);
    }
  }

  public synchronized void save(Session session) {
    validateRunId(session.getRunId());
    sessions.put(session.getRunId(), session);
    Path directory = runDirectory(session.getRunId());
    try {
      Files.createDirectories(directory);
      writeAtomic(directory.resolve("session.json"), session);
    } catch (IOException ex) {
      throw new IllegalStateException("cannot save monitoring session", ex);
    }
  }

  public Optional<Session> find(String runId) {
    validateRunId(runId);
    return Optional.ofNullable(sessions.get(runId));
  }

  public List<Session> list() {
    List<Session> result = new ArrayList<>(sessions.values());
    result.sort(Comparator.comparingLong(Session::getCreatedAt).reversed());
    return result;
  }

  public synchronized void writeSeries(String runId, SeriesResponse response) {
    validateRunId(runId);
    Path directory = runDirectory(runId);
    try {
      Files.createDirectories(directory);
      writeAtomic(directory.resolve("series.json"), response);
    } catch (IOException ex) {
      throw new IllegalStateException("cannot archive monitoring series", ex);
    }
  }

  public Optional<SeriesResponse> readSeries(String runId) {
    validateRunId(runId);
    Path path = runDirectory(runId).resolve("series.json");
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(path.toFile(), SeriesResponse.class));
    } catch (IOException ex) {
      throw new IllegalStateException("cannot read monitoring archive", ex);
    }
  }

  public synchronized boolean delete(String runId) {
    validateRunId(runId);
    Session removed = sessions.remove(runId);
    Path source = runDirectory(runId);
    if (removed == null && !Files.exists(source)) {
      return false;
    }
    Path deleting = root.resolve(".deleting-" + runId + "-" + System.currentTimeMillis());
    try {
      if (Files.exists(source)) {
        try {
          Files.move(source, deleting, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
          Files.move(source, deleting);
        }
      }
      writeAtomic(root.resolve("deleted").resolve(runId + ".json"),
          java.util.Map.of("runId", runId, "deletedAt", System.currentTimeMillis()));
    } catch (IOException ex) {
      throw new IllegalStateException("cannot mark monitoring archive for deletion", ex);
    }
    deleteExecutor.submit(() -> deleteTree(deleting));
    return true;
  }

  public boolean hasTombstone(String runId) {
    validateRunId(runId);
    return Files.exists(root.resolve("deleted").resolve(runId + ".json"));
  }

  private void loadSessions() throws IOException {
    try (var directories = Files.list(root)) {
      directories
          .filter(Files::isDirectory)
          .filter(path -> !path.getFileName().toString().startsWith("."))
          .filter(path -> !"deleted".equals(path.getFileName().toString()))
          .forEach(path -> {
            Path sessionPath = path.resolve("session.json");
            if (!Files.isRegularFile(sessionPath)) {
              return;
            }
            try {
              Session session = objectMapper.readValue(sessionPath.toFile(), Session.class);
              if (session.getRunId() != null && SAFE_RUN_ID.matcher(session.getRunId()).matches()) {
                sessions.put(session.getRunId(), session);
              }
            } catch (IOException ignored) {
              // A damaged monitoring archive must not prevent ResBench startup.
            }
          });
    }
  }

  private void writeAtomic(Path path, Object value) throws IOException {
    Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
    try {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ex) {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private Path runDirectory(String runId) {
    Path path = root.resolve(runId).normalize();
    if (!path.getParent().equals(root)) {
      throw new IllegalArgumentException("invalid runId");
    }
    return path;
  }

  private void validateRunId(String runId) {
    if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
      throw new IllegalArgumentException("invalid runId");
    }
  }

  private void deleteTree(Path rootToDelete) {
    if (!Files.exists(rootToDelete)) {
      return;
    }
    try {
      Files.walkFileTree(rootToDelete, new java.nio.file.SimpleFileVisitor<>() {
        @Override
        public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.deleteIfExists(file);
          return java.nio.file.FileVisitResult.CONTINUE;
        }

        @Override
        public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.deleteIfExists(dir);
          return java.nio.file.FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException ignored) {
      // Retention cleanup will retry leftover .deleting directories later.
    }
  }

  @PreDestroy
  public void close() {
    deleteExecutor.shutdown();
  }
}
