package onl.gassmann.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Created by Henrik Gaßmann on 2016-12-15.
 */
public class FileConfigurationSource
        extends ConfigurationSource
{
    public FileConfigurationSource(Path filePath)
    {
        List<String> lines;
        try
        {
            lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            // todo logging etc.
            return;
        }

        lines.stream()
                .filter(line -> line.length() > 0 && !line.startsWith("#"))
                .forEach(this::addKeyValuePair);
    }
}
