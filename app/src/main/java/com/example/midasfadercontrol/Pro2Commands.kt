package com.example.midasfadercontrol

/**
 * Known Pro2/PPC command addresses for a single input channel
 * (group "enVirtualMicInputs"). Channel index is always 0-based
 * (index 0 = physical channel 1 on the console — confirmed by the
 * off-by-one bug found and fixed during testing).
 *
 * Confidence level per command:
 *   FADER — confirmed against real captured traffic AND the
 *           muffeeee/midas-pro-series-osc-commands address list.
 *   MUTE  — address/type confirmed from the address list. Behaviour is a
 *           TOGGLE on the console (any received packet flips current state),
 *           NOT an explicit set — confirmed by real-world testing (sending
 *           the command 3x "for reliability" caused intermittent double-press
 *           symptoms, exactly as expected from repeated toggles racing UDP
 *           packet loss). Send exactly one packet per intended toggle.
 *   SOLO  — address/type taken from the address list, same pattern as
 *           fader/mute. Confirmed working correctly in real-world use
 *           (behaves as an explicit "set", not a toggle - unlike MUTE).
 *   GAIN  — CORRECTED after capturing real Mixtender 2 traffic: the address
 *           actually used by the real app for the TRIM knob is
 *           enMicSplitStepGain, not enInputGain (see comment on gainAddress()
 *           below). Confirmed by real-world testing.
 *   NAME  — address/type confirmed by capturing real Mixtender 2 traffic
 *           while renaming a channel: typetag ",is" (int32 channel index +
 *           string), matches what was already implemented here.
 *   COLOUR — NOT in the muffeeee address list at all (checked all 33 groups,
 *           ~3900 parameters - zero mentions of colour). Found only by
 *           capturing real Mixtender 2 traffic while changing a channel's
 *           colour: address enPPCIntegerMessage/.../enChannelColour,
 *           typetag ",ii" (int32 channel index + int32 ARGB colour, same
 *           packed format as Android's own Color.argb()/0xAARRGGBB).
 *   COMPRESSOR — fully confirmed by capturing real Mixtender 2 traffic
 *           while adjusting every compressor control on a channel (ratio,
 *           attack, release, threshold, makeup gain, in/out). All five
 *           continuous params are enPPCRotaryMessage (float 0..1, same
 *           pattern as fader/gain). enCompLimIn is enPPCSwitchMessage -
 *           treated the same way as MUTE (single-packet toggle, state
 *           driven by push, not local assumption), since we have no
 *           independent confirmation of whether it's SET or TOGGLE and
 *           this design works correctly either way.
 *   MASTER (фейдер/mute) — НЕ подтверждено реальным захватом, только по
 *           списку команд (группа enVirtualMasters). Формат и типы
 *           (enPPCFaderMessage/enFaderLevel, enPPCSwitchMessage/enMuteStatus)
 *           буквально идентичны уже подтверждённым для входных каналов -
 *           высокая вероятность, что заработает как есть, но стоит
 *           проверить на реальном пульте перед тем, как полностью доверять.
 *           По мануалу у Pro2 3 мастер-канала (индексы предположительно 0..2).
 *   AUX SEND (посыл с канала на aux-шину) — НЕ подтверждено реальным
 *           захватом. В списке команд посылы на 16 aux-шин называются
 *           "SubSend", а не "AuxSend" (enSubSendLevel1..16, отдельный
 *           параметр на каждую шину, а не один параметр с индексом шины).
 *           Тип enPPCRotaryMessage, float 0..1 - тот же паттерн, что gain.
 */
object Pro2Commands {
    private const val GROUP = "enVirtualMicInputs"

    fun faderAddress() = "/enPPCFaderMessage/$GROUP/enFaderLevel"
    fun muteAddress() = "/enPPCSwitchMessage/$GROUP/enMuteStatus"
    fun soloAddress() = "/enPPCSwitchMessage/$GROUP/enFaderSolo"
    // Вторая шина solo - ПОДТВЕРЖДЕНО реальным трафиком (есть даже у
    // обычных каналов, не только у мастера/aux/шин).
    fun soloBAddress() = "/enPPCSwitchMessage/$GROUP/enFaderSoloB"
    // Стерео-пара (link) - было enRoutingLinked, проверено на реальном
    // пульте, не работало (тупик, обсуждалось отдельно). По датасету
    // muffeeee/midas-pro-series-osc-commands enRoutingLinked - это лишь ОДИН
    // из полутора десятков флагов настройки УЖЕ слинкованной пары (что
    // именно линковать - fader/mute/EQ/routing и т.д.), а не сам переключатель
    // "сделать пару". Пробуем enChannelLinked - по структуре похож на
    // главный тумблер, но сам пока не задокументирован сообществом
    // ("unknown") - нужна проверка на реальном пульте.
    fun linkAddress() = "/enPPCSwitchMessage/$GROUP/enChannelLinked"

    // === ПАТЧИНГ ВХОДА (enChannelSource) - кодировка РАСШИФРОВАНА, уровень A.
    //
    // Значение - 32-битное беззнаковое:
    //     uSource = Type            (биты 0-7,  младший байт)
    //             | path    << 8    (биты 8-19, 12 бит)
    //             | Channel << 20   (биты 20-27, 8 бит)
    // Отдельный флаг 0x80000000 = Silence Bit (источник не назначен).
    //
    // Подтверждено четырьмя независимыми путями: разбор Mac Offline Editor,
    // SceneData::ConvertDSPSource, CSystemInputSource::Assign в реальной
    // прошивке, и живой сетевой трафик с Pro2 (12 точек по DL251, обе
    // границы; 3 точки по локальным входам).
    //
    // ВАЖНО про запись: формат записи отдельно не наблюдался в захватах,
    // НО enChannelColour - параметр того же типа (enPPCIntegerMessage), в
    // той же группе, и его запись тегами ",ii" (индекс канала + int32
    // значение) давно работает на реальном пульте. Поэтому пишем так же.
    fun channelSourceAddress() = "/enPPCIntegerMessage/$GROUP/enChannelSource"

    /** Источник не назначен (распатчено). */
    const val SOURCE_SILENCE: Int = -2147483648 // 0x80000000

    /** DL251 (Type 42), сквозной физический канал 1..48, path всегда 0. */
    fun dl251Source(physicalChannel: Int): Int =
        42 or ((physicalChannel - 1) shl 20)

    /** Локальный вход поверхности (Type 14 LineIOCPU, path 17), вход 1..8. */
    fun localInputSource(physicalInput: Int): Int =
        0x110e or ((physicalInput - 1) shl 20)

    /**
     * Человекочитаемая расшифровка uSource. Возвращает null, если значение
     * не соответствует ни одной ПОДТВЕРЖДЁННОЙ трафиком форме - в этом
     * случае наверху показываем сырое число, а не выдумываем подпись.
     */
    fun describeSource(uSource: Int): String? {
        if (uSource == SOURCE_SILENCE) return "не назначен"
        if (uSource < 0) return null
        val type = uSource and 0xFF
        val path = (uSource shr 8) and 0xFFF
        val channel = (uSource shr 20) and 0xFF
        return when {
            type == 42 && path == 0 && channel <= 47 -> "DL251 ch ${channel + 1}"
            type == 14 && path == 17 && channel <= 7 -> "Локальный вход ${channel + 1}"
            else -> null
        }
    }

