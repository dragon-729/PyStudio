package com.pystudio.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Template(val id: String, val title: String, val desc: String, val files: Map<String, String>)

object Templates {
    val all = listOf(
        Template("empty", "Empty project", "A single empty main.py", mapOf("main.py" to "# New PyStudio project\n")),
        Template(
            "hello", "Hello World", "Greeting with input() and a README",
            mapOf(
                "main.py" to "name = input(\"What is your name? \")\nprint(f\"Hello, {name}! Welcome to PyStudio.\")\n",
                "README.md" to "# Hello World\n\nA tiny **PyStudio** project.\n\n- Edit `main.py`\n- Press the *Run* button\n"
            )
        ),
        Template(
            "calc", "Calculator", "Console calculator with functions",
            mapOf(
                "main.py" to """
def calculate(a: float, op: str, b: float) -> float:
    if op == "+":
        return a + b
    if op == "-":
        return a - b
    if op == "*":
        return a * b
    if op == "/":
        return a / b
    raise ValueError(f"Unknown operator: {op}")


while True:
    text = input("Enter 'a op b' (or q to quit): ").strip()
    if text.lower() == "q":
        break
    try:
        a, op, b = text.split()
        print("=", calculate(float(a), op, float(b)))
    except Exception as e:
        print("Error:", e)
""".trimStart()
            )
        ),
        Template(
            "guess", "Number guessing game", "Uses random and loops",
            mapOf(
                "main.py" to """
import random

secret = random.randint(1, 100)
tries = 0
print("I'm thinking of a number between 1 and 100.")
while True:
    guess = int(input("Your guess: "))
    tries += 1
    if guess < secret:
        print("Higher!")
    elif guess > secret:
        print("Lower!")
    else:
        print(f"Correct! You needed {tries} tries.")
        break
""".trimStart()
            )
        ),
        Template(
            "json", "JSON & files", "Read and write JSON files",
            mapOf(
                "main.py" to """
import json
from pathlib import Path

data = {"name": "PyStudio", "tags": ["python", "android"], "version": 1}
path = Path("data.json")
path.write_text(json.dumps(data, indent=2))
print("Saved", path.resolve())
print(json.loads(path.read_text()))
""".trimStart()
            )
        ),
        Template(
            "multi", "Multi-file package", "main.py importing a local package",
            mapOf(
                "main.py" to "from utils.mathx import square, cube\n\nprint(square(7), cube(3))\n",
                "utils/__init__.py" to "",
                "utils/mathx.py" to "def square(x):\n    return x * x\n\n\ndef cube(x):\n    return x * x * x\n"
            )
        )
    )
}

data class FileNode(val file: File, val depth: Int, val isDir: Boolean)

class ProjectStore(private val ctx: Context, private val prefs: Prefs) {
    val root = File(ctx.filesDir, "projects").also { it.mkdirs() }

    fun projects(): List<File> =
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun recent(): List<File> =
        prefs.recents.split("\n").filter { it.isNotBlank() }.map { File(root, it) }.filter { it.isDirectory }

    fun touch(p: File) {
        prefs.recents = (listOf(p.name) + prefs.recents.split("\n")).filter { it.isNotBlank() }.distinct()
            .take(8).joinToString("\n")
        prefs.lastProject = p.name
    }

