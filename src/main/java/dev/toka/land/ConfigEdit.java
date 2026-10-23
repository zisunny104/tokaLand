package dev.toka.land;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Changes one true/false value under a top-level section by editing its line, so the comments and the order of the file stay.
 * The server's config API rewrites the whole file and loses every comment when it saves.
 */
final class ConfigEdit {
    private ConfigEdit() {}

    static void setBoolean(Path file, String section, String key, boolean value) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (!apply(lines, section, key, value)) throw new IOException("Cannot find " + section + "." + key + " in " + file.getFileName());
        Path temporary = file.resolveSibling(file.getFileName() + ".pending");
        Files.writeString(temporary, String.join(eol, lines), StandardCharsets.UTF_8);
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** True when the line was found and changed. */
    static boolean apply(List<String> lines, String section, String key, boolean value) {
        Pattern entry = Pattern.compile("^(\\s+" + Pattern.quote(key) + ":\\s*)(true|false)\\b(.*)$");
        boolean inside = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.equals(section + ":")) { inside = true; continue; }
            if (!inside) continue;
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("#")) return false;
            Matcher matcher = entry.matcher(line);
            if (matcher.matches()) { lines.set(i, matcher.group(1) + value + matcher.group(3)); return true; }
        }
        return false;
    }
}