    /**
     * ЗАПИСЬ патчинга. Вызывать только осознанно - меняет реальную
     * маршрутизацию звука на пульте.
     */
    fun setChannelSource(channelIndex: Int, uSource: Int): ByteArray =
        OscUtil.encode(channelSourceAddress(), listOf(channelIndex, uSource))

    // === ДВА ГЕЙНА НА КАНАЛЕ: enInputGain и enMicSplitStepGain.
    //
    // РАЗРЕШЕНО ОФИЦИАЛЬНЫМ МАНУАЛОМ PRO2 - привязка ниже верная:
    //   * enMicSplitStepGain = "stage box control knob ... adjusts the
    //     input gain of the remote amplifier in 5dB steps, ranging from
    //     -5dB to +40dB" (стр. 251). Это аналоговый гейн преампа на
    //     стейджбоксе - то есть настоящий GAIN. Само имя параметра
    //     ("StepGain") сходится с шаговой регулировкой по 5 дБ.
    //   * enInputGain = "console digital trim (gives +20dB to -40dB
    //     continuous trim)" (стр. 70). Это цифровой ТРИМ.
    //
    // Тем самым снято противоречие, о котором я предупреждал: более
    // раннее наблюдение по трафику Mixtender ("TRIM шлёт
    // enMicSplitStepGain") было ошибочным. На пульте обе ручки физически
    // подписаны "gain trim" и переключаются кнопкой SWAP - вероятно,
    // поэтому при захвате их и перепутали.
    //
    // ⚠ Диапазоны РАЗНЫЕ и пока не откалиброваны: GAIN шагами по 5 дБ
    // (-5..+40), TRIM непрерывный (-40..+20). Сейчас обе ручки
    // показывают сырые 0.00-1.00.
    fun gainAddress() = "/enPPCRotaryMessage/$GROUP/enMicSplitStepGain"
    fun gainTrimAddress() = "/enPPCRotaryMessage/$GROUP/enInputGain"
    fun nameAddress() = "/enPPCStringMessage/$GROUP/enPathname"
    fun colourAddress() = "/enPPCIntegerMessage/$GROUP/enChannelColour"
    // Подтверждено реальным трафиком (метры в самом начале проекта): только чтение,
    // push-значение приходит как 1 байт (0..~127-130 в наблюдаемых данных).
    fun meterAddress() = "/enPPCMeterMessage/$GROUP/enMeter"

    // === Вход (INPUT) - панорама и фантомное питание ПОДТВЕРЖДЕНЫ реальным
    // трафиком iPad. Переворот фазы (enInputPhaseIn) НЕ встретился в захвате
    // (видимо, iPad его в той сессии не трогал), но есть в списке команд с
    // понятным описанием "Toggle channel input phase" - высокая уверенность
    // по аналогии с остальными enPPCSwitchMessage-переключателями. ===
    fun panAddress() = "/enPPCRotaryMessage/$GROUP/enFaderPan"
    fun phantomPowerAddress() = "/enPPCSwitchMessage/$GROUP/enMicSplitPhantomPowerIn"
    fun phaseAddress() = "/enPPCSwitchMessage/$GROUP/enInputPhaseIn"

    // === HP/LP фильтры и задержка входа - ПОДТВЕРЖДЕНО реальным трафиком iPad. ===
    fun hpFilterInAddress() = "/enPPCSwitchMessage/$GROUP/enInputHighPassFltIn"

    // Крутизна фильтров - переключатель SLOPE на пульте. Мануал (стр. 314):
    // hi pass 12 или 24 дБ/окт, lo pass 6 или 12 дБ/окт. В датазете оба
    // описаны как "Cycle channel input ... filter slopes" - то есть
    // циклическая кнопка, шлём константу 1, пульт сам переключает.
    // Адреса ЧТЕНИЯ текущей крутизны (enPPCIntegerMessage) уже объявлены
    // ниже - hpFilterSlopeAddress/lpFilterSlopeAddress. Здесь - адреса
    // ПЕРЕКЛЮЧЕНИЯ, тот же параметр, но Switch-тип: тот же приём, что уже
    // используется для режимов компрессора и гейта.
    fun hpFilterSlopeCycleAddress() = "/enPPCSwitchMessage/$GROUP/enInputHighPassFltSlope"
    fun lpFilterSlopeCycleAddress() = "/enPPCSwitchMessage/$GROUP/enInputLowPassFltSlope"
    fun setHpFilterSlopeNext(channelIndex: Int): ByteArray =
        OscUtil.encode(hpFilterSlopeCycleAddress(), listOf(channelIndex, 1))
    fun setLpFilterSlopeNext(channelIndex: Int): ByteArray =
        OscUtil.encode(lpFilterSlopeCycleAddress(), listOf(channelIndex, 1))
    fun hpFilterFreqAddress() = "/enPPCRotaryMessage/$GROUP/enInputHighPassFltFrequency"
    // ПОКА НЕ ИСПОЛЬЗУЮТСЯ. Это адреса ЧТЕНИЯ текущей крутизны фильтров
    // (Integer-тип). Кнопки SLOPE уже есть и переключают крутизну, но
    // подписки на текущее значение ещё нет - поэтому кнопка не может
    // показать, какая крутизна выбрана. Оставлены намеренно: понадобятся,
    // как только добавим подписку. Не удалять как "мёртвый код".
    fun hpFilterSlopeAddress() = "/enPPCIntegerMessage/$GROUP/enInputHighPassFltSlope"
    fun lpFilterInAddress() = "/enPPCSwitchMessage/$GROUP/enInputLowPassFltIn"
    fun lpFilterFreqAddress() = "/enPPCRotaryMessage/$GROUP/enInputLowPassFltFrequency"
    fun lpFilterSlopeAddress() = "/enPPCIntegerMessage/$GROUP/enInputLowPassFltSlope"
    fun inputDelayAddress() = "/enPPCRotaryMessage/$GROUP/enInputDelay"

    // === Gate/Expander - ПОЛНОСТЬЮ ПОДТВЕРЖДЕНО реальным трафиком iPad
    // (большой захват "all config"). ===
    fun gateInAddress() = "/enPPCSwitchMessage/$GROUP/enExpGateIn"
    fun gateThresholdAddress() = "/enPPCRotaryMessage/$GROUP/enExpanderGateThreshold"
    fun gateRangeAddress() = "/enPPCRotaryMessage/$GROUP/enExpanderGateRange"
    fun gateAttackAddress() = "/enPPCRotaryMessage/$GROUP/enExpanderGateAttackTime"
    fun gateHoldAddress() = "/enPPCRotaryMessage/$GROUP/enExpanderGateHoldTime"
    fun gateReleaseAddress() = "/enPPCRotaryMessage/$GROUP/enExpanderGateReleaseTime"
    fun gateTransientAddress() = "/enPPCRotaryMessage/$GROUP/enExpanderGateTransient"
    fun gateFilterFreqAddress() = "/enPPCRotaryMessage/$GROUP/enExpanderGateFilterFrequency"
    fun gateFiltersInAddress() = "/enPPCSwitchMessage/$GROUP/enExpGateFiltersIn"
    // Метры снижения усиления (Gain Reduction) - ПОДТВЕРЖДЕНО реальным
    // трафиком iPad. Формат метра тот же, что и у обычного метра сигнала
    // (1 байт, 0..255) - см. заметку у meterAddress().
    fun compGrMeterAddress() = "/enPPCMeterMessage/$GROUP/enCompGRMeter"
    fun gateGrMeterAddress() = "/enPPCMeterMessage/$GROUP/enExpGRMeter"
    // Detector-метры - входной сигнал ДО обработки (в отличие от GR -
    // снижение усиления). ПОДТВЕРЖДЕНО реальным трафиком iPad.
    fun gateDetMeterAddress() = "/enPPCMeterMessage/$GROUP/enGateDetMeter"

