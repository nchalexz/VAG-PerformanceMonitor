package io.github.nchalexz.aaperfmon.core

/**
 * Единственное соединение с адаптером на всё приложение.
 *
 * Диагностический экран открывает адаптер и кладёт его сюда. Приборная
 * панель берёт это же соединение, а не открывает второе: у ELM327 один
 * последовательный канал, и два владельца будут мешать друг другу.
 *
 * Ссылка обнуляется при штатном отключении. Если диагностический экран
 * закрыт вместе с процессом, ссылка исчезает вместе с ним.
 */
object AdapterHolder {
    @Volatile
    var elm: Elm327? = null
        private set

    /** Название канала, чтобы приборка могла показать, к чему подключена. */
    @Volatile
    var title: String = ""
        private set

    fun attach(adapter: Elm327, channelTitle: String) {
        elm = adapter
        title = channelTitle
    }

    fun detach() {
        elm = null
        title = ""
    }
}
