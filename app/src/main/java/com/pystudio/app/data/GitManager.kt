package com.pystudio.app.data

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

data class GitChange(val status: String, val path: String)
data class GitCommit(val id: String, val message: String, val author: String, val time: Long)
data class GitInfo(
    val isRepo: Boolean, val branch: String = "", val changes: List<GitChange> = emptyList(),
    val commits: List<GitCommit> = emptyList(), val branches: List<String> = emptyList(), val remote: String = ""
)

/** Git support through JGit (pure Java, no git binary needed). All calls are blocking: use Dispatchers.IO. */
object GitManager {
    private fun creds(user: String, token: String) =
        if (token.isBlank()) null else UsernamePasswordCredentialsProvider(user.ifBlank { token }, if (user.isBlank()) "" else token)

    fun isRepo(dir: File) = File(dir, ".git").isDirectory

    fun init(dir: File) {
        Git.init().setDirectory(dir).call().close()
        val ig = File(dir, ".gitignore")
        if (!ig.exists()) ig.writeText("__pycache__/\n*.pyc\n.venv/\n.idea/\n")
    }

    fun info(dir: File): GitInfo {
        if (!isRepo(dir)) return GitInfo(false)
        Git.open(dir).use { git ->
            val st = git.status().call()
            val ch = ArrayList<GitChange>()
            st.added.forEach { ch.add(GitChange("A", it)) }
            st.changed.forEach { ch.add(GitChange("M", it)) }
            st.modified.forEach { ch.add(GitChange("M", it)) }
            st.removed.forEach { ch.add(GitChange("D", it)) }
            st.missing.forEach { ch.add(GitChange("D", it)) }
            st.untracked.forEach { ch.add(GitChange("?", it)) }
            st.conflicting.forEach { ch.add(GitChange("U", it)) }
            val commits = try {
                git.log().setMaxCount(50).call().map {
                    GitCommit(it.abbreviate(7).name(), it.shortMessage, it.authorIdent.name, it.commitTime * 1000L)
                }
            } catch (e: Exception) { emptyList() }
            val branches = try {
                git.branchList().call().map { Repository.shortenRefName(it.name) }
            } catch (e: Exception) { emptyList() }
            val remote = git.repository.config.getString("remote", "origin", "url") ?: ""
            return GitInfo(true, git.repository.branch ?: "", ch.distinct().sortedBy { it.path }, commits, branches, remote)
        }
    }

    fun commitAll(dir: File, message: String, name: String, email: String) {
        Git.open(dir).use { git ->
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            git.commit().setMessage(message)
                .setAuthor(name.ifBlank { "PyStudio" }, email.ifBlank { "pystudio@localhost" })
                .setCommitter(name.ifBlank { "PyStudio" }, email.ifBlank { "pystudio@localhost" })
                .call()
        }
    }

    fun checkout(dir: File, branch: String, create: Boolean) {
        Git.open(dir).use { it.checkout().setName(branch).setCreateBranch(create).call() }
    }

    fun setRemote(dir: File, url: String) {
        Git.open(dir).use { git ->
            val cfg = git.repository.config
            cfg.setString("remote", "origin", "url", url)
            cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            cfg.save()
        }
    }

    fun clone(url: String, dest: File, user: String, token: String) {
        val c = Git.cloneRepository().setURI(url).setDirectory(dest)
        creds(user, token)?.let { c.setCredentialsProvider(it) }
        c.call().close()
    }

    fun pull(dir: File, user: String, token: String): String {
        Git.open(dir).use { git ->
            val c = git.pull()
            creds(user, token)?.let { c.setCredentialsProvider(it) }
            val r = c.call()
            return if (r.isSuccessful) "Pull successful" else "Pull finished with conflicts or errors"
        }
    }

    fun push(dir: File, user: String, token: String): String {
        Git.open(dir).use { git ->
            val c = git.push()
            creds(user, token)?.let { c.setCredentialsProvider(it) }
            val res = c.call()
            val msgs = res.flatMap { it.remoteUpdates }.map { "${it.remoteName}: ${it.status}" }
            return "Push: " + msgs.joinToString().ifBlank { "done" }
        }
    }
}
