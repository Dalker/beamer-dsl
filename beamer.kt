/** DSL pour générer du code LaTeX/beamer pour une présentation orale
 *  créé par Daniel Kessler, décembre 2022
 *
 *  inspiré par le DSL exemple de la doc officielle Kotlin, à savoir:
 *  https://kotlinlang.org/docs/type-safe-builders.html#full-definition-of-the-com-example-html-package
 *  mais en y intégrant beaucoup plus d'"idiomes" de Kotlin pour plus de concision
 */
package dalker.beamer

import kotlin.reflect.KClass

@DslMarker
annotation class LaTeXDSL

@LaTeXDSL
interface LaTeXable {
    fun toLaTeX(sb: StringBuilder, indent: String = "")
}

abstract class LaTeX : LaTeXable {
    override fun toString(): String =
        StringBuilder().also { toLaTeX(it, "") }.toString()
}

interface LaTeXArguments {
    val args: MutableList<String>
    val optArgs: MutableList<String>
    val comment: String?
    operator fun String.unaryMinus(): Unit
    operator fun String.not(): Unit
}

class Arguments(
    override val args: MutableList<String> = mutableListOf<String>(),
    override val optArgs: MutableList<String> = mutableListOf<String>(),
    override var comment: String? = null,
) : LaTeXArguments {
    constructor(arg: String?) : this() { arg ?.let { args.add(arg) } }

    override operator fun String.unaryMinus() { optArgs.add(this) }
    override operator fun String.not() { comment = this }
}

interface LaTeXContainer : LaTeXable {
    fun <T : LaTeX> addContent(content: T, bloc: T.() -> Unit = {}): Unit
    fun <T : LaTeX> insertContent(content: T, bloc: T.() -> Unit = {}): Unit
    operator fun String.unaryPlus(): Unit
    operator fun Int.times(s: String): Unit
    fun command(name: String, arg: String? = null, bloc: Command.() -> Unit = {}): Unit
    fun environment(name: String, arg: String? = null, bloc: Environment.() -> Unit = {}): Unit
    fun itemize(bloc: Environment.() -> Unit)
    fun containingCommand(name: String, bloc: ContainingCommand.() -> Unit = {}): Unit
    fun section(name: String? = null, sub: String? = null): Unit
    fun comment(comment: String): Unit
    fun blankline(): Unit
}

/** Conteneur de strutures LaTeX */
abstract class Container : LaTeX(), LaTeXContainer {
    private val contents = mutableListOf<LaTeX>()

    /** Déterminer si un contenu d'une certaine classe est présent */
    fun has(contentType: KClass<out LaTeX>): Boolean {
        contents.forEach {
            when {
                it::class == contentType -> return true
                it is Container && it.has(contentType) -> return true
            }
        }
        return false
    }

    /** Ajouter un contenu et possiblement agir dessus */
    override fun <T : LaTeX> addContent(content: T, bloc: T.() -> Unit) {
        content.apply(bloc).also { contents.add(it) }
    }
    override fun <T : LaTeX> insertContent(content: T, bloc: T.() -> Unit) {
        content.apply(bloc).also { contents.add(0, it) }
    }

    override fun toLaTeX(sb: StringBuilder, indent: String) {
        for (content in contents) {
            content.toLaTeX(sb, indent)
        }
    }

    // inclure du texte brut
    override operator fun String.unaryPlus() = addContent(RawTeX("$this\n"))
    override operator fun Int.times(s: String) = (if(this < 0) "${-this}-" else "$this").let {
        addContent(RawTeX("\\onslide<$it>{$s}\n"))
    }
    override fun comment(comment: String) = addContent(RawTeX("%$comment\n"))
    override fun blankline() = addContent(RawTeX("\n"))

    // inclure une macro LaTeX
    override fun command(name: String, arg: String?, bloc: Command.() -> Unit) =
        addContent(Command(name, arg), bloc)
    override fun containingCommand(name: String, bloc: ContainingCommand.() -> Unit)
        = addContent(ContainingCommand(name), bloc)
    override fun section(name: String?, sub: String?) {
        name ?.let { addContent(Command("section", it)) }
        sub ?.let { addContent(Command("subsection", it)) }
    }

    // inclure un environnement LaTeX
    override fun environment(name: String, arg: String?, bloc: Environment.() -> Unit)
        = addContent(Environment(name, arg), bloc)
    override fun itemize(bloc: Environment.() -> Unit)
        = addContent(Itemize(), bloc)
}

/** texte brut - qui pourrait quand même contenir du code LaTeX */
class RawTeX(val text: String) : LaTeX() {
    override fun toLaTeX(sb: StringBuilder, indent: String) {
        sb.append(indent
                  + text.replace("...", "\\ldots{}")
                      .replace(Regex("""(.*)/(.+)/(.*)""")) {
                                   val (pre, match, post) = it.destructured
                                   "$pre\\textit{$match}$post"
        })
    }
}

