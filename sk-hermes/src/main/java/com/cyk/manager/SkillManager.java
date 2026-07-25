package com.cyk.manager;

import com.cyk.bean.Skill;
import com.cyk.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class SkillManager {

    private static final Logger logger = LoggerFactory.getLogger(SkillManager.class);

    private final Path skillsDir;
    private final Map<String, Skill> cache = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static final SkillManager INSTANCE = new SkillManager();

    public static SkillManager getInstance() {
        return INSTANCE;
    }

    private SkillManager() {
        this.skillsDir = Constants.getHermesHome().resolve("skills");
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            logger.error("Failed to create skills directory: {}", e.getMessage());
        }
        loadAll();
    }

    private void loadAll() {
        lock.writeLock().lock();
        try {
            cache.clear();
            if (!Files.exists(skillsDir)) return;

            // Scan flat .md files (traditional single-file skills)
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, "*.md")) {
                for (Path file : stream) {
                    try {
                        String raw = Files.readString(file, StandardCharsets.UTF_8);
                        Skill skill = Skill.fromMarkdown(raw);
                        if (skill != null) {
                            cache.put(skill.getName(), skill);
                        } else {
                            logger.warn("Skipping unparseable skill file: {}", file.getFileName());
                        }
                    } catch (IOException e) {
                        logger.warn("Failed to read skill file: {} - {}", file.getFileName(), e.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to list skills directory: {}", e.getMessage());
            }

            // Scan subdirectories for directory-based skills (SKILL.md manifest)
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, Files::isDirectory)) {
                for (Path dir : stream) {
                    Path manifest = dir.resolve("SKILL.md");
                    if (!Files.exists(manifest)) {
                        logger.debug("Skipping directory without SKILL.md: {}", dir.getFileName());
                        continue;
                    }
                    try {
                        String raw = Files.readString(manifest, StandardCharsets.UTF_8);
                        Skill skill = Skill.fromMarkdown(raw);
                        if (skill != null) {
                            skill.setDirectory(dir);
                            cache.put(skill.getName(), skill);
                        } else {
                            logger.warn("Skipping unparseable skill manifest: {}", manifest);
                        }
                    } catch (IOException e) {
                        logger.warn("Failed to read skill manifest: {} - {}", manifest, e.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to list skill directories: {}", e.getMessage());
            }

            logger.info("Loaded {} skills from {}", cache.size(), skillsDir);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Skill> listAll() {
        lock.readLock().lock();
        try {
            return cache.values().stream()
                    .sorted(Comparator.comparing(Skill::getName))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Skill> get(String name) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(cache.get(name));
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Skill> search(String query) {
        lock.readLock().lock();
        try {
            String lowerQuery = query.toLowerCase();
            return cache.values().stream()
                    .filter(s -> matches(s, lowerQuery))
                    .sorted(Comparator.comparing(Skill::getName))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean matches(Skill skill, String lowerQuery) {
        if (skill.getName() != null && skill.getName().toLowerCase().contains(lowerQuery)) return true;
        if (skill.getDescription() != null && skill.getDescription().toLowerCase().contains(lowerQuery)) return true;
        if (skill.getTags() != null) {
            return skill.getTags().stream().anyMatch(t -> t.toLowerCase().contains(lowerQuery));
        }
        return false;
    }

    public Skill save(Skill skill) {
        lock.writeLock().lock();
        try {
            Skill existing = cache.get(skill.getName());
            if (existing != null) {
                skill.setCreatedAt(existing.getCreatedAt());
                skill.setVersion(existing.getVersion() + 1);
            } else {
                if (skill.getCreatedAt() == null) {
                    skill.setCreatedAt(Instant.now());
                }
                skill.setVersion(1);
            }
            skill.setUpdatedAt(Instant.now());

            Path file;
            if (skill.getDirectory() != null) {
                // Directory-based skill: write to SKILL.md inside the directory
                file = skill.getDirectory().resolve("SKILL.md");
            } else {
                file = skillsDir.resolve(skill.getName() + ".md");
            }
            Files.writeString(file, skill.toMarkdown(), StandardCharsets.UTF_8);

            cache.put(skill.getName(), skill);

            logger.info("Saved skill: {} (v{})", skill.getName(), skill.getVersion());
            return skill;
        } catch (IOException e) {
            logger.error("Failed to save skill {}: {}", skill.getName(), e.getMessage());
            throw new RuntimeException("Failed to save skill: " + skill.getName(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean delete(String name) {
        lock.writeLock().lock();
        try {
            Skill removed = cache.remove(name);
            if (removed == null) {
                return false;
            }

            if (removed.getDirectory() != null) {
                // Directory-based skill: recursively delete the entire directory
                try {
                    try (var walk = Files.walk(removed.getDirectory())) {
                        walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    logger.warn("Failed to delete: {}", path);
                                }

                            });
                    }
                } catch (IOException e) {
                    logger.warn("Failed to delete skill directory {}: {}", removed.getDirectory(), e.getMessage());
                }
            } else {
                Path file = skillsDir.resolve(name + ".md");
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    logger.warn("Failed to delete skill file {}: {}", file, e.getMessage());
                }
            }

            logger.info("Deleted skill: {}", name);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void reload() {
        loadAll();
    }
}
