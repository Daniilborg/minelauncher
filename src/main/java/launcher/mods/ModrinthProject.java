package launcher.mods;

/** Один результат поиска в Modrinth: мод, ресурспак или шейдер. */
public record ModrinthProject(
        String projectId,
        String slug,
        String title,
        String description,
        String iconUrl,
        int downloads,
        String author,
        String projectType
) {}
