package com.limosa.core

import java.net.InetAddress

/**
 * 127.0.0.1, spelled out. Do not use InetAddress.getLoopbackAddress(): on Android it is the
 * IPv6 loopback (::1), which the WebView and QEMU (which use 127.0.0.1) cannot reach.
 */
val LOOPBACK_V4: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
