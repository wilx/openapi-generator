package org.openapitools.codegen;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.openapitools.codegen.api.TemplatePathLocator;
import org.openapitools.codegen.api.TemplateProcessor;
import org.openapitools.codegen.api.TemplatingEngineAdapter;
import org.openapitools.codegen.api.TemplatingExecutor;
import org.openapitools.codegen.templating.TemplateManagerOptions;
import org.openapitools.codegen.templating.TemplateNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Manages the lookup, compilation, and writing of template files
 */
public class TemplateManager implements TemplatingExecutor, TemplateProcessor {
    private final TemplateManagerOptions options;
    private final TemplatingEngineAdapter engineAdapter;
    private final TemplatePathLocator[] templateLoaders;

    private final Logger LOGGER = LoggerFactory.getLogger(TemplateManager.class);

    /** Cache of resolved template path -> raw template content, populated on first read per run. */
    private final Map<String, String> templateContentCache = new ConcurrentHashMap<>();

    /**
     * Constructs a new instance of a {@link TemplateManager}
     *
     * @param options         The {@link TemplateManagerOptions} for reading and writing templates
     * @param engineAdapter   The adaptor to underlying templating engine
     * @param templateLoaders Loaders which define where we look for templates
     */
    public TemplateManager(
            TemplateManagerOptions options,
            TemplatingEngineAdapter engineAdapter,
            TemplatePathLocator[] templateLoaders) {
        this.options = options;
        this.engineAdapter = engineAdapter;
        this.templateLoaders = templateLoaders;
    }

    private String getFullTemplateFile(String name) {
        String template = Arrays.stream(this.templateLoaders)
                .map(i -> i.getFullTemplatePath(name))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");

        if (StringUtils.isEmpty(template)) {
            throw new TemplateNotFoundException(name);
        }

        if (name == null || name.contains("..")) {
            throw new IllegalArgumentException("Template location must be constrained to template directory.");
        }

        return template;
    }

    /**
     * returns the template content by name
     *
     * @param name the template name (e.g. model.mustache)
     * @return the contents of that template
     */
    @Override
    public String getFullTemplateContents(String name) {
        String fullPath = getFullTemplateFile(name);
        return templateContentCache.computeIfAbsent(fullPath, this::readTemplate);
    }

    /**
     * Returns the path of a template, allowing access to the template where consuming literal contents aren't desirable or possible.
     *
     * @param name the template name (e.g. model.mustache)
     * @return The {@link Path} to the template
     */
    @Override
    public Path getFullTemplatePath(String name) {
        return Paths.get(getFullTemplateFile(name));
    }

    /**
     * Pre-compiled pattern for replacing the OS file separator with '/' in classpath resource paths.
     * Only non-null on operating systems where {@link File#separator} is not already '/'.
     */
    private static final Pattern FILE_SEP_PATTERN =
            "/".equals(File.separator) ? null : Pattern.compile(Pattern.quote(File.separator));

    /**
     * Gets a normalized classpath resource location according to OS-specific file separator
     *
     * @param name The name of the resource file/directory to find
     * @return A normalized string according to OS-specific file separator
     */
    public static String getCPResourcePath(final String name) {
        if (FILE_SEP_PATTERN != null) {
            return FILE_SEP_PATTERN.matcher(name).replaceAll("/");
        }
        return name;
    }

    /**
     * Reads a template's contents from the specified location
     *
     * @param name The location of the template
     * @return The raw template contents
     */
    @SuppressWarnings("java:S112")
    // ignored rule java:S112 as RuntimeException is used to match previous exception type
    public String readTemplate(String name) {
        if (name == null || name.contains("..")) {
            throw new IllegalArgumentException("Template location must be constrained to template directory.");
        }
        try (Reader reader = getTemplateReader(name)) {
            if (reader == null) {
                throw new RuntimeException("no file found");
            }
            try (Scanner s = new Scanner(reader).useDelimiter("\\A")) {
                return s.hasNext() ? s.next() : "";
            }
        } catch (Exception e) {
            LOGGER.error("{}", e.getMessage(), e);
        }
        throw new RuntimeException("can't load template " + name);
    }

