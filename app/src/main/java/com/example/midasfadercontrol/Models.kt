package com.example.midasfadercontrol

// Вынесено из MainActivity.kt для читаемости (было 4712 строк в одном
// файле) - чисто организационная правка, поведение не менялось.
// Перечисления и модели данных, используемые по всему приложению.

// === Живая подписка на пульт (см. Pro2Commands.batchSubscribe) ===
enum class ParamKind { FADER, MUTE, SOLO, SOLO_B, LINK, GAIN, NAME, COLOUR, METER, COMP_RATIO, COMP_ATTACK, COMP_RELEASE, COMP_THRESHOLD, COMP_MAKEUP, COMP_IN, COMP_FILTERS_IN, COMP_FILTER_FREQ, COMP_GR_METER, COMP_DET_METER, AUX_SEND, AUX_SEND_ENABLE, AUX_SEND_PREFADE, EQ_IN, EQ_BAND_ACTIVE, EQ_FREQ, EQ_GAIN, EQ_WIDTH, EQ_SHAPE_BASS, EQ_SHAPE_TREBLE, PAN, PHANTOM, PHASE, GAIN_TRIM, HP_FILTER_IN, HP_FILTER_FREQ, LP_FILTER_IN, LP_FILTER_FREQ, INPUT_DELAY, GATE_IN, GATE_THRESHOLD, GATE_RANGE, GATE_ATTACK, GATE_HOLD, GATE_RELEASE, GATE_TRANSIENT, GATE_FILTER_FREQ, GATE_FILTERS_IN, GATE_GR_METER, GATE_DET_METER, COMP_MODE, GATE_MODE, COMP_PRESENCE, BUS_TRIM, COMP_STYLE, COMP_FILTER_BANDWIDTH, COMP_KNEE, CHANNEL_SOURCE }
// Порядок вкладок: КАНАЛЫ, AUX RETURNS, AUX ШИНЫ, MASTER - мастер намеренно
// в конце (по просьбе - обычно с ним работают реже всего).
enum class StripMode { CHANNELS, AUX_RETURNS, AUX_BUS, VCA, MASTER, MAIN_OUTS }
data class Subscription(val channel: Int, val kind: ParamKind, val auxBus: Int = 0, val eqBand: Int = 0)