    fun sanitize(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifBlank { "project" }

    private fun unique(base: String): String {
        var n = base
        var i = 2
        while (File(root, n).exists()) n = "$base-${i++}"
        return n
    }

    fun create(name: String, t: Template): File {
        val dir = File(root, unique(sanitize(name))).also { it.mkdirs() }
        t.files.forEach { (rel, content) -> File(dir, rel).also { it.parentFile?.mkdirs() }.writeText(content) }
        return dir
    }

    fun renameProject(p: File, newName: String): File {
        val dst = File(root, unique(sanitize(newName)))
        p.renameTo(dst)
        return dst
    }

    fun deleteProject(p: File) { p.deleteRecursively() }

    // ----- import / export ---------------------------------------------------
    fun exportZip(dir: File): File {
        val out = File(ctx.cacheDir, "share/${dir.name}.zip").also { it.parentFile?.mkdirs() }
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            dir.walkTopDown().filter { it.isFile && !it.path.contains("__pycache__") }.forEach { f ->
                z.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }

    fun importZip(uri: Uri, nameHint: String): File {
        val names = ArrayList<String>()
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) { names.add(e.name); e = z.nextEntry }
            }
        } ?: error("Cannot read the zip file")
        val firstDirs = names.map { it.substringBefore('/', "") }.toSet()
        val strip = if (firstDirs.size == 1 && firstDirs.first().isNotEmpty() && names.all { it.contains('/') })
            firstDirs.first() + "/" else ""
        val dir = File(root, unique(sanitize(nameHint.removeSuffix(".zip")))).also { it.mkdirs() }
        val base = dir.canonicalPath
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val rel = e.name.removePrefix(strip)
                    if (rel.isNotEmpty()) {
                        val target = File(dir, rel).canonicalFile
                        require(target.path.startsWith(base + File.separator) || target.path == base) { "Unsafe zip entry" }
                        if (e.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { z.copyTo(it) }
                        }
                    }
                    e = z.nextEntry
                }
            }
        }
        return dir
    }

    fun importFolder(uri: Uri): File {
        val tree = DocumentFile.fromTreeUri(ctx, uri) ?: error("Cannot open the folder")
        val dir = File(root, unique(sanitize(tree.name ?: "imported")))
        copyTree(tree, dir)
        return dir
    }

    private fun copyTree(src: DocumentFile, dst: File) {
        dst.mkdirs()
        for (c in src.listFiles()) {
            val n = c.name ?: continue
            if (c.isDirectory) {
                if (n != "__pycache__") copyTree(c, File(dst, n))
            } else {
                ctx.contentResolver.openInputStream(c.uri)?.use { i -> File(dst, n).outputStream().use { o -> i.copyTo(o) } }
            }
        }
    }

    fun importFile(uri: Uri, destDir: File): File {
        val name = DocumentFile.fromSingleUri(ctx, uri)?.name ?: "imported.py"
        val out = File(destDir, name)
        ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return out
    }
}

object FileOps {
    private fun check(name: String) {
        require(name.isNotBlank() && !name.contains('/') && name != "." && name != "..") { "Invalid name" }
    }

    fun createFile(dir: File, name: String): File {
        check(name)
        val f = File(dir, name)
        require(!f.exists()) { "'$name' already exists" }
        f.createNewFile()
        return f
    }

    fun createFolder(dir: File, name: String): File {
        check(name)
        val f = File(dir, name)
        require(!f.exists()) { "'$name' already exists" }
        f.mkdirs()
        return f
    }

    fun rename(f: File, newName: String): File {
        check(newName)
        val t = File(f.parentFile, newName)
        require(!t.exists()) { "'$newName' already exists" }
        require(f.renameTo(t)) { "Rename failed" }
        return t
    }

    private fun isInside(dir: File, parent: File): Boolean {
        val d = dir.canonicalPath
        val p = parent.canonicalPath
        return d == p || d.startsWith(p + File.separator)
    }

    fun copyInto(f: File, destDir: File): File {
        require(!(f.isDirectory && isInside(destDir, f))) { "Cannot copy a folder into itself" }
        var t = File(destDir, f.name)
        var i = 1
        while (t.exists()) {
            val suffix = if (i == 1) "_copy" else "_copy$i"
            val ext = if (f.extension.isNotEmpty()) "." + f.extension else ""
            t = File(destDir, f.nameWithoutExtension + suffix + ext)
            i++
        }
        if (f.isDirectory) f.copyRecursively(t) else f.copyTo(t)
        return t
    }

    fun moveInto(f: File, destDir: File): File {
        require(!(f.isDirectory && isInside(destDir, f))) { "Cannot move a folder into itself" }
        val t = File(destDir, f.name)
        require(!t.exists()) { "'${f.name}' already exists there" }
        if (!f.renameTo(t)) { f.copyRecursively(t); f.deleteRecursively() }
        return t
    }

    fun flatten(root: File, expanded: Set<String>, depth: Int = 0, out: MutableList<FileNode> = ArrayList()): List<FileNode> {
        val kids = root.listFiles()
            ?.filter { (!it.name.startsWith(".") || it.name == ".gitignore") && it.name != "__pycache__" }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        for (k in kids) {
            out.add(FileNode(k, depth, k.isDirectory))
            if (k.isDirectory && k.absolutePath in expanded) flatten(k, expanded, depth + 1, out)
        }
        return out
    }
}