    @SuppressWarnings({"squid:S2095", "java:S112"})
    // ignored rule squid:S2095 as used in the CLI and it's required to return a reader
    // ignored rule java:S112 as RuntimeException is used to match previous exception type
    public Reader getTemplateReader(String name) {
        try {
            InputStream is = getInputStream(name);
            return new InputStreamReader(is, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
            throw new RuntimeException("can't load template " + name);
        }
    }

    private InputStream getInputStream(String name) throws IOException {
        if (name == null || name.contains("..")) {
            throw new IllegalArgumentException("Template location must be constrained to template directory.");
        }
        String cpResourcePath = getCPResourcePath(name);
        URL resource = this.getClass().getClassLoader().getResource(cpResourcePath);
        if (resource != null) {
            // Open a fresh, non-cached connection each time.
            // setUseCaches(false) prevents sharing the underlying JarFile across classloaders,
            // which avoids "Stream closed" errors when concurrent Gradle workers use isolated
            // classloaders that happen to point to the same JAR URL.
            URLConnection conn = resource.openConnection();
            conn.setUseCaches(false);
            return conn.getInputStream();
        }
        return new FileInputStream(name); // May throw but never return a null value
    }

    /**
     * Writes data to a compiled template
     *
     * @param data     Input data
     * @param template Input template location
     * @param target   The targeted file output location
     * @return The actual file
     */
    @Override
    public File write(Map<String, Object> data, String template, File target) throws IOException {
        if (this.engineAdapter.handlesFile(template)) {
            // Only pass files with valid endings through template engine
            return writeTemplateToFile(target.toPath(), data, template);
        } else {
            // Do a straight copy of the file if not listed as supported by the template engine.
            String fullTemplatePath = null;
            try {
                // look up the file using the same template resolution logic the adapters would use.
                fullTemplatePath = getFullTemplateFile(template);
            } catch (TemplateNotFoundException ex) {
                // not found on classpath; fall through to direct file read below
            }
            if (fullTemplatePath != null) {
                try (InputStream is = getInputStream(fullTemplatePath)) {
                    return writeToFile(target.getAbsolutePath(), IOUtils.toByteArray(is));
                }
            } else {
                try (InputStream is = Files.newInputStream(Paths.get(template))) {
                    return writeToFile(target.getAbsolutePath(), IOUtils.toByteArray(is));
                }
            }
        }
    }

    @Override
    public void ignore(Path path, String context) {
        LOGGER.info("Ignored {} ({})", path, context);
    }

    @Override
    public void skip(Path path, String context) {
        LOGGER.info("Skipped {} ({})", path, context);
    }

    /**
     * Write String to a file, formatting as UTF-8
     *
     * @param filename The name of file to write
     * @param contents The contents string.
     * @return File representing the written file.
     * @throws IOException If file cannot be written.
     */
    public File writeToFile(String filename, String contents) throws IOException {
        return writeToFile(filename, contents.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes rendered template output to a file without materializing the full rendered content as a string.
     *
     * @param filename The name of file to write
     * @param data Input data for the template
     * @param template The template location
     * @return File representing the written file.
     * @throws IOException If file cannot be written.
     */
    public File writeTemplateToFile(String filename, Map<String, Object> data, String template) throws IOException {
        return writeTemplateToFile(Paths.get(filename), data, template);
    }

    private File writeTemplateToFile(Path outputPath, Map<String, Object> data, String template) throws IOException {
        if (this.options.isSkipOverwrite() && Files.exists(outputPath)) {
            LOGGER.info("skip overwrite of file {}", outputPath);
            return outputPath.toFile();
        }

        if (this.options.isMinimalUpdate()) {
            Path tempPath = createTempFile(outputPath);
            try {
                writeTemplateToFileRaw(tempPath, data, template);
                if (!filesEqual(tempPath, outputPath)) {
                    LOGGER.info("writing file {}", outputPath);
                    Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
                    tempPath = null;
                } else {
                    LOGGER.info("skipping unchanged file {}", outputPath);
                }
            } finally {
                if (tempPath != null && Files.exists(tempPath)) {
                    try {
                        Files.delete(tempPath);
                    } catch (Exception ex) {
                        LOGGER.error("Error removing temporary file {}", tempPath, ex);
                    }
                }
            }
        } else {
            LOGGER.info("writing file {}", outputPath);
            Path tempPath = createTempFile(outputPath);
            try {
                writeTemplateToFileRaw(tempPath, data, template);
                Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
                tempPath = null;
            } finally {
                if (tempPath != null && Files.exists(tempPath)) {
                    try {
                        Files.delete(tempPath);
                    } catch (Exception ex) {
                        LOGGER.error("Error removing temporary file {}", tempPath, ex);
                    }
                }
            }
        }

        return outputPath.toFile();
    }

    private Path createTempFile(Path outputPath) throws IOException {
        Path absoluteOutputPath = outputPath.toAbsolutePath();
        Path outputDirectory = absoluteOutputPath.getParent();
        Files.createDirectories(outputDirectory);
        String fileName = absoluteOutputPath.getFileName().toString();
        String prefix = fileName.length() < 3 ? fileName + "..." : fileName + ".";
        return Files.createTempFile(outputDirectory, prefix, ".tmp");
    }

    /**
     * Write bytes to a file
     *
     * @param filename The name of file to write
     * @param contents The contents bytes.  Typically, this is a UTF-8 formatted string.
     * @return File representing the written file.
     * @throws IOException If file cannot be written.
     */
    @Override
    public File writeToFile(String filename, byte[] contents) throws IOException {
        // Use Paths.get here to normalize path (for Windows file separator, space escaping on Linux/Mac, etc)
        Path outputPath = Paths.get(filename);

        if (this.options.isMinimalUpdate()) {
            Path tempPath = Paths.get(filename + ".tmp");
            try {
                writeToFileRaw(tempPath, contents);
                if (!filesEqual(tempPath, outputPath)) {
                    LOGGER.info("writing file {}", filename);
                    Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
                    tempPath = null;
                } else {
                    LOGGER.info("skipping unchanged file {}", filename);
                }
            } finally {
                if (tempPath != null && Files.exists(tempPath)) {
                    try {
                        Files.delete(tempPath);
                    } catch (Exception ex) {
                        LOGGER.error("Error removing temporary file {}", tempPath, ex);
                    }
                }
            }
        } else {
            LOGGER.info("writing file {}", filename);
            writeToFileRaw(outputPath, contents);
        }

        return outputPath.toFile();
    }

    private void writeToFileRaw(Path outputPath, byte[] contents) throws IOException {
        if (this.options.isSkipOverwrite() && Files.exists(outputPath)) {
            LOGGER.info("skip overwrite of file {}", outputPath);
            return;
        }

        Path outputDirectory = outputPath.getParent();
        if (outputDirectory != null) {
            Files.createDirectories(outputDirectory);
        }
        Files.write(outputPath, contents);
    }

    private Path writeTemplateToFileRaw(Path outputPath, Map<String, Object> data, String template) throws IOException {
        Path outputDirectory = outputPath.getParent();
        if (outputDirectory != null) {
            Files.createDirectories(outputDirectory);
        }

        try (Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            this.engineAdapter.writeTemplate(this, data, template, writer);
        }

        return outputPath;
    }

    private boolean filesEqual(Path file1, Path file2) throws IOException {
        if (!Files.exists(file1) || !Files.exists(file2)) return false;
        if (Files.size(file1) != Files.size(file2)) return false;
        try (InputStream is1 = Files.newInputStream(file1);
             InputStream is2 = Files.newInputStream(file2)) {
            return IOUtils.contentEquals(is1, is2);
        }
    }

}
