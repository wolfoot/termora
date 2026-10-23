package app.termora.terminal

// Android's java.util.List predates Java 21 SequencedCollection.
fun <T> MutableList<T>.addFirst(element: T) = add(0, element)

fun <T> MutableList<T>.addLast(element: T) {
    add(element)
}
