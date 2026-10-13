package app.termora

object ShellArguments {
    fun quote(value: String): String {
        require('\u0000' !in value) { "Shell arguments cannot contain NUL" }
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
