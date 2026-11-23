package com.modulai.core.skills

import java.io.IOException
import java.io.InputStream

/** Read access to the skills shipped with the app; on Android backed by the APK assets. */
interface SkillAssets {
    /** Names of the entries directly under [path]; empty when there is no such folder. */
    fun list(path: String): List<String>
    fun open(path: String): InputStream
}

object BuiltInSkills {
    /**
     * Copies the bundled skills under [dir] into [store]. A bundled skill is installed when it is new
     * to this installation, and updated when the app ships a newer version and the installed copy is
     * still the one the app put there. Skills the user deleted, replaced or edited are left alone.
     * Folders under [dir] without a SKILL.md are skipped, so [dir] may be the assets root.
     */
    fun installMissing(assets: SkillAssets, store: SkillStore, dir: String = "") {
        for (name in assets.list(dir)) {
            val folder = if (dir.isEmpty()) name else "$dir/$name"
            val skillMd = read(assets, "$folder/${SkillParser.FILE_NAME}")?.let { String(it, Charsets.UTF_8) } ?: continue
            val id = runCatching { SkillParser.idFor(SkillParser.parse(skillMd, name).name) }.getOrNull() ?: continue

            val shipped = SkillStore.hash(skillMd)
            val recorded = store.bundledHash(id)
            val installed = store.installedHash(id)
            val wanted = if (recorded == null) installed == null else installed == recorded && shipped != recorded
            if (!wanted) continue

            val files = assets.list(folder)
                .filter { it != SkillParser.FILE_NAME }
                .mapNotNull { file -> read(assets, "$folder/$file")?.let { file to it } }
                .toMap()
            store.install(SkillBundle(skillMd, files, name))
            store.recordBundled(id, shipped)
        }
    }

    /** Brings back the bundled skills the user deleted. Skills replaced by the user stay as they are. */
    fun restore(assets: SkillAssets, store: SkillStore, dir: String = "") {
        store.forgetDeletedBundled()
        installMissing(assets, store, dir)
    }

    private fun read(assets: SkillAssets, path: String): ByteArray? = try {
        assets.open(path).use { it.readAtMost(SkillArchive.MAX_FILE_BYTES) }
    } catch (e: IOException) {
        // Not a file: a subfolder, or a folder that is not a skill.
        null
    }
}
