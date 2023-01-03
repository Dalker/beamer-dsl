/** DSL pour générer du code LaTeX/beamer pour une présentation orale
 *  créé par Daniel Kessler, décembre 2022
 *
 *  inspiré par le DSL exemple de la doc officielle Kotlin, à savoir:
 *  https://kotlinlang.org/docs/type-safe-builders.html#full-definition-of-the-com-example-html-package
 *  mais en y intégrant beaucoup plus d'"idiomes" de Kotlin pour plus de concision
 */
package dalker.beamer

import kotlin.reflect.KClass

typealias FrameDefinition = Beamer.() -> Frame

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
    constructor(vararg args_: String) : this() {
        for (arg in args_) { args.add(arg) }
    }

    override operator fun String.unaryMinus() { optArgs.add(this) }
    override operator fun String.not() { comment = this }
}

interface LaTeXContainer : LaTeXable {
    fun <T : LaTeX> addContent(content: T, bloc: T.() -> Unit = {}): T
    fun <T : LaTeX> insertContent(content: T, bloc: T.() -> Unit = {}): T
    operator fun String.unaryPlus(): TranslatableTeX
    operator fun Int.times(s: String): TranslatableTeX
    operator fun Int.times(l: () -> LaTeX): LaTeX
    fun comment(comment: String): RawTeX
    fun blankline(): RawTeX
    fun command(name: String, vararg args: String, bloc: Command.() -> Unit = {}): Command
    fun section(name: String? = null, sub: String? = null): Unit
    fun environment(name: String, vararg args: String, bloc: Environment.() -> Unit = {}): Environment
    fun itemize(bloc: Environment.() -> Unit): Itemize
    fun containingCommand(name: String, bloc: ContainingCommand.() -> Unit = {}): ContainingCommand
    fun on(slideNumber: Int, bloc: ContainingCommand.() -> Unit): ContainingCommand
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
    override fun <T : LaTeX> addContent(content: T, bloc: T.() -> Unit) =
        content.apply(bloc).also { contents.add(it) }
    override fun <T : LaTeX> insertContent(content: T, bloc: T.() -> Unit) =
        content.apply(bloc).also { contents.add(0, it) }

    override fun toLaTeX(sb: StringBuilder, indent: String) {
        for (content in contents) {
            content.toLaTeX(sb, indent)
        }
    }

    // inclure du texte brut
    override operator fun String.unaryPlus() = addContent(TranslatableTeX("$this\n"))
    override operator fun Int.times(s: String) = (if(this > 0) "${this}-" else "-$this").let {
        addContent(TranslatableTeX("\\onslide<$it>{$s}\n"))
    }
    override operator fun Int.times(l: () -> LaTeX) = (if(this > 0) "${this}-" else "-$this").let {
        containingCommand("onslide<$it>").apply { addContent(l()) }
        // addContent(TranslatableTeX("\\onslide<$it>{$l}\n"))
    }
    override fun comment(comment: String) = addContent(RawTeX("%$comment\n"))
    override fun blankline() = addContent(RawTeX("\n"))

    // inclure une macro LaTeX
    override fun command(name: String, vararg args: String, bloc: Command.() -> Unit) =
        addContent(Command(name, *args), bloc)
    override fun containingCommand(name: String, bloc: ContainingCommand.() -> Unit) =
        addContent(ContainingCommand(name), bloc)
    override fun section(name: String?, sub: String?) {
        name ?.let { addContent(Command("section", it)) }
        sub ?.let { addContent(Command("subsection", it)) }
    }
    override fun on(slideNumber: Int, bloc: ContainingCommand.() -> Unit) =
        (if(slideNumber < 0) "${-slideNumber}-" else "$slideNumber").let {
            addContent(ContainingCommand("onslide<$it>"), bloc)
        }

    // inclure un environnement LaTeX
    override fun environment(name: String, vararg args: String, bloc: Environment.() -> Unit)
        = addContent(Environment(name, *args), bloc)
    override fun itemize(bloc: Environment.() -> Unit)
        = addContent(Itemize(), bloc)
}

/** texte brut - qui pourrait quand même contenir du code LaTeX */
class RawTeX(val text: String) : LaTeX() {
    override fun toLaTeX(sb: StringBuilder, indent: String) {
        sb.append(text) // no indent: this is used for unprocessed text!
    }
}

/** texte interprétable comme mini-ML vers LaTeX */
class TranslatableTeX(val text: String) : LaTeX() {
    override fun toLaTeX(sb: StringBuilder, indent: String) {
        sb.append(indent
                  + text.replace("...", "\\ldots{}")
                      .replace(Regex("""/([^/]+)/""")) {
                          "\\textit{${it.value.drop(1).dropLast(1)}}"
                      }
                      .replace(Regex("""\|([^\|]+)\|""")) {
                          "\\texttt{${it.value.drop(1).dropLast(1)}}"
                      }
        )
    }
}

/** Commande LaTeX avec possibles arguments {...}, arguments optionnels [...] et commentaire % ... */
open class Command(val name: String, vararg args: String) :
    LaTeX(), LaTeXArguments by Arguments(*args) {
    override fun toLaTeX(sb: StringBuilder, indent: String) {
        sb.append("$indent\\$name")
        if (optArgs.isNotEmpty()) {
            sb.append(optArgs.joinToString(", ", "[", "]"))
        }
        if (args.isNotEmpty()) {
            sb.append(args.joinToString("}{", "{", "}"))
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

class NewCommand(val name: String, val nArgs: Int) : Container() {
    override fun toLaTeX(sb: StringBuilder, indent: String) {
        sb.append("$indent\\newcommand\\$name[$nArgs]")
        sb.append("{\n")
        super.toLaTeX(sb, indent + "  ")
        sb.append("}\n")
    }
}

open class Environment(val name: String, vararg args: String) :
    Container(), LaTeXArguments by Arguments(*args) {
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
    override operator fun String.unaryPlus() = addContent(TranslatableTeX("\\item $this\n"))
    override operator fun Int.times(s: String) = (if(this > 0) "${this}-" else "-$this").let {
        addContent(TranslatableTeX("\\item<$it> $s\n"))
    }
}

class Header() : Container() {
    inner class Package(name: String) : Command("usepackage", name)
    fun pkg(name: String, bloc: Package.() -> Unit = {}) =
        addContent(Package(name), bloc)
    fun newcommand(name: String, nArgs: Int, bloc: NewCommand.() -> Unit) =
        addContent(NewCommand(name, nArgs), bloc)
}

class Code(language: String, blockTitle: String) : Environment("block", blockTitle) {
    constructor(language: String): this(language, language)

    val mint = Environment("minted", language).also { addContent(it) }

    operator fun rem(code: String): Code =
        mint.addContent(RawTeX(code.trim() + "\n")).let { this }

    companion object {
        fun header() = Header().apply { pkg("minted") { !"pour inclure du code" } }
    }
}

open class Frame(title: String? = null) : Environment("frame") {
    init { title?.let{ args.add(title) }}
    fun pause() = addContent(Command("pause"))
    fun code(language: String) = addContent(Code(language))
    fun code(language: String, blockTitle: String) =
        addContent(Code(language, blockTitle))
    override fun toLaTeX(sb: StringBuilder, indent: String) {
        if (has(Code::class)) apply { -"fragile" }
        super.toLaTeX(sb, indent)
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
    fun newcommand(name: String, nArgs: Int, bloc: NewCommand.() -> Unit) =
        customHeader.newcommand(name, nArgs, bloc)

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
