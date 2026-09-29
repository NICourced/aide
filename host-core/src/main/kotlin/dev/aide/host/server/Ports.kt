package dev.aide.host.server

import java.net.ServerSocket

/** Свободный порт на loopback. Нужен и локальному хосту, и тестам. */
fun freeLoopbackPort(): Int = ServerSocket(0).use { it.localPort }