/** Commande LaTeX avec possibles arguments {...}, arguments optionnels [...] et commentaire % ... */
open class Command(val name: String, arg: String? = null) :
    LaTeX(), LaTeXArguments by Arguments(arg) {
    override fun toLaTeX(sb: StringBuilder, indent: String) {
        sb.append("$indent\\$name")
        if (optArgs.isNotEmpty()) {
            sb.append(optArgs.joinToString(", ", "[", "]"))
        }
        if (args.isNotEmpty()) {
            sb.append(args.joinToString(", ", "{", "}"))
        }
        comment?.let { sb.append(" % $comment") }
        sb.append("\n")
    }
}

/** Commande LaTeX avec argument(s) { ... } en plusieurs lignes, géré comme un Container */
class ContainingCommand(val name: String) :
    Container(), LaTeXArguments by Arguments() {
        override fun toLaTeX(sb: StringBuilder, indent: String) {
            comment?.let { sb.append(" % $comment\n") }
            sb.append("$indent\\$name")
            if (optArgs.isNotEmpty()) {
                sb.append(optArgs.joinToString(", ", "[", "]"))
            }
            sb.append("{\n")
            super.toLaTeX(sb, indent + "  ")
            sb.append("}\n")
        }
    }

open class Environment(val name: String, arg: String? = null) :
    Container(), LaTeXArguments by Arguments(arg) {
    override fun toLaTeX(sb: StringBuilder, indent: String) {
        sb.append("""$indent\begin{$name}""")
        if (optArgs.isNotEmpty()) {
            sb.append(optArgs.joinToString(", ", "[", "]"))
        }
        if (args.isNotEmpty()) {
            sb.append(args.joinToString(", ", "{", "}"))
        }
        comment?.let { sb.append(" % $comment") }
        sb.append("\n")
        super.toLaTeX(sb, indent + "  ")
        sb.append("$indent\\end{$name}\n")
    }
}

class Itemize() : Environment("itemize") {
    override operator fun String.unaryPlus() = addContent(RawTeX("\\item $this\n"))
    override operator fun Int.times(s: String) = (if(this < 0) "${-this}-" else "$this").let {
        addContent(RawTeX("\\item<$it> $s\n"))
    }
}

class Header() : Container() {
    inner class Package(name: String) : Command("usepackage", name)
    fun pkg(name: String, bloc: Package.() -> Unit = {}) = addContent(Package(name), bloc)
}

class Code(language: String) : Environment("minted", language) {
    companion object {
        fun header() = Header().apply { pkg("minted") { !"pour inclure du code" } }
    }
}

open class Frame(title: String? = null) : Environment("frame", title)

class CodeFrame(title: String? = null) : Frame(title) {
    init { -"fragile" }
    fun code(language: String, bloc: Code.() -> Unit) {
        addContent(Code(language), bloc)
    }
}

class Beamer(val toctitle: String? = null, private val document: Environment) : LaTeXContainer by document {
    constructor(toctitle: String? = null) : this(toctitle, Environment("document"))

    val baseHeader = Header().apply {
        command("documentclass", "beamer")
        blankline()
        comment(" = paquets standards pour LaTeX au XXIème siècle =")
        pkg("nag") { -"l2tabu"; -"orthodox"; !"se plaindre d'usages obsolètes de LaTeX" }
        pkg("microtype") { !"rendre disponible améliorations post-pdfTeX" }
        blankline()
        comment(" = configuration supplémentaire =")
    }

    init {
        toctitle ?.let { tt ->
            baseHeader.apply {
                containingCommand("AtBeginSection") {
                    environment("frame", tt) {
                        command("tableofcontents") { -"currentsection" }
                    }
                }
            }
            frame(tt) {
                command("tableofcontents") { -"hideallsubsections"}
            }
        }
    }

    val customHeader = Header()
    fun head(bloc: Header.() -> Unit) = customHeader.apply(bloc)
    fun pkg(name: String, bloc: Header.Package.() -> Unit = {}) =
        customHeader.pkg(name, bloc)

    fun titleframe(title: String, author: String, date: String? = null, subtitle: String? = null) {
        head {
            command("title", title)
            command("author", author)
            date ?.let { command("date", it) }
                 ?:run { containingCommand("date") { command("today") } }
            subtitle ?.let { command("subtitle", it) }
            }
        insertContent(Frame()) { command("titlepage") }
    }

    fun frame(title: String? = null, bloc: Frame.() -> Unit) =
        addContent(Frame(title), bloc)
    fun codeframe(title: String? = null, bloc: CodeFrame.() -> Unit) =
        addContent(CodeFrame(title), bloc)

    override fun toString() = StringBuilder().also {
        baseHeader.toLaTeX(it)
        if (document.has(Code::class)) Code.header().toLaTeX(it)
        customHeader.toLaTeX(it)
        RawTeX("\n").toLaTeX(it)
        document.toLaTeX(it)
    }.toString()
}

fun beamer(toctitle: String? = null, bloc: Beamer.() -> Unit): Beamer =
    Beamer(toctitle).apply(bloc)