/** Последние известные значения одного канала - переживают поворот экрана (см. ConnectionHolder). */
data class ChannelData(
    var fader: Float = 0f,
    var mutedLocal: Boolean = false,
    var soloed: Boolean = false,
    // Патчинг - сырое число с пульта (enChannelSource), пока без расшифровки
    // кодировки. -1 = ещё не пришло значение.
    var channelSource: Int = -1,
    var gain: Float = 0f,
    var name: String = "",
    var colourArgb: Int? = null,
    var compRatio: Float = 0f,
    var compAttack: Float = 0f,
    var compRelease: Float = 0f,
    var compThreshold: Float = 0f,
    var compMakeup: Float = 0f,
    var compInLocal: Boolean = false,
    var compFiltersInLocal: Boolean = false,
    var compFilterFreq: Float = 0f,
    // Посылы на 16 aux-шин (индекс 0 = aux 1, ... индекс 15 = aux 16).
    // НЕ подтверждено реальным захватом - см. заметку в Pro2Commands.
    val auxSends: FloatArray = FloatArray(16),
    // EQ (4 полосы: 0=bass, 1=low-mid, 2=mid-high, 3=treble) - подтверждено
    // описаниями в списке команд, но НЕ реальным захватом.
    var eqInLocal: Boolean = false,
    val eqBandActiveLocal: BooleanArray = BooleanArray(4),
    val eqFreq: FloatArray = FloatArray(4),
    val eqGain: FloatArray = FloatArray(4),
    val eqWidth: FloatArray = FloatArray(4),
    // Вход (INPUT) - pan/phantom ПОДТВЕРЖДЕНЫ реальным захватом, phase - по
    // описанию в списке команд.
    var pan: Float = 0.5f,
    // Основной GAIN (enInputGain) хранится в поле gain выше. Это - GAIN
    // TRIM (enMicSplitStepGain) - раньше мы по ошибке называли gain TRIM'ом,
    // теперь они разделены правильно.
    var gainTrim: Float = 0f,
    var phantomLocal: Boolean = false,
    var phaseLocal: Boolean = false,
    // Gate - ПОЛНОСТЬЮ ПОДТВЕРЖДЕНО реальным захватом трафика iPad.
    var gateInLocal: Boolean = false,
    var gateThreshold: Float = 0f,
    var gateRange: Float = 0f,
    var gateAttack: Float = 0f,
    var gateHold: Float = 0f,
    var gateRelease: Float = 0f,
    var gateTransient: Float = 0f,
    var gateFilterFreq: Float = 0f,
    var gateFiltersInLocal: Boolean = false,
    var compGrMeter: Float = 0f,
    var gateGrMeter: Float = 0f,
    var compDetMeter: Float = 0f,
    var gateDetMeter: Float = 0f,
    // Режим компрессора/gate - ПОДТВЕРЖДЕНО реальным захватом, только
    // чтение (не знаем, как отправлять SET - см. заметку в Pro2Commands.kt).
    var compMode: Int = -1,
    var gateMode: Int = -1,
    var soloBLocal: Boolean = false,
    var linkedLocal: Boolean = false,
    // Форма BASS/TREBLE - 4 режима по кругу (0=PARAMETRIC/bell,
    // 1=BRIGHT, 2=CLASSIC, 3=SOFT - три разных варианта shelf).
    // Раньше считали простым bell/shelf-булевым, оказалось не так -
    // подтверждено пользователем вручную на реальном пульте.
    var eqBassShapeMode: Int = 0,
    var eqTrebleShapeMode: Int = 0,
    // HP/LP фильтры и задержка входа - ПОДТВЕРЖДЕНО реальным захватом.
    var hpFilterInLocal: Boolean = false,
    var hpFilterFreq: Float = 0f,
    var lpFilterInLocal: Boolean = false,
    var lpFilterFreq: Float = 0f,
    var inputDelay: Float = 0f,
    // Отдельно от уровня посыла - вкл/выкл и pre/post для каждой из 16 шин.
    val auxSendEnable: BooleanArray = BooleanArray(16) { true },
    val auxSendPreFade: BooleanArray = BooleanArray(16)
)

/** Состояние одного мастер-канала - НЕ подтверждено реальным захватом. */
data class MasterData(
    var fader: Float = 0f,
    var mutedLocal: Boolean = false,
    var name: String = "",
    var soloBLocal: Boolean = false
)

/** Состояние одного aux return - НЕ подтверждено реальным захватом. */
data class AuxReturnData(
    var fader: Float = 0f,
    var mutedLocal: Boolean = false,
    var name: String = "",
    var colourArgb: Int? = null,
    var soloBLocal: Boolean = false
)

/** Состояние одной aux-шины (собственный уровень шины, не посыл с канала). */
/**
 * Общая "форма" EQ (6 полос) + компрессора + фильтров - реализуют и
 * AuxBusData, и MainOutData, чтобы экран деталей строился ОДНОЙ общей
 * функцией (buildGroupCompBlock/buildGroupEqBlock), а не дублировался.
 */
interface EqCompHolder {
    var compRatio: Float
    var compAttack: Float
    var compRelease: Float
    var compThreshold: Float
    var compRange: Float
    var compMakeup: Float
    var compSoftClip: Float
    val eqFreq: FloatArray
    val eqGain: FloatArray
    val eqWidth: FloatArray
    var hpFreq: Float
    var lpFreq: Float
    var lowNotchFreq: Float
    var highNotchFreq: Float
}

