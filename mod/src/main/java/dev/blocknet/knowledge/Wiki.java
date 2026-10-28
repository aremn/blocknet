// SPDX-License-Identifier: MIT
package dev.blocknet.knowledge;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Public guide knowledge only: no world blocks, client classes or content-mod dependencies. */
public final class Wiki {
    private static final Map<MinecraftServer, Wiki> CACHE = new WeakHashMap<>();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, String> lang = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, String> resources = new LinkedHashMap<>();
    private final Map<String, Integer> formats = new LinkedHashMap<>();
    private record Page(String source, String title, String text, Map<String, Integer> terms) {}
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    public static void started(ServerStartedEvent event) { CACHE.remove(event.getServer()); }
    public static void reload(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) CACHE.remove(event.getPlayerList().getServer());
    }
    public static Wiki get(MinecraftServer server) { return CACHE.computeIfAbsent(server, Wiki::new); }

    private Wiki(MinecraftServer server) {
        for (String format : List.of("GuideME", "Patchouli", "FTB Quests", "tooltips")) formats.put(format, 0);
        // Assets are absent from a dedicated server's SERVER_DATA manager. Read the loaded
        // mod roots too (jar-backed Paths and development directories use the same API).
        for (var file : ModList.get().getModFiles()) {
            for (String type : List.of("assets", "data")) scan(file.getFile().findResource(type), type);
        }
        server.getResourceManager().listResources("", id -> interesting(id.getPath())).forEach((id, resource) -> {
            try (var reader = resource.openAsReader()) {
                resources.put("data/" + id.getNamespace() + "/" + id.getPath(), reader.lines().collect(Collectors.joining("\n")));
            } catch (Exception ignored) { /* Optional formats/resources are best effort. */ }
        });
        // Resolve translated book text only after every namespace's English dictionary is read.
        resources.entrySet().stream().sorted(Comparator.comparing(e -> !e.getKey().endsWith("/lang/en_us.json")))
            .forEach(e -> {
                try { ingest(e.getKey(), e.getValue()); } catch (RuntimeException ignored) { }
            });
        resources.clear();
        for (var item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            String name = lang.getOrDefault(item.getDescriptionId(), item.getDescription().getString());
            names.put(id, name);
            add("tooltips", "item: " + id, name, id + " " + name);
        }
        Path quests = FMLPaths.CONFIGDIR.get().resolve("ftbquests/quests");
        if (Files.isDirectory(quests)) try (var files = Files.walk(quests)) {
            files.filter(p -> p.toString().endsWith(".snbt")).sorted().forEach(p -> {
                try {
                    var tag = readQuest(p);
                    indexQuests(tag, "quest: " + quests.relativize(p).toString().replace('\\', '/'));
                } catch (Exception ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static boolean interesting(String path) {
        return path.endsWith(".md") || path.endsWith("/lang/en_us.json")
            || path.startsWith("lang/en_us.json") || path.contains("patchouli_books/") && path.endsWith(".json");
    }
    private void scan(Path root, String type) {
        if (!Files.isDirectory(root)) return;
        try (var files = Files.walk(root)) {
            files.filter(Files::isRegularFile).filter(p -> interesting(p.toString().replace('\\', '/'))).sorted().forEach(p -> {
                try { resources.put(type + "/" + root.relativize(p).toString().replace('\\', '/'), Files.readString(p)); }
                catch (Exception ignored) { }
            });
        } catch (IOException ignored) { }
    }
    private void ingest(String path, String raw) {
        String[] parts = path.split("/", 4);
        if (parts.length < 4) return;
        String ns = parts[1], relative = parts[2] + "/" + parts[3];
        if (relative.equals("lang/en_us.json")) {
            var json = JsonParser.parseString(raw).getAsJsonObject();
            json.entrySet().forEach(e -> {
                if (!e.getValue().isJsonPrimitive()) return;
                String key = e.getKey(), value = e.getValue().getAsString(); lang.put(key, value);
                if (key.startsWith("item.") || key.startsWith("block.") || key.matches(".*\\.(desc|tooltip|info)(\\..*)?"))
                    add("tooltips", "tooltip: " + ns + "/" + key, key, value);
            });
        } else if (relative.endsWith(".md")) {
            // GuideME: arbitrary guide folder, YAML front matter, then Markdown. Default
            // language lives directly in that folder; translated copies live in locale folders.
            if (relative.matches(".*/(?!en_us/)[a-z]{2}_[a-z]{2}/.*")) return;
            int end = raw.startsWith("---") ? raw.indexOf("---", 3) : -1;
            String header = end < 0 ? "" : raw.substring(3, end), body = end < 0 ? raw : raw.substring(end + 3);
            var title = Pattern.compile("(?m)^\\s*title:\\s*(.+)$").matcher(header);
            var heading = Pattern.compile("(?m)^#\\s+(.+)$").matcher(body);
            String name = title.find() ? title.group(1) : heading.find() ? heading.group(1) : relative;
            add("GuideME", "guide: " + ns + "/" + parts[3].replaceFirst("\\.md$", ""), name, body + " " + header);
        } else if (relative.contains("patchouli_books/") && relative.contains("/entries/")) {
            var json = JsonParser.parseString(raw).getAsJsonObject();
            String title = json.has("name") ? json.get("name").getAsString() : relative;
            List<String> text = new ArrayList<>(); jsonText(json, text);
            add("Patchouli", "book: " + ns + "/" + relative.replaceFirst("\\.json$", ""), translate(title), String.join(" ", text));
        }
    }
    private String translate(String text) { return lang.getOrDefault(text, text); }
    private void jsonText(JsonElement value, List<String> out) {
        if (value.isJsonArray()) value.getAsJsonArray().forEach(v -> jsonText(v, out));
        else if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(e -> {
            if (List.of("name", "title", "text", "item", "items", "pages", "description").contains(e.getKey())) jsonText(e.getValue(), out);
        });
        else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) out.add(translate(value.getAsString()));
    }
    private static void questText(Tag value, List<String> out) {
        if (value instanceof CompoundTag c) for (String key : c.getAllKeys()) {
            Tag child = c.get(key);
            if (child instanceof CompoundTag || child instanceof ListTag || List.of("title", "description", "subtitle", "item", "id", "count").contains(key)) questText(child, out);
        }
        else if (value instanceof ListTag list) list.forEach(v -> questText(v, out));
        else if (value instanceof StringTag s) out.add(s.getAsString());
    }
    private void indexQuests(CompoundTag chapter, String source) {
        List<String> text = new ArrayList<>(); questText(chapter, text);
        add("FTB Quests", source, chapter.getString("title"), String.join(" ", text));
        var quests = chapter.getList("quests", Tag.TAG_COMPOUND);
        for (int i = 0; i < quests.size(); i++) {
            CompoundTag quest = quests.getCompound(i);
            text = new ArrayList<>(); questText(quest, text);
            String id = quest.getString("id");
            add("FTB Quests", source + "#" + (id.isBlank() ? i : id), quest.getString("title"), String.join(" ", text));
        }
    }
    private static CompoundTag readQuest(Path path) throws Exception {
        // FTB's SNBT permits syntax beyond vanilla SNBT (e.g. omitted commas). Use the
        // framework parser when available, without introducing a required mod dependency.
        try {
            Class<?> parser = Class.forName("dev.ftb.mods.ftblibrary.snbt.SNBT");
            Object tag = parser.getMethod("read", Path.class).invoke(null, path);
            if (tag instanceof CompoundTag compound) return compound;
        } catch (ClassNotFoundException | NoSuchMethodException ignored) { }
        return TagParser.parseTag(Files.readString(path));
    }
    private static String clean(String text) {
        return text.replaceAll("(?s)<[^>]+>", " ").replaceAll("!?\\[([^]]*)]\\([^)]*\\)", "$1")
            .replaceAll("\\$\\([^)]*\\)", " ").replaceAll("[§][0-9a-fk-or]", "")
            .replaceAll("[#*`|]", " ").replaceAll("\\s+", " ").trim();
    }
    private static Map<String, Integer> terms(String text) {
        Map<String, Integer> result = new HashMap<>();
        WORD.matcher(text.toLowerCase(Locale.ROOT)).results().forEach(m -> result.merge(m.group(), 1, Integer::sum));
        return result;
    }
    private void add(String format, String source, String title, String text) {
        text = clean(text); title = clean(title);
        if (text.isBlank()) return;
        if (!pages.containsKey(source)) formats.merge(format, 1, Integer::sum);
        pages.put(source, new Page(source, title, text, terms(title + " " + text)));
    }
    public String status() {
        return formats.entrySet().stream().map(e -> e.getKey() + ": " + (e.getValue() == 0 ? "skipped (not found in runtime)" : e.getValue() + " pages"))
            .collect(Collectors.joining("; "));
    }
    public String search(String query) {
        query = query.replaceAll("^\"|\"$", "").trim();
        String expanded = names.containsKey(query) ? query.substring(query.indexOf(':') + 1) + " " + names.get(query) : query;
        Set<String> words = terms(expanded).keySet();
        if (words.isEmpty()) return "nothing found";
        Map<String, Long> df = new HashMap<>();
        for (String w : words) df.put(w, pages.values().stream().filter(p -> p.terms.containsKey(w)).count());
        record Hit(Page page, double score) {}
        List<Hit> hits = new ArrayList<>();
        for (Page p : pages.values()) {
            double score = 0; int matched = 0;
            for (String w : words) {
                int tf = p.terms.getOrDefault(w, 0); if (tf == 0) continue; matched++;
                score += Math.log(1 + (pages.size() + 1.0) / (df.get(w) + 1)) * tf / (tf + 1.2 + p.terms.size() / 150.0)
                    * (terms(p.title).containsKey(w) ? 3 : 1);
            }
            if (matched == 0) continue;
            score *= (double) matched / words.size();
            if (names.containsKey(query) && p.text.contains(query)) score *= 3;
            if (p.source.startsWith("guide:") || p.source.startsWith("book:") || p.source.startsWith("quest:")) score *= 1.5;
            hits.add(new Hit(p, score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h -> h.page.source));
        if (hits.isEmpty()) return "nothing found";
        List<String> snippets = new ArrayList<>();
        for (Hit hit : hits.subList(0, Math.min(3, hits.size()))) {
            Page p = hit.page;
            String title = Arrays.stream(p.title.split("\\s+")).limit(6).collect(Collectors.joining(" "));
            String snippet = Arrays.stream(p.text.split("\\s+")).limit(23).collect(Collectors.joining(" "));
            snippets.add(p.source + " — " + title + "\n" + snippet);
        }
        return String.join("\n", snippets);
    }
}
