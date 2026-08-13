package com.example.midasfadercontrol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Generic OSC message: an address plus a list of typed arguments.
 * Supported argument types on ENCODE: Int, Float, String.
 * Supported argument types on DECODE: Int, Float, String, ByteArray (OSC "blob", tag 'b').
 *
 * IMPORTANT PROTOCOL DISCOVERY (confirmed by capturing real Mixtender 2 traffic):
 * The console does NOT reply to a plain GET on a human-readable path
 * (e.g. "/enPPCSwitchMessage/enVirtualMicInputs/enMuteStatus") with a
 * matching plain message. Live parameter values are only ever pushed
 * through OSC BUNDLES ("#bundle" prefix), and only for parameters the
 * client has explicitly subscribed to via "/batchsubscribe" - see
 * SubscriptionManager. Each bundle element is itself a small OSC message
 * whose address is a CLIENT-CHOSEN handle string (whatever the client
 * sent as the first arg of /batchsubscribe for that parameter), with
 * typetag ",bi": a blob (the actual value, format depends on the
 * parameter type) followed by an int (a slowly-incrementing session/
 * generation counter the console maintains - not something we choose,
 * just something we must echo back in our own /batchsubscribe calls;
 * grab its current value from any incoming bundle element before
 * subscribing).
 */
data class OscMessage(val address: String, val args: List<Any>)

/** A parsed OSC bundle element or plain message. Bundles nest recursively. */
sealed class OscElement {
    data class Message(val address: String, val typeTag: String?, val args: List<Any>) : OscElement()
    data class Bundle(val timeTag: Long, val elements: List<OscElement>) : OscElement()
}

object OscUtil {

    fun encode(address: String, args: List<Any>): ByteArray {
        val addressBytes = pad((address + "\u0000").toByteArray(Charsets.US_ASCII))

        val typeTag = StringBuilder(",")
        for (arg in args) {
            typeTag.append(
                when (arg) {
                    is Int -> 'i'
                    is Float -> 'f'
                    is String -> 's'
                    else -> throw IllegalArgumentException("Unsupported OSC arg type: ${arg::class}")
                }
            )
        }
        val typeTagBytes = pad((typeTag.toString() + "\u0000").toByteArray(Charsets.US_ASCII))

        val out = ByteArrayOutputStream()
        out.write(addressBytes)
        out.write(typeTagBytes)
        for (arg in args) {
            when (arg) {
                is Int -> out.write(ByteBuffer.allocate(4).putInt(arg).array())
                is Float -> out.write(ByteBuffer.allocate(4).putFloat(arg).array())
                is String -> out.write(pad((arg + "\u0000").toByteArray(Charsets.US_ASCII)))
            }
        }
        return out.toByteArray()
    }

    /**
     * Old flat-message decode, kept for backwards compatibility with any
     * code that only ever expects a single plain (non-bundle) message.
     * Prefer decodeElement() for anything received from the console, since
     * the console mostly talks in bundles.
     */
    fun decode(bytes: ByteArray): OscMessage? {
        return try {
            var offset = 0
            val (address, afterAddress) = readOscString(bytes, offset)
            offset = afterAddress

            val (typeTag, afterTag) = readOscString(bytes, offset)
            offset = afterTag

            val args = mutableListOf<Any>()
            for (tag in typeTag.drop(1)) {
                when (tag) {
                    'i' -> {
                        args.add(ByteBuffer.wrap(bytes, offset, 4).int)
                        offset += 4
                    }
                    'f' -> {
                        args.add(ByteBuffer.wrap(bytes, offset, 4).float)
                        offset += 4
                    }
                    's' -> {
                        val (s, newOffset) = readOscString(bytes, offset)
                        args.add(s)
                        offset = newOffset
                    }
                    else -> return OscMessage(address, args) // неизвестный тег дальше не парсим
                }
            }
            OscMessage(address, args)
        } catch (e: Exception) {
            null // повреждённый/неожиданный пакет — просто игнорируем, не роняем приложение
        }
    }