data class AuxBusData(
    var fader: Float = 0f,
    var mutedLocal: Boolean = false,
    var name: String = "",
    var colourArgb: Int? = null,
    var soloBLocal: Boolean = false,
    // LINK (стерео-пара) - ПОДТВЕРЖДЕНО реальным захватом (enConfigPairingState).
    var linked: Boolean = false,
    // Outs (см. заметку у MainOutData) - по датасету muffeeee у aux-шин
    // ("enVirtualSubMixes") есть тот же набор. НЕ подтверждено реальным
    // захватом.
    override val eqFreq: FloatArray = FloatArray(6),
    override val eqGain: FloatArray = FloatArray(6),
    override val eqWidth: FloatArray = FloatArray(6),
    override var hpFreq: Float = 0f,
    override var lpFreq: Float = 0f,
    override var lowNotchFreq: Float = 0f,
    override var highNotchFreq: Float = 0f,
    override var compRatio: Float = 0f,
    override var compAttack: Float = 0f,
    override var compRelease: Float = 0f,
    override var compThreshold: Float = 0f,
    override var compRange: Float = 0f,
    override var compMakeup: Float = 0f,
    override var compSoftClip: Float = 0f
) : EqCompHolder

data class VcaData(
    var fader: Float = 0f,
    var mutedLocal: Boolean = false,
    var name: String = "",
    var colourArgb: Int? = null,
    // Членство в группе по типам "детей" - читается через subscribeVcaMembers,
    // НЕ подтверждено отдельным захватом трафика (Mixtender не попал в
    // захват), но построено по той же структуре аргументов, что и
    // подтверждённая пользователем на реальном пульте команда SET
    // (setVcaChildInput и т.п.): путь адресует конкретного "ребёнка",
    // а аргументом идёт номер САМОЙ VCA-группы.
    val memberInput: BooleanArray = BooleanArray(56),
    val memberSubMix: BooleanArray = BooleanArray(16),
    val memberAuxReturn: BooleanArray = BooleanArray(8),
    val memberMain: BooleanArray = BooleanArray(8),
    val memberMaster: BooleanArray = BooleanArray(3)
)

/** Подписка на членство одного "ребёнка" (канал/шина/aux return/main/master) в конкретной VCA-группе. */
data class VcaMemberSub(val childType: String, val childIndex: Int, val vcaIndex: Int)

data class MainOutData(
    var fader: Float = 0f,
    var mutedLocal: Boolean = false,
    var name: String = "",
    // LINK (стерео-пара) + новые параметры компрессора - все ПОДТВЕРЖДЕНЫ
    // реальным захватом трафика (all config ipad.pcapng) как существующие
    // и с правильным типом сообщения, но их фактическое поведение
    // (значения cycle-режимов, эффект LINK) не проверялось.
    var linked: Boolean = false,
    var compPresence: Float = 0f,
    var busTrim: Float = 0f,
    var compStyle: Int = 0,
    var compFilterBandwidth: Int = 0,
    var compKnee: Int = 0,
    var colourArgb: Int? = null,
    // EQ - 6 полос (не 4, как у канала!) + HP/LP/notch фильтры.
    // НЕ подтверждено реальным захватом - взято из датасета
    // muffeeee/midas-pro-series-osc-commands (enVirtualMainOuts), где эти
    // параметры описаны текстом ("Set matrix out EQ frequency for band N"
    // и т.п.), но не проверялись живьём.
    override val eqFreq: FloatArray = FloatArray(6),
    override val eqGain: FloatArray = FloatArray(6),
    override val eqWidth: FloatArray = FloatArray(6),
    override var hpFreq: Float = 0f,
    override var lpFreq: Float = 0f,
    override var lowNotchFreq: Float = 0f,
    override var highNotchFreq: Float = 0f,
    // Компрессор - тот же набор, что и у канала, плюс range и soft clip.
    override var compRatio: Float = 0f,
    override var compAttack: Float = 0f,
    override var compRelease: Float = 0f,
    override var compThreshold: Float = 0f,
    override var compRange: Float = 0f,
    override var compMakeup: Float = 0f,
    override var compSoftClip: Float = 0f,
    var outputDelay: Float = 0f
) : EqCompHolder

