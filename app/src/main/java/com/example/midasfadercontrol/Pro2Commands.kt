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
    // Стерео-пара (link) - НЕ подтверждено реальным захватом (из большого
    // датасета midas-pro-mcp-server, brute-force реверс-инжиниринг).
    fun linkAddress() = "/enPPCSwitchMessage/$GROUP/enRoutingLinked"
    // ПОДТВЕРЖДЕНО реальным трафиком Mixtender 2 (перехват "TRIM" ручки на канале 32):
    // адрес enInputGain НЕ используется приложением для этой ручки вообще -
    // реально используется enMicSplitStepGain. Обозначение в JSON-файле muffeeee
    // ("Sets channel input gain" для enInputGain) оказалось не тем, что физически
    // происходит при вращении ручки TRIM в реальном приложении - имя параметра
    // в оф. списке говорит про "micsplit return gain", но по факту именно он
    // управляет тем, что в интерфейсе выглядит как input gain trim.
    // ВАЖНО: на пульте на самом деле ДВА разных гейна (подтверждено реальным
    // захватом обоих):
    // - enInputGain - основной входной GAIN (аналоговый преамп)
    // - enMicSplitStepGain - GAIN TRIM (доп. подстройка). Раньше мы по
    //   ошибке называли ЭТОТ параметр просто "GAIN" - на самом деле это TRIM.
    fun gainAddress() = "/enPPCRotaryMessage/$GROUP/enInputGain"
    fun gainTrimAddress() = "/enPPCRotaryMessage/$GROUP/enMicSplitStepGain"
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
    fun hpFilterFreqAddress() = "/enPPCRotaryMessage/$GROUP/enInputHighPassFltFrequency"
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
    fun compDetMeterAddress() = "/enPPCMeterMessage/$GROUP/enCompDetMeter"
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

    fun getGainTrim(channelIndex: Int): ByteArray =
        OscUtil.encode(gainTrimAddress(), listOf(channelIndex))

    fun getName(channelIndex: Int): ByteArray =
        OscUtil.encode(nameAddress(), listOf(channelIndex))

    fun getColour(channelIndex: Int): ByteArray =
        OscUtil.encode(colourAddress(), listOf(channelIndex))

    fun getCompRatio(channelIndex: Int): ByteArray =
        OscUtil.encode(compRatioAddress(), listOf(channelIndex))

    fun getCompAttack(channelIndex: Int): ByteArray =
        OscUtil.encode(compAttackAddress(), listOf(channelIndex))

    fun getCompRelease(channelIndex: Int): ByteArray =
        OscUtil.encode(compReleaseAddress(), listOf(channelIndex))

    fun getCompThreshold(channelIndex: Int): ByteArray =
        OscUtil.encode(compThresholdAddress(), listOf(channelIndex))

    fun getCompMakeupGain(channelIndex: Int): ByteArray =
        OscUtil.encode(compMakeupGainAddress(), listOf(channelIndex))

    fun getCompIn(channelIndex: Int): ByteArray =
        OscUtil.encode(compInAddress(), listOf(channelIndex))

    // === Эквалайзер (4 полосы) - ПОДТВЕРЖДЕНО описаниями в списке команд
    // (каждый параметр имеет чёткое, понятное описание - "Sets bass
    // frequency in the parametric EQ" и т.п.), но НЕ подтверждено реальным
    // захватом трафика - в отличие от компрессора, живьём это не проверялось.
    enum class EqBand { BASS, LOW_MID, MID_HIGH, TREBLE }

    private fun eqBandSuffix(band: EqBand) = when (band) {
        EqBand.BASS -> "Bass"
        EqBand.LOW_MID -> "LowMid"
        EqBand.MID_HIGH -> "MidHigh"
        EqBand.TREBLE -> "Treble"
    }

    fun eqInAddress() = "/enPPCSwitchMessage/$GROUP/enPEQIn"
    fun eqBandActiveAddress(band: EqBand): String {
        // ВАЖНО: у high-mid параметр называется enPEQHighMid (без "Active" в
        // конце), у остальных трёх - enPEQ<Band>Active. Несогласованность
        // есть уже в самом списке команд, не опечатка с нашей стороны.
        val name = if (band == EqBand.MID_HIGH) "enPEQHighMid" else "enPEQ${eqBandSuffix(band)}Active"
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

    fun setEqShape(channelIndex: Int, band: EqBand, isShelf: Boolean): ByteArray =
        OscUtil.encode(eqShapeAddress(band), listOf(channelIndex, if (isShelf) 1 else 0))

    fun getEqIn(channelIndex: Int): ByteArray =
        OscUtil.encode(eqInAddress(), listOf(channelIndex))

    fun getEqBandActive(channelIndex: Int, band: EqBand): ByteArray =
        OscUtil.encode(eqBandActiveAddress(band), listOf(channelIndex))

    fun getEqFreq(channelIndex: Int, band: EqBand): ByteArray =
        OscUtil.encode(eqFreqAddress(band), listOf(channelIndex))

    fun getEqGain(channelIndex: Int, band: EqBand): ByteArray =
        OscUtil.encode(eqGainAddress(band), listOf(channelIndex))

    fun getEqWidth(channelIndex: Int, band: EqBand): ByteArray =
        OscUtil.encode(eqWidthAddress(band), listOf(channelIndex))

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

    fun getMasterFader(masterIndex: Int): ByteArray =
        OscUtil.encode(masterFaderAddress(), listOf(masterIndex))

    fun getMasterMute(masterIndex: Int): ByteArray =
        OscUtil.encode(masterMuteAddress(), listOf(masterIndex))

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

    fun getAuxReturnFader(auxIndex: Int): ByteArray =
        OscUtil.encode(auxReturnFaderAddress(), listOf(auxIndex))

    fun getAuxReturnMute(auxIndex: Int): ByteArray =
        OscUtil.encode(auxReturnMuteAddress(), listOf(auxIndex))

    fun getAuxReturnName(auxIndex: Int): ByteArray =
        OscUtil.encode(auxReturnNameAddress(), listOf(auxIndex))

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

    fun getAuxBusFader(busIndex: Int): ByteArray =
        OscUtil.encode(auxBusFaderAddress(), listOf(busIndex))

    fun getAuxBusMute(busIndex: Int): ByteArray =
        OscUtil.encode(auxBusMuteAddress(), listOf(busIndex))

    fun getAuxBusName(busIndex: Int): ByteArray =
        OscUtil.encode(auxBusNameAddress(), listOf(busIndex))

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

    fun getVcaFader(vcaIndex: Int): ByteArray =
        OscUtil.encode(vcaFaderAddress(), listOf(vcaIndex))

    fun getVcaMute(vcaIndex: Int): ByteArray =
        OscUtil.encode(vcaMuteAddress(), listOf(vcaIndex))

    fun getVcaName(vcaIndex: Int): ByteArray =
        OscUtil.encode(vcaNameAddress(), listOf(vcaIndex))

    // === Посыл канала на aux-шину (НЕ подтверждено реальным захватом) ===
    // В самом пульте это называется "SubSend", не "AuxSend" - у каждой из 16
    // шин свой отдельный параметр (индекс шины зашит в имя, а не передаётся
    // как аргумент - как и предполагает список команд).
    fun subSendLevelAddress(auxBus: Int) = "/enPPCRotaryMessage/$GROUP/enSubSendLevel$auxBus"
    // Отдельно от уровня посыла - ПОДТВЕРЖДЕНО реальным захватом. Включение
    // самого посыла (независимо от того, что стоит на ползунке уровня) и
    // pre/post-фейдер режим.
    fun subSendEnableAddress(auxBus: Int) = "/enPPCSwitchMessage/$GROUP/enSubMixSendEnableIn$auxBus"
    fun subSendPreFadeAddress(auxBus: Int) = "/enPPCSwitchMessage/$GROUP/enSubMixSendsPreFadeIn$auxBus"

    fun setSubSendLevel(channelIndex: Int, auxBus: Int, level: Float): ByteArray =
        OscUtil.encode(subSendLevelAddress(auxBus), listOf(channelIndex, level.coerceIn(0f, 1f)))

    fun getSubSendLevel(channelIndex: Int, auxBus: Int): ByteArray =
        OscUtil.encode(subSendLevelAddress(auxBus), listOf(channelIndex))

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
}
