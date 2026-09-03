package com.example.midasfadercontrol

import kotlinx.coroutines.Job
import java.net.DatagramSocket
import java.net.InetAddress

// Вынесено из MainActivity.kt для читаемости - чисто организационная
// правка, поведение не менялось.

/**
 * Хранит сокет, подписки и последние значения каналов ВНЕ жизненного цикла
 * Activity. ВАЖНО: при повороте экрана Android по умолчанию полностью
 * уничтожает и пересоздаёт Activity - без этого объекта UDP-сокет и все
 * подписки на пульт закрывались бы при каждом повороте, и приходилось бы
 * подключаться заново. Simple top-level object переживает столько, сколько
 * жив процесс приложения, независимо от Activity.
 */
object ConnectionHolder {
    var socket: DatagramSocket? = null
    var receiveJob: Job? = null
    var pollJob: Job? = null

    /** Корутина, разбирающая очередь входящих сообщений на главном потоке. */
    var uiJob: Job? = null

    /**
     * Очередь входящих сообщений от пульта.
     *
     * Ограничена по длине с выбрасыванием САМЫХ СТАРЫХ при переполнении.
     * Это осознанный выбор: значения от пульта - это состояние (уровень
     * фейдера, показание метра), а не события. Если интерфейс не успел
     * отрисовать промежуточное значение, показывать его потом уже незачем -
     * важно последнее. Без ограничения очередь росла бесконечно, и
     * приложение продолжало разгребать устаревшие данные, отставая от
     * реальности всё сильнее.
     */
    val incomingQueue = kotlinx.coroutines.channels.Channel<List<OscElement.Message>>(
        capacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /**
     * Момент (SystemClock.elapsedRealtime), когда от пульта последний раз
     * пришёл хоть один пакет.
     *
     * Нужен для честного индикатора связи. UDP - протокол без установления
     * соединения: DatagramSocket создаётся успешно, даже если пульта нет в
     * сети вообще. Раньше индикатор зеленел просто по факту создания
     * сокета и оставался зелёным всегда - выдернутый кабель, выключенный
     * пульт и смена сети выглядели как "Connected". Единственный
     * достоверный признак живой связи - что пульт РЕАЛЬНО отвечает.
     */
    @Volatile
    var lastPacketAtMs: Long = 0L
    var consoleAddress: InetAddress? = null
    var consolePort: Int = 10000
    var sessionToken: Int? = null
    var subscribedAlready: Boolean = false

    /**
     * Атомарный "замок" на первичную подписку.
     *
     * ИСПРАВЛЕНИЕ ГОНКИ: subscribeAll() вызывался из трёх мест, и два из
     * них выполняются в РАЗНЫХ потоках:
     *   1) сразу после подключения (корутина на Dispatchers.IO),
     *   2) при получении первого пакета с токеном (поток приёма),
     *   3) при входе в режим после смены роли.
     * Место 2 проверяло `if (!subscribedAlready)`, но проверка и
     * присваивание не были атомарными: если пульт присылал токен раньше,
     * чем место 1 успевало выставить флаг, subscribeAll() выполнялся
     * ДВАЖДЫ. В захвате это видно как ~1000 двойных подписок с разбросом
     * 60-135 мс - все базовые подписки 56 каналов, 16 aux-шин и 8
     * aux-возвратов уходили на пульт по два раза.
     *
     * compareAndSet гарантирует, что subscribeAll() выполнит ровно один
     * вызывающий, кто бы ни пришёл первым.
     */
    private val subscribeGate = java.util.concurrent.atomic.AtomicBoolean(false)

    /** true - вызывающий "выиграл" право выполнить subscribeAll(). */
    fun claimSubscribeAll(): Boolean {
        val first = subscribeGate.compareAndSet(false, true)
        if (first) subscribedAlready = true
        return first
    }

    /** Снять замок - только там, где сессия начинается заново. */
    fun releaseSubscribeGate() {
        subscribeGate.set(false)
        subscribedAlready = false
    }
    // Текущий выбранный банк каналов и режим (КАНАЛЫ/AUX/MASTER) - хранится
    // здесь, а не в Activity, чтобы поворот экрана не сбрасывал выбор
    // обратно на банк 1-8 / режим "каналы".
    var uiBankStart: Int = 0
    var uiStripMode: StripMode = StripMode.CHANNELS
    // ВАЖНО (исправление сброса при повороте): раньше "какой канал открыт
    // в детальном экране" и "какая там вкладка" хранились ПРЯМО в
    // MainActivity как обычные поля - а при повороте экрана Android
    // ПОЛНОСТЬЮ пересоздаёт активность (подтверждено логом - разные хеши
    // окна ДО и ПОСЛЕ поворота), и такие поля сбрасываются в null/по
    // умолчанию. Теперь - как и uiBankStart/uiStripMode - хранятся здесь,
    // в ConnectionHolder, который переживает пересоздание активности.
    var openDetailChannel: Int? = null
    var openDetailTabName: String = "INPUT"
    // Режим приложения (инженер/монитор) и выбранная шина монитора -
    // тоже переживают поворот экрана.
    var uiAppMode: String = "engineer"
    var uiMonitorSelectedBus: Int = -1
    // Уникальный для каждого подключения суффикс хендлов - чтобы старые
    // подписки от прошлых сессий (пульт, похоже, не всегда их сам заменяет,
    // а копит) не могли смешаться с текущими и вызывать хаотичные скачки
    // значений на одноимённых хендлах.
    var sessionId: String = ""
    val subscriptions = mutableMapOf<String, Subscription>()
    // Отдельная карта для мастер-каналов - индексы 0..2 у мастеров и
    // обычных каналов пересекаются, поэтому держим их раздельно, чтобы
    // не перепутать при разборе входящих push-обновлений.
    val masterSubscriptions = mutableMapOf<String, Subscription>()
    // Какие каналы уже подписаны на свои 16 aux-посылов (лениво, по
    // требованию - см. subscribeAuxSends).
    val auxSendsSubscribed = mutableSetOf<Int>()
    // Какие каналы уже подписаны на свой EQ (лениво, по требованию).
    val eqSubscribed = mutableSetOf<Int>()
    val gateSubscribed = mutableSetOf<Int>()
    val inputExtrasSubscribed = mutableSetOf<Int>()
    val compGateExtrasSubscribed = mutableSetOf<Int>()
    val mainOutExtrasSubscribed = mutableSetOf<Int>()
    val auxBusExtrasSubscribed = mutableSetOf<Int>()
    val channelData = Array(56) { ChannelData() }
    // По мануалу у Pro2 3 мастер-канала.
    val masterData = Array(3) { MasterData() }
    val auxReturnData = Array(8) { AuxReturnData() }
    val auxBusData = Array(16) { AuxBusData() }
    val vcaData = Array(8) { VcaData() }
    // ПРЕДПОЛОЖЕНИЕ (не подтверждено): 8 мьют-групп, по аналогии с 8
    // VCA-группами - это распространённое соглашение в линейке Midas Pro,
    // но само число НЕ встретилось нигде в датасете и не подтверждено на
    // этом пульте. Если физических кнопок MUTE GROUP на поверхности
    // больше или меньше 8 - поменять только эту константу.
    // ИСПРАВЛЕНО с 8 на 6 по официальному мануалу PRO2 (стр. 147):
    // "You can have up to six auto-mute groups, each one being enabled by
    // its own MUTE button in the population and mute groups section".
    // Раньше стояло 8 по аналогии с VCA - это было предположение, и оно
    // оказалось неверным. Из-за него приложение подписывалось на две
    // несуществующие группы и рисовало две лишние кнопки.
    const val MUTE_GROUP_COUNT = 6
    val muteGroupData = Array(MUTE_GROUP_COUNT) { MuteGroupData() }
    val muteGroupMemberSubscriptions = mutableMapOf<String, MuteGroupMemberSub>()
    val mainOutData = Array(8) { MainOutData() }
    // Отдельная карта для 16 aux-шин.
    val auxBusSubscriptions = mutableMapOf<String, Subscription>()
    // Отдельная карта для VCA-групп (8 шт., подтверждено реальным захватом).
    val vcaSubscriptions = mutableMapOf<String, Subscription>()
    // Базовое состояние самих мьют-групп (mute-кнопка + имя) - ОТДЕЛЬНО от
    // muteGroupMemberSubscriptions выше (та карта - только про членство).
    val muteGroupSubscriptions = mutableMapOf<String, Subscription>()

    /**
     * Принадлежность КАЖДОГО входного канала к каждой мьют-группе.
     * [канал][группа]. Нужно для строки кнопок MG 1-8 в детальном экране
     * канала - так это сделано в X32 Mix / Wing: назначение задаётся со
     * стороны канала, рядом с назначением в DCA, а не через отдельный
     * экран со списком всех каналов.
     */
    val channelMuteGroups = Array(56) { BooleanArray(MUTE_GROUP_COUNT) }

    /** handle -> (канал, группа) для строки MG в детальном экране канала. */
    val channelMuteGroupSubscriptions = mutableMapOf<String, Pair<Int, Int>>()

    /** Каналы, для которых строка MG уже подписана (ленивая подписка). */
    val channelMuteGroupsSubscribed = mutableSetOf<Int>()
    val mainOutSubscriptions = mutableMapOf<String, Subscription>()
    // Отдельная карта для aux returns - индексы 0..7 пересекаются и с
    // обычными каналами, и с VCA в будущем.
    val auxSubscriptions = mutableMapOf<String, Subscription>()
    // Членство "детей" в VCA-группах - активна, только пока открыт
    // соответствующий экран (см. openVcaMembers/subscribeVcaMembers).
    val vcaMemberSubscriptions = mutableMapOf<String, VcaMemberSub>()
    // Исходный (до нашего вмешательства) источник патчинга по каналам -
    // чтобы всегда можно было вернуть ровно то, что было. Заполняется
    // ОДИН раз на канал, первым же значением, пришедшим с пульта.
    val patchBaseline = mutableMapOf<Int, Int>()

    /**
     * Хэндлы, на которые пульт ответил blob-ом нулевой длины ("такого
     * параметра тут нет"). Нужен только чтобы не засорять лог повтором -
     * пульт шлёт такие ответы по 30 раз в секунду.
     */
    val unsupportedHandles = mutableSetOf<String>()

    /**
     * Последнее значение по каждому хэндлу подписки - для отсева
     * повторов (см. handleIncomingMessage). Пульт шлёт значения
     * постоянно, даже неизменившиеся.
     */
    val lastValueByHandle = HashMap<String, Int>()

    /**
     * Единая точка очистки ВСЕГО состояния подписок.
     *
     * ИСПРАВЛЕНИЕ БАГА: раньше очистка была размазана по трём местам
     * (reset() здесь, блок переподключения в MainActivityNetwork,
     * onDestroy в MainActivity), и каждое место чистило СВОЙ набор карт.
     * В итоге mainOutExtrasSubscribed и auxBusExtrasSubscribed не
     * очищались НИГДЕ, а vcaMemberSubscriptions - не очищалась при
     * переподключении. Последствие: после переподключения или смены
     * режима эти "сторожевые" множества оставались заполненными,
     * subscribeMainOutExtras/subscribeAuxBusExtras сразу выходили по
     * `if (...Subscribed.contains(index)) return`, и детальные экраны
     * MATRIX и AUX больше никогда не переподписывались - показывали
     * старые значения и не обновлялись до перезапуска приложения.
     *
     * Теперь любое место, где сессия начинается заново, вызывает этот
     * метод - добавить новую карту и забыть её очистить больше нельзя.
     */
    fun clearAllSubscriptionState() {
        subscriptions.clear()
        masterSubscriptions.clear()
        auxSubscriptions.clear()
        auxBusSubscriptions.clear()
        vcaSubscriptions.clear()
        muteGroupSubscriptions.clear()
        channelMuteGroupSubscriptions.clear()
        channelMuteGroupsSubscribed.clear()
        mainOutSubscriptions.clear()
        vcaMemberSubscriptions.clear()
        auxSendsSubscribed.clear()
        eqSubscribed.clear()
        gateSubscribed.clear()
        inputExtrasSubscribed.clear()
        compGateExtrasSubscribed.clear()
        mainOutExtrasSubscribed.clear()
        auxBusExtrasSubscribed.clear()
        unsupportedHandles.clear()
        lastValueByHandle.clear()
        muteGroupMemberSubscriptions.clear()
    }

    fun reset() {
        socket = null
        receiveJob = null
        pollJob = null
        uiJob = null
        consoleAddress = null
        sessionToken = null
        releaseSubscribeGate()
        lastPacketAtMs = 0L
        clearAllSubscriptionState()
        patchBaseline.clear()
        unsupportedHandles.clear()
        lastValueByHandle.clear()
    }
}