    // === Компрессор/лимитер - ПОЛНОСТЬЮ ПОДТВЕРЖДЕНО реальным трафиком
    // (запись "33 fader compressor") ===
    fun compRatioAddress() = "/enPPCRotaryMessage/$GROUP/enComLimRatio"
    fun compAttackAddress() = "/enPPCRotaryMessage/$GROUP/enCompLimAttackTime"
    fun compReleaseAddress() = "/enPPCRotaryMessage/$GROUP/enCompLimReleaseTime"
    fun compThresholdAddress() = "/enPPCRotaryMessage/$GROUP/enCompLimThreshold"
    fun compMakeupGainAddress() = "/enPPCRotaryMessage/$GROUP/enDynamicsOverallMakeUpGain"
    // enPPCSwitchMessage, как и mute - поэтому обращаемся с ним так же (одна
    // отправка на нажатие, без повторов, состояние - из push, а не локально).
    fun compInAddress() = "/enPPCSwitchMessage/$GROUP/enCompLimIn"
    // Фильтр компрессора - ПОДТВЕРЖДЕНО тем же захватом.
    fun compFiltersInAddress() = "/enPPCSwitchMessage/$GROUP/enCompLimFiltersIn"
    fun compFilterFreqAddress() = "/enPPCRotaryMessage/$GROUP/enCompLimFilterFrequency"
    fun compDetMeterAddress() = "/enPPCMeterMessage/$GROUP/enCompDetMeter"
    // Режим компрессора (например "винтаж") - ПОДТВЕРЖДЕНО реальным
    // захватом (значения 1 и 3 замечены). НЕ ЗНАЕМ, как отправлять SET -
    // смена наблюдалась только когда её делали прямо на экране пульта,
    // поэтому только для чтения (read-only).
    fun compDetectorModeAddress() = "/enPPCIntegerMessage/$GROUP/enCompDetectorMode"
    // Цикл режима ("нажал - переключилось на следующий") - ОТДЕЛЬНЫЙ адрес
    // от чтения выше (тот подтверждён захватом как enPPCIntegerMessage,
    // этот - по датасету muffeeee, где enCompDetectorMode описан как
    // enPPCSwitchMessage с описанием "Cycles to next compressor mode" -
    // та же логика, что и compInAddress: сам факт пакета переключает).
    // НЕ подтверждено реальным захватом - нужна проверка на пульте.
    fun compDetectorModeCycleAddress() = "/enPPCSwitchMessage/$GROUP/enCompDetectorMode"
    // Режим gate (например "Gate" по умолчанию) - тот же случай, только чтение.
    fun gateModeAddress() = "/enPPCIntegerMessage/$GROUP/enGateMode"
    // Аналогично compDetectorModeCycleAddress - отдельный toggle-адрес для
    // переключения на следующий режим. НЕ подтверждено реальным захватом.
    fun gateModeCycleAddress() = "/enPPCSwitchMessage/$GROUP/enGateMode"

