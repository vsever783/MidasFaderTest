package com.example.midasfadercontrol

import android.graphics.Color
import com.example.midasfadercontrol.MainActivity.Companion.MODE_MONITOR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

// Вынесено из MainActivity.kt для читаемости (было 4712 строк в одном
// файле) - чисто организационная правка, поведение не менялось.
// Сетевой/протокольный слой: подключение, приём/передача UDP-пакетов,
// подписки на пульт. Extension-функции на MainActivity - в Kotlin класс
// нельзя разбить на несколько файлов напрямую, поэтому используется
// этот паттерн (требует internal-видимости у полей класса, которые
// отсюда используются - см. MainActivity.kt).

    /**
     * Подписывается на членство ВСЕХ потенциальных "детей" (56 входных
     * каналов + 16 aux-шин + 8 aux returns + 8 main outs + 3 master) в
     * КОНКРЕТНОЙ VCA-группе - вызывается при открытии экрана "VCA N MEMBERS",
     * чтобы кнопки сразу показывали реальное состояние с пульта, а не всегда
     * стартовали серыми.
     *
     * НЕ подтверждено отдельным захватом трафика (см. заметку у VcaData) -
     * структура подписки (диапазон arg1/arg2 = сам номер VCA-группы)
     * выведена из уже подтверждённой на реальном пульте команды SET,
     * которая шлёт (vcaIndex, member) для пути, адресующего конкретного
     * "ребёнка". Если после сборки кнопки все равно не подхватят реальное
     * состояние - значит подписка на чтение здесь работает иначе, чем
     * запись, и нужен отдельный точечный захват.
     */
    internal fun MainActivity.subscribeVcaMembers(vcaIndex: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return
        val sid = sessionId

        CoroutineScope(Dispatchers.IO).launch {
            val jobs = listOf(
                Triple("input", 56) { i: Int -> Pro2Commands.vcaChildInputAddress(i) },
                Triple("submix", 16) { i: Int -> Pro2Commands.vcaChildSubMixAddress(i) },
                Triple("auxreturn", 8) { i: Int -> Pro2Commands.vcaChildAuxReturnAddress(i) },
                Triple("main", 8) { i: Int -> Pro2Commands.vcaChildMainAddress(i) }
            )
            for ((childType, count, addrFn) in jobs) {
                for (i in 0 until count) {
                    val handle = "/h_${sid}_vm${vcaIndex}_${childType}_$i"
                    withContext(Dispatchers.Main) {
                        vcaMemberSubscriptions[handle] = VcaMemberSub(childType, i, vcaIndex)
                    }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, addrFn(i), vcaIndex, vcaIndex, token))
                    } catch (e: Exception) {
                        // не критично
                    }
                    delay(2)
                }
            }
            // Master L/R/C - отдельно, так как адрес принимает букву, а не индекс.
            for ((i, letter) in listOf("L", "R", "C").withIndex()) {
                val handle = "/h_${sid}_vm${vcaIndex}_master_$i"
                withContext(Dispatchers.Main) {
                    vcaMemberSubscriptions[handle] = VcaMemberSub("master", i, vcaIndex)
                }
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, Pro2Commands.vcaChildMasterAddress(letter), vcaIndex, vcaIndex, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }
        }
    }

    /**
     * Подписка на членство ВСЕХ потенциальных "детей" в КОНКРЕТНОЙ
     * мьют-группе - вызывается при открытии экрана "MUTE GROUP N MEMBERS".
     * Точная копия subscribeVcaMembers() выше (см. подробную заметку там) -
     * структура симметрична VCA, тоже НЕ подтверждена собственным захватом.
     */
    /**
     * Подписка на принадлежность ОДНОГО канала ко всем мьют-группам -
     * для строки кнопок MG 1-8 в детальном экране канала (как в X32 Mix).
     *
     * Это тот же параметр enMuteGroupChildInput{N}, что и на экране
     * участников группы, но "с другой стороны": там фиксируют группу и
     * перебирают каналы, здесь фиксируют канал и перебирают группы.
     * Всего 8 подписок на канал - подписываемся лениво, при открытии
     * детального экрана.
     */
    internal fun MainActivity.subscribeChannelMuteGroups(channel: Int) {
        // ИСПРАВЛЕНО: раньше сторожевое множество помечалось ПЕРВОЙ
        // строкой, до проверки сокета. Если экран канала открывали в
        // момент, когда соединения ещё нет (например, сразу после
        // переподключения), канал помечался как "подписан", функция
        // выходила по `socket ?: return`, и повторной попытки уже не
        // было - строка MUTE GROUPS у этого канала навсегда оставалась
        // без данных. Сначала проверяем готовность, помечаем только
        // когда реально отправляем.
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: 0
        val sid = sessionId
        if (!channelMuteGroupsSubscribed.add(channel)) return
        CoroutineScope(Dispatchers.IO).launch {
            for (g in 0 until ConnectionHolder.MUTE_GROUP_COUNT) {
                val handle = "/h_${sid}_ch${channel}_mg$g"
                withContext(Dispatchers.Main) {
                    ConnectionHolder.channelMuteGroupSubscriptions[handle] = channel to g
                }
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(
                        handle, Pro2Commands.muteGroupChildInputAddress(channel), g, g, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }
        }
    }

    internal fun MainActivity.subscribeMuteGroupMembers(groupIndex: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return
        val sid = sessionId

        CoroutineScope(Dispatchers.IO).launch {
            val jobs = listOf(
                Triple("input", 56) { i: Int -> Pro2Commands.muteGroupChildInputAddress(i) },
                Triple("submix", 16) { i: Int -> Pro2Commands.muteGroupChildSubMixAddress(i) },
                Triple("auxreturn", 8) { i: Int -> Pro2Commands.muteGroupChildAuxReturnAddress(i) },
                Triple("main", 8) { i: Int -> Pro2Commands.muteGroupChildMainAddress(i) }
            )
            for ((childType, count, addrFn) in jobs) {
                for (i in 0 until count) {
                    val handle = "/h_${sid}_mgm${groupIndex}_${childType}_$i"
                    withContext(Dispatchers.Main) {
                        muteGroupMemberSubscriptions[handle] = MuteGroupMemberSub(childType, i, groupIndex)
                    }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, addrFn(i), groupIndex, groupIndex, token))
                    } catch (e: Exception) {
                        // не критично
                    }
                    delay(2)
                }
            }
            for ((i, letter) in listOf("L", "R", "C").withIndex()) {
                val handle = "/h_${sid}_mgm${groupIndex}_master_$i"
                withContext(Dispatchers.Main) {
                    muteGroupMemberSubscriptions[handle] = MuteGroupMemberSub("master", i, groupIndex)
                }
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, Pro2Commands.muteGroupChildMasterAddress(letter), groupIndex, groupIndex, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }
        }
    }

    internal fun MainActivity.subscribeChannelSendsForBus(bus: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            for (ch in 0 until numChannels) {
                val handle = "/h_${sid}_${ch}_msend${bus}"
                val nameHandle = "/h_${sid}_${ch}_mname"
                // Метр не зависит от выбранной шины (это входной сигнал самого
                // канала) - подписываемся один раз, но без специальной защиты
                // от повторной подписки при смене шины: пульт просто получит
                // ту же подписку под тем же хендлом ещё раз, это не критично.
                val meterHandle = "/h_${sid}_${ch}_mmeter"
                withContext(Dispatchers.Main) {
                    subscriptions[handle] = Subscription(ch, ParamKind.AUX_SEND, bus + 1)
                    subscriptions[nameHandle] = Subscription(ch, ParamKind.NAME)
                    subscriptions[meterHandle] = Subscription(ch, ParamKind.METER)
                }
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, Pro2Commands.subSendLevelAddress(bus + 1), ch, ch, token))
                } catch (e: Exception) {
                    // не критично - не прерываем остальные
                }
                delay(2)
                // Заодно и имена каналов - вдруг ещё не подписаны.
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(nameHandle, Pro2Commands.nameAddress(), ch, ch, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
                // И метр - индикация того, что на канал реально приходит
                // сигнал, независимо от того, какая шина сейчас выбрана.
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(meterHandle, Pro2Commands.meterAddress(), ch, ch, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }
        }
    }

    /**
     * Отписывается от ВСЕХ подписок предыдущей сессии перед новым
     * подключением (или при закрытии приложения). Раньше этого не
     * происходило вообще - каждое переподключение просто накидывало ещё
     * ~800 новых подписок поверх старых, которые пульт никогда не узнавал,
     * что нужно снять (мы просто закрывали свой сокет, ничего не сообщая
     * пульту). После нескольких переподключений таблица подписок на
     * пульте, судя по всему, разбухала и начинала "сыпаться" - не только
     * у нашего приложения, но и у официального Mixtender (общая
     * перегрузка сессий с одного IP). Отправляется НАИЛУЧШИМ УСИЛИЕМ -
     * не ждём подтверждения, просто разгружаем пульт перед тем, как
     * подписаться заново.
     */
    internal suspend fun MainActivity.unsubscribeAllPrevious() {
        val prevSocket = socket ?: return
        val prevAddress = consoleAddress ?: return
        val prevPort = consolePort
        val allHandles = mutableListOf<String>()
        allHandles.addAll(subscriptions.keys)
        allHandles.addAll(masterSubscriptions.keys)
        allHandles.addAll(auxSubscriptions.keys)
        allHandles.addAll(auxBusSubscriptions.keys)
        allHandles.addAll(vcaSubscriptions.keys)
        allHandles.addAll(mainOutSubscriptions.keys)
        allHandles.addAll(ConnectionHolder.vcaMemberSubscriptions.keys)
        allHandles.addAll(ConnectionHolder.muteGroupMemberSubscriptions.keys)
        if (allHandles.isEmpty()) return
        for (handle in allHandles) {
            try {
                sendRaw(prevSocket, prevAddress, prevPort, Pro2Commands.unsubscribe(handle))
            } catch (e: Exception) {
                // сокет уже мог быть в процессе закрытия - не критично,
                // это best-effort очистка, не обязательная для нового подключения
            }
        }
    }

    internal fun MainActivity.connectAndSync() {
        val host = editHost.text.toString().trim()
        val port = editPort.text.toString().trim().toIntOrNull()
        if (host.isEmpty() || port == null) {
            textStatus.text = "Check IP and port"
            return
        }

        receiveJob?.cancel()
        pollJob?.cancel()

        CoroutineScope(Dispatchers.IO).launch {
            // Отписываемся от подписок ПРЕДЫДУЩЕЙ сессии, пока старый сокет и
            // карты подписок ещё живы (используем именно старое соединение -
            // новое ещё не создано). При самом первом подключении карты
            // пустые, функция сразу выходит, ничего не делая.
            unsubscribeAllPrevious()
            withContext(Dispatchers.Main) { socket?.close() }

            // Новое подключение - начинаем подписку с нуля.
            withContext(Dispatchers.Main) {
                sessionToken = null
                ConnectionHolder.lastPacketAtMs = 0L
                ConnectionHolder.releaseSubscribeGate()
                // ИСПРАВЛЕНИЕ: раньше здесь перечислялись карты вручную и
                // ТРИ из них забывались (vcaMemberSubscriptions,
                // mainOutExtrasSubscribed, auxBusExtrasSubscribed) - из-за
                // чего детальные экраны MATRIX/AUX после переподключения
                // больше не переподписывались. Теперь одна общая функция.
                ConnectionHolder.clearAllSubscriptionState()
                sessionId = System.currentTimeMillis().toString(36)
            }

            try {
                val address = InetAddress.getByName(host)
                // Локальный порт 10001 мог на миг остаться занятым ПРЕДЫДУЩИМ
                // сокетом (тем же самым приложением, при быстром
                // переподключении) - ОС не всегда освобождает порт мгновенно
                // после close(). Пробуем несколько раз с паузой вместо
                // немедленного отказа - раньше это могло дать ложную
                // "Connection error" при быстром переключении между
                // инженерным и мониторным режимом.
                var newSocket: DatagramSocket? = null
                var lastBindError: Exception? = null
                for (attempt in 0 until 5) {
                    try {
                        newSocket = DatagramSocket(10001)
                        break
                    } catch (e: Exception) {
                        lastBindError = e
                        delay(150)
                    }
                }
                if (newSocket == null) throw lastBindError ?: Exception("Не удалось занять локальный порт 10001")

                withContext(Dispatchers.Main) {
                    socket = newSocket
                    consoleAddress = address
                    consolePort = port
                    // НЕ "Connected": сокет создан, но пульт ещё ни разу
                    // не ответил. Зелёный поставит сторожевой таймер в
                    // pollJob, когда реально придут данные.
                    textConnectionStatus.text = "● Подключение..."
                    textConnectionStatus.setTextColor(Color.parseColor("#ff9f0a"))
                    textStatus.text = "Connected to $host:$port, subscribing to live updates..."
                    collapseConnectForm()
                }

                startReceiveLoop(newSocket)

                // ВАЖНО: раньше здесь всегда запрашивалось начальное
                // состояние ВСЕХ 56 каналов (280 GET-запросов без пауз) и
                // полная подписка (~800 параметров), даже для мониторного
                // режима, которому это всё вообще не нужно (мониторке нужны
                // только aux-шины + позже посылы выбранной шины). Из-за
                // этого суммарная нагрузка на пульт при подключении
                // мониторки оказывалась БОЛЬШЕ, чем у инженерского режима,
                // и, судя по всему, именно это сбивало официальный Mixtender.
                if (appMode == MODE_MONITOR) {
                    subscribeMonitorEssentials(newSocket, address, port)
                } else {
                    requestInitialState(newSocket, address, port)
                }

                // ВАЖНО - ИСПРАВЛЕНИЕ ЗАЦИКЛИВАНИЯ: раньше подписка ждала, пока
                // придёт входящий пакет с "токеном" пульта, чтобы его переиспользовать.
                // Но если это устройство/IP ещё ни на что не подписывалось, пульт
                // может вообще ничего не присылать сам по себе - тогда токен никогда
                // не появится, подписка никогда не отправится, и телефон бесконечно
                // ждёт то, что зависит от его же собственного действия.
                // Решение: подписываемся СРАЗУ с условным токеном-заглушкой (0),
                // не дожидаясь ответа. Если позже придёт настоящий токен от пульта -
                // sessionToken обновится, но переподписываться заново не обязательно.
                sessionToken = 0
                // Замок: subscribeAll() выполнит только первый вызывающий.
                // Берём его в обоих режимах - см. пояснение в MainActivity.
                val firstClaim = ConnectionHolder.claimSubscribeAll()
                if (firstClaim && appMode != MODE_MONITOR) {
                    subscribeAll()
                }
                startPollLoop(newSocket, address, port)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    textConnectionStatus.text = "● Connection error"
                    textConnectionStatus.setTextColor(Color.parseColor("#ff3b30"))
                    textStatus.text = "Connection error: ${e.message}"
                }
            }
        }
    }

    internal suspend fun MainActivity.requestInitialState(socket: DatagramSocket, address: InetAddress, port: Int) {
        for (i in 0 until numChannels) {
            sendRaw(socket, address, port, Pro2Commands.getFader(i))
            sendRaw(socket, address, port, Pro2Commands.getMute(i))
            sendRaw(socket, address, port, Pro2Commands.getSolo(i))
            sendRaw(socket, address, port, Pro2Commands.getGain(i))
            sendRaw(socket, address, port, Pro2Commands.getName(i))
            delay(2)
        }
    }

    internal suspend fun MainActivity.subscribeMonitorEssentials(socket: DatagramSocket, address: InetAddress, port: Int) {
        val token = sessionToken ?: 0
        val sid = sessionId
        for (b in 0 until 16) {
            val subs = listOf(
                "/h_${sid}_mb${b}_fader" to Triple(Pro2Commands.auxBusFaderAddress(), ParamKind.FADER, b),
                "/h_${sid}_mb${b}_mute" to Triple(Pro2Commands.auxBusMuteAddress(), ParamKind.MUTE, b),
                "/h_${sid}_mb${b}_name" to Triple(Pro2Commands.auxBusNameAddress(), ParamKind.NAME, b),
                "/h_${sid}_mb${b}_colour" to Triple(Pro2Commands.auxBusColourAddress(), ParamKind.COLOUR, b),
            )
            withContext(Dispatchers.Main) {
                for ((handle, info) in subs) auxBusSubscriptions[handle] = Subscription(info.third, info.second)
            }
            for ((handle, info) in subs) {
                val (path, kind, ch) = info
                try {
                    sendRaw(socket, address, port, Pro2Commands.batchSubscribe(handle, path, ch, ch, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }
        }
    }

    internal fun MainActivity.startPollLoop(socket: DatagramSocket, address: InetAddress, port: Int) {
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(3000L)
                try {
                    sendRaw(socket, address, port, Pro2Commands.renew())
                } catch (e: Exception) {
                    if (!isActive) break
                }
                // СТОРОЖЕВОЙ ТАЙМЕР СВЯЗИ.
                // Пульт при активных подписках шлёт обновления десятки раз
                // в секунду, поэтому молчание в несколько секунд - надёжный
                // признак обрыва (выключили пульт, выдернули кабель, ушли из
                // сети Wi-Fi). Раньше индикатор зеленел один раз при
                // создании сокета и не менялся уже никогда.
                val last = ConnectionHolder.lastPacketAtMs
                val silentMs = if (last == 0L) Long.MAX_VALUE
                               else android.os.SystemClock.elapsedRealtime() - last
                withContext(Dispatchers.Main) {
                    when {
                        silentMs > 6000L -> {
                            textConnectionStatus.text = "● Нет связи с пультом"
                            textConnectionStatus.setTextColor(Color.parseColor("#ff3b30"))
                        }
                        silentMs > 3000L -> {
                            textConnectionStatus.text = "● Связь нестабильна"
                            textConnectionStatus.setTextColor(Color.parseColor("#ff9f0a"))
                        }
                        else -> {
                            textConnectionStatus.text = "● Connected"
                            textConnectionStatus.setTextColor(Color.parseColor("#34c759"))
                        }
                    }
                }
            }
        }
    }

    internal fun MainActivity.startReceiveLoop(socket: DatagramSocket) {
        // Единственный потребитель очереди. Один на всю сессию вместо
        // корутины на каждый пакет.
        ConnectionHolder.uiJob?.cancel()
        ConnectionHolder.uiJob = CoroutineScope(Dispatchers.Main).launch {
            for (batch in ConnectionHolder.incomingQueue) {
                for (msg in batch) handleIncomingMessage(msg)
            }
        }
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            // ИСПРАВЛЕНИЕ: было 4096. DatagramPacket обрезает датаграмму по
            // размеру буфера МОЛЧА - без исключения и без признака обрезки.
            // Обрезанный бандл потом разбирается частично (парсер упирается
            // в `pos + size > end` и прекращает разбор), то есть часть
            // обновлений просто теряется, причём тем чаще, чем больше
            // подписок. 64 КБ - максимальный размер UDP-датаграммы, так что
            // обрезка исключена в принципе.
            val buffer = ByteArray(65536)
            socket.soTimeout = 1000
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    // Отметка живой связи - см. ConnectionHolder.lastPacketAtMs.
                    ConnectionHolder.lastPacketAtMs = android.os.SystemClock.elapsedRealtime()
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)

                    // ВАЖНО: пульт почти всё шлёт завёрнутым в OSC-бандлы, а не
                    // простыми сообщениями - используем decodeElement (умеет и то,
                    // и другое), а не старый decode().
                    val element = OscUtil.decodeElement(data) ?: continue
                    val messages = OscUtil.flatten(element)

                    // ВАЖНО (исправление зависания метров): раньше здесь стоял
                    // withContext(Dispatchers.Main), который блокирует цикл
                    // приёма, пока обновление интерфейса полностью не
                    // закончится - и только потом читается следующий пакет
                    // из сокета. Метры обновляются намного чаще всего
                    // остального (десятки раз в секунду по всем каналам), и
                    // при любой задержке на главном потоке буфер ОС
                    // переполняется, а лишние UDP-пакеты молча теряются -
                    // именно метры страдают от этого первыми и сильнее
                    // всего. Теперь просто "отправляем и забываем" - цикл
                    // приёма сразу же возвращается вычитывать сокет дальше,
                    // не дожидаясь окончания отрисовки.
                    // ИСПРАВЛЕНИЕ ПОДВИСАНИЙ.
                    // Раньше здесь на КАЖДЫЙ пакет создавалась новая
                    // CoroutineScope и запускалась корутина на главном
                    // потоке. Пульт шлёт десятки пакетов в секунду, и
                    // если главный поток не успевает их отрисовывать
                    // (а на планшете виджетов заметно больше), очередь
                    // задач растёт без ограничений. Интерфейс при этом
                    // продолжает разгребать УСТАРЕВШИЕ значения - отсюда
                    // и тормоза при открытии и переключении вкладок.
                    //
                    // Теперь пакеты складываются в очередь ограниченной
                    // длины: при переполнении самые старые выбрасываются.
                    // Для метров и уровней это ровно то, что нужно -
                    // важно последнее значение, а не все промежуточные.
                    ConnectionHolder.incomingQueue.trySend(messages)
                } catch (e: SocketTimeoutException) {
                    // норма - просто нет данных за секунду, продолжаем ждать
                } catch (e: Exception) {
                    if (!isActive) break
                    withContext(Dispatchers.Main) { textStatus.text = "Receive error: ${e.message}" }
                }
            }
        }
    }

    internal fun MainActivity.handleIncomingMessage(message: OscElement.Message) {
        // Учимся реальному токену пульта из ЛЮБОГО входящего ",bi"-сообщения.
        // Подписка (subscribeAll) уже отправлена сразу при подключении с токеном-
        // заглушкой (0), чтобы не зависеть от того, придёт ли что-то от пульта
        // само по себе - здесь просто держим sessionToken в актуальном состоянии
        // на будущее (например, если понадобится переподписаться).
        if (message.typeTag == ",bi" && message.args.size == 2) {
            val token = message.args[1] as? Int
            if (token != null) {
                sessionToken = token
                // Раньше здесь была неатомарная проверка !subscribedAlready -
                // при гонке с потоком подключения подписка уходила дважды.
                if (ConnectionHolder.claimSubscribeAll()) {
                    subscribeAll()
                }
            }
        }

        // ОТСЕВ ПОВТОРОВ.
        // Пульт присылает значение подписки постоянно (по захвату - около
        // 30 раз в секунду), НЕЗАВИСИМО от того, менялось ли оно. Без
        // отсева приложение перерисовывает интерфейс на каждое такое
        // сообщение, хотя показывать нечего нового: тысячи операций с
        // вью в секунду впустую. Именно это и делало планшет вялым.
        //
        // Сравниваем с прошлым значением по хэндлу и выходим, если оно не
        // изменилось. Метры и движущиеся фейдеры проходят дальше как
        // обычно - у них значение действительно меняется.
        //
        // Безопасно потому, что экраны при открытии берут состояние из
        // ConnectionHolder напрямую, а не ждут очередного пакета.
        if (message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray
            if (blob != null && blob.size == 4) {
                val v = (blob[0].toInt() and 0xFF) or ((blob[1].toInt() and 0xFF) shl 8) or
                        ((blob[2].toInt() and 0xFF) shl 16) or ((blob[3].toInt() and 0xFF) shl 24)
                val prev = ConnectionHolder.lastValueByHandle.put(message.address, v)
                if (prev != null && prev == v) return
            }
        }

        // ДИАГНОСТИКА: пульт отвечает на подписку ВСЕГДА, но если параметра
        // в этой группе не существует, в ответ приходит blob НУЛЕВОЙ длины
        // вместо обычных 4 байт. Раньше такие ответы просто молча
        // отбрасывались проверками вида `blob.size >= 4`, и неработающий
        // параметр было невозможно отличить от работающего: ручка рисуется,
        // крутится, команды уходят - а пульт их игнорирует.
        //
        // Так были найдены сразу несколько ошибок в адресах (третья полоса
        // канального EQ, gain EQ aux-шин, три параметра компрессора MATRIX).
        // Теперь каждый такой параметр попадает в лог ровно один раз.
        if (message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val probe = message.args[0] as? ByteArray
            if (probe != null && probe.isEmpty() &&
                ConnectionHolder.unsupportedHandles.add(message.address)
            ) {
                android.util.Log.w(
                    "MidasUnsupported",
                    "Пульт вернул пустое значение для ${message.address} - " +
                        "параметра нет в этой группе, адрес нужно проверить"
                )
            }
        }

        // Путь 1: это ответ на нашу подписку?
        val subscription = subscriptions[message.address]
        if (subscription != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleSubscribedValue(subscription, blob)
            return
        }
        val masterSub = masterSubscriptions[message.address]
        if (masterSub != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleMasterSubscribedValue(masterSub, blob)
            return
        }
        val auxSub = auxSubscriptions[message.address]
        if (auxSub != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleAuxReturnSubscribedValue(auxSub, blob)
            return
        }
        val auxBusSub = auxBusSubscriptions[message.address]
        if (auxBusSub != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleAuxBusSubscribedValue(auxBusSub, blob)
            return
        }
        val vcaSub = vcaSubscriptions[message.address]
        if (vcaSub != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleVcaSubscribedValue(vcaSub, blob)
            return
        }
        val chMg = ConnectionHolder.channelMuteGroupSubscriptions[message.address]
        if (chMg != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            if (blob.size >= 4) {
                val member = java.nio.ByteBuffer.wrap(blob)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                val (ch, g) = chMg
                ConnectionHolder.channelMuteGroups[ch][g] = member
                updateChannelMuteGroupButton(ch, g, member)
            }
            return
        }

        val muteGroupSub = ConnectionHolder.muteGroupSubscriptions[message.address]
        if (muteGroupSub != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleMuteGroupSubscribedValue(muteGroupSub, blob)
            return
        }
        val mainOutSub = mainOutSubscriptions[message.address]
        if (mainOutSub != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleMainOutSubscribedValue(mainOutSub, blob)
            return
        }
        val vcaMemberSub = vcaMemberSubscriptions[message.address]
        if (vcaMemberSub != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleVcaMemberSubscribedValue(vcaMemberSub, blob)
            return
        }
        val muteGroupMemberSub = ConnectionHolder.muteGroupMemberSubscriptions[message.address]
        if (muteGroupMemberSub != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleMuteGroupMemberSubscribedValue(muteGroupMemberSub, blob)
            return
        }

        // Путь 2: человекочитаемый адрес (см. примечание выше - по факту не срабатывает,
        // оставлено на будущее / на случай другой прошивки пульта).
        val args = message.args
        when (message.address) {
            Pro2Commands.faderAddress() -> {
                val channel = (args.getOrNull(0) as? Int) ?: return
                val level = (args.getOrNull(1) as? Float) ?: return
                updateFaderUi(channel, level)
            }
            Pro2Commands.muteAddress() -> {
                val channel = (args.getOrNull(0) as? Int) ?: return
                val muted = (args.getOrNull(1) as? Int) ?: return
                updateMuteUi(channel, muted != 0)
            }
            Pro2Commands.soloAddress() -> {
                val channel = (args.getOrNull(0) as? Int) ?: return
                val soloed = (args.getOrNull(1) as? Int) ?: return
                updateSoloUi(channel, soloed != 0)
            }
            Pro2Commands.gainAddress() -> {
                val channel = (args.getOrNull(0) as? Int) ?: return
                val level = (args.getOrNull(1) as? Float) ?: return
                updateGainUi(channel, level)
            }
            Pro2Commands.nameAddress() -> {
                val channel = (args.getOrNull(0) as? Int) ?: return
                val name = (args.getOrNull(1) as? String) ?: return
                updateNameUi(channel, name)
            }
            else -> { /* прочие сообщения (например, метры) пока не обрабатываем */ }
        }
    }

    internal fun MainActivity.subscribeAll() {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            for (i in 0 until numChannels) {
                val subs = listOf(
                    "/h_${sid}_${i}_fader" to Triple(Pro2Commands.faderAddress(), ParamKind.FADER, i),
                    "/h_${sid}_${i}_mute" to Triple(Pro2Commands.muteAddress(), ParamKind.MUTE, i),
                    "/h_${sid}_${i}_solo" to Triple(Pro2Commands.soloAddress(), ParamKind.SOLO, i),
                    "/h_${sid}_${i}_gain" to Triple(Pro2Commands.gainAddress(), ParamKind.GAIN, i),
                    "/h_${sid}_${i}_name" to Triple(Pro2Commands.nameAddress(), ParamKind.NAME, i),
                    "/h_${sid}_${i}_colour" to Triple(Pro2Commands.colourAddress(), ParamKind.COLOUR, i),
                    "/h_${sid}_${i}_meter" to Triple(Pro2Commands.meterAddress(), ParamKind.METER, i),
                    "/h_${sid}_${i}_compratio" to Triple(Pro2Commands.compRatioAddress(), ParamKind.COMP_RATIO, i),
                    "/h_${sid}_${i}_compattack" to Triple(Pro2Commands.compAttackAddress(), ParamKind.COMP_ATTACK, i),
                    "/h_${sid}_${i}_comprelease" to Triple(Pro2Commands.compReleaseAddress(), ParamKind.COMP_RELEASE, i),
                    "/h_${sid}_${i}_compthreshold" to Triple(Pro2Commands.compThresholdAddress(), ParamKind.COMP_THRESHOLD, i),
                    "/h_${sid}_${i}_compmakeup" to Triple(Pro2Commands.compMakeupGainAddress(), ParamKind.COMP_MAKEUP, i),
                    "/h_${sid}_${i}_compin" to Triple(Pro2Commands.compInAddress(), ParamKind.COMP_IN, i),
                )
                // ВАЖНО (исправление потерянных подписок): раньше здесь на
                // КАЖДУЮ подписку было отдельное переключение на главный
                // поток (withContext), да ещё пакеты слались вообще без
                // пауз - пульт получал шквал из тысяч UDP-пакетов почти
                // одновременно и, судя по всему, часть просто не успевал
                // обработать (отсюда непокрашенные/неподписанные каналы и
                // шины). Теперь: сначала одним пакетом регистрируем все
                // хендлы (один переход на главный поток вместо тысячи), а
                // сами пакеты шлём с небольшой паузой между ними - хватает,
                // чтобы не заваливать пульт, но не сильно удлиняет общее
                // время подписки.
                withContext(Dispatchers.Main) {
                    for ((handle, info) in subs) {
                        subscriptions[handle] = Subscription(info.third, info.second)
                    }
                }
                for ((handle, info) in subs) {
                    val (path, kind, channel) = info
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, channel, channel, token))
                    } catch (e: Exception) {
                        // подписка на один параметр не удалась - не прерываем остальные
                    }
                    delay(2)
                }
            }

            // Мастер-каналы (3 шт. по мануалу) - НЕ подтверждено реальным
            // захватом, но объём небольшой (12 подписок), поэтому
            // подписываемся сразу вместе со всем остальным.
            for (m in 0 until 3) {
                val masterSubs = listOf(
                    "/h_${sid}_m${m}_fader" to Triple(Pro2Commands.masterFaderAddress(), ParamKind.FADER, m),
                    "/h_${sid}_m${m}_mute" to Triple(Pro2Commands.masterMuteAddress(), ParamKind.MUTE, m),
                    "/h_${sid}_m${m}_solo" to Triple(Pro2Commands.masterSoloAddress(), ParamKind.SOLO, m),
                    "/h_${sid}_m${m}_solob" to Triple(Pro2Commands.masterSoloBAddress(), ParamKind.SOLO_B, m),
                    "/h_${sid}_m${m}_meter" to Triple(Pro2Commands.masterMeterAddress(), ParamKind.METER, m),
                    "/h_${sid}_m${m}_name" to Triple(Pro2Commands.masterNameAddress(), ParamKind.NAME, m),
                )
                for ((handle, info) in masterSubs) {
                    val (path, kind, mIdx) = info
                    withContext(Dispatchers.Main) { masterSubscriptions[handle] = Subscription(mIdx, kind) }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, mIdx, mIdx, token))
                    } catch (e: Exception) {
                        // не критично - не прерываем остальное
                    }
                }
            }

            // Aux Returns (8 шт. по мануалу) - НЕ подтверждено реальным
            // захватом. Объём небольшой (32 подписки), подписываемся сразу.
            for (a in 0 until 8) {
                val auxSubs = listOf(
                    "/h_${sid}_a${a}_fader" to Triple(Pro2Commands.auxReturnFaderAddress(), ParamKind.FADER, a),
                    "/h_${sid}_a${a}_mute" to Triple(Pro2Commands.auxReturnMuteAddress(), ParamKind.MUTE, a),
                    "/h_${sid}_a${a}_solo" to Triple(Pro2Commands.auxReturnSoloAddress(), ParamKind.SOLO, a),
                    "/h_${sid}_a${a}_solob" to Triple(Pro2Commands.auxReturnSoloBAddress(), ParamKind.SOLO_B, a),
                    "/h_${sid}_a${a}_meter" to Triple(Pro2Commands.auxReturnMeterAddress(), ParamKind.METER, a),
                    "/h_${sid}_a${a}_name" to Triple(Pro2Commands.auxReturnNameAddress(), ParamKind.NAME, a),
                    "/h_${sid}_a${a}_colour" to Triple(Pro2Commands.auxReturnColourAddress(), ParamKind.COLOUR, a),
                )
                for ((handle, info) in auxSubs) {
                    val (path, kind, aIdx) = info
                    withContext(Dispatchers.Main) { auxSubscriptions[handle] = Subscription(aIdx, kind) }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, aIdx, aIdx, token))
                    } catch (e: Exception) {
                        // не критично - не прерываем остальное
                    }
                }
            }

            // 16 aux-шин - НЕ подтверждено реальным захватом. Объём умеренный
            // (64 подписки), но всё ещё небольшой на фоне 56 каналов, так что
            // подписываемся сразу, без ленивой подписки.
            for (b in 0 until 16) {
                val busSubs = listOf(
                    "/h_${sid}_b${b}_fader" to Triple(Pro2Commands.auxBusFaderAddress(), ParamKind.FADER, b),
                    "/h_${sid}_b${b}_mute" to Triple(Pro2Commands.auxBusMuteAddress(), ParamKind.MUTE, b),
                    "/h_${sid}_b${b}_solo" to Triple(Pro2Commands.auxBusSoloAddress(), ParamKind.SOLO, b),
                    "/h_${sid}_b${b}_solob" to Triple(Pro2Commands.auxBusSoloBAddress(), ParamKind.SOLO_B, b),
                    "/h_${sid}_b${b}_meter" to Triple(Pro2Commands.auxBusMeterAddress(), ParamKind.METER, b),
                    "/h_${sid}_b${b}_name" to Triple(Pro2Commands.auxBusNameAddress(), ParamKind.NAME, b),
                    "/h_${sid}_b${b}_colour" to Triple(Pro2Commands.auxBusColourAddress(), ParamKind.COLOUR, b),
                )
                for ((handle, info) in busSubs) {
                    val (path, kind, bIdx) = info
                    withContext(Dispatchers.Main) { auxBusSubscriptions[handle] = Subscription(bIdx, kind) }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, bIdx, bIdx, token))
                    } catch (e: Exception) {
                        // не критично - не прерываем остальное
                    }
                }
            }

            // VCA-группы (8 шт.) - ПОЛНОСТЬЮ ПОДТВЕРЖДЕНО реальным трафиком iPad.
            for (v in 0 until 8) {
                val vcaSubs = listOf(
                    "/h_${sid}_v${v}_fader" to Triple(Pro2Commands.vcaFaderAddress(), ParamKind.FADER, v),
                    "/h_${sid}_v${v}_mute" to Triple(Pro2Commands.vcaMuteAddress(), ParamKind.MUTE, v),
                    "/h_${sid}_v${v}_solo" to Triple(Pro2Commands.vcaSoloAddress(), ParamKind.SOLO, v),
                    "/h_${sid}_v${v}_name" to Triple(Pro2Commands.vcaNameAddress(), ParamKind.NAME, v),
                    "/h_${sid}_v${v}_colour" to Triple(Pro2Commands.vcaColourAddress(), ParamKind.COLOUR, v),
                )
                for ((handle, info) in vcaSubs) {
                    val (path, kind, vIdx) = info
                    withContext(Dispatchers.Main) { vcaSubscriptions[handle] = Subscription(vIdx, kind) }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, vIdx, vIdx, token))
                    } catch (e: Exception) {
                        // не критично - не прерываем остальное
                    }
                }
            }

            // Мьют-группы (8 шт.) - базовое состояние (mute-кнопка + имя).
            // Членство детей подписывается отдельно, лениво, только при
            // открытии экрана участников (см. subscribeMuteGroupMembers) -
            // как и у VCA, 5 полей x 56+16+8+8+3 = слишком много для
            // безусловной подписки при каждом входе в приложение.
            // ⚠ Структура НЕ подтверждена собственным захватом трафика.
            for (g in 0 until ConnectionHolder.MUTE_GROUP_COUNT) {
                val muteGroupSubs = listOf(
                    "/h_${sid}_mg${g}_mute" to Triple(Pro2Commands.muteGroupMuteAddress(), ParamKind.MUTE, g),
                    "/h_${sid}_mg${g}_name" to Triple(Pro2Commands.muteGroupNameAddress(), ParamKind.NAME, g),
                )
                for ((handle, info) in muteGroupSubs) {
                    val (path, kind, gIdx) = info
                    withContext(Dispatchers.Main) { ConnectionHolder.muteGroupSubscriptions[handle] = Subscription(gIdx, kind) }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, gIdx, gIdx, token))
                    } catch (e: Exception) {
                        // не критично - не прерываем остальное
                    }
                }
            }

            // Main Outs (8 шт., "matrix out" на пульте) - базовая полоса.
            // fader ПОДТВЕРЖДЁН сторонним датасетом; mute/solo/цвет/метр -
            // по аналогии с остальными группами (см. заметку в
            // Pro2Commands.kt у mainOut*Address()).
            for (mo in 0 until 8) {
                val mainOutSubs = listOf(
                    "/h_${sid}_mo${mo}_fader" to Triple(Pro2Commands.mainOutFaderAddress(), ParamKind.FADER, mo),
                    "/h_${sid}_mo${mo}_mute" to Triple(Pro2Commands.mainOutMuteAddress(), ParamKind.MUTE, mo),
                    "/h_${sid}_mo${mo}_solo" to Triple(Pro2Commands.mainOutSoloAddress(), ParamKind.SOLO, mo),
                    "/h_${sid}_mo${mo}_name" to Triple(Pro2Commands.mainOutNameAddress(), ParamKind.NAME, mo),
                    "/h_${sid}_mo${mo}_colour" to Triple(Pro2Commands.mainOutColourAddress(), ParamKind.COLOUR, mo),
                    "/h_${sid}_mo${mo}_meter" to Triple(Pro2Commands.mainOutMeterAddress(), ParamKind.METER, mo),
                )
                for ((handle, info) in mainOutSubs) {
                    val (path, kind, moIdx) = info
                    withContext(Dispatchers.Main) { mainOutSubscriptions[handle] = Subscription(moIdx, kind) }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, moIdx, moIdx, token))
                    } catch (e: Exception) {
                        // не критично - не прерываем остальное
                    }
                }
            }

            withContext(Dispatchers.Main) {
                textStatus.text = "Subscribed to live updates for all channels"
            }
        }
    }

    internal fun MainActivity.subscribeAuxSends(channel: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return
        if (auxSendsSubscribed.contains(channel)) return
        auxSendsSubscribed.add(channel)

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            for (bus in 1..16) {
                val handle = "/h_${sid}_${channel}_send$bus"
                val enHandle = "/h_${sid}_${channel}_senden$bus"
                val preHandle = "/h_${sid}_${channel}_sendpre$bus"
                withContext(Dispatchers.Main) {
                    subscriptions[handle] = Subscription(channel, ParamKind.AUX_SEND, bus)
                    subscriptions[enHandle] = Subscription(channel, ParamKind.AUX_SEND_ENABLE, bus)
                    subscriptions[preHandle] = Subscription(channel, ParamKind.AUX_SEND_PREFADE, bus)
                }
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, Pro2Commands.subSendLevelAddress(bus), channel, channel, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(enHandle, Pro2Commands.subSendEnableAddress(bus), channel, channel, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(preHandle, Pro2Commands.subSendPreFadeAddress(bus), channel, channel, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }

            // Посылы в 8 матричных шин (enMainSendLevel1-8). Показываются
            // на том же экране SENDS, что и аукс-посылы, поэтому
            // подписываемся здесь же - отдельная ленивая подписка не нужна.
            for (mtx in 1..8) {
                val handle = "/h_${sid}_${channel}_mtx$mtx"
                val enHandle = "/h_${sid}_${channel}_mtxen$mtx"
                val preHandle = "/h_${sid}_${channel}_mtxpre$mtx"
                withContext(Dispatchers.Main) {
                    subscriptions[handle] = Subscription(channel, ParamKind.MATRIX_SEND, mtx)
                    subscriptions[enHandle] = Subscription(channel, ParamKind.MATRIX_SEND_ENABLE, mtx)
                    subscriptions[preHandle] = Subscription(channel, ParamKind.MATRIX_SEND_PREFADE, mtx)
                }
                for ((h, path) in listOf(
                    handle to Pro2Commands.mainSendLevelAddress(mtx),
                    enHandle to Pro2Commands.mainSendEnableAddress(mtx),
                    preHandle to Pro2Commands.mainSendPreFadeAddress(mtx)
                )) {
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(h, path, channel, channel, token))
                    } catch (e: Exception) {
                        // не критично
                    }
                    delay(2)
                }
            }
        }
    }

    internal fun MainActivity.subscribeAuxBusExtras(index: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return
        if (auxBusExtrasSubscribed.contains(index)) return
        auxBusExtrasSubscribed.add(index)

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            val subs = mutableListOf<Pair<String, Subscription>>()
            val paths = mutableMapOf<String, String>()
            for (band in 0 until 6) {
                val fh = "/h_${sid}_ab${index}_eqfreq$band"
                val gh = "/h_${sid}_ab${index}_eqgain$band"
                val wh = "/h_${sid}_ab${index}_eqwidth$band"
                subs.add(fh to Subscription(index, ParamKind.EQ_FREQ, eqBand = band))
                subs.add(gh to Subscription(index, ParamKind.EQ_GAIN, eqBand = band))
                subs.add(wh to Subscription(index, ParamKind.EQ_WIDTH, eqBand = band))
                paths[fh] = Pro2Commands.auxBusEqFreqAddress(band)
                paths[gh] = Pro2Commands.auxBusEqGainAddress(band)
                paths[wh] = Pro2Commands.auxBusEqWidthAddress(band)
            }
            val ratioH = "/h_${sid}_ab${index}_compratio"
            val attackH = "/h_${sid}_ab${index}_compattack"
            val releaseH = "/h_${sid}_ab${index}_comprelease"
            val threshH = "/h_${sid}_ab${index}_compthreshold"
            val makeupH = "/h_${sid}_ab${index}_compmakeup"
            subs.add(ratioH to Subscription(index, ParamKind.COMP_RATIO))
            subs.add(attackH to Subscription(index, ParamKind.COMP_ATTACK))
            subs.add(releaseH to Subscription(index, ParamKind.COMP_RELEASE))
            subs.add(threshH to Subscription(index, ParamKind.COMP_THRESHOLD))
            subs.add(makeupH to Subscription(index, ParamKind.COMP_MAKEUP))
            paths[ratioH] = Pro2Commands.auxBusCompRatioAddress()
            paths[attackH] = Pro2Commands.auxBusCompAttackAddress()
            paths[releaseH] = Pro2Commands.auxBusCompReleaseAddress()
            paths[threshH] = Pro2Commands.auxBusCompThresholdAddress()
            paths[makeupH] = Pro2Commands.auxBusCompMakeupAddress()

            // LINK - ПОДТВЕРЖДЕНО реальным захватом (в отличие от EQ/comp
            // выше, которые в записи вообще не встретились подписанными).
            val linkH = "/h_${sid}_ab${index}_link"
            subs.add(linkH to Subscription(index, ParamKind.LINK))
            paths[linkH] = Pro2Commands.auxBusPairingAddress()

            withContext(Dispatchers.Main) {
                for ((handle, sub) in subs) auxBusSubscriptions[handle] = sub
            }
            for ((handle, sub) in subs) {
                val path = paths[handle] ?: continue
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, index, index, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }
        }
    }

    internal fun MainActivity.subscribeMainOutExtras(index: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return
        if (mainOutExtrasSubscribed.contains(index)) return
        mainOutExtrasSubscribed.add(index)

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            val subs = mutableListOf<Pair<String, Subscription>>()
            for (band in 0 until 6) {
                subs.add("/h_${sid}_mo${index}_eqfreq$band" to Subscription(index, ParamKind.EQ_FREQ, eqBand = band))
                subs.add("/h_${sid}_mo${index}_eqgain$band" to Subscription(index, ParamKind.EQ_GAIN, eqBand = band))
                subs.add("/h_${sid}_mo${index}_eqwidth$band" to Subscription(index, ParamKind.EQ_WIDTH, eqBand = band))
            }
            val paths = mutableMapOf<String, String>()
            for (band in 0 until 6) {
                paths["/h_${sid}_mo${index}_eqfreq$band"] = Pro2Commands.mainOutEqFreqAddress(band)
                paths["/h_${sid}_mo${index}_eqgain$band"] = Pro2Commands.mainOutEqGainAddress(band)
                paths["/h_${sid}_mo${index}_eqwidth$band"] = Pro2Commands.mainOutEqWidthAddress(band)
            }
            subs.add("/h_${sid}_mo${index}_compratio" to Subscription(index, ParamKind.COMP_RATIO))
            subs.add("/h_${sid}_mo${index}_compattack" to Subscription(index, ParamKind.COMP_ATTACK))
            subs.add("/h_${sid}_mo${index}_comprelease" to Subscription(index, ParamKind.COMP_RELEASE))
            subs.add("/h_${sid}_mo${index}_compthreshold" to Subscription(index, ParamKind.COMP_THRESHOLD))
            subs.add("/h_${sid}_mo${index}_compmakeup" to Subscription(index, ParamKind.COMP_MAKEUP))
            paths["/h_${sid}_mo${index}_compratio"] = Pro2Commands.mainOutCompRatioAddress()
            paths["/h_${sid}_mo${index}_compattack"] = Pro2Commands.mainOutCompAttackAddress()
            paths["/h_${sid}_mo${index}_comprelease"] = Pro2Commands.mainOutCompReleaseAddress()
            paths["/h_${sid}_mo${index}_compthreshold"] = Pro2Commands.mainOutCompThresholdAddress()
            paths["/h_${sid}_mo${index}_compmakeup"] = Pro2Commands.mainOutCompMakeupAddress()

            // Новые параметры - ПОДТВЕРЖДЕНЫ реальным захватом трафика
            // (all config ipad.pcapng), поведение не проверялось.
            subs.add("/h_${sid}_mo${index}_link" to Subscription(index, ParamKind.LINK))
            subs.add("/h_${sid}_mo${index}_bustrim" to Subscription(index, ParamKind.BUS_TRIM))
            subs.add("/h_${sid}_mo${index}_compstyle" to Subscription(index, ParamKind.COMP_STYLE))
            paths["/h_${sid}_mo${index}_link"] = Pro2Commands.mainOutPairingAddress()
            paths["/h_${sid}_mo${index}_bustrim"] = Pro2Commands.mainOutBusTrimAddress()
            paths["/h_${sid}_mo${index}_compstyle"] = Pro2Commands.mainOutCompStyleAddress()
            // УБРАНЫ: presence / filterbw / knee.
            // Пульт отвечал на эти подписки blob-ом НУЛЕВОЙ длины, то есть
            // "такого параметра в этой группе нет" - по 7241 ответу на
            // каждый, ни одного значения. Датасет подтверждает независимо:
            // enCompLimPresence / enCompLimKnee / enCompLimFilterBandwidth
            // существуют у Masters, MicInputs и SubMixes, но НЕ у MainOuts.
            // Изначально они были приписаны MainOuts по ошибке при разборе
            // захвата. enCompStyle, наоборот, ОСТАЁТСЯ - он у MainOuts
            // реальный (7241 ответ с настоящими 4-байтовыми значениями),
            // хотя в датасете его нет вообще ни в одной группе.

            withContext(Dispatchers.Main) {
                for ((handle, sub) in subs) mainOutSubscriptions[handle] = sub
            }
            for ((handle, sub) in subs) {
                val path = paths[handle] ?: continue
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, index, index, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }
        }
    }

    internal fun MainActivity.subscribeEq(channel: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return
        if (eqSubscribed.contains(channel)) return
        eqSubscribed.add(channel)

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            val subs = mutableListOf<Triple<String, String, Subscription>>()
            subs.add(Triple("/h_${sid}_${channel}_eqin", Pro2Commands.eqInAddress(), Subscription(channel, ParamKind.EQ_IN)))
            val bands = arrayOf(Pro2Commands.EqBand.BASS, Pro2Commands.EqBand.LOW_MID, Pro2Commands.EqBand.MID_HIGH, Pro2Commands.EqBand.TREBLE)
            for ((bandIndex, band) in bands.withIndex()) {
                subs.add(Triple("/h_${sid}_${channel}_eqact$bandIndex", Pro2Commands.eqBandActiveAddress(band), Subscription(channel, ParamKind.EQ_BAND_ACTIVE, eqBand = bandIndex)))
                subs.add(Triple("/h_${sid}_${channel}_eqfreq$bandIndex", Pro2Commands.eqFreqAddress(band), Subscription(channel, ParamKind.EQ_FREQ, eqBand = bandIndex)))
                subs.add(Triple("/h_${sid}_${channel}_eqgain$bandIndex", Pro2Commands.eqGainAddress(band), Subscription(channel, ParamKind.EQ_GAIN, eqBand = bandIndex)))
                subs.add(Triple("/h_${sid}_${channel}_eqwidth$bandIndex", Pro2Commands.eqWidthAddress(band), Subscription(channel, ParamKind.EQ_WIDTH, eqBand = bandIndex)))
            }
            // Форма (bell/shelf) - ТОЛЬКО у BASS (индекс 0) и TREBLE (индекс 3).
            subs.add(Triple("/h_${sid}_${channel}_eqshapebass", Pro2Commands.eqShapeAddress(Pro2Commands.EqBand.BASS), Subscription(channel, ParamKind.EQ_SHAPE_BASS)))
            subs.add(Triple("/h_${sid}_${channel}_eqshapetreble", Pro2Commands.eqShapeAddress(Pro2Commands.EqBand.TREBLE), Subscription(channel, ParamKind.EQ_SHAPE_TREBLE)))
            withContext(Dispatchers.Main) {
                for ((handle, _, sub) in subs) subscriptions[handle] = sub
            }
            for ((handle, path, sub) in subs) {
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, channel, channel, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }
        }
    }

    internal fun MainActivity.subscribeInputExtras(channel: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return
        if (inputExtrasSubscribed.contains(channel)) return
        inputExtrasSubscribed.add(channel)

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            val subs = listOf(
                "/h_${sid}_${channel}_solob" to Triple(Pro2Commands.soloBAddress(), ParamKind.SOLO_B, channel),
                "/h_${sid}_${channel}_gaintrim" to Triple(Pro2Commands.gainTrimAddress(), ParamKind.GAIN_TRIM, channel),
                "/h_${sid}_${channel}_pan" to Triple(Pro2Commands.panAddress(), ParamKind.PAN, channel),
                "/h_${sid}_${channel}_phantom" to Triple(Pro2Commands.phantomPowerAddress(), ParamKind.PHANTOM, channel),
                "/h_${sid}_${channel}_phase" to Triple(Pro2Commands.phaseAddress(), ParamKind.PHASE, channel),
                "/h_${sid}_${channel}_hpin" to Triple(Pro2Commands.hpFilterInAddress(), ParamKind.HP_FILTER_IN, channel),
                "/h_${sid}_${channel}_hpfreq" to Triple(Pro2Commands.hpFilterFreqAddress(), ParamKind.HP_FILTER_FREQ, channel),
                "/h_${sid}_${channel}_lpin" to Triple(Pro2Commands.lpFilterInAddress(), ParamKind.LP_FILTER_IN, channel),
                "/h_${sid}_${channel}_lpfreq" to Triple(Pro2Commands.lpFilterFreqAddress(), ParamKind.LP_FILTER_FREQ, channel),
                "/h_${sid}_${channel}_delay" to Triple(Pro2Commands.inputDelayAddress(), ParamKind.INPUT_DELAY, channel),
                "/h_${sid}_${channel}_link" to Triple(Pro2Commands.linkAddress(), ParamKind.LINK, channel),
                "/h_${sid}_${channel}_chsource" to Triple(Pro2Commands.channelSourceAddress(), ParamKind.CHANNEL_SOURCE, channel),
            )
            withContext(Dispatchers.Main) {
                for ((handle, info) in subs) subscriptions[handle] = Subscription(info.third, info.second)
            }
            for ((handle, info) in subs) {
                val (path, kind, ch) = info
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, ch, ch, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }
        }
    }

    internal fun MainActivity.subscribeCompGateExtras(channel: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return
        if (compGateExtrasSubscribed.contains(channel)) return
        compGateExtrasSubscribed.add(channel)

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            val subs = listOf(
                "/h_${sid}_${channel}_compgr" to Triple(Pro2Commands.compGrMeterAddress(), ParamKind.COMP_GR_METER, channel),
                "/h_${sid}_${channel}_compdet" to Triple(Pro2Commands.compDetMeterAddress(), ParamKind.COMP_DET_METER, channel),
                "/h_${sid}_${channel}_compflt" to Triple(Pro2Commands.compFiltersInAddress(), ParamKind.COMP_FILTERS_IN, channel),
                "/h_${sid}_${channel}_compfltfreq" to Triple(Pro2Commands.compFilterFreqAddress(), ParamKind.COMP_FILTER_FREQ, channel),
                "/h_${sid}_${channel}_gategr" to Triple(Pro2Commands.gateGrMeterAddress(), ParamKind.GATE_GR_METER, channel),
                "/h_${sid}_${channel}_gatedet" to Triple(Pro2Commands.gateDetMeterAddress(), ParamKind.GATE_DET_METER, channel),
                "/h_${sid}_${channel}_compmode" to Triple(Pro2Commands.compDetectorModeAddress(), ParamKind.COMP_MODE, channel),
                "/h_${sid}_${channel}_gatemode" to Triple(Pro2Commands.gateModeAddress(), ParamKind.GATE_MODE, channel),
            )
            withContext(Dispatchers.Main) {
                for ((handle, info) in subs) subscriptions[handle] = Subscription(info.third, info.second)
            }
            for ((handle, info) in subs) {
                val (path, kind, ch) = info
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, ch, ch, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }
        }
    }

    internal fun MainActivity.subscribeGate(channel: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return
        if (gateSubscribed.contains(channel)) return
        gateSubscribed.add(channel)

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            val subs = listOf(
                "/h_${sid}_${channel}_gatein" to Triple(Pro2Commands.gateInAddress(), ParamKind.GATE_IN, channel),
                "/h_${sid}_${channel}_gatethr" to Triple(Pro2Commands.gateThresholdAddress(), ParamKind.GATE_THRESHOLD, channel),
                "/h_${sid}_${channel}_gaterange" to Triple(Pro2Commands.gateRangeAddress(), ParamKind.GATE_RANGE, channel),
                "/h_${sid}_${channel}_gateatk" to Triple(Pro2Commands.gateAttackAddress(), ParamKind.GATE_ATTACK, channel),
                "/h_${sid}_${channel}_gatehold" to Triple(Pro2Commands.gateHoldAddress(), ParamKind.GATE_HOLD, channel),
                "/h_${sid}_${channel}_gaterel" to Triple(Pro2Commands.gateReleaseAddress(), ParamKind.GATE_RELEASE, channel),
                "/h_${sid}_${channel}_gatetrans" to Triple(Pro2Commands.gateTransientAddress(), ParamKind.GATE_TRANSIENT, channel),
                "/h_${sid}_${channel}_gatefreq" to Triple(Pro2Commands.gateFilterFreqAddress(), ParamKind.GATE_FILTER_FREQ, channel),
                "/h_${sid}_${channel}_gateflt" to Triple(Pro2Commands.gateFiltersInAddress(), ParamKind.GATE_FILTERS_IN, channel),
            )
            withContext(Dispatchers.Main) {
                for ((handle, info) in subs) subscriptions[handle] = Subscription(info.third, info.second)
            }
            for ((handle, info) in subs) {
                val (path, kind, ch) = info
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, ch, ch, token))
                } catch (e: Exception) {
                    // не критично
                }
                delay(2)
            }
        }
    }

    internal fun MainActivity.sendRawAsync(packet: ByteArray) {
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Send error: ${e.message}" }
            }
        }
    }

    internal fun MainActivity.sendRaw(socket: DatagramSocket, address: InetAddress, port: Int, packet: ByteArray) {
        socket.send(DatagramPacket(packet, packet.size, address, port))
    }


