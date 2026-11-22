package com.modulai.core.models

/** What a pasted Hugging Face reference points at. */
sealed interface HfLink {
    val repo: String

    /** A whole repository; the user still has to pick a file. */
    data class Repo(override val repo: String) : HfLink

    data class File(override val repo: String, val revision: String, val path: String) : HfLink {
        val fileName get() = path.substringAfterLast('/')
        val downloadUrl get() = "https://huggingface.co/$repo/resolve/$revision/$path?download=true"
    }

    companion object {
        private val SEGMENT = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")

        /**
         * Accepts `org/repo`, a repository URL, or a file URL (`/resolve/` or `/blob/`).
         * Returns null for anything that is not a Hugging Face model reference.
         */
        fun parse(input: String): HfLink? {
            var s = input.trim().substringBefore('?').substringBefore('#')
            val hadScheme = s.startsWith("https://") || s.startsWith("http://")
            s = s.removePrefix("https://").removePrefix("http://")
            when {
                s.startsWith("huggingface.co/") -> s = s.removePrefix("huggingface.co/")
                s.startsWith("hf.co/") -> s = s.removePrefix("hf.co/")
                // Any other URL, with or without a scheme, is not ours.
                hadScheme || s.count { it == '/' } > 1 && '.' in s.substringBefore('/') -> return null
            }
            val parts = s.trim('/').split('/')
            if (parts.size < 2 || !SEGMENT.matches(parts[0]) || !SEGMENT.matches(parts[1])) return null
            val repo = "${parts[0]}/${parts[1]}"
            if (parts.size == 2) return Repo(repo)
            if (parts.size >= 5 && (parts[2] == "resolve" || parts[2] == "blob")) {
                return File(repo, parts[3], parts.drop(4).joinToString("/"))
            }
            // `/tree/main` and similar still identify the repository.
            return Repo(repo)
        }
    }
}