    fun setFader(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(faderAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setMute(channelIndex: Int, muted: Boolean): ByteArray =
        OscUtil.encode(muteAddress(), listOf(channelIndex, if (muted) 1 else 0))

    fun setSolo(channelIndex: Int, soloed: Boolean): ByteArray =
        OscUtil.encode(soloAddress(), listOf(channelIndex, if (soloed) 1 else 0))

    fun setSoloB(channelIndex: Int, soloed: Boolean): ByteArray =
        OscUtil.encode(soloBAddress(), listOf(channelIndex, if (soloed) 1 else 0))

    fun setLink(channelIndex: Int, linked: Boolean): ByteArray =
        OscUtil.encode(linkAddress(), listOf(channelIndex, if (linked) 1 else 0))

    fun setGain(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(gainAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setGainTrim(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(gainTrimAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    /** argbColor - standard Android ARGB packed int, e.g. from Color.argb(255, r, g, b). */
    fun setColour(channelIndex: Int, argbColor: Int): ByteArray =
        OscUtil.encode(colourAddress(), listOf(channelIndex, argbColor))

    fun setName(channelIndex: Int, name: String): ByteArray =
        OscUtil.encode(nameAddress(), listOf(channelIndex, name))

    fun setPan(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(panAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setPhantomPower(channelIndex: Int, on: Boolean): ByteArray =
        OscUtil.encode(phantomPowerAddress(), listOf(channelIndex, if (on) 1 else 0))

    fun setPhase(channelIndex: Int, inverted: Boolean): ByteArray =
        OscUtil.encode(phaseAddress(), listOf(channelIndex, if (inverted) 1 else 0))

    // Слоуп (крутизна) фильтров пока не делаем - это отдельный "циклический"
    // параметр (переключение по клику через список опций 6/12/18/24 дБ/окт),
    // а не непрерывное значение - нужен отдельный UI-паттерн. Частота+вкл/выкл
    // уже дают основную пользу.
    fun setHpFilterIn(channelIndex: Int, on: Boolean): ByteArray =
        OscUtil.encode(hpFilterInAddress(), listOf(channelIndex, if (on) 1 else 0))

    fun setHpFilterFreq(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(hpFilterFreqAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setLpFilterIn(channelIndex: Int, on: Boolean): ByteArray =
        OscUtil.encode(lpFilterInAddress(), listOf(channelIndex, if (on) 1 else 0))

    fun setLpFilterFreq(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(lpFilterFreqAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setInputDelay(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(inputDelayAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setGateIn(channelIndex: Int, on: Boolean): ByteArray =
        OscUtil.encode(gateInAddress(), listOf(channelIndex, if (on) 1 else 0))

    fun setGateThreshold(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(gateThresholdAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setGateRange(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(gateRangeAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setGateAttack(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(gateAttackAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setGateHold(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(gateHoldAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setGateRelease(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(gateReleaseAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setGateTransient(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(gateTransientAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setGateFilterFreq(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(gateFilterFreqAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setGateFiltersIn(channelIndex: Int, on: Boolean): ByteArray =
        OscUtil.encode(gateFiltersInAddress(), listOf(channelIndex, if (on) 1 else 0))

    fun setCompRatio(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(compRatioAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setCompAttack(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(compAttackAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setCompRelease(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(compReleaseAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setCompThreshold(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(compThresholdAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setCompMakeupGain(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(compMakeupGainAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    /** compIn - как и mute, это TOGGLE: значение в аргументе не важно, важен сам факт пакета. */
    fun setCompIn(channelIndex: Int): ByteArray =
        OscUtil.encode(compInAddress(), listOf(channelIndex, 1))

    /** Переключает режим компрессора на следующий по списку - см. заметку у compDetectorModeCycleAddress. */
    fun setCompDetectorModeNext(channelIndex: Int): ByteArray =
        OscUtil.encode(compDetectorModeCycleAddress(), listOf(channelIndex, 1))

    /** Переключает режим gate/expander на следующий по списку - см. заметку у gateModeCycleAddress. */
    fun setGateModeNext(channelIndex: Int): ByteArray =
        OscUtil.encode(gateModeCycleAddress(), listOf(channelIndex, 1))

    fun setCompFiltersIn(channelIndex: Int, on: Boolean): ByteArray =
        OscUtil.encode(compFiltersInAddress(), listOf(channelIndex, if (on) 1 else 0))

    fun setCompFilterFreq(channelIndex: Int, level: Float): ByteArray =
        OscUtil.encode(compFilterFreqAddress(), listOf(channelIndex, level.coerceIn(0f, 1f)))

    /** GET request: same address, only the channel index as argument, no value. */
    fun getFader(channelIndex: Int): ByteArray =
        OscUtil.encode(faderAddress(), listOf(channelIndex))

    fun getMute(channelIndex: Int): ByteArray =
        OscUtil.encode(muteAddress(), listOf(channelIndex))

    fun getSolo(channelIndex: Int): ByteArray =
        OscUtil.encode(soloAddress(), listOf(channelIndex))

    fun getGain(channelIndex: Int): ByteArray =
        OscUtil.encode(gainAddress(), listOf(channelIndex))


    fun getName(channelIndex: Int): ByteArray =
        OscUtil.encode(nameAddress(), listOf(channelIndex))








    // === Эквалайзер (4 полосы) - ПОДТВЕРЖДЕНО описаниями в списке команд
    // (каждый параметр имеет чёткое, понятное описание - "Sets bass
    // frequency in the parametric EQ" и т.п.), но НЕ подтверждено реальным
    // захватом трафика - в отличие от компрессора, живьём это не проверялось.
    enum class EqBand { BASS, LOW_MID, MID_HIGH, TREBLE }

    private fun eqBandSuffix(band: EqBand) = when (band) {
        EqBand.BASS -> "Bass"
        EqBand.LOW_MID -> "LowMid"
        // ИСПРАВЛЕНО: было "MidHigh". Реальное имя в прошивке -
        // "HighMid" (enPEQFrequencyHighMid / enPEQGainHighMid /
        // enPEQWidthHighMid). С "MidHigh" пульт отвечал на подписку
        // blob-ом НУЛЕВОЙ длины, то есть "такого параметра нет", и вся
        // третья полоса эквалайзера молча не работала. В датасете
        // muffeeee тут опечатка - там указано MidHigh.
        EqBand.MID_HIGH -> "HighMid"
        EqBand.TREBLE -> "Treble"
    }

    fun eqInAddress() = "/enPPCSwitchMessage/$GROUP/enPEQIn"
    fun eqBandActiveAddress(band: EqBand): String {
        // ИСПРАВЛЕНО: было enPEQHighMid (без "Active"). Пульт отвечал
        // пустым blob-ом. В прошивке параметр называется
        // enPEQHighMidActive - то есть схема как раз согласованная,
        // enPEQ<Band>Active для всех четырёх полос.
        // Историческая заметка: несогласованность
        // есть уже в самом списке команд, не опечатка с нашей стороны.
        val name = "enPEQ${eqBandSuffix(band)}Active"
        return "/enPPCSwitchMessage/$GROUP/$name"
    }
    fun eqFreqAddress(band: EqBand) = "/enPPCRotaryMessage/$GROUP/enPEQFrequency${eqBandSuffix(band)}"
    fun eqGainAddress(band: EqBand) = "/enPPCRotaryMessage/$GROUP/enPEQGain${eqBandSuffix(band)}"
    fun eqWidthAddress(band: EqBand) = "/enPPCRotaryMessage/$GROUP/enPEQWidth${eqBandSuffix(band)}"
    // Форма полосы (shelf/bell) - ТОЛЬКО у BASS и TREBLE (у средних полос
    // LOW_MID/MID_HIGH её нет - они всегда "колокол"). ПОДТВЕРЖДЕНО
    // реальным трафиком iPad.
    fun eqShapeAddress(band: EqBand): String {
        require(band == EqBand.BASS || band == EqBand.TREBLE) {
            "Форма есть только у BASS и TREBLE"
        }
        return "/enPPCIntegerMessage/$GROUP/enPEQ${eqBandSuffix(band)}Shape"
    }

    fun setEqIn(channelIndex: Int): ByteArray =
        OscUtil.encode(eqInAddress(), listOf(channelIndex, 1))

    fun setEqBandActive(channelIndex: Int, band: EqBand): ByteArray =
        OscUtil.encode(eqBandActiveAddress(band), listOf(channelIndex, 1))

    fun setEqFreq(channelIndex: Int, band: EqBand, level: Float): ByteArray =
        OscUtil.encode(eqFreqAddress(band), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setEqGain(channelIndex: Int, band: EqBand, level: Float): ByteArray =
        OscUtil.encode(eqGainAddress(band), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setEqWidth(channelIndex: Int, band: EqBand, level: Float): ByteArray =
        OscUtil.encode(eqWidthAddress(band), listOf(channelIndex, level.coerceIn(0f, 1f)))

    /**
     * ПЕРЕСМОТРЕНО: раньше считали это простым bell/shelf-переключателем
     * (0/1), но реальный пульт при нажатии этой кнопки циклически
     * переключает между 4 режимами - "parametric" (это и есть bell),
     * "bright", "classic", "soft" (три разных варианта shelf) -
     * подтверждено пользователем вручную на реальном пульте (см. заметку
     * "Настройки канала.txt" в переписке). Значит это TOGGLE/CYCLE-тип,
     * как compDetectorModeCycleAddress/gateModeCycleAddress - сам факт
     * пакета переключает на следующий режим, отправляемое значение не
     * важно. Адрес (eqShapeAddress) при этом остаётся тем же самым, что
     * было независимо подтверждено раньше реальным трафиком iPad - под
     * вопросом только то, что это boolean, а не 4-значный цикл.
     */
    fun setEqShapeNext(channelIndex: Int, band: EqBand): ByteArray =
        OscUtil.encode(eqShapeAddress(band), listOf(channelIndex, 1))






    // === Мастер (НЕ подтверждено реальным захватом - см. заметку выше) ===
    private const val MASTER_GROUP = "enVirtualMasters"
    fun masterFaderAddress() = "/enPPCFaderMessage/$MASTER_GROUP/enFaderLevel"
    // ИСПРАВЛЕНО: подтверждено реальным трафиком iPad (Mixtender) - у мастера
    // используется enFaderMute, а НЕ enMuteStatus (это была ошибка по
    // аналогии с каналами - на самом деле мастер здесь ближе к aux
    // returns/aux-шинам, у которых тоже enFaderMute).
    fun masterMuteAddress() = "/enPPCSwitchMessage/$MASTER_GROUP/enFaderMute"
    fun masterSoloAddress() = "/enPPCSwitchMessage/$MASTER_GROUP/enFaderSolo"
    fun masterSoloBAddress() = "/enPPCSwitchMessage/$MASTER_GROUP/enFaderSoloB"
    fun masterMeterAddress() = "/enPPCMeterMessage/$MASTER_GROUP/enMeter"
    fun masterNameAddress() = "/enPPCStringMessage/$MASTER_GROUP/enPathname"

    fun setMasterFader(masterIndex: Int, level: Float): ByteArray =
        OscUtil.encode(masterFaderAddress(), listOf(masterIndex, level.coerceIn(0f, 1f)))

    fun setMasterMute(masterIndex: Int, muted: Boolean): ByteArray =
        OscUtil.encode(masterMuteAddress(), listOf(masterIndex, if (muted) 1 else 0))

    fun setMasterSolo(masterIndex: Int, soloed: Boolean): ByteArray =
        OscUtil.encode(masterSoloAddress(), listOf(masterIndex, if (soloed) 1 else 0))

    fun setMasterSoloB(masterIndex: Int, soloed: Boolean): ByteArray =
        OscUtil.encode(masterSoloBAddress(), listOf(masterIndex, if (soloed) 1 else 0))



    // === Aux Returns (8 шт. по мануалу) - НЕ подтверждено реальным захватом.
    // ВАЖНО: тут другие имена параметров, чем у каналов/мастера -
    // enFaderMute (не enMuteStatus), и enFaderLevel тут почему-то
    // enPPCRotaryMessage, а не enPPCFaderMessage, как у каналов и мастера.
    // Список команд для этой группы вообще без описаний ("(unknown)"
    // у каждого параметра) - надёжность ниже, чем у всего остального. ===
    private const val AUX_RETURN_GROUP = "enVirtualAuxReturns"
    fun auxReturnFaderAddress() = "/enPPCRotaryMessage/$AUX_RETURN_GROUP/enFaderLevel"
    fun auxReturnMuteAddress() = "/enPPCSwitchMessage/$AUX_RETURN_GROUP/enFaderMute"
    fun auxReturnSoloAddress() = "/enPPCSwitchMessage/$AUX_RETURN_GROUP/enFaderSolo"
    fun auxReturnSoloBAddress() = "/enPPCSwitchMessage/$AUX_RETURN_GROUP/enFaderSoloB"
    fun auxReturnNameAddress() = "/enPPCStringMessage/$AUX_RETURN_GROUP/enPathname"
    fun auxReturnColourAddress() = "/enPPCIntegerMessage/$AUX_RETURN_GROUP/enChannelColour"
    fun auxReturnMeterAddress() = "/enPPCMeterMessage/$AUX_RETURN_GROUP/enMeter"

    // ПОКА НЕ ИСПОЛЬЗУЮТСЯ: цвет aux-возвратов и матриц приложение только
    // читает, экрана смены цвета для них нет (в отличие от каналов).
    // Оставлены как готовая основа, если решим добавить.
    fun setAuxReturnColour(auxIndex: Int, argbColor: Int): ByteArray =
        OscUtil.encode(auxReturnColourAddress(), listOf(auxIndex, argbColor))

    fun setAuxReturnFader(auxIndex: Int, level: Float): ByteArray =
        OscUtil.encode(auxReturnFaderAddress(), listOf(auxIndex, level.coerceIn(0f, 1f)))

    fun setAuxReturnMute(auxIndex: Int, muted: Boolean): ByteArray =
        OscUtil.encode(auxReturnMuteAddress(), listOf(auxIndex, if (muted) 1 else 0))

    fun setAuxReturnSolo(auxIndex: Int, soloed: Boolean): ByteArray =
        OscUtil.encode(auxReturnSoloAddress(), listOf(auxIndex, if (soloed) 1 else 0))

    fun setAuxReturnSoloB(auxIndex: Int, soloed: Boolean): ByteArray =
        OscUtil.encode(auxReturnSoloBAddress(), listOf(auxIndex, if (soloed) 1 else 0))




    // === 16 aux-шин (СОБСТВЕННЫЙ уровень/mute самой шины, НЕ посыл с канала -
    // это уже enSubSendLevel выше). НЕ подтверждено реальным захватом, но
    // описание в списке команд для enFaderLevel прямо говорит "Set aux fader
    // level", так что уверенность выше, чем у aux returns. ===
    private const val AUX_BUS_GROUP = "enVirtualSubMixes"
    fun auxBusFaderAddress() = "/enPPCFaderMessage/$AUX_BUS_GROUP/enFaderLevel"
    fun auxBusMuteAddress() = "/enPPCSwitchMessage/$AUX_BUS_GROUP/enFaderMute"
    fun auxBusSoloAddress() = "/enPPCSwitchMessage/$AUX_BUS_GROUP/enFaderSolo"
    fun auxBusSoloBAddress() = "/enPPCSwitchMessage/$AUX_BUS_GROUP/enFaderSoloB"
    fun auxBusNameAddress() = "/enPPCStringMessage/$AUX_BUS_GROUP/enPathname"
    // Подтверждено реальным трафиком iPad.
    fun auxBusColourAddress() = "/enPPCIntegerMessage/$AUX_BUS_GROUP/enChannelColour"
    fun auxBusMeterAddress() = "/enPPCMeterMessage/$AUX_BUS_GROUP/enMeter"

    fun setAuxBusFader(busIndex: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusFaderAddress(), listOf(busIndex, level.coerceIn(0f, 1f)))

    fun setAuxBusMute(busIndex: Int, muted: Boolean): ByteArray =
        OscUtil.encode(auxBusMuteAddress(), listOf(busIndex, if (muted) 1 else 0))

    fun setAuxBusSolo(busIndex: Int, soloed: Boolean): ByteArray =
        OscUtil.encode(auxBusSoloAddress(), listOf(busIndex, if (soloed) 1 else 0))

    fun setAuxBusSoloB(busIndex: Int, soloed: Boolean): ByteArray =
        OscUtil.encode(auxBusSoloBAddress(), listOf(busIndex, if (soloed) 1 else 0))




    // === VCA-группы (8 шт.) - ПОЛНОСТЬЮ ПОДТВЕРЖДЕНО реальным трафиком iPad
    // (Mixtender). ВАЖНО: старый список команд (muffeeee JSON) указывал
    // неверную адресацию enMCAFaderLevel1..8 (номер группы в имени
    // параметра) - на самом деле всё как у остальных групп: multiPath,
    // индекс передаётся аргументом, имя параметра одно на все группы. ===
    private const val VCA_GROUP = "enVirtualVCAGroups"
    fun vcaFaderAddress() = "/enPPCFaderMessage/$VCA_GROUP/enVCAFaderLevel"
    fun vcaMuteAddress() = "/enPPCSwitchMessage/$VCA_GROUP/enVCAMute"
    fun vcaSoloAddress() = "/enPPCSwitchMessage/$VCA_GROUP/enVCASolo"
    fun vcaNameAddress() = "/enPPCStringMessage/$VCA_GROUP/enPathname"
    fun vcaColourAddress() = "/enPPCIntegerMessage/$VCA_GROUP/enChannelColour"

    // === Назначение каналов в VCA-группы - НЕ подтверждено реальным
    // захватом (структура взята из большого стороннего датасета
    // midas-pro-mcp-server, brute-force реверс-инжиниринг), НО структура
    // симметрична с mute-группами (см. muteGroupChild*Address ниже) и
    // объясняет то, что мы видели в собственном захвате (индекс аргумента
    // - это номер VCA-группы 0..7, а не что-то другое). Имя параметра
    // определяет КОГО назначаем, числовой аргумент - В КАКУЮ группу. ===
    fun vcaChildInputAddress(inputIndex: Int) = "/enPPCSwitchMessage/$VCA_GROUP/enVCAChildInput${inputIndex + 1}"
    fun vcaChildSubMixAddress(busIndex: Int) = "/enPPCSwitchMessage/$VCA_GROUP/enVCAChildSubMix${busIndex + 1}"
    fun vcaChildAuxReturnAddress(auxIndex: Int) = "/enPPCSwitchMessage/$VCA_GROUP/enVCAChildAuxReturn${auxIndex + 1}"
    fun vcaChildMainAddress(mainIndex: Int) = "/enPPCSwitchMessage/$VCA_GROUP/enVCAChildMain${mainIndex + 1}"
    fun vcaChildMasterAddress(letter: String) = "/enPPCSwitchMessage/$VCA_GROUP/enVCAChildMaster$letter"

    fun setVcaFader(vcaIndex: Int, level: Float): ByteArray =
        OscUtil.encode(vcaFaderAddress(), listOf(vcaIndex, level.coerceIn(0f, 1f)))

    fun setVcaChildInput(inputIndex: Int, vcaIndex: Int, member: Boolean): ByteArray =
        OscUtil.encode(vcaChildInputAddress(inputIndex), listOf(vcaIndex, if (member) 1 else 0))

    fun setVcaChildSubMix(busIndex: Int, vcaIndex: Int, member: Boolean): ByteArray =
        OscUtil.encode(vcaChildSubMixAddress(busIndex), listOf(vcaIndex, if (member) 1 else 0))

    fun setVcaChildAuxReturn(auxIndex: Int, vcaIndex: Int, member: Boolean): ByteArray =
        OscUtil.encode(vcaChildAuxReturnAddress(auxIndex), listOf(vcaIndex, if (member) 1 else 0))

    fun setVcaChildMain(mainIndex: Int, vcaIndex: Int, member: Boolean): ByteArray =
        OscUtil.encode(vcaChildMainAddress(mainIndex), listOf(vcaIndex, if (member) 1 else 0))

    fun setVcaChildMaster(letter: String, vcaIndex: Int, member: Boolean): ByteArray =
        OscUtil.encode(vcaChildMasterAddress(letter), listOf(vcaIndex, if (member) 1 else 0))

    fun setVcaMute(vcaIndex: Int, muted: Boolean): ByteArray =
        OscUtil.encode(vcaMuteAddress(), listOf(vcaIndex, if (muted) 1 else 0))

    fun setVcaSolo(vcaIndex: Int, soloed: Boolean): ByteArray =
        OscUtil.encode(vcaSoloAddress(), listOf(vcaIndex, if (soloed) 1 else 0))




    // === Main Outs (в самом пульте это "matrix out", 8 позиций) - базовая
    // полоса (фейдер/mute/solo/имя/цвет/метр). enFaderLevel подтверждён
    // сторонним датасетом; mute/solo/цвет НЕ найдены в том датасете вообще
    // (пробел в их обходе), взяты по аналогии с master/aux/VCA - там та же
    // схема имён ("enFaderMute"/"enFaderSolo"/"enChannelColour") везде
    // одинакова, так что предположение обоснованное, но не подтверждено
    // напрямую для этой конкретной группы. У Main Outs также есть
    // 6-полосный EQ и отдельный расширенный компрессор (range/soft clip) -
    // это отдельная большая задача, пока не реализовано.
    private const val MAIN_OUT_GROUP = "enVirtualMainOuts"
    fun mainOutFaderAddress() = "/enPPCFaderMessage/$MAIN_OUT_GROUP/enFaderLevel"
    fun mainOutMuteAddress() = "/enPPCSwitchMessage/$MAIN_OUT_GROUP/enFaderMute"
    fun mainOutSoloAddress() = "/enPPCSwitchMessage/$MAIN_OUT_GROUP/enFaderSolo"
    fun mainOutNameAddress() = "/enPPCStringMessage/$MAIN_OUT_GROUP/enPathname"
    fun mainOutColourAddress() = "/enPPCIntegerMessage/$MAIN_OUT_GROUP/enChannelColour"
    fun mainOutMeterAddress() = "/enPPCMeterMessage/$MAIN_OUT_GROUP/enMeter"

    fun setMainOutFader(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutFaderAddress(), listOf(index, level.coerceIn(0f, 1f)))

    fun setMainOutMute(index: Int, muted: Boolean): ByteArray =
        OscUtil.encode(mainOutMuteAddress(), listOf(index, if (muted) 1 else 0))

    fun setMainOutSolo(index: Int, soloed: Boolean): ByteArray =
        OscUtil.encode(mainOutSoloAddress(), listOf(index, if (soloed) 1 else 0))

    fun setMainOutColour(index: Int, argbColor: Int): ByteArray =
        OscUtil.encode(mainOutColourAddress(), listOf(index, argbColor))

    // === Main Outs - EQ (6 полос!) и компрессор. НЕ подтверждено реальным
    // захватом - адреса взяты из датасета muffeeee/midas-pro-series-osc-
    // commands (enVirtualMainOuts), где параметры описаны текстом ("Set
    // matrix out EQ frequency for band N" и т.п.), но живьём не
    // проверялись. Требует проверки на реальном пульте. ===
    fun mainOutEqFreqAddress(band: Int) = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enPEQFrequencyBand${band + 1}"
    fun mainOutEqGainAddress(band: Int) = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enPEQGainBand${band + 1}"
    fun mainOutEqWidthAddress(band: Int) = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enPEQWidthBand${band + 1}"
    fun mainOutHpFreqAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enGEQHPFrequency"
    fun mainOutLpFreqAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enGEQLPFrequency"
    fun mainOutLowNotchFreqAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enGEQLowNotchFrequency"
    fun mainOutHighNotchFreqAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enGEQHighNotchFrequency"

    fun mainOutCompRatioAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enComLimRatio"
    fun mainOutCompAttackAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enCompLimAttackTime"
    fun mainOutCompReleaseAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enCompLimReleaseTime"
    fun mainOutCompThresholdAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enCompLimThreshold"
    fun mainOutCompRangeAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enCompLimRange"
    fun mainOutCompMakeupAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enDynamicsOverallMakeUpGain"
    fun mainOutCompSoftClipAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enCompLimSoftClip"
    fun mainOutDelayAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enOutputDelay"

    fun setMainOutEqFreq(index: Int, band: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutEqFreqAddress(band), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutEqGain(index: Int, band: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutEqGainAddress(band), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutEqWidth(index: Int, band: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutEqWidthAddress(band), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutHpFreq(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutHpFreqAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutLpFreq(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutLpFreqAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutLowNotchFreq(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutLowNotchFreqAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutHighNotchFreq(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutHighNotchFreqAddress(), listOf(index, level.coerceIn(0f, 1f)))

    fun setMainOutCompRatio(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutCompRatioAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutCompAttack(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutCompAttackAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutCompRelease(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutCompReleaseAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutCompThreshold(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutCompThresholdAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutCompRange(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutCompRangeAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutCompMakeup(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutCompMakeupAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setMainOutCompSoftClip(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutCompSoftClipAddress(), listOf(index, level.coerceIn(0f, 1f)))
    // ПОКА НЕ ИСПОЛЬЗУЕТСЯ: задержка матричного выхода не выведена в UI.
    fun setMainOutDelay(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutDelayAddress(), listOf(index, level.coerceIn(0f, 1f)))


    // === Main Out - LINK (стерео-пара), Presence, Bus Trim, циклические
    // режимы компрессора. ПОДТВЕРЖДЕНО реальным захватом трафика iPad
    // (all config ipad.pcapng) - типы сообщений (enPPCSwitchMessage /
    // enPPCRotaryMessage / enPPCIntegerMessage) настоящие, но конкретное
    // ЗНАЧЕНИЕ, которое присылает пульт для cycle-параметров, и точный
    // эффект LINK - не проверялись (в подписках видно только сам факт
    // существования и тип параметра, не поведение). ===
    fun mainOutPairingAddress() = "/enPPCSwitchMessage/$MAIN_OUT_GROUP/enConfigPairingState"
    fun setMainOutPairingNext(index: Int): ByteArray = OscUtil.encode(mainOutPairingAddress(), listOf(index, 1))


    fun mainOutBusTrimAddress() = "/enPPCRotaryMessage/$MAIN_OUT_GROUP/enBusTrimLevel"
    fun setMainOutBusTrim(index: Int, level: Float): ByteArray =
        OscUtil.encode(mainOutBusTrimAddress(), listOf(index, level.coerceIn(0f, 1f)))

    // Читаем как enPPCIntegerMessage (подтверждено подпиской), переключаем
    // как enPPCSwitchMessage-пульс - та же логика, что уже применена для
    // compDetectorModeCycleAddress/gateModeCycleAddress/eqShapeAddress:
    // "прочитать" и "переключить на следующий" у пульта - разные адреса с
    // одним и тем же именем параметра, но разным типом-префиксом.
    fun mainOutCompStyleAddress() = "/enPPCIntegerMessage/$MAIN_OUT_GROUP/enCompStyle"
    fun mainOutCompStyleCycleAddress() = "/enPPCSwitchMessage/$MAIN_OUT_GROUP/enCompStyle"
    fun setMainOutCompStyleNext(index: Int): ByteArray = OscUtil.encode(mainOutCompStyleCycleAddress(), listOf(index, 1))



    // === Aux-шина - LINK (стерео-пара). ПОДТВЕРЖДЕНО реальным захватом
    // (тот же тип enPPCSwitchMessage, что и у Main Out выше). ===
    fun auxBusPairingAddress() = "/enPPCSwitchMessage/$AUX_BUS_GROUP/enConfigPairingState"
    fun setAuxBusPairingNext(index: Int): ByteArray = OscUtil.encode(auxBusPairingAddress(), listOf(index, 1))

    // === Aux-шины - EQ (6 полос) и компрессор. Та же структура, что у Main
    // Outs. ИСПРАВЛЕНО: gain для EQ был enPEQQGainBand (с двойным Q) -
    // считалось, что так в прошивке. Проверка показала обратное: строка "enPEQQGain"
    // встречается в прошивке и Offline Editor РОВНО НОЛЬ раз, а пульт
    // отвечал на подписку blob-ом нулевой длины ("параметра нет") на всех
    // шести полосах. Двойное Q - опечатка в датасете muffeeee. Реальное
    // имя такое же, как у Masters и MainOuts: enPEQGainBand1-6.
    // НЕ подтверждено реальным захватом. ===
    fun auxBusEqFreqAddress(band: Int) = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enPEQFrequencyBand${band + 1}"
    fun auxBusEqGainAddress(band: Int) = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enPEQGainBand${band + 1}"
    fun auxBusEqWidthAddress(band: Int) = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enPEQWidthBand${band + 1}"
    fun auxBusHpFreqAddress() = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enGEQHPFrequency"
    fun auxBusLpFreqAddress() = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enGEQLPFrequency"
    fun auxBusLowNotchFreqAddress() = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enGEQLowNotchFrequency"
    fun auxBusHighNotchFreqAddress() = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enGEQHighNotchFrequency"

    fun auxBusCompRatioAddress() = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enComLimRatio"
    fun auxBusCompAttackAddress() = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enCompLimAttackTime"
    fun auxBusCompReleaseAddress() = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enCompLimReleaseTime"
    fun auxBusCompThresholdAddress() = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enCompLimThreshold"
    fun auxBusCompRangeAddress() = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enCompLimRange"
    fun auxBusCompMakeupAddress() = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enDynamicsOverallMakeUpGain"
    fun auxBusCompSoftClipAddress() = "/enPPCRotaryMessage/$AUX_BUS_GROUP/enCompLimSoftClip"

    fun setAuxBusEqFreq(index: Int, band: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusEqFreqAddress(band), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusEqGain(index: Int, band: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusEqGainAddress(band), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusEqWidth(index: Int, band: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusEqWidthAddress(band), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusHpFreq(index: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusHpFreqAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusLpFreq(index: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusLpFreqAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusLowNotchFreq(index: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusLowNotchFreqAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusHighNotchFreq(index: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusHighNotchFreqAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusCompRatio(index: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusCompRatioAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusCompAttack(index: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusCompAttackAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusCompRelease(index: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusCompReleaseAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusCompThreshold(index: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusCompThresholdAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusCompRange(index: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusCompRangeAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusCompMakeup(index: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusCompMakeupAddress(), listOf(index, level.coerceIn(0f, 1f)))
    fun setAuxBusCompSoftClip(index: Int, level: Float): ByteArray =
        OscUtil.encode(auxBusCompSoftClipAddress(), listOf(index, level.coerceIn(0f, 1f)))

    // === Посыл канала на aux-шину (НЕ подтверждено реальным захватом) ===
    // В самом пульте это называется "SubSend", не "AuxSend" - у каждой из 16
    // шин свой отдельный параметр (индекс шины зашит в имя, а не передаётся
    // как аргумент - как и предполагает список команд).
    fun subSendLevelAddress(auxBus: Int) = "/enPPCRotaryMessage/$GROUP/enSubSendLevel$auxBus"

    // === ПОСЫЛЫ В МАТРИЦУ (8 матричных шин) ===
    // Мануал (стр. 12): канал маршрутизируется "via level controls to 24
    // mix buses" - это 16 аукс-шин (enSubSendLevel, уже реализовано) плюс
    // 8 матричных (enMainSendLevel, здесь). Несмотря на слово "Main" в
    // имени, это именно матричные посылы, а не мастер-шина.
    //
    // ⚠ Панорама посыла (enMainSendPan) идёт ПАРАМИ - всего 4 параметра
    // на 8 шин, по одному на стереопару. Пока не реализована.
    fun mainSendLevelAddress(matrix: Int) = "/enPPCRotaryMessage/$GROUP/enMainSendLevel$matrix"
    fun mainSendEnableAddress(matrix: Int) = "/enPPCSwitchMessage/$GROUP/enMainSendEnableIn$matrix"
    fun mainSendPreFadeAddress(matrix: Int) = "/enPPCSwitchMessage/$GROUP/enMainSendPreFadeIn$matrix"

    fun setMainSend(channelIndex: Int, matrix: Int, level: Float): ByteArray =
        OscUtil.encode(mainSendLevelAddress(matrix), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun setMainSendEnable(channelIndex: Int, matrix: Int, on: Boolean): ByteArray =
        OscUtil.encode(mainSendEnableAddress(matrix), listOf(channelIndex, if (on) 1 else 0))

    fun setMainSendPreFade(channelIndex: Int, matrix: Int, pre: Boolean): ByteArray =
        OscUtil.encode(mainSendPreFadeAddress(matrix), listOf(channelIndex, if (pre) 1 else 0))
    // Отдельно от уровня посыла - ПОДТВЕРЖДЕНО реальным захватом. Включение
    // самого посыла (независимо от того, что стоит на ползунке уровня) и
    // pre/post-фейдер режим.
    fun subSendEnableAddress(auxBus: Int) = "/enPPCSwitchMessage/$GROUP/enSubMixSendEnableIn$auxBus"
    fun subSendPreFadeAddress(auxBus: Int) = "/enPPCSwitchMessage/$GROUP/enSubMixSendsPreFadeIn$auxBus"

    fun setSubSendLevel(channelIndex: Int, auxBus: Int, level: Float): ByteArray =
        OscUtil.encode(subSendLevelAddress(auxBus), listOf(channelIndex, level.coerceIn(0f, 1f)))


    fun setSubSendEnable(channelIndex: Int, auxBus: Int, on: Boolean): ByteArray =
        OscUtil.encode(subSendEnableAddress(auxBus), listOf(channelIndex, if (on) 1 else 0))

    fun setSubSendPreFade(channelIndex: Int, auxBus: Int, pre: Boolean): ByteArray =
        OscUtil.encode(subSendPreFadeAddress(auxBus), listOf(channelIndex, if (pre) 1 else 0))

    // === Живые обновления через подписку (обнаружено захватом трафика Mixtender 2) ===
    // Пульт не отвечает на обычный --get человекочитаемым путём. Вместо этого клиент
    // сам придумывает короткий "хендл" (любую строку) для параметра, который хочет
    // отслеживать, и один раз отправляет /batchsubscribe. С этого момента пульт сам
    // присылает обновления на этот хендл через OSC-бандлы, формат ",bi" (blob со
    // значением + int - служебный "токен"/счётчик пульта, который нужно просто
    // подсмотреть из любого входящего пакета и переиспользовать здесь).
    fun batchSubscribe(handle: String, fullPath: String, arg1: Int, arg2: Int, token: Int): ByteArray =
        OscUtil.encode("/batchsubscribe", listOf(handle, fullPath, arg1, arg2, token))

    fun unsubscribe(handle: String): ByteArray =
        OscUtil.encode("/unsubscribe", listOf(handle))

    // Подтверждено реальным трафиком Mixtender 2 в самом начале этого проекта:
    // "/renew" отправляется периодически (~раз в 3 сек), пока идёт сессия -
    // судя по всему, продлевает "аренду" подписки на стороне пульта. Без
    // этого пульт, похоже, отключает подписку по таймауту через какое-то
    // время после последнего /renew, и push-обновления перестают приходить.
    fun renew(): ByteArray = OscUtil.encode("/renew", emptyList())

    // === Global Tap - задаёт темп делею (или любому другому эффекту с
    // этой функцией) простукиванием ритма. НЕ подтверждено реальным
    // захватом - адрес из датасета muffeeee (enGlobals/enGlobalTapSwitch),
    // тип enPPCSwitchMessage - по уже подтверждённой закономерности
    // (mute/phantom/gate и т.д.) это toggle/pulse-параметр: физическая
    // кнопка простукивания темпа и так по своей природе работает как серия
    // отдельных нажатий, а не как удержание состояния, так что шлём "1"
    // при каждом тапе. Глобальный параметр - индекса канала/группы нет. ===
    fun globalTapAddress() = "/enPPCSwitchMessage/enGlobals/enGlobalTapSwitch"
    fun setGlobalTap(): ByteArray = OscUtil.encode(globalTapAddress(), listOf(1))

    // === Мьют-группы - структура полностью симметрична VCA-группам выше
    // (см. подробную заметку у vcaChild*Address()), из того же датасета
    // muffeeee, НЕ подтверждено собственным захватом. Число групп (8) -
    // предположение по аналогии с 8 VCA-группами, см. заметку у
    // ConnectionHolder.MUTE_GROUP_COUNT.
    //
    // ВАЖНО про enMuteGroupMute: датасет описывает его прямым текстом как
    // "This toggles the mute groups to be muted or unmuted" - однозначно
    // toggle-параметр (в отличие от спорного случая с mute одиночного
    // канала, см. пункт 1 hardware_check_list.md). Шлём константу 1.
    private const val MUTE_GROUP_GROUP = "enVirtualMuteGroups"
    fun muteGroupMuteAddress() = "/enPPCSwitchMessage/$MUTE_GROUP_GROUP/enMuteGroupMute"
    fun muteGroupNameAddress() = "/enPPCStringMessage/$MUTE_GROUP_GROUP/enPathname"
    fun muteGroupChildInputAddress(inputIndex: Int) = "/enPPCSwitchMessage/$MUTE_GROUP_GROUP/enMuteGroupChildInput${inputIndex + 1}"
    fun muteGroupChildSubMixAddress(busIndex: Int) = "/enPPCSwitchMessage/$MUTE_GROUP_GROUP/enMuteGroupChildSubMix${busIndex + 1}"
    fun muteGroupChildAuxReturnAddress(auxIndex: Int) = "/enPPCSwitchMessage/$MUTE_GROUP_GROUP/enMuteGroupChildAuxReturn${auxIndex + 1}"
    fun muteGroupChildMainAddress(mainIndex: Int) = "/enPPCSwitchMessage/$MUTE_GROUP_GROUP/enMuteGroupChildMain${mainIndex + 1}"
    fun muteGroupChildMasterAddress(letter: String) = "/enPPCSwitchMessage/$MUTE_GROUP_GROUP/enMuteGroupChildMaster$letter"

    fun setMuteGroupMute(groupIndex: Int): ByteArray =
        OscUtil.encode(muteGroupMuteAddress(), listOf(groupIndex, 1))

    fun setMuteGroupChildInput(inputIndex: Int, groupIndex: Int, member: Boolean): ByteArray =
        OscUtil.encode(muteGroupChildInputAddress(inputIndex), listOf(groupIndex, if (member) 1 else 0))

    fun setMuteGroupChildSubMix(busIndex: Int, groupIndex: Int, member: Boolean): ByteArray =
        OscUtil.encode(muteGroupChildSubMixAddress(busIndex), listOf(groupIndex, if (member) 1 else 0))

    fun setMuteGroupChildAuxReturn(auxIndex: Int, groupIndex: Int, member: Boolean): ByteArray =
        OscUtil.encode(muteGroupChildAuxReturnAddress(auxIndex), listOf(groupIndex, if (member) 1 else 0))

    fun setMuteGroupChildMain(mainIndex: Int, groupIndex: Int, member: Boolean): ByteArray =
        OscUtil.encode(muteGroupChildMainAddress(mainIndex), listOf(groupIndex, if (member) 1 else 0))

    fun setMuteGroupChildMaster(letter: String, groupIndex: Int, member: Boolean): ByteArray =
        OscUtil.encode(muteGroupChildMasterAddress(letter), listOf(groupIndex, if (member) 1 else 0))
}