/**
 * Аккуратная массовая отписка при выходе.
 *
 * ПОЧЕМУ ЭТО ВАЖНО ДЛЯ ДРУГИХ КЛИЕНТОВ ПУЛЬТА.
 * Пока подписка жива, пульт продолжает слать по ней значения — даже если
 * наше приложение уже закрыто. Пульт обслуживает нескольких клиентов
 * одновременно (например, официальный Mixtender на другом планшете), и
 * брошенные подписки расходуют его ресурсы впустую до истечения таймаута.
 *
 * Здесь исправлены две проблемы прежнего кода:
 *  1) цикл прерывался (break) на ПЕРВОЙ же ошибке отправки — одна
 *     случайная осечка оставляла все оставшиеся сотни подписок живыми;
 *     теперь одиночные ошибки пропускаются, и выход происходит только
 *     если подряд не проходит много пакетов (значит, сокет действительно
 *     мёртв и продолжать бессмысленно);
 *  2) отписки уходили сплошным потоком без пауз, хотя подписки мы
 *     специально шлём с интервалом 2 мс, чтобы не заваливать пульт —
 *     теперь темп одинаковый в обе стороны.
 */
internal suspend fun unsubscribeHandles(
    socket: DatagramSocket,
    address: InetAddress,
    port: Int,
    handles: Collection<String>
) {
    var consecutiveFailures = 0
    for (handle in handles) {
        try {
            val packet = Pro2Commands.unsubscribe(handle)
            socket.send(DatagramPacket(packet, packet.size, address, port))
            consecutiveFailures = 0
        } catch (e: Exception) {
            consecutiveFailures++
            if (consecutiveFailures >= 10) return
        }
        delay(2)
    }
}