    /**
     * Full recursive OSC element decoder: handles both bundles and plain
     * messages, including the 'b' (blob) argument type the console uses
     * for pushed parameter values. Use this for ALL incoming console
     * traffic - the console wraps almost everything in bundles.
     */
    fun decodeElement(bytes: ByteArray, offset: Int = 0, end: Int = bytes.size): OscElement? {
        return try {
            if (end - offset >= 8 && String(bytes, offset, 7, Charsets.US_ASCII) == "#bundle") {
                var pos = offset + 8
                val timeTag = ByteBuffer.wrap(bytes, pos, 8).long
                pos += 8
                val elements = mutableListOf<OscElement>()
                while (pos < end) {
                    val size = ByteBuffer.wrap(bytes, pos, 4).int
                    pos += 4
                    // ЗАЩИТА ОТ ЗАВИСАНИЯ: если размер элемента бандла окажется
                    // некорректным (отрицательным, нулевым, или ведущим за
                    // пределы данных), это могло бы застрять в бесконечном
                    // цикле на реальном большом бандле (особенно теперь, когда
                    // подписок стало больше - бандлы крупнее). Просто прерываем
                    // разбор оставшейся части этого пакета вместо зависания.
                    if (size <= 0 || pos + size > end) break
                    val elem = decodeElement(bytes, pos, pos + size)
                    if (elem != null) elements.add(elem)
                    pos += size
                }
                OscElement.Bundle(timeTag, elements)
            } else {
                var pos = offset
                val (address, afterAddress) = readOscString(bytes, pos)
                pos = afterAddress
                if (pos >= end || bytes[pos] != ','.code.toByte()) {
                    return OscElement.Message(address, null, emptyList())
                }
                val (typeTag, afterTag) = readOscString(bytes, pos)
                pos = afterTag
                val args = mutableListOf<Any>()
                for (tag in typeTag.drop(1)) {
                    when (tag) {
                        'i' -> {
                            args.add(ByteBuffer.wrap(bytes, pos, 4).int)
                            pos += 4
                        }
                        'f' -> {
                            args.add(ByteBuffer.wrap(bytes, pos, 4).float)
                            pos += 4
                        }
                        's' -> {
                            val (s, newOffset) = readOscString(bytes, pos)
                            args.add(s)
                            pos = newOffset
                        }
                        'b' -> {
                            val blobLen = ByteBuffer.wrap(bytes, pos, 4).int
                            pos += 4
                            val blob = bytes.copyOfRange(pos, pos + blobLen)
                            args.add(blob)
                            pos += blobLen + padLen(blobLen)
                        }
                        else -> return OscElement.Message(address, typeTag, args) // неизвестный тег дальше не парсим
                    }
                }
                OscElement.Message(address, typeTag, args)
            }
        } catch (e: Exception) {
            null // повреждённый/неожиданный пакет — просто игнорируем, не роняем приложение
        }
    }

    /**
     * Flattens a decoded element into a list of plain (address, args)
     * messages, unwrapping any nested bundles. Convenient for iterating
     * over everything a single incoming UDP packet contained.
     */
    fun flatten(element: OscElement, out: MutableList<OscElement.Message> = mutableListOf()): List<OscElement.Message> {
        when (element) {
            is OscElement.Message -> out.add(element)
            is OscElement.Bundle -> for (e in element.elements) flatten(e, out)
        }
        return out
    }

    private fun padLen(n: Int) = (4 - (n % 4)) % 4

    private fun pad(bytes: ByteArray): ByteArray {
        val padLen = (4 - (bytes.size % 4)) % 4
        return if (padLen == 0) bytes else bytes + ByteArray(padLen)
    }

    private fun readOscString(bytes: ByteArray, offset: Int): Pair<String, Int> {
        var end = offset
        while (bytes[end] != 0.toByte()) end++
        val s = String(bytes, offset, end - offset, Charsets.US_ASCII)
        val totalWithNull = end - offset + 1
        val rem = totalWithNull % 4
        val padded = if (rem == 0) totalWithNull else totalWithNull + (4 - rem)
        return s to (offset + padded)
    }
}
